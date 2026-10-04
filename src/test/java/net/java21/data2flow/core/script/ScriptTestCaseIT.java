package net.java21.data2flow.core.script;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 테스트 케이스와 배포 전 자동 확인(SCR-03.03, AT-SCR-07.1~3, API-SCR-10·11·05). 비교는 pipeline(API-SCR-35, 대역) */
class ScriptTestCaseIT extends ScriptItSupport {

    static final String CASE = """
            {"name":"%s","input":{"deviceId":"1","metrics":[{"key":"temperature","value":22.0}]},
             "expected":{"metrics":[{"key":"temperature","value":22.5}]},"compareMode":"TOLERANCE","tolerance":0.01}""";

    private long org;
    private long integrator;
    private long admin;
    private String scriptId;
    private String draftId;

    @BeforeEach
    void setUp() throws Exception {
        org = fx.organization("tc");
        integrator = fx.user(org, "tc.integrator", "INTEGRATOR");
        admin = fx.user(org, "tc.admin", "ADMIN");
        JsonNode created = create(org, integrator, "{\"name\":\"보정\",\"kind\":\"TRANSFORM\",\"templateKey\":\"calibration-offset\"}");
        scriptId = created.get("id").asString();
        draftId = created.get("draftVersionId").asString();
    }

    private String addCase(String name) throws Exception {
        return body(mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/test-cases"), CASE.formatted(name))))
                .andExpect(status().isCreated()).andReturn()).get("response").get("id").asString();
    }

    private void pipelineResult(int passed, int failed, String results) {
        PIPELINE.on("POST", "/internal/pipeline/scripts/test-cases/run", 200,
                "{\"passed\":" + passed + ",\"failed\":" + failed + ",\"results\":[" + results + "]}");
    }

