package net.java21.data2flow.core.retention;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.support.StubHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보관 정책(TSD-02.01·05.01·05.03, BR-TSD-02·03·17, NFR-04.03): API-TSD-40·41·42, 내부 API-TSD-60, pipeline API-TSD-62·53 중계.
 * pipeline은 대역 서버다.
 */
class RetentionPolicyIT extends IntegrationTestSupport {

    static final StubHttpServer PIPELINE = new StubHttpServer();

    @DynamicPropertySource
    static void pipeline(DynamicPropertyRegistry registry) {
        registry.add("data2flow.core.pipeline-base-url", PIPELINE::baseUrl);
    }

    private long org;
    private long admin;

    @BeforeEach
    void setUp() {
        PIPELINE.reset();
        PIPELINE.ok("POST", "/internal/pipeline/retention/preview", 200,
                "{\"affectedRows\":1200,\"affectedBytes\":96000,\"byMetric\":[{\"scope\":\"ORG\",\"scopeRef\":null,\"dataClass\":\"TELEMETRY\","
                        + "\"rows\":1200,\"bytes\":96000}]}");
        PIPELINE.ok("POST", "/internal/pipeline/retention/apply-policy", 200, "{\"appliesAt\":\"2026-10-04T02:00:00Z\"}");
        org = fx.organization("ret");
        admin = fx.user(org, "ret.admin", "ADMIN");
    }

