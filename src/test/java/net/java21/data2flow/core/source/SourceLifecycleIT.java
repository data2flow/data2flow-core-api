package net.java21.data2flow.core.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DSC-07.01 소스 상태 DRAFT → ACTIVE ↔ PAUSED → ARCHIVED(BR-DSC-21 감사), DSC-07.03 한도(BR-DSC-06, AT-DSC-21.3·21.4)
 * — TC-DSC-163~166·175~178
 */
class SourceLifecycleIT extends SourceItSupport {

    @Test
    @DisplayName("[DSC-07.01][BR-DSC-21] 전이마다 감사·EVT-DSC-01, 허용되지 않은 전이 409, ACTIVE 대표 상태 CONNECTING → PAUSED DISABLED(EVT-DSC-04) — TC-DSC-163·166")
    void transitions() throws Exception {
        long id = createSource(mqttBody("life", null));
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/pause"), "{\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_STATE_CONFLICT"));
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/activate"), "{\"baseVersion\":3}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        mvc.perform(as(org, integrator, post("/core/sources/" + id + "/activate")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("baseVersion"));
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/activate"), "{\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.lifecycle").value("ACTIVE"))
                .andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, operator, get("/core/sources/" + id))).andExpect(jsonPath("$.response.state").value("CONNECTING"));
        assertThat(outbox(org, "source.connection.changed")).hasSize(1).first().asString().contains("\"from\":\"DISABLED\"", "\"to\":\"CONNECTING\"");

        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/activate"), "{\"baseVersion\":1}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/pause"), "{\"baseVersion\":1}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.lifecycle").value("PAUSED"));
        mvc.perform(as(org, operator, get("/core/sources/" + id))).andExpect(jsonPath("$.response.state").value("DISABLED"));
        assertThat(outbox(org, "source.connection.changed")).hasSize(2);
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/resume"), "{\"baseVersion\":2}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.lifecycle").value("ACTIVE"));
        // 보관은 확인이 필요하다(코드 입력 또는 true)
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/archive"), "{\"baseVersion\":3}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("confirm"));
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/archive"), "{\"baseVersion\":3,\"confirm\":\"wrong\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/archive"), "{\"baseVersion\":3,\"confirm\":\"life\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.lifecycle").value("ARCHIVED"))
                .andExpect(jsonPath("$.response.archivedAt").value("2026-10-03T00:00:00Z"));
        // ARCHIVED는 읽기 전용, 되살릴 수 없다(복제로 새로)
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/resume"), "{\"baseVersion\":4}"))).andExpect(status().isConflict());
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/activate"), "{\"baseVersion\":4}"))).andExpect(status().isConflict());
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"name\":\"x\",\"baseVersion\":4}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_STATE_CONFLICT"));
        mvc.perform(as(org, integrator, json(put("/core/sources/" + id + "/secret"),
                "{\"kind\":\"HEADER\",\"value\":\"x\"}"))).andExpect(status().isConflict());
        // 목록 기본은 ARCHIVED 제외
        mvc.perform(as(org, operator, get("/core/sources"))).andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(as(org, operator, get("/core/sources").param("lifecycle", "ARCHIVED"))).andExpect(jsonPath("$.totalCount").value(1));

        assertThat(auditCount(org, "SOURCE_ACTIVATED")).isEqualTo(1);
        assertThat(auditCount(org, "SOURCE_PAUSED")).isEqualTo(1);
        assertThat(auditCount(org, "SOURCE_RESUMED")).isEqualTo(1);
        assertThat(auditCount(org, "SOURCE_ARCHIVED")).isEqualTo(1);
        assertThat(outbox(org, "CONFIG")).hasSize(5);
        assertThat(jdbc.sql("SELECT version FROM data2flow_core.config_versions WHERE organization_id = :o AND scope = 'SOURCES'")
                .param("o", org).query(Long.class).single()).isEqualTo(5);
    }

    @Test
    @DisplayName("[DSC-07.01] 활성화 조건: 필수 비밀값이 없으면 SOURCE_SECRET_REQUIRED, ACTIVE에서 필수 설정을 지우는 수정도 거부")
    void activationRequirements() throws Exception {
        long id = createSource(mqttBody("req", null).replace("\"secret\":{\"kind\":\"HEADER\",\"value\":\"student:s3cr3t-Pa55\"},", ""));
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/activate"), "{\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_SECRET_REQUIRED"));
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"secret\":{\"kind\":\"HEADER\",\"value\":\"u:p\"},\"baseVersion\":0}")))
                .andExpect(status().isOk());
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/activate"), "{\"baseVersion\":1}"))).andExpect(status().isOk());
        mvc.perform(as(org, integrator, json(patch("/core/sources/" + id), "{\"connection\":{\"url\":\"wss://b.test/mqtt\",\"auth\":\"USERPASS\",\"username\":\"u\"},\"baseVersion\":2}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_SECRET_REQUIRED"));
    }

    @Test
    @DisplayName("[DSC-07.03][AT-DSC-21.3][AT-DSC-21.4] 조직 소스 한도 초과 429, ADMIN이 한도를 바꾸면 성공 + 감사 SOURCE_LIMIT_CHANGED — TC-DSC-175·178")
    void sourceLimits() throws Exception {
        mvc.perform(as(org, admin, json(patch("/core/source-limits"), "{\"maxSources\":2,\"maxTopicsPerSource\":1,\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.maxSources").value(2))
                .andExpect(jsonPath("$.response.maxMessageBytes").value(262144)).andExpect(jsonPath("$.response.version").value(1));
        createSource(mqttBody("l1", null));
        long archived = createSource(mqttBody("l2", "\"activate\":true"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("l3", null))))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.header.resultMessage").value("소스(또는 토픽) 수 한도를 넘었습니다"));
        // 보관한 소스는 한도에서 빠진다
        mvc.perform(as(org, integrator, json(post("/core/sources/" + archived + "/archive"), "{\"baseVersion\":0,\"confirm\":true}")))
                .andExpect(status().isOk());
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("l4", null)
                        .replace("[{\"topic\":\"application/+/device/+/event/up\",\"qos\":1}]", "[{\"topic\":\"a\"},{\"topic\":\"b\"}]"))))
                .andExpect(status().isTooManyRequests());
        createSource(mqttBody("l4", null));
        mvc.perform(as(org, admin, json(patch("/core/source-limits"), "{\"maxSources\":100,\"baseVersion\":0}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, admin, json(patch("/core/source-limits"), "{\"maxSources\":0,\"baseVersion\":1}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("maxSources"));
        mvc.perform(as(org, admin, json(patch("/core/source-limits"), "{\"maxSources\":100,\"baseVersion\":1}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.activeSources").value(2));
        createSource(mqttBody("l5", null));
        assertThat(auditCount(org, "SOURCE_LIMIT_CHANGED")).isEqualTo(2);
        mvc.perform(as(org, operator, get("/core/source-limits"))).andExpect(jsonPath("$.response.maxSources").value(100))
                .andExpect(jsonPath("$.response.maxMessagesPerSec").value(500));
    }
}