    @Test
    @DisplayName("[SCR-03.03][API-SCR-10] 케이스 CRUD: 201+Location, 같은 이름 400 errors[name], FIELDS는 compareFields 필수, 수정하면 지난 결과 지움, 삭제 204, 상세 include=tests, 감사")
    void crud() throws Exception {
        String id = addCase("기본");
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/test-cases"), CASE.formatted("기본"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("name"));
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/test-cases"),
                        "{\"name\":\"x\",\"input\":{},\"expected\":null,\"compareMode\":\"FIELDS\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("compareFields"));
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/test-cases"), "{\"name\":\"y\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + scriptId + "/test-cases/" + id),
                        "{\"name\":\"이름 바꿈\",\"input\":{\"a\":1},\"expected\":null,\"compareMode\":\"FIELDS\",\"compareFields\":[\"$.metrics[0].value\"]}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.compareFields[0]").value("$.metrics[0].value"))
                .andExpect(jsonPath("$.response.expected").doesNotExist()).andExpect(jsonPath("$.response.tolerance").doesNotExist());
        mvc.perform(as(org, integrator, get("/core/scripts/" + scriptId + "/test-cases")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].name").value("이름 바꿈"));
        mvc.perform(as(org, integrator, get("/core/scripts/" + scriptId).param("include", "tests")))
                .andExpect(jsonPath("$.response.tests[0].id").value(id));
        mvc.perform(as(org, integrator, delete("/core/scripts/" + scriptId + "/test-cases/" + id))).andExpect(status().isNoContent());
        mvc.perform(as(org, integrator, delete("/core/scripts/" + scriptId + "/test-cases/" + id))).andExpect(status().isNotFound());
        assertThat(auditCount(org, "SCRIPT_TEST_CASE_CHANGED")).isEqualTo(3);
        long other = fx.organization("tc2");
        long otherUser = fx.user(other, "tc2.integrator", "INTEGRATOR");
        mvc.perform(as(other, otherUser, get("/core/scripts/" + scriptId + "/test-cases"))).andExpect(status().isNotFound());
        long viewer = fx.user(org, "tc.viewer", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/scripts/" + scriptId + "/test-cases"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[SCR-03.03][SCR-04.03][BR-SCR-14] 스크립트당 50개, 51번째는 409 SCRIPT_QUOTA_EXCEEDED")
    void quota() throws Exception {
        for (int i = 0; i < 50; i++) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.script_test_cases (organization_id, script_id, name, input, expected)
                            VALUES (:o, :s, :n, '{}'::jsonb, 'null'::jsonb)""")
                    .param("o", org).param("s", Long.parseLong(scriptId)).param("n", "c" + i).update();
        }
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/test-cases"), CASE.formatted("51번째"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_QUOTA_EXCEEDED"));
    }

    @Test
    @DisplayName("[SCR-03.03][AT-SCR-07.2][API-SCR-11] 일괄 실행은 DRAFT 코드·모든 케이스를 pipeline API-SCR-35로 보내고, 케이스마다 마지막 결과를 남긴다 — TC-SCR-047(pipeline 비교)")
    void runAll() throws Exception {
        String a = addCase("a");
        String b = addCase("b");
        pipelineResult(1, 1, "{\"caseId\":\"" + a + "\",\"name\":\"a\",\"passed\":true,\"diff\":[],\"durationMs\":0.3},"
                + "{\"caseId\":\"" + b + "\",\"name\":\"b\",\"passed\":false,\"diff\":[{\"path\":\"$.metrics[0].value\",\"expected\":22.5,\"actual\":22.4}],\"durationMs\":0.2}");
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/test-cases/run"), "{}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.passed").value(1))
                .andExpect(jsonPath("$.response.failed").value(1)).andExpect(jsonPath("$.response.results[1].diff[0].actual").value(22.4));
        PipelineStubServer.Received sent = PIPELINE.received("/test-cases/run").getFirst();
        JsonNode req = JSON.readTree(sent.body());
        assertThat(req.get("organizationId").asLong()).isEqualTo(org);
        assertThat(req.get("kind").asString()).isEqualTo("TRANSFORM");
        assertThat(req.get("code").asString()).contains("tempOffset");
        assertThat(req.get("cases")).hasSize(2);
        assertThat(req.get("cases").get(0).get("compareMode").asString()).isEqualTo("TOLERANCE");
        assertThat(req.get("cases").get(0).get("tolerance").asDouble()).isEqualTo(0.01);
        mvc.perform(as(org, integrator, get("/core/scripts/" + scriptId + "/test-cases")))
                .andExpect(jsonPath("$.responses[1].lastResult.passed").value(false))
                .andExpect(jsonPath("$.responses[1].lastResult.diff[0].path").value("$.metrics[0].value"))
                .andExpect(jsonPath("$.responses[0].lastResult.at").value("2026-10-03T00:00:00Z"));
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/test-cases/run"), "{\"code\":\"function transform(m){return m;}\"}")))
                .andExpect(status().isOk());
        assertThat(JSON.readTree(PIPELINE.received("/test-cases/run").getLast().body()).get("code").asString()).contains("return m;");
    }

    @Test
    @DisplayName("[SCR-03.03][AT-SCR-07.1·07.3] 배포 전 자동 확인: 실패 케이스가 있으면 400 SCRIPT_TEST_FAILED(실패 케이스·차이), INTEGRATOR force 403, ADMIN force+사유는 배포·감사 — TC-SCR-048")
    void deployGuard() throws Exception {
        String a = addCase("a");
        pipelineResult(0, 1, "{\"caseId\":\"" + a + "\",\"name\":\"a\",\"passed\":false,\"diff\":[{\"path\":\"$.metrics[0].value\"}],\"durationMs\":0.3}");
        String deploy = "{\"versionId\":\"" + draftId + "\",\"memo\":\"보정 배포\",\"baseActiveVersionId\":null%s}";
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/deploy"), deploy.formatted(""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_TEST_FAILED"))
                .andExpect(jsonPath("$.response.failed").value(1)).andExpect(jsonPath("$.response.results[0].caseId").value(a));
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/deploy"),
                        deploy.formatted(",\"force\":true,\"forceReason\":\"현장 긴급 보정 적용\""))))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, admin, json(post("/core/scripts/" + scriptId + "/deploy"),
                        deploy.formatted(",\"force\":true,\"forceReason\":\"현장 긴급 보정 적용\""))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.testResult.failed").value(1))
                .andExpect(jsonPath("$.response.activeVersionId").value(draftId));
        assertThat(auditCount(org, "SCRIPT_FORCE_DEPLOYED")).isEqualTo(1);
        // 통과하면 force 없이 배포된다
        String v2 = body(mvc.perform(as(org, integrator, json(put("/core/scripts/" + scriptId + "/draft"),
                "{\"code\":\"function transform(msg, ctx) {\\n  return msg;\\n}\\n\",\"baseVersionNo\":1}"))).andReturn())
                .get("response").get("versionId").asString();
        pipelineResult(1, 0, "{\"caseId\":\"" + a + "\",\"name\":\"a\",\"passed\":true,\"diff\":[],\"durationMs\":0.3}");
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/deploy"),
                        "{\"versionId\":\"" + v2 + "\",\"memo\":\"수정 배포\",\"baseActiveVersionId\":\"" + draftId + "\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.testResult.passed").value(1));
        // 롤백(ARCHIVED)은 테스트를 다시 돌리지 않는다
        int calls = PIPELINE.received("/test-cases/run").size();
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/deploy"),
                        "{\"versionId\":\"" + draftId + "\",\"memo\":\"롤백\",\"baseActiveVersionId\":\"" + v2 + "\"}")))
                .andExpect(status().isOk());
        assertThat(PIPELINE.received("/test-cases/run")).hasSize(calls);
    }

    @Test
    @DisplayName("[SCR-03.03] pipeline이 응답하지 않으면 배포 503, ADMIN force는 건너뛰고 배포(testResult.skipped)")
    void pipelineDown() throws Exception {
        addCase("a");
        PIPELINE.on("POST", "/internal/pipeline/scripts/test-cases/run", 503, "SERVICE_UNAVAILABLE");
        String deploy = "{\"versionId\":\"" + draftId + "\",\"memo\":\"보정 배포\",\"baseActiveVersionId\":null%s}";
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/deploy"), deploy.formatted(""))))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(as(org, admin, json(post("/core/scripts/" + scriptId + "/deploy"),
                        deploy.formatted(",\"force\":true,\"forceReason\":\"pipeline 점검 중 배포\""))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.testResult.skipped").value("PIPELINE_UNAVAILABLE"));
    }
}
