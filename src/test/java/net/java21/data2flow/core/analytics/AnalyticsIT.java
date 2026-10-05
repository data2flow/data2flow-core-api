package net.java21.data2flow.core.analytics;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.analytics.service.AnalysisScheduler;
import net.java21.data2flow.core.live.service.AnalysisRunStreams;
import net.java21.data2flow.core.support.AnalyticsItSupport;
import net.java21.data2flow.core.support.StubHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분석 정의·실행 중계(ANA-api §1 → analytics 내부 API 대역), 권한 매트릭스(AnalyticsPermissionMatrixIT 범위), 일정 실행기, 고정 위젯·사이트 쾌적도,
 * 스크립트 AI 초안 중계. analytics·ai는 대역 HTTP 서버가 문서(ANA-api §2·부록 A)의 모양으로 답한다.
 */
class AnalyticsIT extends AnalyticsItSupport {

    @Autowired
    AnalysisScheduler scheduler;
    @Autowired
    AnalysisRunStreams streams;

    long siteA;
    long roomA;
    long siteB;
    long deviceA;
    long deviceB;
    final AtomicLong nextAnalysis = new AtomicLong(100);

    @BeforeEach
    void data() {
        siteA = data.site(org, "본관");
        roomA = data.space(org, siteA, "ROOM", "실습실");
        siteB = data.site(org, "별관");
        data.metric(org, "temperature", "°C");
        data.metric(org, "co2", "ppm");
        jdbc.sql("UPDATE data2flow_core.metrics SET semantic = key WHERE organization_id = :org").param("org", org).update();
        long model = data.model(org, "AM319", List.of("temperature", "co2"));
        long source = data.source(org, "lns");
        deviceA = data.device(org, source, "a-1", "ACTIVE", roomA, model);
        deviceB = data.device(org, source, "b-1", "ACTIVE", siteB, model);
        STUB.on("POST", "/internal/analytics/analyses", r -> new StubHttpServer.Reply(200, StubHttpServer.success(
                "{\"analysisId\":\"" + nextAnalysis.incrementAndGet() + "\",\"templateVersion\":\"1.2.0\",\"version\":0,\"realtime\":false}"),
                Map.of()));
    }

    private String bindings(String role, long deviceId, String metric) {
        return "[{\"role\":\"" + role + "\",\"sources\":[{\"kind\":\"DEVICE_METRIC\",\"deviceId\":\"" + deviceId + "\",\"metricKey\":\"" + metric + "\"}]}]";
    }

    private String analysisBody(String template, String bindings, String schedule) {
        return "{\"name\":\"CO2 이상\",\"templateKey\":\"" + template + "\",\"bindings\":" + bindings
                + ",\"period\":{\"type\":\"RELATIVE\",\"days\":14},\"resolution\":\"1h\",\"params\":{}"
                + (schedule == null ? "" : ",\"schedule\":" + schedule) + "}";
    }

    private String create(long user, String template, String bindings, String schedule) throws Exception {
        MvcResult r = mvc.perform(json(as(org, user, post("/core/analytics/analyses")), analysisBody(template, bindings, schedule)))
                .andExpect(status().isCreated()).andReturn();
        return read(r, "$.response.analysisId");
    }

    private void stubAnalysis(String id, String template) {
        STUB.ok("GET", "/internal/analytics/analyses/" + id, 200, "{\"analysisId\":\"" + id + "\",\"templateKey\":\"" + template
                + "\",\"templateVersion\":\"1.2.0\",\"bindings\":" + bindings("target", deviceA, "co2")
                + ",\"period\":{\"type\":\"RELATIVE\",\"days\":14},\"resolution\":\"1h\",\"qualityFilter\":\"NORMAL_ONLY\",\"includeVirtual\":false}");
    }

    private void stubCheck(String template, String level, String issues, long points) {
        STUB.ok("POST", "/internal/analytics/templates/" + template + "/check", 200, "{\"level\":\"" + level + "\",\"issues\":" + issues
                + ",\"stats\":{\"points\":" + points + ",\"estimatedRawPoints\":" + points + ",\"seriesCount\":1},\"limits\":{\"maxPoints\":5000000}}");
    }

    private void stubRun(String runId, String analysisId, String status) {
        STUB.ok("GET", "/internal/analytics/runs/" + runId, 200, "{\"run\":{\"runId\":\"" + runId + "\",\"analysisId\":\"" + analysisId
                + "\",\"status\":\"" + status + "\",\"progress\":100,\"stage\":\"SAVE\",\"finishedAt\":\"2026-10-03T00:05:00Z\","
                + "\"errorDetail\":\"stack\"},\"result\":{\"summary\":{\"headline\":\"쾌적\",\"metrics\":[{\"key\":\"score\",\"value\":83.6},"
                + "{\"key\":\"anomalies\",\"value\":2}]},\"charts\":[{\"id\":\"c1\",\"type\":\"line\"},{\"id\":\"c2\",\"type\":\"bar\"}],"
                + "\"caveats\":[\"참고\"]}}");
    }

