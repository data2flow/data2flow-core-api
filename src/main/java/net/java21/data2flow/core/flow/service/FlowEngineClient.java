package net.java21.data2flow.core.flow.service;

import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.config.LoopProperties;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/** data2flow-flow-engine 내부 API(FLW-api §2). 지표는 엔진 소유 {@code data2flow_flow.flow_metric_minutes}에 있다 */
@Component
public class FlowEngineClient {

    private final InternalHttp http;

    public FlowEngineClient(LoopProperties properties, JsonMapper json) {
        this.http = new InternalHttp("flow-engine", properties.flowEngineBaseUrl(), properties.relayTimeout(), json);
    }

    /** API-FLW-82 인스턴스별 적용 상태 {flowId, instances:[{instanceId, appliedVersion, overlayRevision, reportedAt}]} */
    public JsonNode applyStatus(UUID flowId) {
        return http.call(HttpMethod.GET, "/internal/flow/flows/" + flowId + "/apply-status", null, null);
    }

    /** API-FLW-83 노드 카탈로그(목록 봉투) */
    public JsonNode nodeTypes() {
        return http.envelope(HttpMethod.GET, "/internal/flow/node-types", null, null);
    }

    /** API-FLW-84 엔진 컴파일러 검증 → {errors:[{field, code, message}]} */
    public JsonNode validate(long organizationId, UUID flowId, JsonNode definition) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("organizationId", Long.toString(organizationId));
        if (flowId != null) {
            body.put("flowId", flowId.toString());
        }
        body.put("definition", definition);
        return http.call(HttpMethod.POST, "/internal/flow/definitions/validate", null, body);
    }

    /** API-FLW-14 → {@code GET /internal/flow/flows/{flow-id}/metrics?window=&step=} */
    public JsonNode metrics(UUID flowId, String window, String step) {
        return http.call(HttpMethod.GET, "/internal/flow/flows/" + flowId + "/metrics", InternalHttp.query("window", window, "step", step),
                null);
    }

    /** API-FLW-86 규칙 → 표준 플로우 {organizationId, ruleId, rule} → {definition, target}. 바꿀 수 없으면 엔진의 400 RULE_CONDITION_INVALID */
    public JsonNode compileRule(long organizationId, long ruleId, java.util.Map<String, Object> rule) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("organizationId", Long.toString(organizationId));
        body.put("ruleId", Long.toString(ruleId));
        body.put("rule", rule);
        return http.call(HttpMethod.POST, "/internal/flow/rules/compile", null, body);
    }

    /** API-FLW-12 → {@code POST /internal/flow/test-runs} → {trace} */
    public JsonNode testRun(java.util.Map<String, Object> body) {
        return http.call(HttpMethod.POST, "/internal/flow/test-runs", null, body);
    }

    /** API-FLW-13 → {@code POST /internal/flow/replays} → 202 {jobId, status} */
    public JsonNode replay(java.util.Map<String, Object> body) {
        return http.call(HttpMethod.POST, "/internal/flow/replays", null, body);
    }

    /** API-FLW-13 → {@code GET /internal/flow/replays/{job-id}} */
    public JsonNode replayJob(String jobId) {
        return http.call(HttpMethod.GET, "/internal/flow/replays/" + jobId, null, null);
    }

    /** API-FLW-13 → {@code POST /internal/flow/replays/{job-id}/cancel} */
    public JsonNode cancelReplay(String jobId) {
        return http.call(HttpMethod.POST, "/internal/flow/replays/" + jobId + "/cancel", null, java.util.Map.of());
    }

    /** API-FLW-41 → {@code GET /internal/flow/traces/{message-id}?flowId=} */
    public JsonNode trace(String messageId, UUID flowId) {
        return http.call(HttpMethod.GET, "/internal/flow/traces/" + messageId, InternalHttp.query("flowId", flowId.toString()), null);
    }
}
