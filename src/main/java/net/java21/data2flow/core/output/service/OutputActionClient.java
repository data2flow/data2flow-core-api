package net.java21.data2flow.core.output.service;

import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.config.LoopProperties;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

/**
 * data2flow-action 출력 연결 내부 API(출력 계약 API-DSC-76·77). 테스트 발송은 대상에 실제로 한 건을 보내므로 대기 시간을 넉넉히(10초 + 여유) 둔다.
 * 연결 실패·시간 초과는 503 SERVICE_UNAVAILABLE.
 */
@Component
public class OutputActionClient {

    private final InternalHttp http;

    public OutputActionClient(LoopProperties properties, JsonMapper json) {
        Duration timeout = properties.relayTimeout().compareTo(Duration.ofSeconds(10)) > 0 ? properties.relayTimeout() : Duration.ofSeconds(10);
        this.http = new InternalHttp("action", properties.actionBaseUrl(), timeout.plusSeconds(4), json);
    }

    /** API-DSC-76 → {@code {ok, failureKind?, rendered, response{status, bodyPreview}}} */
    public JsonNode test(Map<String, Object> body) {
        return http.call(HttpMethod.POST, "/internal/action/output-connections/test", null, body);
    }

    /** API-DSC-77 → {@code {queued}} */
    public JsonNode replayFailed(long outputId, Map<String, Object> body) {
        return http.call(HttpMethod.POST, "/internal/action/output-connections/" + outputId + "/replay-failed", null, body);
    }
}