    private boolean event(String type, String payload) {
        String body = "{\"v\":1,\"messageId\":\"" + UUID.randomUUID() + "\",\"type\":\"" + type + "\",\"organizationId\":" + org
                + ",\"occurredAt\":\"2026-10-03T00:00:00Z\",\"payload\":" + payload + "}";
        return consumer.onMessage(body.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 템플릿

    @Test
    @DisplayName("[ANA-01.01][ANA-01.03][ANA-01.07][ANA-01.04] 템플릿 목록(역할 5종 200·인증 없음 401), 조직 설정의 비활성은 숨김, 질문으로 찾기 KEYWORD, 실행 가능성 — TC-ANA-007·018")
    void templates() throws Exception {
        for (long u : List.of(admin, integrator, operator, analyst, viewer)) {
            mvc.perform(as(org, u, get("/core/analytics/templates"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(2))
                    .andExpect(jsonPath("$.responses[0].enabled").value(true));
        }
        mvc.perform(get("/core/analytics/templates")).andExpect(status().isUnauthorized());
        STUB.on("GET", "/internal/analytics/template-settings", r -> list("[{\"key\":\"comfort-index\",\"enabled\":false}]"));
        mvc.perform(as(org, viewer, get("/core/analytics/templates"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, viewer, get("/core/analytics/templates?includeDisabled=true&category=ENV_QUALITY")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].enabled").value(false));
        mvc.perform(as(org, viewer, get("/core/analytics/templates").param("keyword", "CO2 이상하게")))
                .andExpect(jsonPath("$.responses[0].key").value("anomaly-detect")).andExpect(jsonPath("$.responses[0].mode").value("KEYWORD"))
                .andExpect(jsonPath("$.responses[0].matchedQuestion").value("어젯밤 CO2가 이상하게 높았던 시간이 있나?"));
        mvc.perform(as(org, viewer, get("/core/analytics/templates?includeDisabled=true&view=runnable&spaceId=" + siteB)))
                .andExpect(jsonPath("$.responses[0].runnable").value(true)).andExpect(jsonPath("$.responses[1].runnable").value(true));
        long empty = data.site(org, "빈 건물");
        mvc.perform(as(org, viewer, get("/core/analytics/templates?includeDisabled=true&view=runnable&spaceId=" + empty)))
                .andExpect(jsonPath("$.responses[1].runnable").value(false)).andExpect(jsonPath("$.responses[1].missingRoles[0].role").value("temp"));
        mvc.perform(as(org, viewer, get("/core/analytics/templates/comfort-index"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.enabled").value(false));
        mvc.perform(as(org, viewer, get("/core/analytics/templates/no-such"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("TEMPLATE_NOT_FOUND"))
                .andExpect(jsonPath("$.header.resultMessage").value("템플릿을 찾을 수 없습니다"));
        // 역할 후보(API-ANA-19): 의미 조건 temperature, 공간 범위로 거름
        data.spaceScope(org, analyst, List.of(siteA));
        mvc.perform(as(org, analyst, get("/core/analytics/templates/comfort-index/roles/temp/candidates")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].deviceId").value(Long.toString(deviceA)))
                .andExpect(jsonPath("$.responses[0].semantic").value("temperature"));
        mvc.perform(as(org, analyst, get("/core/analytics/templates/comfort-index/roles/temp/candidates?kind=SPACE_AGGREGATE")))
                .andExpect(jsonPath("$.responses[0].spaceId").value(Long.toString(roomA)));
        mvc.perform(as(org, viewer, get("/core/analytics/templates/comfort-index/roles/temp/candidates"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[ANA-01.03][BR-ANA-17] 템플릿 끄기는 ADMIN만(나머지 403), 끄면 그 템플릿 일정 PAUSED·실시간 중지, 감사 TEMPLATE_DISABLED — TC-ANA-012·015")
    void templateSettings() throws Exception {
        String id = create(analyst, "anomaly-detect", bindings("target", deviceA, "co2"), "{\"preset\":\"DAILY\",\"at\":\"06:00\"}");
        STUB.ok("PUT", "/internal/analytics/template-settings/anomaly-detect", 200, "{\"key\":\"anomaly-detect\",\"enabled\":false}");
        for (long u : List.of(integrator, operator, analyst, viewer)) {
            mvc.perform(json(as(org, u, put("/core/analytics/template-settings/anomaly-detect")), "{\"enabled\":false}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(json(as(org, admin, put("/core/analytics/template-settings/anomaly-detect")), "{\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(as(org, admin, get("/core/analytics/template-settings"))).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT schedule_state FROM data2flow_core.analysis_refs WHERE analysis_id = :id")
                .param("id", Long.parseLong(id)).query(String.class).single()).isEqualTo("PAUSED");
        assertThat(auditCount(org, "TEMPLATE_DISABLED")).isEqualTo(1);
        // 비활성 템플릿: 실행·저장 409 TEMPLATE_DISABLED(TC-ANA-013)
        STUB.on("GET", "/internal/analytics/template-settings", r -> list("[{\"key\":\"anomaly-detect\",\"enabled\":false}]"));
        mvc.perform(json(as(org, analyst, post("/core/analytics/analyses/" + id + "/runs")), "{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("TEMPLATE_DISABLED"));
        mvc.perform(json(as(org, analyst, put("/core/analytics/analyses/" + id)), analysisBody("anomaly-detect",
                        bindings("target", deviceA, "co2"), null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("TEMPLATE_DISABLED"));
    }

    // ------------------------------------------------------------------ 분석 정의

    @Test
    @DisplayName("[ANA-01.05][BR-ANA-02][BR-ANA-03] 저장: 역할 누락 400(문구 치환)·의미 조건 위반 400·권한 밖 공간 403·VIEWER 403, 성공 201·멱등 재전송 같은 id, analytics에 ownerUserId·spaceScopeIds — TC-ANA-024·082")
    void createAnalysis() throws Exception {
        mvc.perform(json(as(org, analyst, post("/core/analytics/analyses")), analysisBody("anomaly-detect", "[]", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ANALYSIS_BINDING_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value("target 역할에 1~50개의 series 데이터를 연결하세요"));
        mvc.perform(json(as(org, analyst, post("/core/analytics/analyses")), analysisBody("comfort-index", bindings("temp", deviceA, "co2"), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultMessage").value("temp 역할에 1~1개의 temperature 데이터를 연결하세요"));
        mvc.perform(json(as(org, viewer, post("/core/analytics/analyses")), analysisBody("anomaly-detect", bindings("target", deviceA, "co2"), null)))
                .andExpect(status().isForbidden());
        data.spaceScope(org, analyst, List.of(siteA));
        mvc.perform(json(as(org, analyst, post("/core/analytics/analyses")), analysisBody("anomaly-detect", bindings("target", deviceB, "co2"), null)))
                .andExpect(status().isForbidden());

        MvcResult first = mvc.perform(json(as(org, analyst, post("/core/analytics/analyses")).header("Idempotency-Key", "a-1"),
                analysisBody("anomaly-detect", bindings("target", deviceA, "co2"), null))).andExpect(status().isCreated()).andReturn();
        MvcResult again = mvc.perform(json(as(org, analyst, post("/core/analytics/analyses")).header("Idempotency-Key", "a-1"),
                analysisBody("anomaly-detect", bindings("target", deviceA, "co2"), null))).andExpect(status().isCreated()).andReturn();
        String id = read(first, "$.response.analysisId");
        assertThat((String) read(again, "$.response.analysisId")).isEqualTo(id);
        assertThat(STUB.received("POST", "/internal/analytics/analyses")).hasSize(1);
        StubHttpServer.Received sent = STUB.received("POST", "/internal/analytics/analyses").getFirst();
        assertThat(sent.body()).contains("\"ownerUserId\":\"" + analyst + "\"").contains("\"spaceScopeIds\":[\"" + roomA + "\"]");
        assertThat(sent.header("X-CALLER-SERVICE")).isEqualTo("data2flow-core-api");
        assertThat(sent.header("X-ORG-ID")).isEqualTo(Long.toString(org));
        assertThat(auditCount(org, "ANALYSIS_CREATED")).isEqualTo(1);

        // 목록(API-ANA-07): 범위 안 분석만, 소유자 이름
        String other = create(admin, "anomaly-detect", bindings("target", deviceB, "co2"), null);
        mvc.perform(as(org, analyst, get("/core/analytics/analyses"))).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].id").value(id)).andExpect(jsonPath("$.responses[0].owner.name").isNotEmpty())
                .andExpect(jsonPath("$.responses[0].targetSummary").value("기기 a-1 · co2"));
        mvc.perform(as(org, admin, get("/core/analytics/analyses?owner=me"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, viewer, get("/core/analytics/analyses?keyword=CO2"))).andExpect(jsonPath("$.totalCount").value(2));
        // 범위 밖 분석 GET 404 ANALYSIS_NOT_FOUND
        mvc.perform(as(org, analyst, get("/core/analytics/analyses/" + other)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("ANALYSIS_NOT_FOUND"));
        stubAnalysis(id, "anomaly-detect");
        mvc.perform(as(org, analyst, get("/core/analytics/analyses/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.owner.userId").value(Long.toString(analyst)));
    }

    @Test
    @DisplayName("[ANA-03.02][ANA-04.01] analytics의 파라미터 오류는 그대로(400 ANALYSIS_PARAMS_INVALID), 잘못된 cron 400 ANALYSIS_SCHEDULE_INVALID, 수정은 소유자·ADMIN만, 삭제 204 — TC-ANA-085·097·099")
    void updateAndDelete() throws Exception {
        STUB.fail("POST", "/internal/analytics/analyses", 400, "ANALYSIS_PARAMS_INVALID", "\"errors\":[{\"field\":\"params.sensitivity\",\"code\":\"Range\"}]");
        mvc.perform(json(as(org, analyst, post("/core/analytics/analyses")), analysisBody("anomaly-detect", bindings("target", deviceA, "co2"), null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ANALYSIS_PARAMS_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("params.sensitivity"));
        mvc.perform(json(as(org, analyst, post("/core/analytics/analyses")), analysisBody("anomaly-detect", bindings("target", deviceA, "co2"),
                        "{\"cron\":\"0 61 * * *\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ANALYSIS_SCHEDULE_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value("일정 형식이 올바르지 않습니다"));
        STUB.on("POST", "/internal/analytics/analyses", r -> new StubHttpServer.Reply(200, StubHttpServer.success("{\"analysisId\":\"77\"}"), Map.of()));
        String id = create(analyst, "anomaly-detect", bindings("target", deviceA, "co2"), "{\"cron\":\"0 6 * * 1\"}");
        long other = fx.user(org, "loop.analyst2", "ANALYST");
        STUB.ok("PUT", "/internal/analytics/analyses/77", 200, "{\"analysisId\":\"77\",\"version\":1}");
        String updated = analysisBody("anomaly-detect", bindings("target", deviceA, "co2"), "{\"preset\":\"WEEKLY\",\"at\":\"07:30\",\"weekday\":\"TUE\"}")
                .replace("CO2 이상", "CO2 이상(수정)");
        mvc.perform(json(as(org, other, put("/core/analytics/analyses/" + id)), updated)).andExpect(status().isForbidden());
        mvc.perform(json(as(org, admin, put("/core/analytics/analyses/" + id)), updated)).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.scheduleState").value("ACTIVE"))
                .andExpect(jsonPath("$.response.nextScheduledAt").value("2026-10-05T22:30:00Z"));
        assertThat(STUB.received("PUT", "/internal/analytics/analyses/77").getFirst().body()).contains("\"ownerUserId\":\"" + analyst + "\"");
        STUB.on("DELETE", "/internal/analytics/analyses/77", r -> new StubHttpServer.Reply(204, null, Map.of()));
        mvc.perform(as(org, other, delete("/core/analytics/analyses/" + id))).andExpect(status().isForbidden());
        mvc.perform(as(org, analyst, delete("/core/analytics/analyses/" + id))).andExpect(status().isNoContent());
        mvc.perform(as(org, analyst, get("/core/analytics/analyses/" + id))).andExpect(status().isNotFound());
        assertThat(auditCount(org, "ANALYSIS_DELETED")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 실행

    @Test
    @DisplayName("[ANA-03.03][ANA-11.01][BR-ANA-05·07] 실행 요청: FAIL 409 사유, WARN 미확인 400·확인 202, 한도 초과 409, VIEWER 403, fast·5만 이하면 SYNC — TC-ANA-088·099·109·187")
    void runRequest() throws Exception {
        String id = create(analyst, "anomaly-detect", bindings("target", deviceA, "co2"), null);
        stubAnalysis(id, "anomaly-detect");
        STUB.ok("POST", "/internal/analytics/runs", 202, "{\"runId\":\"501\",\"status\":\"QUEUED\",\"queuePosition\":2}");

        stubCheck("anomaly-detect", "FAIL", "[{\"code\":\"MIN_PERIOD\",\"severity\":\"FAIL\",\"message\":\"7일 이상 필요\"}]", 100);
        mvc.perform(json(as(org, analyst, post("/core/analytics/analyses/" + id + "/runs")), "{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("ANALYSIS_INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.header.resultMessage").value("데이터가 부족합니다: 7일 이상 필요"));
        stubCheck("anomaly-detect", "FAIL", "[{\"code\":\"TOO_MANY_POINTS\",\"severity\":\"FAIL\",\"message\":\"많음\",\"fix\":{\"type\":\"SET_RESOLUTION\",\"value\":\"1d\"}}]", 9_000_000);
        mvc.perform(json(as(org, analyst, post("/core/analytics/analyses/" + id + "/runs")), "{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("ANALYSIS_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.header.resultMessage").value("데이터가 너무 많습니다. 집계 단위를 1d 이상으로 올리세요"));
        stubCheck("anomaly-detect", "WARN", "[{\"code\":\"MISSING\",\"severity\":\"WARN\",\"message\":\"누락 25%\"}]", 1000);
        mvc.perform(json(as(org, analyst, post("/core/analytics/analyses/" + id + "/runs")), "{\"acknowledgeWarnings\":false}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ANALYSIS_WARNING_NOT_ACKNOWLEDGED"));
        mvc.perform(json(as(org, viewer, post("/core/analytics/analyses/" + id + "/runs")), "{\"acknowledgeWarnings\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(json(as(org, analyst, post("/core/analytics/analyses/" + id + "/runs")), "{\"acknowledgeWarnings\":true}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.runId").value("501"))
                .andExpect(jsonPath("$.response.status").value("QUEUED")).andExpect(jsonPath("$.response.queuePosition").value(2));
        assertThat(STUB.received("POST", "/internal/analytics/runs").getLast().body()).contains("\"trigger\":\"MANUAL\"").contains("\"mode\":\"SYNC\"");
        assertThat(auditCount(org, "ANALYSIS_RUN_REQUESTED")).isEqualTo(1);
        mvc.perform(as(org, viewer, get("/core/analytics/analyses"))).andExpect(jsonPath("$.responses[0].lastRun.runId").value("501"))
                .andExpect(jsonPath("$.responses[0].lastRun.status").value("QUEUED"));
    }

    @Test
    @DisplayName("[ANA-04.02][ANA-04.04][BR-ANA-16] 결과 조회(V 200, errorDetail은 I 이상), 다른 분석의 실행 404 ANALYSIS_RUN_NOT_FOUND, 취소 409 전달·VIEWER 403, 비교·이력·내보내기 중계 — TC-ANA-111·116·133")
    void runResults() throws Exception {
        String id = create(analyst, "anomaly-detect", bindings("target", deviceA, "co2"), null);
        stubRun("501", id, "SUCCEEDED");
        stubRun("502", "9999", "SUCCEEDED");
        mvc.perform(as(org, viewer, get("/core/analytics/analyses/" + id + "/runs/501"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.run.status").value("SUCCEEDED")).andExpect(jsonPath("$.response.run.errorDetail").doesNotExist())
                .andExpect(jsonPath("$.response.result.charts[0].id").value("c1"));
        mvc.perform(as(org, integrator, get("/core/analytics/analyses/" + id + "/runs/501")))
                .andExpect(jsonPath("$.response.run.errorDetail").value("stack"));
        mvc.perform(as(org, viewer, get("/core/analytics/analyses/" + id + "/runs/502")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("ANALYSIS_RUN_NOT_FOUND"));
        mvc.perform(as(org, viewer, get("/core/analytics/analyses/" + id + "/runs/503")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("ANALYSIS_RUN_NOT_FOUND"));
        STUB.fail("POST", "/internal/analytics/runs/501/cancel", 409, "ANALYSIS_RUN_STATE_CONFLICT", null);
        mvc.perform(as(org, viewer, post("/core/analytics/analyses/" + id + "/runs/501/cancel"))).andExpect(status().isForbidden());
        mvc.perform(as(org, analyst, post("/core/analytics/analyses/" + id + "/runs/501/cancel")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("ANALYSIS_RUN_STATE_CONFLICT"));
        STUB.on("GET", "/internal/analytics/analyses/" + id + "/runs", r -> list("[{\"runId\":\"501\",\"status\":\"FAILED\",\"errorDetail\":\"x\"}]"));
        mvc.perform(as(org, viewer, get("/core/analytics/analyses/" + id + "/runs"))).andExpect(jsonPath("$.responses[0].runId").value("501"))
                .andExpect(jsonPath("$.responses[0].errorDetail").doesNotExist());
        STUB.ok("GET", "/internal/analytics/analyses/" + id + "/runs/compare", 200, "{\"metrics\":[],\"versionMismatch\":false}");
        mvc.perform(as(org, viewer, get("/core/analytics/analyses/" + id + "/runs/compare?base=500&target=501")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.versionMismatch").value(false));
        STUB.ok("POST", "/internal/analytics/analyses/" + id + "/runs/501/export", 202, "{\"exportJobId\":\"9\"}");
        mvc.perform(json(as(org, viewer, post("/core/analytics/analyses/" + id + "/runs/501/export")), "{\"format\":\"PDF\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(json(as(org, analyst, post("/core/analytics/analyses/" + id + "/runs/501/export")), "{\"format\":\"PDF\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.exportJobId").value("9"));
    }

    private String token(long user, String scopes) throws Exception {
        MvcResult r = mvc.perform(json(as(org, user, post("/core/api-tokens")), "{\"kind\":\"MCP\",\"name\":\"t" + UUID.randomUUID()
                .toString().substring(0, 8) + "\",\"scopes\":[" + scopes + "],\"expiresAt\":\"" + clock.instant().plus(Duration.ofDays(5)) + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return read(r, "$.response.id");
    }

    @Test
    @DisplayName("[IAM-04.07][AIA-08.01][AIA-01.01] MCP 토큰 주체로 결과 읽기: read:analytics 200·read:devices 403, 내부 API-ANA-40(실행 ID만) 같은 판정, access-grant는 범위 교집합")
    void tokenPrincipals() throws Exception {
        String id = create(analyst, "anomaly-detect", bindings("target", deviceA, "co2"), null);
        stubRun("501", id, "SUCCEEDED");
        String analytics = token(analyst, "\"read:analytics\"");
        String devices = token(analyst, "\"read:devices\"");
        mvc.perform(as(org, analyst, get("/core/analytics/analyses/" + id + "/runs/501")).header("X-ACCESS-TOKEN-ID", analytics)
                .header("X-TOKEN-SCOPE", "read:analytics")).andExpect(status().isOk());
        mvc.perform(as(org, analyst, get("/core/analytics/analyses/" + id + "/runs/501")).header("X-ACCESS-TOKEN-ID", devices)
                .header("X-TOKEN-SCOPE", "read:devices")).andExpect(status().isForbidden());
        mvc.perform(as(org, analyst, post("/core/analytics/analyses/" + id + "/runs")).header("X-ACCESS-TOKEN-ID", analytics)
                .header("X-TOKEN-SCOPE", "read:analytics")).andExpect(status().isForbidden());

        mvc.perform(as(org, viewer, get("/internal/core/analysis-runs/501"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.run.analysisId").value(id)).andExpect(jsonPath("$.response.run.errorDetail").doesNotExist());
        STUB.on("PUT", "/internal/analytics/runs/501/ai-commentary", r -> new StubHttpServer.Reply(204, null, Map.of()));
        mvc.perform(json(as(org, viewer, put("/internal/core/analysis-runs/501/ai-commentary")), "{\"commentaryId\":\"c-9\"}"))
                .andExpect(status().isNoContent());
        assertThat(STUB.received("PUT", "/internal/analytics/runs/501/ai-commentary").getFirst().body()).contains("c-9");
        mvc.perform(json(as(org, viewer, put("/internal/core/analysis-runs/501/ai-commentary")), "{}")).andExpect(status().isBadRequest());
        stubRun("502", "9999", "SUCCEEDED");
        mvc.perform(as(org, viewer, get("/internal/core/analysis-runs/502"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("ANALYSIS_RUN_NOT_FOUND"));

        // access-grant: 토큰 주체는 소유자 권한 ∩ 범위 권한
        mvc.perform(get("/internal/core/organizations/" + org + "/users/" + analyst + "/access-grant?accessTokenId=" + analytics))
                .andExpect(jsonPath("$.response.active").value(true))
                .andExpect(jsonPath("$.response.permissions").value(containsInAnyOrder("ANALYTICS_READ", "ENE_READ")));
        mvc.perform(as(org, analyst, get("/internal/core/organizations/" + org + "/users/" + analyst + "/access-grant"))
                        .header("X-ACCESS-TOKEN-ID", devices).header("X-TOKEN-SCOPE", "read:devices"))
                .andExpect(jsonPath("$.response.permissions").value(containsInAnyOrder("DEV_READ", "FLOW_READ")));
        mvc.perform(get("/internal/core/organizations/" + org + "/users/" + analyst + "/access-grant"))
                .andExpect(jsonPath("$.response.permissions.length()").value(greaterThan(5)));
        mvc.perform(get("/internal/core/organizations/" + org + "/users/" + viewer + "/access-grant?accessTokenId=" + analytics))
                .andExpect(jsonPath("$.response.active").value(false));
    }

    @Test
    @DisplayName("[ANA-06.01][ANA-07.01][ANA-07.05] 실시간 켜기 O 이상(ANALYST 403)·권한 밖 기기 이벤트 제외, 재학습·모델 적용 I 이상, 피드백 O 이상 201·범위 밖 실행 404 — TC-ANA-139·146·157")
    void realtimeModelsFeedback() throws Exception {
        String id = create(admin, "anomaly-detect", bindings("target", deviceA, "co2"), null);
        STUB.ok("POST", "/internal/analytics/realtime/" + id, 200, "{\"analysisId\":\"" + id + "\",\"realtime\":true}");
        mvc.perform(json(as(org, analyst, put("/core/analytics/analyses/" + id + "/realtime")), "{\"enabled\":true}")).andExpect(status().isForbidden());
        mvc.perform(json(as(org, operator, put("/core/analytics/analyses/" + id + "/realtime")), "{\"enabled\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.realtime").value(true)).andExpect(jsonPath("$.response.activeModelId").isEmpty());
        mvc.perform(as(org, viewer, get("/core/analytics/analyses?realtime=true"))).andExpect(jsonPath("$.totalCount").value(1));
        STUB.on("GET", "/internal/analytics/analyses/" + id + "/realtime-events", r -> list("[{\"realtimeEventId\":\"1\",\"deviceId\":\""
                + deviceA + "\"},{\"realtimeEventId\":\"2\",\"deviceId\":\"" + deviceB + "\"}]"));
        mvc.perform(as(org, viewer, get("/core/analytics/analyses/" + id + "/realtime-events"))).andExpect(jsonPath("$.responses.length()").value(2));
        data.spaceScope(org, viewer, List.of(siteA));
        mvc.perform(as(org, viewer, get("/core/analytics/analyses/" + id + "/realtime-events"))).andExpect(jsonPath("$.responses.length()").value(1));

        STUB.ok("POST", "/internal/analytics/analyses/" + id + "/models/train", 202, "{\"modelId\":\"31\",\"version\":2}");
        mvc.perform(json(as(org, operator, post("/core/analytics/analyses/" + id + "/models/train")), "{}")).andExpect(status().isForbidden());
        mvc.perform(json(as(org, integrator, post("/core/analytics/analyses/" + id + "/models/train")), "{}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.modelId").value("31"));
        STUB.fail("POST", "/internal/analytics/models/77/activate", 404, "RESOURCE_NOT_FOUND", null);
        mvc.perform(json(as(org, integrator, post("/core/analytics/models/77/activate")), "{\"force\":false}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("ML_MODEL_NOT_FOUND"));
        STUB.on("GET", "/internal/analytics/models", r -> list("[{\"modelId\":\"31\",\"analysisId\":\"" + id + "\"},{\"modelId\":\"32\",\"analysisId\":\"999\"}]"));
        mvc.perform(as(org, analyst, get("/core/analytics/models"))).andExpect(jsonPath("$.responses.length()").value(1));

        stubRun("501", id, "SUCCEEDED");
        STUB.ok("POST", "/internal/analytics/feedback", 201, "{\"feedbackId\":\"5\",\"verdict\":\"FALSE_POSITIVE\"}");
        String fb = "{\"runId\":\"501\",\"occurredAt\":\"2026-10-02T00:00:00Z\",\"seriesKey\":\"s\",\"verdict\":\"FALSE_POSITIVE\"}";
        mvc.perform(json(as(org, analyst, post("/core/analytics/feedback")), fb)).andExpect(status().isForbidden());
        mvc.perform(json(as(org, operator, post("/core/analytics/feedback")), fb)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.feedbackId").value("5"));
        stubRun("502", "9999", "SUCCEEDED");
        mvc.perform(json(as(org, operator, post("/core/analytics/feedback")), fb.replace("501", "502")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("ANALYSIS_RUN_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 일정·이벤트

    @Test
    @DisplayName("[ANA-04.01][AT-ANA-10.1][BR-ANA-11] 매주 월 06:00(KST)이 되면 run 1건(trigger=SCHEDULE, 소유자 신원), 같은 분 다시 돌려도 0건, EVT-ANA-05면 STOPPED_BY_FAILURE — TC-ANA-096·191")
    void scheduler() throws Exception {
        String id = create(analyst, "anomaly-detect", bindings("target", deviceA, "co2"), "{\"preset\":\"WEEKLY\",\"at\":\"06:00\",\"weekday\":\"MON\"}");
        mvc.perform(as(org, analyst, get("/core/analytics/analyses"))).andExpect(jsonPath("$.responses[0].nextScheduledAt").value("2026-10-04T21:00:00Z"))
                .andExpect(jsonPath("$.responses[0].scheduleState").value("ACTIVE"));
        STUB.ok("POST", "/internal/analytics/runs", 202, "{\"runId\":\"601\",\"status\":\"QUEUED\"}");
        assertThat(scheduler.runOnce()).isZero();
        clock.set(Instant.parse("2026-10-04T21:00:00Z"));
        assertThat(scheduler.runOnce()).isEqualTo(1);
        assertThat(scheduler.runOnce()).isZero();
        StubHttpServer.Received sent = STUB.received("POST", "/internal/analytics/runs").getFirst();
        assertThat(sent.body()).contains("\"trigger\":\"SCHEDULE\"").contains("\"analysisId\":\"" + id + "\"");
        assertThat(sent.header("X-USER-ID")).isEqualTo(Long.toString(analyst));
        assertThat(jdbc.sql("SELECT next_run_at::text FROM data2flow_core.analysis_refs WHERE analysis_id = :id").param("id", Long.parseLong(id))
                .query(String.class).single()).startsWith("2026-10-11 21:00:00");

        // EVT-ANA-01 성공 → 최근 성공 실행, 늦게 온 옛 실행은 덮지 않음
        assertThat(event("analytics.run.succeeded", "{\"runId\":\"601\",\"analysisId\":\"" + id + "\",\"status\":\"SUCCEEDED\",\"trigger\":\"SCHEDULE\",\"finishedAt\":\"2026-10-04T21:03:00Z\"}"))
                .isTrue();
        assertThat(event("analytics.run.failed", "{\"runId\":\"590\",\"analysisId\":\"" + id + "\",\"status\":\"FAILED\",\"trigger\":\"SCHEDULE\"}")).isTrue();
        mvc.perform(as(org, analyst, get("/core/analytics/analyses"))).andExpect(jsonPath("$.responses[0].lastRun.status").value("SUCCEEDED"));
        assertThat(event("analytics.schedule.stopped", "{\"analysisId\":\"" + id + "\",\"ownerUserId\":\"" + analyst + "\",\"consecutiveFailures\":3}"))
                .isTrue();
        mvc.perform(as(org, analyst, get("/core/analytics/analyses?scheduleState=STOPPED_BY_FAILURE"))).andExpect(jsonPath("$.totalCount").value(1));
        clock.set(Instant.parse("2026-10-11T21:00:00Z"));
        assertThat(scheduler.runOnce()).isZero();
        assertThat(event("analytics.unknown", "{}")).isFalse();
    }

    @Test
    @DisplayName("[ANA-04.02][API-ANA-16] 실행 상태 스트림: 연결 직후 run-status, 상태 이벤트·주기 조회로 바뀌면 run-status, 끝나면 run-done 후 닫음. 범위 밖 실행 404")
    void runStream() throws Exception {
        String id = create(analyst, "anomaly-detect", bindings("target", deviceA, "co2"), null);
        STUB.ok("GET", "/internal/analytics/runs/701", 200, "{\"run\":{\"runId\":\"701\",\"analysisId\":\"" + id + "\",\"status\":\"RUNNING\",\"progress\":10,\"stage\":\"LOAD\"}}");
        stubRun("702", "9999", "RUNNING");
        mvc.perform(as(org, viewer, get("/core/stream/analytics/runs/702").accept(MediaType.TEXT_EVENT_STREAM))).andExpect(status().isNotFound());
        MvcResult stream = mvc.perform(as(org, viewer, get("/core/stream/analytics/runs/701").accept(MediaType.TEXT_EVENT_STREAM)))
                .andExpect(request().asyncStarted()).andReturn();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(body(stream)).contains("event:run-status").contains("\"progress\":10"));
        STUB.ok("GET", "/internal/analytics/runs/701", 200, "{\"run\":{\"runId\":\"701\",\"analysisId\":\"" + id + "\",\"status\":\"RUNNING\",\"progress\":60,\"stage\":\"COMPUTE\"}}");
        streams.poll();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(body(stream)).contains("\"progress\":60"));
        streams.onEvent(org, codec().readTree("{\"runId\":\"701\",\"status\":\"SUCCEEDED\",\"progress\":100}"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(body(stream)).contains("event:run-done").contains("SUCCEEDED"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(streams.size()).isZero());
    }

    private tools.jackson.databind.json.JsonMapper codec() {
        return tools.jackson.databind.json.JsonMapper.builder().build();
    }

    // ------------------------------------------------------------------ 데이터셋·KPI·설정

    @Test
    @DisplayName("[ANA-10.01][ANA-09.03][ANA-05.08] 데이터셋 쓰기 A 이상·범위 밖 404, KPI 범위 밖 공간 404·analytics 한 곳에서 계산, 결과 보관 30~1095일(ADMIN) — TC-ANA-174·179·134")
    void datasetsKpisSettings() throws Exception {
        String ds = "{\"name\":\"실습실\",\"bindings\":" + bindings("target", deviceA, "co2") + ",\"period\":{\"type\":\"RELATIVE\",\"days\":30}}";
        STUB.ok("POST", "/internal/analytics/datasets", 201, "{\"datasetId\":\"11\",\"version\":1}");
        mvc.perform(json(as(org, viewer, post("/core/analytics/datasets")), ds)).andExpect(status().isForbidden());
        mvc.perform(json(as(org, analyst, post("/core/analytics/datasets")), ds)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.datasetId").value("11"));
        STUB.ok("GET", "/internal/analytics/datasets/12", 200, "{\"datasetId\":\"12\",\"bindings\":" + bindings("target", deviceB, "co2") + "}");
        STUB.ok("GET", "/internal/analytics/datasets/11", 200, "{\"datasetId\":\"11\",\"bindings\":" + bindings("target", deviceA, "co2") + "}");
        STUB.on("GET", "/internal/analytics/datasets", r -> list("[{\"datasetId\":\"11\"},{\"datasetId\":\"12\"}]"));
        data.spaceScope(org, viewer, List.of(siteA));
        mvc.perform(as(org, viewer, get("/core/analytics/datasets/12"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("DATASET_NOT_FOUND"));
        mvc.perform(as(org, viewer, get("/core/analytics/datasets/11"))).andExpect(status().isOk());
        mvc.perform(as(org, viewer, get("/core/analytics/datasets"))).andExpect(jsonPath("$.responses.length()").value(1));
        STUB.on("DELETE", "/internal/analytics/datasets/11", r -> new StubHttpServer.Reply(204, null, Map.of()));
        mvc.perform(as(org, analyst, delete("/core/analytics/datasets/11"))).andExpect(status().isNoContent());

        STUB.ok("POST", "/internal/analytics/kpis/compute", 200, "{\"kpis\":[{\"key\":\"COMPLIANCE_RATE\",\"value\":92.5,\"unit\":\"%\",\"available\":true}]}");
        mvc.perform(as(org, viewer, get("/core/analytics/kpis?spaceId=" + siteB))).andExpect(status().isNotFound());
        mvc.perform(get("/core/analytics/kpis?spaceId=" + siteA)).andExpect(status().isUnauthorized());
        mvc.perform(as(org, viewer, get("/core/analytics/kpis?spaceId=" + siteA + "&keys=COMPLIANCE_RATE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses[0].value").value(92.5)).andExpect(jsonPath("$.totalCount").value(1));
        assertThat(STUB.received("POST", "/internal/analytics/kpis/compute").getFirst().body()).contains("\"keys\":[\"COMPLIANCE_RATE\"]");

        mvc.perform(as(org, admin, get("/core/analytics/settings"))).andExpect(jsonPath("$.response.resultRetentionDays").value(365));
        mvc.perform(json(as(org, analyst, put("/core/analytics/settings")), "{\"resultRetentionDays\":90}")).andExpect(status().isForbidden());
        mvc.perform(json(as(org, admin, put("/core/analytics/settings")), "{\"resultRetentionDays\":20}")).andExpect(status().isBadRequest());
        mvc.perform(json(as(org, admin, put("/core/analytics/settings")), "{\"resultRetentionDays\":90}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.resultRetentionDays").value(90));
        assertThat(jdbc.sql("SELECT retain_days FROM data2flow_core.retention_policies WHERE organization_id = :org AND data_class = 'ANALYSIS_RESULT'")
                .param("org", org).query(Integer.class).single()).isEqualTo(90);
    }

    // ------------------------------------------------------------------ 대시보드·사이트·스크립트 AI

    @Test
    @DisplayName("[DSH-04.04][ANA-05.06][AT-DSH-05.3] 분석 결과 위젯 고정 → 최신 성공 결과(차트·수치), 다음 성공 이벤트면 새 결과, 실패 무시, 분석 삭제면 deleted — TC-DSH-040·TC-ANA-128")
    void pinnedWidget() throws Exception {
        String id = create(analyst, "comfort-index", bindings("temp", deviceA, "temperature"), null);
        MvcResult board = mvc.perform(json(as(org, analyst, post("/core/dashboards")), "{\"name\":\"분석판\"}")).andExpect(status().isCreated()).andReturn();
        String dashboard = read(board, "$.response.id");
        mvc.perform(json(as(org, viewer, post("/core/dashboards/" + dashboard + "/widgets/pin-analysis")), "{\"analysisId\":\"" + id + "\"}"))
                .andExpect(status().isForbidden());
        MvcResult pinned = mvc.perform(json(as(org, analyst, post("/core/dashboards/" + dashboard + "/widgets/pin-analysis")),
                "{\"analysisId\":\"" + id + "\",\"chartId\":\"c2\",\"metricKeys\":[\"score\"]}")).andExpect(status().isOk()).andReturn();
        String widget = read(pinned, "$.response.widgetId");
        assertThat((Integer) read(pinned, "$.response.version")).isEqualTo(2);
        String dataPath = "/core/dashboards/" + dashboard + "/widgets/" + widget + "/data";
        mvc.perform(json(as(org, analyst, post(dataPath)), "{}")).andExpect(status().isOk()).andExpect(jsonPath("$.response.type").value("analysis"))
                .andExpect(jsonPath("$.response.data.runId").isEmpty());

        stubRun("801", id, "SUCCEEDED");
        event("analytics.run.succeeded", "{\"runId\":\"801\",\"analysisId\":\"" + id + "\",\"status\":\"SUCCEEDED\",\"trigger\":\"SCHEDULE\",\"finishedAt\":\"2026-10-03T00:05:00Z\"}");
        event("analytics.run.failed", "{\"runId\":\"802\",\"analysisId\":\"" + id + "\",\"status\":\"FAILED\",\"trigger\":\"SCHEDULE\"}");
        mvc.perform(json(as(org, analyst, post(dataPath)), "{}")).andExpect(jsonPath("$.response.data.runId").value("801"))
                .andExpect(jsonPath("$.response.data.chart.id").value("c2")).andExpect(jsonPath("$.response.data.metrics.length()").value(1))
                .andExpect(jsonPath("$.response.data.metrics[0].key").value("score"));

        // 사이트 쾌적도(DEV-10.02): 본관 84(83.6 반올림), 별관 null
        mvc.perform(as(org, viewer, get("/core/sites/summary"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response[?(@.siteId=='" + siteA + "')].comfortScore").value(84))
                .andExpect(jsonPath("$.response[?(@.siteId=='" + siteB + "')].comfortScore").value(contains((Object) null)));

        data.spaceScope(org, analyst, List.of(siteB));
        mvc.perform(json(as(org, analyst, post(dataPath)), "{}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("WIDGET_DATA_FORBIDDEN"));
        STUB.on("DELETE", "/internal/analytics/analyses/" + id, r -> new StubHttpServer.Reply(204, null, Map.of()));
        data.spaceScope(org, analyst, List.of());
        mvc.perform(as(org, analyst, delete("/core/analytics/analyses/" + id))).andExpect(status().isNoContent());
        mvc.perform(json(as(org, analyst, post(dataPath)), "{}")).andExpect(jsonPath("$.response.data.deleted").value(true));
        mvc.perform(as(org, viewer, get("/core/widget-types"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response[?(@.type=='analysis')]").isNotEmpty());
    }

    @Test
    @DisplayName("[SCR-03.07][AIA-04.01] 스크립트 AI 초안 중계(API-SCR-16): SCRIPT_WRITE만, ai 내부 API 응답 그대로, ai 장애·한도 503 SCRIPT_AI_UNAVAILABLE — TC-SCR-063·065")
    void scriptAiDraft() throws Exception {
        String req = "{\"kind\":\"TRANSFORM\",\"requirement\":\"이슬점 추가\",\"sampleRawMessageIds\":[\"1\"]}";
        STUB.ok("POST", "/internal/ai/script-drafts", 200, "{\"code\":\"return msg;\",\"explanation\":\"설명\",\"testRun\":{\"ok\":true}}");
        mvc.perform(json(as(org, operator, post("/core/scripts/ai-draft")), req)).andExpect(status().isForbidden());
        mvc.perform(json(as(org, integrator, post("/core/scripts/ai-draft")), "{\"kind\":\"X\",\"requirement\":\"a\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(as(org, integrator, post("/core/scripts/ai-draft")), req)).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.code").value("return msg;")).andExpect(jsonPath("$.response.testRun.ok").value(true));
        assertThat(STUB.received("POST", "/internal/ai/script-drafts").getFirst().header("X-USER-ID")).isEqualTo(Long.toString(integrator));
        STUB.fail("POST", "/internal/ai/script-drafts", 429, "AI_QUOTA_EXCEEDED", null);
        mvc.perform(json(as(org, integrator, post("/core/scripts/ai-draft")), req)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.header.resultCode").value("SCRIPT_AI_UNAVAILABLE"))
                .andExpect(jsonPath("$.header.resultMessage").value("AI 도우미를 지금 쓸 수 없습니다"));
    }
}
