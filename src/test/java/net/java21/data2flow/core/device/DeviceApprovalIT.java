package net.java21.data2flow.core.device;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-02.03 승인 대기 승인·거부·모델 추천, DSC-03.05 승인 시 서명 키(ADR-031) */
class DeviceApprovalIT extends IntegrationTestSupport {

    private DeviceTestData d;
    private long org;
    private long admin;
    private long room;
    private long model;
    private long co2Model;
    private long source;

    @BeforeEach
    void setUp() {
        d = new DeviceTestData(jdbc);
        org = fx.organization("appr");
        admin = fx.user(org, "appr.admin", "ADMIN");
        long site = data.site(org, "본관");
        room = data.space(org, site, "ROOM", "실습실");
        data.metric(org, "temperature", "℃");
        data.metric(org, "humidity", "%");
        data.metric(org, "co2", "ppm");
        model = data.model(org, "EM300-TH", List.of("temperature", "humidity"));
        co2Model = data.model(org, "EM500-CO2", List.of("co2", "temperature", "humidity"));
        source = data.source(org, "cs");
    }

    private String approveBody(long modelId, long spaceId, String items) {
        return "{\"items\":[" + items + "],\"modelId\":\"" + modelId + "\",\"spaceId\":\"" + spaceId + "\",\"tags\":[\"new\"]}";
    }

    private static String item(long id, int version) {
        return "{\"deviceId\":\"" + id + "\",\"baseVersion\":" + version + "}";
    }

