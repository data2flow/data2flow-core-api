package net.java21.data2flow.core.devicesearch;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.devicegroup.service.DynamicGroupMembership;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-13.03 기기 검색식·저장된 검색(API-DEV-133·134, BR-DEV-35) */
class DeviceSearchIT extends IntegrationTestSupport {

    @Autowired
    DynamicGroupMembership membership;

    private long org;
    private long admin;
    private long operator;
    private long site;
    private long floor3;
    private long floor4;
    private long model;
    private long source;

    @BeforeEach
    void setUp() {
        org = fx.organization("ds");
        admin = fx.user(org, "ds.admin", "ADMIN");
        operator = fx.user(org, "ds.op", "OPERATOR");
        site = data.site(org, "본관");
        floor3 = data.space(org, site, "FLOOR", "3층");
        floor4 = data.space(org, site, "FLOOR", "4층");
        data.metric(org, "temperature", "°C");
        model = data.model(org, "EM300-TH", List.of("temperature"));
        source = data.source(org, "cs");
    }

    private void battery(long device, int value) {
        data.deviceState(org, device, "ONLINE", clock.instant(), "{\"co2\":{\"v\":" + (400 + value * 10) + ",\"t\":\"2026-10-03T00:00:00Z\",\"q\":0}}");
        jdbc.sql("UPDATE data2flow_pipeline.device_state SET battery = :b WHERE device_id = :d").param("b", value).param("d", device).update();
    }

