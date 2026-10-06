package net.java21.data2flow.core.script.service;

import net.java21.data2flow.contracts.http.InternalHttpClients;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.script.dto.ScriptDtos.StaticCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * data2flow-pipeline 스크립트 내부 API 호출(실제 GraalJS 샌드박스는 pipeline에 있다, SCR-api.md 머리말):
 * API-SCR-30 정적 검사 {@code POST /internal/pipeline/scripts/check}, API-SCR-31 테스트 실행 {@code POST /internal/pipeline/scripts/test-run}.
 * 내부망 HTTP, 토큰 없이 {@code X-CALLER-SERVICE}만 붙인다(ADR-021). 응답 {@code {header, response}}의 response를 꺼낸다.
 */
@Component
public class PipelineScriptClient {

    static final String CALLER = "data2flow-core-api";
    private static final Logger log = LoggerFactory.getLogger(PipelineScriptClient.class);

    private final RestClient checkClient;
    private final RestClient runClient;
    private final JsonMapper json;

    public PipelineScriptClient(CoreProperties properties, JsonMapper json) {
        this.json = json;
        // 정적 검사는 편집 중 500ms 디바운스로 불리고 200ms 목표(API-SCR-07). 테스트 실행은 실행 50ms + 컴파일·예열 여유
        this.checkClient = client(properties.pipelineBaseUrl(), Duration.ofSeconds(3));
        this.runClient = client(properties.pipelineBaseUrl(), Duration.ofSeconds(10));
    }

    private static RestClient client(String baseUrl, Duration readTimeout) {
        HttpClient http = InternalHttpClients.create(Duration.ofSeconds(1));
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** API-SCR-30. pipeline이 응답하지 않으면 503 SERVICE_UNAVAILABLE */
    public StaticCheck check(long organizationId, String kind, String code, Object moduleRefs) {
        JsonNode response = post(checkClient, "/internal/pipeline/scripts/check",
                body("organizationId", organizationId, "kind", kind, "code", code, "moduleRefs", moduleRefs));
        try {
            return json.treeToValue(response, StaticCheck.class);
        } catch (tools.jackson.core.JacksonException ex) {
            log.warn("pipeline 정적 검사 응답 형식 오류: {}", ex.getClass().getSimpleName());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** API-SCR-31. 실행 결과 본문을 그대로 돌려준다(BR-SCR-08: pipeline도 core도 아무것도 저장하지 않음) */
    public JsonNode testRun(Map<String, Object> request) {
        return post(runClient, "/internal/pipeline/scripts/test-run", request);
    }

    private JsonNode post(RestClient client, String path, Map<String, Object> body) {
        try {
            String raw = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(DataflowHeaders.CALLER_SERVICE, CALLER)
                    .headers(h -> {
                        String requestId = MDC.get("requestId");
                        if (requestId != null) {
                            h.set(DataflowHeaders.REQUEST_ID, requestId);
                        }
                    })
                    .body(json.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            JsonNode envelope = raw == null || raw.isBlank() ? null : json.readTree(raw);
            if (envelope == null) {
                throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            JsonNode response = envelope.get("response");
            return response == null || response.isNull() ? envelope : response;
        } catch (RestClientException | tools.jackson.core.JacksonException ex) {
            log.warn("pipeline 스크립트 내부 API 호출 실패: {} ({})", path, ex.getClass().getSimpleName());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                map.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return map;
    }
}
