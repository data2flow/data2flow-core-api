package net.java21.data2flow.core.catalog.service;

import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.core.config.CoreProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * pipeline 재매핑 요청(API-TSD-51 {@code POST /internal/pipeline/telemetry/remap-metric}, 202 {@code {jobId}}).
 * 내부망 HTTP, 토큰 없이 {@code X-CALLER-SERVICE}만 붙인다(ADR-021). 시계열은 pipeline 소유라 core가 직접 바꾸지 않는다.
 */
@Component
public class PipelineRemapClient {

    static final String CALLER = "data2flow-core-api";

    private final RestClient client;

    public PipelineRemapClient(CoreProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().baseUrl(properties.pipelineBaseUrl()).requestFactory(factory).build();
    }

    /** 재매핑을 시작하고 pipeline 작업 ID를 돌려준다. 실패하면 예외 */
    public String remap(long organizationId, String alias, String targetKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId);
        body.put("alias", alias);
        body.put("targetKey", targetKey);
        JsonNode response = client.post().uri("/internal/pipeline/telemetry/remap-metric")
                .contentType(MediaType.APPLICATION_JSON)
                .header(DataflowHeaders.CALLER_SERVICE, CALLER)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            return null;
        }
        JsonNode jobId = response.has("jobId") ? response.get("jobId") : response.path("response").path("jobId");
        return jobId.isMissingNode() || jobId.isNull() ? null : jobId.asString();
    }
}
