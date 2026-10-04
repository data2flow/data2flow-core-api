package net.java21.data2flow.core.script;

import net.java21.data2flow.core.common.Pg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스크립트 운영(SCR-03.05·04.02·05.01·05.02): 설정값 API-SCR-22, 로그 수집 API-SCR-14, 지표 API-SCR-12(pipeline API-SCR-36 대역),
 * 오류 스냅샷 API-SCR-13, 실행 묶음 configRevision·logCaptureUntil·config(API-SCR-32)
 */
class ScriptOpsIT extends ScriptItSupport {

    private long org;
    private long integrator;
    private String scriptId;
    private String draftId;

    @BeforeEach
    void setUp() throws Exception {
        org = fx.organization("ops");
        integrator = fx.user(org, "ops.integrator", "INTEGRATOR");
        JsonNode created = create(org, integrator, "{\"name\":\"보정\",\"kind\":\"TRANSFORM\",\"templateKey\":\"calibration-offset\"}");
        scriptId = created.get("id").asString();
        draftId = created.get("draftVersionId").asString();
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/deploy"),
                "{\"versionId\":\"" + draftId + "\",\"memo\":\"첫 배포\",\"baseActiveVersionId\":null}"))).andExpect(status().isOk());
    }

    private JsonNode bundleScript() throws Exception {
        return body(mvc.perform(get("/internal/core/scripts/runtime-bundle?organizationId=" + org)).andReturn())
                .get("response").get("scripts").get(0);
    }

    private int version() throws Exception {
        return body(mvc.perform(as(org, integrator, get("/core/scripts/" + scriptId))).andReturn()).get("response").get("version").asInt();
    }

    @Test
    @DisplayName("[SCR-04.02][AT-SCR-08.2·08.3][BR-SCR-12] 설정값 0.5 → 0.7: 새 버전 없이 저장·설정 판 +1·EVT-SCR-01·감사, 실행 묶음에 반영. apiToken 400, 기준 버전 불일치 409 — TC-SCR-070·071(core)")
    void config() throws Exception {
        long versions = jdbc.sql("SELECT count(*) FROM data2flow_core.script_versions WHERE script_id = :s").param("s", Long.parseLong(scriptId))
                .query(Long.class).single();
        long messages = outboxConfigCount(org);
        int v = version();
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + scriptId + "/config"),
                        "{\"config\":{\"tempOffset\":0.7,\"label\":\"A동\",\"enabled\":true},\"baseVersion\":" + v + "}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.config.tempOffset").value(0.7))
                .andExpect(jsonPath("$.response.configRevision").value(1));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.script_versions WHERE script_id = :s").param("s", Long.parseLong(scriptId))
                .query(Long.class).single()).isEqualTo(versions);
        assertThat(outboxConfigCount(org)).isEqualTo(messages + 1);
        assertThat(auditCount(org, "SCRIPT_CONFIG_CHANGED")).isEqualTo(1);
        JsonNode s = bundleScript();
        assertThat(s.get("config").get("tempOffset").asDouble()).isEqualTo(0.7);
        assertThat(s.get("configRevision").asInt()).isEqualTo(1);
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + scriptId + "/config"), "{\"config\":{\"apiToken\":\"x\"},\"baseVersion\":" + version() + "}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_CONFIG_SECRET_FORBIDDEN"));
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + scriptId + "/config"), "{\"config\":{\"n\":{\"a\":1}},\"baseVersion\":" + version() + "}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + scriptId + "/config"), "{\"config\":{\"n\":1},\"baseVersion\":99}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_VERSION_CONFLICT"));
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + scriptId + "/config"), "{\"config\":{\"n\":1}}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[SCR-05.02][AT-SCR-10.2][BR-SCR-17] 로그 수집 켜면 30분(실행 묶음 logCaptureUntil), 끄면 즉시 null, 운영 로그 목록 — TC-SCR-082(core)")
    void logCapture() throws Exception {
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/log-capture"), "{\"enabled\":true}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.until").value("2026-10-03T00:30:00Z"));
        assertThat(bundleScript().get("logCaptureUntil").asString()).isEqualTo("2026-10-03T00:30:00Z");
        clock.advance(Duration.ofMinutes(10));
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/log-capture"), "{\"enabled\":true}")))
                .andExpect(jsonPath("$.response.until").value("2026-10-03T00:40:00Z"));
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/log-capture"), "{\"enabled\":false}")))
                .andExpect(jsonPath("$.response.enabled").value(false)).andExpect(jsonPath("$.response.until").doesNotExist());
        assertThat(bundleScript().get("logCaptureUntil").isNull()).isTrue();
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/log-capture"), "{}"))).andExpect(status().isBadRequest());
        for (int i = 0; i < 3; i++) {
            jdbc.sql("""
                            INSERT INTO data2flow_pipeline.script_logs (organization_id, script_id, version_no, at, device_id, message)
                            VALUES (:o, :s, 1, :at, 7, :m)""")
                    .param("o", org).param("s", Long.parseLong(scriptId)).param("at", Pg.ts(Instant.parse("2026-10-03T00:0" + i + ":00Z")))
                    .param("m", "offset " + i).update();
        }
        mvc.perform(as(org, integrator, get("/core/scripts/" + scriptId + "/logs").param("from", "2026-10-03T00:01:00Z")))
                .andExpect(jsonPath("$.totalCount").value(2)).andExpect(jsonPath("$.responses[0].message").value("offset 2"))
                .andExpect(jsonPath("$.responses[0].deviceId").value("7"));
        assertThat(auditCount(org, "SCRIPT_LOG_CAPTURE_CHANGED")).isEqualTo(3);
    }

    @Test
    @DisplayName("[SCR-03.05][SCR-05.01][AT-SCR-10.1·10.3] 지표는 pipeline API-SCR-36 + 배포 표시, 기간·단위 검증, 오류 스냅샷 최근부터·입력 원문은 SCRIPT_WRITE만, 다른 조직 404 — TC-SCR-060·080(core)")
    void statsAndErrors() throws Exception {
        PIPELINE.on("GET", "/internal/pipeline/scripts/\\d+/stats", 200,
                "{\"points\":[{\"t\":\"2026-10-02T23:00:00Z\",\"versionNo\":1,\"processed\":120,\"errors\":2,\"timeouts\":0,\"avgMs\":1.2,\"p95Ms\":25.0}],"
                        + "\"warnings\":[{\"type\":\"SLOW\",\"value\":25.0,\"hints\":[\"LARGE_LOOP\"]}]}");
        mvc.perform(as(org, integrator, get("/core/scripts/" + scriptId + "/stats").param("from", "2026-10-02T00:00:00Z")
                        .param("to", "2026-10-03T01:00:00Z").param("step", "1h")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.points[0].p95Ms").value(25.0))
                .andExpect(jsonPath("$.response.warnings[0].type").value("SLOW"))
                .andExpect(jsonPath("$.response.deployMarks[0].versionNo").value(1))
                .andExpect(jsonPath("$.response.deployMarks[0].at").value("2026-10-03T00:00:00Z"));
        assertThat(PIPELINE.receivedContaining("/stats?").getFirst().path()).contains("organizationId=" + org).contains("step=1h");
        mvc.perform(as(org, integrator, get("/core/scripts/" + scriptId + "/stats").param("step", "5m"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, integrator, get("/core/scripts/" + scriptId + "/stats").param("from", "2026-01-01T00:00:00Z")))
                .andExpect(status().isBadRequest());
        for (int i = 0; i < 3; i++) {
            jdbc.sql("""
                            INSERT INTO data2flow_pipeline.script_errors (organization_id, script_id, version_id, version_no, occurred_at, error_code,
                                                                          message, line, col, input_snapshot, device_id)
                            VALUES (:o, :s, :v, 1, :at, 'SCRIPT_RUNTIME_ERROR', :m, 3, 7, '{"payload":"AQI="}'::jsonb, 11)""")
                    .param("o", org).param("s", Long.parseLong(scriptId)).param("v", Long.parseLong(draftId))
                    .param("at", Pg.ts(Instant.parse("2026-10-02T2" + i + ":00:00Z"))).param("m", "TypeError " + i).update();
        }
        mvc.perform(as(org, integrator, get("/core/scripts/" + scriptId + "/errors")))
                .andExpect(jsonPath("$.totalCount").value(3)).andExpect(jsonPath("$.responses[0].message").value("TypeError 2"))
                .andExpect(jsonPath("$.responses[0].line").value(3)).andExpect(jsonPath("$.responses[0].inputSnapshot.payload").value("AQI="));
        long operator = fx.user(org, "ops.operator", "OPERATOR");
        mvc.perform(as(org, operator, get("/core/scripts/" + scriptId + "/errors")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses[0].inputSnapshot").doesNotExist());
        long other = fx.organization("ops2");
        long otherUser = fx.user(other, "ops2.integrator", "INTEGRATOR");
        mvc.perform(as(other, otherUser, get("/core/scripts/" + scriptId + "/errors"))).andExpect(status().isNotFound());
        mvc.perform(as(other, otherUser, get("/core/scripts/" + scriptId + "/stats"))).andExpect(status().isNotFound());
    }
}
