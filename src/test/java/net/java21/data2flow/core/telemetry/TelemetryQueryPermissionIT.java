package net.java21.data2flow.core.telemetry;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 시계열 조회 권한(TS_READ, IAM-04.05·04.06, BR-TSD-13): 기본 역할 5개 허용, 역할 권한 없음 403, 다른 조직·권한 밖 공간 404,
 * 여러 계열 조회는 범위 밖 기기를 빼고 개수만 알린다 — TC-TSD-004·010·055·065·073·081·089·098·143·149.
 */
class TelemetryQueryPermissionIT extends IntegrationTestSupport {

    long org;
    long admin;
    long floor3;
    long floor4;
    long dev3;
    long dev4;
    String from;

    @BeforeEach
    void setUp() {
        org = fx.organization("tsdp");
        admin = fx.user(org, "ts.admin", "ADMIN");
        long site = data.site(org, "본관");
        floor3 = data.space(org, site, "FLOOR", "3층");
        floor4 = data.space(org, site, "FLOOR", "4층");
        long source = data.source(org, "chirp");
        data.metric(org, "temperature", "℃");
        dev3 = data.device(org, source, "d3", "ACTIVE", floor3, null);
        dev4 = data.device(org, source, "d4", "ACTIVE", floor4, null);
        Instant t = MutableClock.T0.minus(Duration.ofMinutes(5));
        data.telemetry(org, dev3, "temperature", t, 21, 0, false);
        data.telemetry(org, dev4, "temperature", t, 25, 0, false);
        from = MutableClock.T0.minus(Duration.ofMinutes(10)).toString();
    }

