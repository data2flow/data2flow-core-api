package net.java21.data2flow.core.device;

import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-07.01·07.04·07.05 기기 속성과 이력, 내부 API-DEV-122·130 */
class DeviceAttributeIT extends IntegrationTestSupport {

    private DeviceTestData d;
    private long org;
    private long admin;
    private long model;
    private long device;
    private long room;

    @BeforeEach
    void setUp() {
        d = new DeviceTestData(jdbc);
        org = fx.organization("attr");
        admin = fx.user(org, "attr.admin", "ADMIN");
        long site = data.site(org, "본관");
        room = data.space(org, site, "ROOM", "실습실");
        data.metric(org, "temperature", "℃");
        model = data.model(org, "EM300-TH", List.of("temperature"));
        long source = data.source(org, "cs");
        device = data.device(org, source, "a1", "ACTIVE", room, model);
    }

    private String url(String scope, String key) {
        return "/core/devices/" + device + "/attributes/" + scope + "/" + key;
    }

    @Test
    @DisplayName("[DEV-07.04] 값이 바뀔 때마다 이력 1건(변경자·시각·이전·새 값), 같은 값이면 이력 없음, 최신순 — TC-DEV-194·196")
    void historyOnlyOnChange() throws Exception {
        mvc.perform(as(org, admin, json(put(url("server", "tempOffset")), "{\"value\":-0.5}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.value").value(-0.5))
                .andExpect(jsonPath("$.response.scope").value("SERVER")).andExpect(jsonPath("$.response.applyState").isEmpty());
        mvc.perform(as(org, admin, json(put(url("SERVER", "tempOffset")), "{\"value\":-0.5}"))).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(as(org, admin, json(put(url("SERVER", "tempOffset")), "{\"value\":-0.7}"))).andExpect(status().isOk());
        mvc.perform(as(org, admin, get("/core/devices/" + device + "/attributes/history")))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[0].oldValue").value(-0.5))
                .andExpect(jsonPath("$.responses[0].newValue").value(-0.7))
                .andExpect(jsonPath("$.responses[0].changedBy").value(Long.toString(admin)))
                .andExpect(jsonPath("$.responses[0].changedByName").value("이름 attr.admin"))
                .andExpect(jsonPath("$.responses[1].oldValue").isEmpty());
        mvc.perform(as(org, admin, get("/core/devices/" + device + "/attributes/history").param("key", "other")))
                .andExpect(jsonPath("$.totalCount").value(0));
        assertThat(auditCount(org, "DEVICE_ATTRIBUTE_CHANGED")).isEqualTo(2);
        assertThat(d.outbox(org, "CONFIG", "")).isGreaterThanOrEqualTo(4);
        mvc.perform(as(org, admin, delete(url("SERVER", "tempOffset")))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, delete(url("SERVER", "tempOffset")))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get("/core/devices/" + device + "/attributes/history"))).andExpect(jsonPath("$.totalCount").value(3));
    }

    @Test
    @DisplayName("[DEV-07.01][BR-DEV-24] CLIENT는 403 ATTRIBUTE_READONLY, SHARED는 DEVICE_CONTROL도 필요, OPERATOR는 서버 속성 403, 공유 속성은 desired·PENDING — TC-DEV-182·183·185")
    void scopesAndPermissions() throws Exception {
        mvc.perform(as(org, admin, json(put(url("CLIENT", "firmware")), "{\"value\":\"1.2\"}")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("ATTRIBUTE_READONLY"));
        long op = fx.user(org, "attr.op", "OPERATOR");
        mvc.perform(as(org, op, json(put(url("SERVER", "maxTemp")), "{\"value\":28}"))).andExpect(status().isForbidden());
        long integrator = fx.user(org, "attr.int", "INTEGRATOR");
        mvc.perform(as(org, integrator, json(put(url("SHARED", "reportInterval")), "{\"value\":300}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.desired").value(300))
                .andExpect(jsonPath("$.response.applyState").value("PENDING"));
        long analyst = fx.user(org, "attr.ana", "ANALYST");
        mvc.perform(as(org, analyst, get("/core/devices/" + device + "/attributes")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.shared.reportInterval.value").value(300));
        jdbc.sql("INSERT INTO data2flow_core.device_attributes (organization_id, device_id, scope, key, value) VALUES (:o, :d, 'CLIENT', 'fw', '\"1.0\"')")
                .param("o", org).param("d", device).update();
        mvc.perform(as(org, admin, get("/core/devices/" + device + "/attributes"))).andExpect(jsonPath("$.response.client.fw.value").value("1.0"));
        mvc.perform(as(org, admin, json(put(url("BOGUS", "a")), "{\"value\":1}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(put(url("SERVER", "9bad")), "{\"value\":1}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(put(url("SERVER", "a")), "{}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(put(url("SERVER", "a")), "{\"value\":\"" + "x".repeat(5000) + "\"}"))).andExpect(status().isBadRequest());
        long other = fx.organization("attr2");
        long otherAdmin = fx.user(other, "attr2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, json(put(url("SERVER", "a")), "{\"value\":1}"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[DEV-07.05] 모델 속성 스키마: number 속성에 \"abc\"는 400 ATTRIBUTE_SCHEMA_VIOLATION, 필수 키는 지울 수 없음, 런타임에 기본값 적용 — TC-DEV-198")
    void schemaAndRuntime() throws Exception {
        d.attributeSchema(model, "{\"type\":\"object\",\"properties\":{\"tempOffset\":{\"type\":\"number\",\"default\":0},"
                + "\"maxTemp\":{\"type\":\"number\"}},\"required\":[\"maxTemp\"]}");
        mvc.perform(as(org, admin, json(put(url("SERVER", "tempOffset")), "{\"value\":\"abc\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ATTRIBUTE_SCHEMA_VIOLATION"));
        mvc.perform(as(org, admin, json(put(url("SERVER", "maxTemp")), "{\"value\":28}"))).andExpect(status().isOk());
        mvc.perform(as(org, admin, delete(url("SERVER", "maxTemp"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ATTRIBUTE_SCHEMA_VIOLATION"));
        mvc.perform(as(org, admin, json(put(url("SHARED", "reportInterval")), "{\"value\":300}"))).andExpect(status().isOk());

        long script = jdbc.sql("""
                        INSERT INTO data2flow_core.scripts (organization_id, name, kind, created_by, updated_by) VALUES (:o, 't', 'TRANSFORM', 0, 0)
                        RETURNING id""").param("o", org).query(Long.class).single();
        jdbc.sql("INSERT INTO data2flow_core.script_bindings (organization_id, script_id, kind, target_type, target_id) VALUES (:o, :s, 'TRANSFORM', 'MODEL', :m)")
                .param("o", org).param("s", script).param("m", Long.toString(model)).update();
        mvc.perform(get("/internal/core/devices/" + device + "/runtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.modelId").value(Long.toString(model)))
                .andExpect(jsonPath("$.response.attributes.server.tempOffset").value(0))
                .andExpect(jsonPath("$.response.attributes.server.maxTemp").value(28))
                .andExpect(jsonPath("$.response.attributes.shared.reportInterval").value(300))
                .andExpect(jsonPath("$.response.transformScripts", hasSize(1)))
                .andExpect(jsonPath("$.response.transformScripts[0].scope").value("MODEL"))
                .andExpect(jsonPath("$.response.transformScripts[0].scriptId").value(Long.toString(script)));
        mvc.perform(get("/internal/core/devices/999999/runtime")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[DEV-02.01][API-DEV-130] 변경분 페이지: updatedAfter 뒤만, 변경 시각 순, 상태 필터, 형식 오류 400")
    void changedDevices() throws Exception {
        jdbc.sql("UPDATE data2flow_core.devices SET updated_at = '2026-10-01T00:00:00Z' WHERE id = :d").param("d", device).update();
        long source = jdbc.sql("SELECT source_id FROM data2flow_core.devices WHERE id = :d").param("d", device).query(Long.class).single();
        long pending = data.device(org, source, "p2", "PENDING", null, null);
        jdbc.sql("UPDATE data2flow_core.devices SET updated_at = '2026-10-02T00:00:00Z' WHERE id = :d").param("d", pending).update();
        mvc.perform(get("/internal/core/devices"))
                .andExpect(jsonPath("$.totalCount").value(2)).andExpect(jsonPath("$.responses[0].deviceId").value(Long.toString(device)))
                .andExpect(jsonPath("$.responses[0].externalId").value("a1"));
        mvc.perform(get("/internal/core/devices").param("updatedAfter", "2026-10-01T12:00:00Z"))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].status").value("PENDING"));
        mvc.perform(get("/internal/core/devices").param("status", "ACTIVE").param("size", "500"))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.size").value(500));
        mvc.perform(get("/internal/core/devices").param("status", "GONE")).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/core/devices").param("updatedAfter", "yesterday")).andExpect(status().isBadRequest());
    }
}