    @Test
    @DisplayName("[DEV-02.03][AT-DEV-03.2·03.1] PENDING 3대 일괄 승인: 모두 ACTIVE, 감사 3건, 승인 전 데이터 그대로, EVT-DEV-01 APPROVED — TC-DEV-043·045")
    void approveThree() throws Exception {
        long a = data.device(org, source, "p1", "PENDING", null, null);
        long b = data.device(org, source, "p2", "PENDING", null, null);
        long c = data.device(org, source, "p3", "PENDING", null, null);
        jdbc.sql("UPDATE data2flow_core.devices SET auto_registered = true WHERE id IN (:a, :b, :c)").param("a", a).param("b", b).param("c", c).update();
        data.telemetry(org, a, "temperature", clock.instant().minusSeconds(3600), 22.5, 0, false);
        mvc.perform(as(org, admin, json(post("/core/devices/approve"), approveBody(model, room, item(a, 0) + "," + item(b, 0) + "," + item(c, 0)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.results", hasSize(3)))
                .andExpect(jsonPath("$.response.results[0].ok").value(true))
                .andExpect(jsonPath("$.response.results[0].signingKey").doesNotExist());
        assertThat(List.of(d.status(a), d.status(b), d.status(c))).containsOnly("ACTIVE");
        assertThat(auditCount(org, "DEVICE_APPROVED")).isEqualTo(3);
        assertThat(d.outboxPayloads(org, "device.changed")).hasSize(3).allMatch(p -> p.contains("APPROVED"));
        mvc.perform(as(org, admin, get("/core/devices/" + a)))
                .andExpect(jsonPath("$.response.status").value("ACTIVE"))
                .andExpect(jsonPath("$.response.model.code").value("EM300-TH"))
                .andExpect(jsonPath("$.response.tags[0]").value("new"));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_pipeline.telemetry WHERE device_id = :d").param("d", a)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("[DEV-13.01][BR-DEV-32] 승인할 때 모델의 시맨틱 템플릿으로 장비·점이 만들어진다 — TC-DEV-304")
    void approveAppliesSemanticTemplate() throws Exception {
        jdbc.sql("UPDATE data2flow_core.device_models SET semantic_template = CAST(:t AS jsonb) WHERE id = :m")
                .param("t", "{\"equipClass\":\"Air_Quality_Sensor\",\"points\":[{\"metricKey\":\"temperature\",\"pointType\":\"MEASUREMENT\",\"quantity\":\"Temperature\",\"tags\":[\"air\"]}]}")
                .param("m", model).update();
        long a = data.device(org, source, "sem-1", "PENDING", null, null);
        mvc.perform(as(org, admin, json(post("/core/devices/approve"), approveBody(model, room, item(a, 0)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.results[0].ok").value(true));
        assertThat(jdbc.sql("""
                        SELECT p.metric_key FROM data2flow_core.points p JOIN data2flow_core.equipment e ON e.id = p.equipment_id
                         WHERE e.device_id = :d""").param("d", a).query(String.class).list()).containsExactly("temperature");
    }

    @Test
    @DisplayName("[DEV-02.03][AT-DEV-03.6] 같은 기기를 두 번 승인하면 두 번째는 409 DEVICE_STATE_CONFLICT(항목별), 모델·공간 필수 400, 없는 모델 404 — TC-DEV-044")
    void approveConflictsAndValidation() throws Exception {
        long a = data.device(org, source, "q1", "PENDING", null, null);
        mvc.perform(as(org, admin, json(post("/core/devices/approve"), approveBody(model, room, item(a, 0)))))
                .andExpect(jsonPath("$.response.results[0].ok").value(true));
        mvc.perform(as(org, admin, json(post("/core/devices/approve"), approveBody(model, room, item(a, 0)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.results[0].ok").value(false))
                .andExpect(jsonPath("$.response.results[0].errorCode").value("DEVICE_STATE_CONFLICT"));
        mvc.perform(as(org, admin, json(post("/core/devices/approve"), "{\"items\":[" + item(a, 1) + "],\"spaceId\":\"" + room + "\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_MODEL_REQUIRED"));
        mvc.perform(as(org, admin, json(post("/core/devices/approve"), "{\"items\":[" + item(a, 1) + "],\"modelId\":\"" + model + "\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_SPACE_REQUIRED"));
        mvc.perform(as(org, admin, json(post("/core/devices/approve"), approveBody(999999, room, item(a, 1)))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("MODEL_NOT_FOUND"));
        mvc.perform(as(org, admin, json(post("/core/devices/approve"), approveBody(model, room, item(999999, 0)))))
                .andExpect(jsonPath("$.response.results[0].errorCode").value("DEVICE_NOT_FOUND"));
        long full = data.device(org, source, "q2", "PENDING", null, null);
        for (int i = 0; i < 20; i++) {
            d.tag(org, full, "t" + i);
        }
        mvc.perform(as(org, admin, json(post("/core/devices/approve"), approveBody(model, room, item(full, 0)))))
                .andExpect(jsonPath("$.response.results[0].errorCode").value("DEVICE_TAG_LIMIT"));
        long single = data.device(org, source, "q3", "PENDING", null, null);
        mvc.perform(as(org, admin, json(post("/core/devices/approve"),
                        "{\"items\":[" + item(single, 0) + "],\"modelId\":\"" + model + "\",\"spaceId\":\"" + room + "\",\"name\":\"강단 센서\"}")))
                .andExpect(jsonPath("$.response.results[0].ok").value(true));
        mvc.perform(as(org, admin, get("/core/devices/" + single))).andExpect(jsonPath("$.response.name").value("강단 센서"));
    }

    @Test
    @DisplayName("[DEV-02.03][AT-DEV-03.7] VIEWER·ANALYST는 승인·거부 403, OPERATOR는 승인 가능 — TC-DEV-046")
    void approvePermissions() throws Exception {
        long a = data.device(org, source, "r1", "PENDING", null, null);
        for (String role : List.of("VIEWER", "ANALYST")) {
            long user = fx.user(org, "u." + role.toLowerCase(), role);
            mvc.perform(as(org, user, json(post("/core/devices/approve"), approveBody(model, room, item(a, 0)))))
                    .andExpect(status().isForbidden());
            mvc.perform(as(org, user, json(post("/core/devices/reject"), "{\"deviceIds\":[\"" + a + "\"]}")))
                    .andExpect(status().isForbidden());
        }
        long op = fx.user(org, "u.op", "OPERATOR");
        mvc.perform(as(org, op, json(post("/core/devices/approve"), approveBody(model, room, item(a, 0)))))
                .andExpect(jsonPath("$.response.results[0].ok").value(true));
    }

    @Test
    @DisplayName("[DEV-02.03][BR-DEV-07][AT-DEV-03.5] 거부하면 DELETED + 무시 목록, 같은 외부 ID 자동 등록은 409 DEVICE_REJECTED, PENDING 아니면 항목별 409 — TC-DEV-045·047")
    void rejectAddsToIgnoreList() throws Exception {
        long a = data.device(org, source, "a84041000181c5f0", "PENDING", null, null);
        long active = data.device(org, source, "act1", "ACTIVE", room, model);
        long noIgnore = data.device(org, source, "n1", "PENDING", null, null);
        mvc.perform(as(org, admin, json(post("/core/devices/reject"), "{\"deviceIds\":[\"%d\",\"%d\"]}".formatted(a, active))))
                .andExpect(jsonPath("$.response.results[0].ok").value(true))
                .andExpect(jsonPath("$.response.results[0].ignored").value(true))
                .andExpect(jsonPath("$.response.results[1].errorCode").value("DEVICE_STATE_CONFLICT"));
        assertThat(d.status(a)).isEqualTo("DELETED");
        assertThat(auditCount(org, "DEVICE_REJECTED")).isEqualTo(1);
        mvc.perform(json(post("/internal/core/devices/auto-register"),
                        "{\"organizationId\":%d,\"sourceId\":%d,\"externalId\":\"A84041000181C5F0\"}".formatted(org, source)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_REJECTED"));
        mvc.perform(get("/internal/core/sources/" + source + "/devices/a84041000181c5f0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.ignored").value(true))
                .andExpect(jsonPath("$.response.deviceId").doesNotExist());

        mvc.perform(as(org, admin, json(post("/core/devices/reject"), "{\"deviceIds\":[\"%d\",\"999999\"],\"addToIgnoreList\":false}"
                        .formatted(noIgnore))))
                .andExpect(jsonPath("$.response.results[0].ignored").value(false))
                .andExpect(jsonPath("$.response.results[1].errorCode").value("DEVICE_NOT_FOUND"));
        // 무시 목록에 없으면 다시 수신될 때 같은 행이 PENDING으로 되살아난다
        mvc.perform(json(post("/internal/core/devices/auto-register"),
                        "{\"organizationId\":%d,\"sourceId\":%d,\"externalId\":\"n1\"}".formatted(org, source)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.created").value(true))
                .andExpect(jsonPath("$.response.deviceId").value(Long.toString(noIgnore)));
    }

    @Test
    @DisplayName("[DSC-03.05][AT-DSC-07.5] 플랫폼 브로커 기기 승인: 서명 키를 응답에 한 번만, DB에는 SHA-256 해시만, 다시 조회해도 없음 — TC-DSC-323")
    void platformBrokerSigningKey() throws Exception {
        long broker = d.source(org, "pb", "PLATFORM_BROKER", "AUTO_REGISTER", null, null, 100);
        long a = data.device(org, broker, "esp-02", "PENDING", null, null);
        String body = mvc.perform(as(org, admin, json(post("/core/devices/approve"), approveBody(model, room, item(a, 0)))))
                .andExpect(jsonPath("$.response.results[0].ok").value(true))
                .andReturn().getResponse().getContentAsString();
        String key = JsonPath.read(body, "$.response.results[0].signingKey");
        assertThat(key).hasSizeGreaterThan(30);
        String hash = jdbc.sql("SELECT signing_key_hash FROM data2flow_core.device_credentials WHERE device_id = :d").param("d", a)
                .query(String.class).single();
        assertThat(hash).isEqualTo(Tokens.sha256Hex(key)).doesNotContain(key);
        mvc.perform(as(org, admin, get("/core/devices/" + a + "/credentials")))
                .andExpect(jsonPath("$.response[0].username").value("esp-02"))
                .andExpect(jsonPath("$.response[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.response[0].signingKey").doesNotExist());
        assertThat(d.outbox(org, "CONFIG", "")).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("[DEV-02.03][API-DEV-29] 모델 추천: 수신한 측정 항목과 모델 측정 항목의 겹침 순(최대 3개), 데이터 없으면 빈 목록 — TC-DEV-044")
    void modelSuggestions() throws Exception {
        long a = data.device(org, source, "s1", "PENDING", null, null);
        data.deviceState(org, a, "ONLINE", clock.instant(), "{\"temperature\":{\"v\":1},\"humidity\":{\"v\":2}}");
        mvc.perform(as(org, admin, get("/core/devices/" + a + "/model-suggestions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", hasSize(2)))
                .andExpect(jsonPath("$.response[0].modelId").value(Long.toString(model)))
                .andExpect(jsonPath("$.response[0].score").value(1.0))
                .andExpect(jsonPath("$.response[1].modelId").value(Long.toString(co2Model)));
        long b = data.device(org, source, "s2", "PENDING", null, null);
        jdbc.sql("UPDATE data2flow_core.devices SET source_meta = CAST('{\"metrics\":[\"co2\"]}' AS jsonb) WHERE id = :id").param("id", b).update();
        mvc.perform(as(org, admin, get("/core/devices/" + b + "/model-suggestions")))
                .andExpect(jsonPath("$.response", hasSize(1))).andExpect(jsonPath("$.response[0].modelCode").value("EM500-CO2"));
        long c = data.device(org, source, "s3", "PENDING", null, null);
        mvc.perform(as(org, admin, get("/core/devices/" + c + "/model-suggestions"))).andExpect(jsonPath("$.response", hasSize(0)));
    }
}