    private String save(long user, String body) throws Exception {
        return mvc.perform(as(org, user, json(put("/core/retention-policies"), body))).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("[TSD-02.01][NFR-04.03][API-TSD-40] 저장 전에는 13종 시스템 기본값(inherited=true), minDays 표시 — TC-TSD-123")
    void defaults() throws Exception {
        mvc.perform(as(org, admin, get("/core/retention-policies")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.effective.length()").value(13))
                .andExpect(jsonPath("$.response.effective[?(@.dataClass=='TELEMETRY')].retainDays").value(365))
                .andExpect(jsonPath("$.response.effective[?(@.dataClass=='TELEMETRY')].minDays").value(30))
                .andExpect(jsonPath("$.response.effective[?(@.dataClass=='TELEMETRY')].compressAfterDays").value(7))
                .andExpect(jsonPath("$.response.effective[?(@.dataClass=='AGG_1D')].retainDays").value(0))
                .andExpect(jsonPath("$.response.effective[0].inherited").value(true))
                .andExpect(jsonPath("$.response.version").value(0));
    }

    @Test
    @DisplayName("[TSD-05.01][AT-TSD-06.1][BR-TSD-03] 365→180일을 미리 보기 없이 저장하면 409, 미리 보기(pipeline 추정+토큰) 뒤 같은 변경안 저장은 200 + pipeline 통지 — TC-TSD-122·126")
    void shortenNeedsConfirm() throws Exception {
        String body = "{\"items\":[{\"scope\":\"ORG\",\"dataClass\":\"TELEMETRY\",\"retainDays\":180}]}";
        mvc.perform(as(org, admin, json(put("/core/retention-policies"), body)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("RETENTION_CONFIRM_REQUIRED"));
        String preview = mvc.perform(as(org, admin, json(post("/core/retention-policies/preview"), body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.shortened").value(true))
                .andExpect(jsonPath("$.response.affectedRows").value(1200))
                .andExpect(jsonPath("$.response.byMetric[0].dataClass").value("TELEMETRY"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(preview, "$.response.confirmToken");
        StubHttpServer.Received sent = PIPELINE.received("POST", "/internal/pipeline/retention/preview").getFirst();
        assertThat(sent.body()).contains("\"organizationId\":" + org).contains("\"retainDays\":180").contains("\"dataClass\":\"TELEMETRY\"");
        assertThat(sent.header("X-CALLER-SERVICE")).isEqualTo("data2flow-core-api");
        // 다른 변경안에는 토큰이 맞지 않는다
        mvc.perform(as(org, admin, json(put("/core/retention-policies"),
                        "{\"items\":[{\"dataClass\":\"TELEMETRY\",\"retainDays\":90}],\"confirmToken\":\"" + token + "\"}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, admin, json(put("/core/retention-policies"), body.replace("]}", "],\"confirmToken\":\"" + token + "\"}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(1))
                .andExpect(jsonPath("$.response.appliesAt").value("2026-10-04T02:00:00Z"))
                .andExpect(jsonPath("$.response.effective[?(@.dataClass=='TELEMETRY' && @.scope=='ORG')].retainDays").value(180))
                .andExpect(jsonPath("$.response.effective[?(@.dataClass=='TELEMETRY' && @.scope=='ORG')].inherited").value(false));
        assertThat(PIPELINE.received("POST", "/internal/pipeline/retention/apply-policy").getFirst().body())
                .contains("\"policyVersion\":1");
        assertThat(auditCount(org, "RETENTION_POLICY_CHANGED")).isEqualTo(1);
        // 같은 값 다시 저장은 변경 없음(판 그대로, 감사 없음)
        mvc.perform(as(org, admin, json(put("/core/retention-policies"), body))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(1));
        assertThat(auditCount(org, "RETENTION_POLICY_CHANGED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[TSD-05.01][TSD-05.03][AT-TSD-06.3·06.4] 늘리기는 확인 없이 저장, LAeq 90일 재정의는 단축이라 확인 필요, door ON_CHANGE, 모델은 코드로 받아 ID로 저장, 내부 API-TSD-60 — TC-TSD-123·126·135")
    void overridesAndInternal() throws Exception {
        data.metric(org, "laeq", "dB");
        long door = data.metric(org, "door", null);
        jdbc.sql("UPDATE data2flow_core.metrics SET state_type = true WHERE id = :id").param("id", door).update();
        data.metric(org, "temperature", "℃");
        long model = data.model(org, "EM300-TH", List.of("temperature"));
        PIPELINE.reset();
        PIPELINE.fail("POST", "/internal/pipeline/retention/apply-policy", 503, "SERVICE_UNAVAILABLE", null);
        String body = """
                {"items":[{"dataClass":"RAW_MESSAGE","retainDays":60,"archiveBeforeDelete":true},
                          {"scope":"METRIC","scopeRef":"door","dataClass":"TELEMETRY","retainDays":730,"storeMode":"ON_CHANGE"},
                          {"scope":"MODEL","scopeRef":"EM300-TH","dataClass":"AGG_1H","retainDays":1825}]}""";
        mvc.perform(as(org, admin, json(put("/core/retention-policies"), body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.appliesAt").value("2026-10-03T02:00:00Z"))
                .andExpect(jsonPath("$.response.effective.length()").value(15))
                .andExpect(jsonPath("$.response.effective[?(@.scope=='MODEL')].scopeRef").value(Long.toString(model)))
                .andExpect(jsonPath("$.response.effective[?(@.scope=='METRIC')].storeMode").value("ON_CHANGE"))
                .andExpect(jsonPath("$.response.effective[?(@.dataClass=='RAW_MESSAGE')].archiveBeforeDelete").value(true));
        String laeq = """
                {"items":[{"scope":"METRIC","scopeRef":"door","dataClass":"TELEMETRY","retainDays":730,"storeMode":"ON_CHANGE"},
                          {"scope":"MODEL","scopeRef":"%d","dataClass":"AGG_1H","retainDays":1825},
                          {"scope":"METRIC","scopeRef":"laeq","dataClass":"TELEMETRY","retainDays":90}]}""".formatted(model);
        mvc.perform(as(org, admin, json(put("/core/retention-policies"), laeq)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("RETENTION_CONFIRM_REQUIRED"));
        // 내부 API-TSD-60: 배포 조직 전체(테스트는 조직 코드 제한 없음 → ACTIVE 조직 모두)
        String internal = mvc.perform(get("/internal/core/retention-policies")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Object> mine = JsonPath.read(internal, "$.response.organizations[?(@.organizationId=='" + org + "')]");
        assertThat(mine).hasSize(1);
        assertThat((List<Object>) JsonPath.read(internal, "$.response.organizations[?(@.organizationId=='" + org
                + "')].effective[?(@.scope=='METRIC' && @.scopeRef=='door')].storeMode")).containsExactly("ON_CHANGE");
        assertThat((List<Object>) JsonPath.read(internal, "$.response.organizations[?(@.organizationId=='" + org + "')].version"))
                .containsExactly(1);
    }

    @Test
    @DisplayName("[TSD-05.01][NFR-04.03] 검증 400 RETENTION_INVALID: 최소값 미만, AGG_1D 단축, 원본 메시지 재정의, 없는 측정 항목·모델, 상태형 아닌 ON_CHANGE, 중복 — TC-TSD-122")
    void validation() throws Exception {
        data.metric(org, "temperature", "℃");
        for (String item : List.of(
                "{\"dataClass\":\"TELEMETRY\",\"retainDays\":7}",
                "{\"dataClass\":\"AGG_1D\",\"retainDays\":3650}",
                "{\"scope\":\"METRIC\",\"scopeRef\":\"temperature\",\"dataClass\":\"RAW_MESSAGE\",\"retainDays\":30}",
                "{\"scope\":\"METRIC\",\"scopeRef\":\"nope\",\"dataClass\":\"TELEMETRY\",\"retainDays\":90}",
                "{\"scope\":\"MODEL\",\"scopeRef\":\"NOPE\",\"dataClass\":\"TELEMETRY\",\"retainDays\":90}",
                "{\"scope\":\"METRIC\",\"scopeRef\":\"temperature\",\"dataClass\":\"TELEMETRY\",\"retainDays\":90,\"storeMode\":\"ON_CHANGE\"}",
                "{\"dataClass\":\"TELEMETRY\",\"retainDays\":365,\"storeMode\":\"ALL\"}",
                "{\"dataClass\":\"LINK\",\"retainDays\":30,\"compressAfterDays\":3}",
                "{\"scope\":\"X\",\"dataClass\":\"TELEMETRY\",\"retainDays\":365}",
                "{\"dataClass\":\"NOPE\",\"retainDays\":365}",
                "{\"scope\":\"ORG\",\"scopeRef\":\"x\",\"dataClass\":\"TELEMETRY\",\"retainDays\":365}",
                "{\"dataClass\":\"TELEMETRY\",\"retainDays\":400},{\"dataClass\":\"TELEMETRY\",\"retainDays\":500}")) {
            mvc.perform(as(org, admin, json(put("/core/retention-policies"), "{\"items\":[" + item + "]}")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("RETENTION_INVALID"));
        }
        mvc.perform(as(org, admin, json(put("/core/retention-policies"), "{}"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
        mvc.perform(as(org, admin, json(post("/core/retention-policies/preview"), "{\"items\":[{\"dataClass\":\"TELEMETRY\",\"retainDays\":400}]}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.shortened").value(false))
                .andExpect(jsonPath("$.response.affectedRows").value(0));
        assertThat(PIPELINE.received("POST", "/internal/pipeline/retention/preview")).isEmpty();
    }

    @Test
    @DisplayName("[TSD-05.01] 권한 TS_POLICY: ADMIN·INTEGRATOR 허용, OPERATOR·VIEWER 403, 조직마다 따로 — TC-TSD-124·136")
    void permissions() throws Exception {
        long integrator = fx.user(org, "ret.int", "INTEGRATOR");
        long operator = fx.user(org, "ret.op", "OPERATOR");
        long viewer = fx.user(org, "ret.viewer", "VIEWER");
        mvc.perform(as(org, integrator, get("/core/retention-policies"))).andExpect(status().isOk());
        mvc.perform(as(org, operator, get("/core/retention-policies"))).andExpect(status().isForbidden());
        mvc.perform(as(org, viewer, json(put("/core/retention-policies"), "{\"items\":[]}"))).andExpect(status().isForbidden());
        mvc.perform(as(org, viewer, get("/core/storage-stats"))).andExpect(status().isForbidden());
        assertThat(save(integrator, "{\"items\":[{\"dataClass\":\"COMMAND\",\"retainDays\":400}]}")).contains("\"version\":1");
        long other = fx.organization("ret2");
        long otherAdmin = fx.user(other, "ret2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/retention-policies")))
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.effective[?(@.dataClass=='COMMAND')].retainDays").value(365));
        PIPELINE.reset();
        PIPELINE.fail("POST", "/internal/pipeline/retention/preview", 503, "SERVICE_UNAVAILABLE", null);
        mvc.perform(as(org, admin, json(post("/core/retention-policies/preview"), "{\"items\":[{\"dataClass\":\"COMMAND\",\"retainDays\":90}]}")))
                .andExpect(status().isServiceUnavailable());
    }
}
