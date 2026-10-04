package net.java21.data2flow.core.retention.service;

import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.config.CoreProperties;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

/**
 * data2flow-pipeline 보관 내부 API(TSD-api §5): API-TSD-62 삭제 추정 {@code POST /internal/pipeline/retention/preview},
 * API-TSD-53 정책 반영 통지 {@code POST /internal/pipeline/retention/apply-policy}. 4xx는 그대로 전하고 5xx·연결 실패는 503.
 */
@Component
public class PipelineRetentionClient {

    private final InternalHttp http;

    public PipelineRetentionClient(CoreProperties properties, JsonMapper json) {
        this.http = new InternalHttp("pipeline", properties.pipelineBaseUrl(), Duration.ofSeconds(15), json);
    }

    /** API-TSD-62 {organizationId, items[{scope, scopeRef, dataClass, retainDays}]} → {affectedRows, affectedBytes, byMetric[]} */
    public JsonNode preview(Map<String, Object> body) {
        return http.call(HttpMethod.POST, "/internal/pipeline/retention/preview", null, body);
    }

    /** API-TSD-53 {organizationId, policyVersion} → {appliesAt} */
    public JsonNode applyPolicy(long organizationId, long policyVersion) {
        return http.call(HttpMethod.POST, "/internal/pipeline/retention/apply-policy", null,
                Map.of("organizationId", organizationId, "policyVersion", policyVersion));
    }
}