    @Test
    @DisplayName("[TSD-03.06][IAM-04.05] TS_READ: ADMIN·INTEGRATOR·OPERATOR·ANALYST·VIEWER 모두 200 — TC-TSD-010·055")
    void builtinRolesAllowed() throws Exception {
        for (String role : List.of("ADMIN", "INTEGRATOR", "OPERATOR", "ANALYST", "VIEWER")) {
            long user = fx.user(org, "user." + role.toLowerCase(), role);
            mvc.perform(as(org, user, get("/core/telemetry/series").param("deviceId", Long.toString(dev3)).param("metrics", "temperature")
                            .param("from", from)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.response.series[0].points", hasSize(1)));
            mvc.perform(as(org, user, get("/core/telemetry/latest").param("deviceIds", dev3 + "," + dev4)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.response", hasSize(2)));
        }
    }

    @Test
    @DisplayName("[IAM-04.05] 역할에 TS_READ가 없으면 403 PERMISSION_DENIED(감사 ACCESS_DENIED) — TC-TSD-010")
    void customRoleWithoutTsRead() throws Exception {
        String body = mvc.perform(as(org, admin, json(post("/core/custom-roles"), "{\"name\":\"기기만\",\"permissions\":[\"DEV_READ\"]}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long roleId = Long.parseLong(JsonPath.read(body, "$.response.id").toString());
        long limited = fx.user(org, "only.dev", null);
        fx.assignCustomRole(org, limited, roleId, admin);
        mvc.perform(as(org, limited, get("/core/telemetry/series").param("deviceId", Long.toString(dev3)).param("metrics", "temperature")
                        .param("from", from)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        mvc.perform(as(org, limited, get("/core/telemetry/latest").param("deviceIds", Long.toString(dev3)))).andExpect(status().isForbidden());
        mvc.perform(as(org, limited, get("/core/telemetry/space-series").param("spaceId", Long.toString(floor3)).param("metric", "temperature")
                .param("from", from))).andExpect(status().isForbidden());
        mvc.perform(as(org, limited, json(post("/core/telemetry/query"),
                "{\"series\":[{\"deviceId\":\"%d\",\"metric\":\"temperature\"}],\"from\":\"%s\"}".formatted(dev3, from))))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, limited, get("/core/telemetry/link-quality").param("deviceId", Long.toString(dev3)).param("from", from)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[IAM-04.05][BR-IAM-01] 다른 조직 기기·공간 ID는 404(존재를 드러내지 않음) — TC-TSD-055·098")
    void otherOrganization() throws Exception {
        long other = fx.organization("tsdq");
        long stranger = fx.user(other, "other.admin", "ADMIN");
        mvc.perform(as(other, stranger, get("/core/telemetry/series").param("deviceId", Long.toString(dev3)).param("metrics", "temperature")
                        .param("from", from)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_NOT_FOUND"));
        mvc.perform(as(other, stranger, get("/core/telemetry/space-series").param("spaceId", Long.toString(floor3)).param("metric", "temperature")
                .param("from", from))).andExpect(status().isNotFound());
        mvc.perform(as(other, stranger, get("/core/telemetry/latest").param("deviceIds", Long.toString(dev3))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response", hasSize(0)));
        mvc.perform(as(other, stranger, json(post("/core/telemetry/query"),
                        "{\"series\":[{\"deviceId\":\"%d\",\"metric\":\"temperature\"}],\"from\":\"%s\"}".formatted(dev3, from))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.series", hasSize(0)))
                .andExpect(jsonPath("$.response.excludedDeviceCount").value(1));
    }

    @Test
    @DisplayName("[IAM-04.06][AT-TSD-01.7][BR-TSD-13] 공간 범위 3층 사용자: 4층 기기 404 DEVICE_NOT_FOUND, 여러 계열은 빼고 excludedDeviceCount 1 — TC-TSD-065·143")
    void spaceScope() throws Exception {
        long scoped = fx.user(org, "floor3.viewer", "VIEWER");
        data.spaceScope(org, scoped, List.of(floor3));
        String q = "&metrics=temperature&from=" + from;
        mvc.perform(as(org, scoped, get("/core/telemetry/series?deviceId=" + dev3 + q))).andExpect(status().isOk());
        mvc.perform(as(org, scoped, get("/core/telemetry/series?deviceId=" + dev4 + q)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_NOT_FOUND"));
        mvc.perform(as(org, scoped, get("/core/telemetry/raw-points").param("deviceId", Long.toString(dev4)).param("metric", "temperature")
                .param("from", from))).andExpect(status().isNotFound());
        mvc.perform(as(org, scoped, get("/core/telemetry/link-quality").param("deviceId", Long.toString(dev4)).param("from", from)))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, scoped, get("/core/telemetry/state-intervals").param("deviceId", Long.toString(dev4)).param("metric", "temperature")
                .param("from", from))).andExpect(status().isNotFound());
        mvc.perform(as(org, scoped, get("/core/telemetry/space-series").param("spaceId", Long.toString(floor4)).param("metric", "temperature")
                .param("from", from))).andExpect(status().isNotFound());
        mvc.perform(as(org, scoped, get("/core/telemetry/space-series").param("spaceId", Long.toString(floor3)).param("metric", "temperature")
                .param("from", from))).andExpect(status().isOk()).andExpect(jsonPath("$.response.excludedDeviceCount").value(0));
        mvc.perform(as(org, scoped, json(post("/core/telemetry/query"), """
                        {"series":[{"deviceId":"%d","metric":"temperature"},{"deviceId":"%d","metric":"temperature"},{"spaceId":"%d","metric":"temperature"}],
                         "from":"%s","resolution":"raw"}""".formatted(dev3, dev4, floor4, from))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.series", hasSize(1)))
                .andExpect(jsonPath("$.response.series[0].deviceId").value(Long.toString(dev3)))
                .andExpect(jsonPath("$.response.excludedDeviceCount").value(1));
        mvc.perform(as(org, scoped, get("/core/telemetry/latest").param("deviceIds", dev3 + "," + dev4)))
                .andExpect(jsonPath("$.response", hasSize(1))).andExpect(jsonPath("$.response[0].deviceId").value(Long.toString(dev3)));
        // 범위 밖 공간이 섞인 공간 집계(상위 공간 지정)는 404
        long site = jdbc.sql("SELECT parent_id FROM data2flow_core.spaces WHERE id = :id").param("id", floor3).query(Long.class).single();
        mvc.perform(as(org, scoped, get("/core/telemetry/latest").param("spaceId", Long.toString(site)))).andExpect(status().isNotFound());
    }
}