    @Test
    @DisplayName("[DEV-13.03][AT-DEV-25.1·25.2] 예시 검색식 결과가 정확하고 counts{total, tookMs}, 오류 위치(열 11), 키워드 검색은 그대로 — TC-DEV-313·315")
    void searchExpression() throws Exception {
        long room3 = data.space(org, floor3, "ROOM", "실습실");
        long a = data.device(org, source, "a", "ACTIVE", room3, model);
        long b = data.device(org, source, "b", "ACTIVE", room3, model);
        long c = data.device(org, source, "c", "ACTIVE", floor4, model);
        long other = data.device(org, source, "x", "ACTIVE", room3, null);
        battery(a, 10);
        battery(b, 80);
        battery(c, 5);
        battery(other, 5);
        jdbc.sql("INSERT INTO data2flow_core.device_tags (organization_id, device_id, tag) VALUES (:o, :d, '창가')").param("o", org).param("d", a).update();
        mvc.perform(as(org, operator, get("/core/devices").param("q", "model = \"EM300-TH\" and battery < 20 and space in \"3층\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].id").value(Long.toString(a)))
                .andExpect(jsonPath("$.counts.total").value(1)).andExpect(jsonPath("$.counts.tookMs").isNumber());
        mvc.perform(as(org, operator, get("/core/devices").param("q", "tag = \"창가\" or metric.co2 >= 1200")))
                .andExpect(jsonPath("$.totalCount").value(2));
        mvc.perform(as(org, operator, get("/core/devices").param("q", "not space in \"3층\"")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].id").value(Long.toString(c)));
        mvc.perform(as(org, operator, get("/core/devices").param("q", "battery < ")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_QUERY_INVALID"))
                .andExpect(jsonPath("$.response.column").value(11)).andExpect(jsonPath("$.errors[0].field").value("q"));
        mvc.perform(as(org, operator, get("/core/devices").param("q", "기기 a")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.counts").doesNotExist());
    }

    @Test
    @DisplayName("[DEV-13.03][AT-DEV-25.1][NFR] 기기 1만 대에서 검색식 p95 1초 이내 — TC-DEV-313")
    void tenThousandDevices() throws Exception {
        long room3 = data.space(org, floor3, "ROOM", "실습실");
        jdbc.sql("""
                        INSERT INTO data2flow_core.devices (organization_id, source_id, external_id, name, status, space_id, model_id)
                        SELECT :org, :src, 'bulk-' || g, '대량 ' || g, 'ACTIVE', CASE WHEN g % 2 = 0 THEN :room ELSE :floor4 END, :model
                          FROM generate_series(1, 10000) g""")
                .param("org", org).param("src", source).param("room", room3).param("floor4", floor4).param("model", model).update();
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.device_state (device_id, organization_id, connectivity, battery, latest)
                        SELECT id, organization_id, 'ONLINE', (id % 100), '{}'::jsonb FROM data2flow_core.devices WHERE organization_id = :org""")
                .param("org", org).update();
        jdbc.sql("ANALYZE data2flow_core.devices").update();
        long expected = jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.devices d JOIN data2flow_pipeline.device_state st ON st.device_id = d.id
                         WHERE d.organization_id = :org AND d.space_id = :room AND st.battery < 20""")
                .param("org", org).param("room", room3).query(Long.class).single();
        List<Long> took = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long started = System.nanoTime();
            String res = mvc.perform(as(org, admin, get("/core/devices").param("q", "model = \"EM300-TH\" and battery < 20 and space in \"3층\"")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            took.add((System.nanoTime() - started) / 1_000_000);
            assertThat(((Number) JsonPath.read(res, "$.totalCount")).longValue()).isEqualTo(expected);
        }
        Collections.sort(took);
        assertThat(took.get(18)).as("p95 ms %s", took).isLessThan(1000);
    }

    @Test
    @DisplayName("[DEV-13.03][AT-DEV-25.3·25.4] 저장된 검색 CRUD(본인·공유), 실행 시점 결과, 검색식을 동적 그룹 조건으로 쓰면 새로 맞는 기기가 자동 포함 — TC-DEV-313·315")
    void savedSearchesAndGroups() throws Exception {
        long d1 = data.device(org, source, "d1", "ACTIVE", floor3, model);
        battery(d1, 10);
        String id = JsonPath.read(mvc.perform(as(org, operator, json(post("/core/saved-searches"),
                        "{\"name\":\"배터리 부족\",\"query\":\"battery < 20\",\"shared\":true}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.response.id");
        mvc.perform(as(org, operator, json(post("/core/saved-searches"), "{\"name\":\"배터리 부족\",\"query\":\"battery < 20\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, operator, json(post("/core/saved-searches"), "{\"name\":\"잘못\",\"query\":\"battery <\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_QUERY_INVALID"));
        long viewer = fx.user(org, "ds.viewer", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/saved-searches"))).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].ownerId").value(Long.toString(operator)));
        mvc.perform(as(org, viewer, json(post("/core/saved-searches"), "{\"name\":\"v\",\"query\":\"battery < 20\"}")))
                .andExpect(status().isForbidden());
        long op2 = fx.user(org, "ds.op2", "OPERATOR");
        mvc.perform(as(org, op2, json(put("/core/saved-searches/" + id), "{\"name\":\"x\",\"query\":\"battery < 30\"}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, operator, json(put("/core/saved-searches/" + id), "{\"name\":\"배터리 부족\",\"query\":\"battery < 30\",\"shared\":false}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.query").value("battery < 30"));
        mvc.perform(as(org, viewer, get("/core/saved-searches/" + id))).andExpect(status().isNotFound());
        // 동적 그룹 조건으로(AT-DEV-25.4)
        String group = JsonPath.read(mvc.perform(as(org, admin, json(post("/core/device-groups"),
                        "{\"name\":\"배터리 부족\",\"type\":\"DYNAMIC\",\"criteria\":{\"query\":\"battery < 20\"}}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.response.id");
        mvc.perform(as(org, admin, get("/core/device-groups/" + group))).andExpect(jsonPath("$.response.memberCount").value(1));
        long d2 = data.device(org, source, "d2", "ACTIVE", floor3, model);
        battery(d2, 3);
        membership.refreshDevice(org, d2);
        mvc.perform(as(org, admin, get("/core/device-groups/" + group))).andExpect(jsonPath("$.response.memberCount").value(2));
        mvc.perform(as(org, admin, json(post("/core/device-groups"), "{\"name\":\"x\",\"type\":\"DYNAMIC\",\"criteria\":{\"query\":\"battery <\"}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("criteria.query"));
        mvc.perform(as(org, operator, delete("/core/saved-searches/" + id))).andExpect(status().isNoContent());
        assertThat(auditCount(org, "SAVED_SEARCH_CHANGED")).isEqualTo(3);
    }

    @Test
    @DisplayName("[DEV-13.03][AT-DEV-25.5][BR-DEV-35] 사이트 권한이 제한된 사용자는 권한 밖 기기가 결과·건수에 없음, 다른 조직 기기 없음 — TC-DEV-316")
    void scoped() throws Exception {
        long annex = data.site(org, "별관");
        long inMain = data.device(org, source, "m", "ACTIVE", floor3, model);
        long inAnnex = data.device(org, source, "n", "ACTIVE", annex, model);
        battery(inMain, 1);
        battery(inAnnex, 1);
        long viewer = fx.user(org, "ds.v", "VIEWER");
        data.spaceScope(org, viewer, List.of(annex));
        mvc.perform(as(org, viewer, get("/core/devices").param("q", "battery < 20")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].id").value(Long.toString(inAnnex)))
                .andExpect(jsonPath("$.counts.total").value(1));
        long other = fx.organization("ds2");
        mvc.perform(as(other, fx.user(other, "ds2.a", "ADMIN"), get("/core/devices").param("q", "battery < 20")))
                .andExpect(jsonPath("$.totalCount").value(0));
    }
}
