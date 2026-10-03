package net.java21.data2flow.core.source.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.source.domain.SourceErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * data2flow-ingress 내부 API 호출(HTTP 80, 토큰 없이 {@code X-CALLER-SERVICE}, ADR-021):
 * <ul>
 *   <li>API-DSC-51 {@code POST /internal/ingress/sources/test} — 연결 테스트(본문에 복호화한 비밀값이 있으므로 로그에 남기지 않는다)</li>
 *   <li>API-DSC-52 {@code GET /internal/ingress/sources/{source-id}/live} — 원본 메시지 샘플 SSE</li>
 * </ul>
 * core는 외부 브로커에 직접 붙지 않는다(연결은 ingress만).
 */
@Component
public class IngressClient {

    static final String CALLER = "data2flow-core-api";
    private static final Logger log = LoggerFactory.getLogger(IngressClient.class);

    private final String baseUrl;
    private final HttpClient http;
    private final JsonMapper json;

    public IngressClient(CoreProperties properties, JsonMapper json) {
        this.baseUrl = properties.ingressBaseUrl();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.json = json;
    }

    /**
     * 연결 테스트. ingress 응답 봉투({@code {header, response}})면 response를, 아니면 본문 전체를 돌려준다.
     * ingress가 낸 소스 오류 코드(SOURCE_CONFIG_INVALID·SOURCE_SECRET_REQUIRED·SOURCE_AUTH_UNSUPPORTED)는 그대로, 429는 RATE_LIMITED,
     * 그 밖의 실패·연결 불가는 503 SERVICE_UNAVAILABLE.
     *
     * @param readTimeout 테스트 제한 시간 + 여유
     */
    public JsonNode test(Map<String, Object> request, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        try {
            String body = client.post().uri("/internal/ingress/sources/test")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(DataflowHeaders.CALLER_SERVICE, CALLER)
                    .body(json.writeValueAsString(request))
                    .retrieve()
                    .body(String.class);
            JsonNode node = json.readTree(body == null ? "{}" : body);
            return node.has("response") && node.has("header") ? node.get("response") : node;
        } catch (RestClientResponseException ex) {
            throw mapError(ex.getStatusCode().value(), ex.getResponseBodyAsString());
        } catch (RestClientException ex) {
            log.warn("ingress 연결 테스트 호출 실패: {}", ex.getClass().getSimpleName());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    BusinessException mapError(int status, String body) {
        if (status == 429) {
            return new BusinessException(CommonErrorCode.RATE_LIMITED);
        }
        if (status >= 400 && status < 500) {
            try {
                String code = json.readTree(body).path("header").path("resultCode").asString("");
                for (SourceErrorCode c : new SourceErrorCode[]{SourceErrorCode.SOURCE_CONFIG_INVALID, SourceErrorCode.SOURCE_SECRET_REQUIRED,
                        SourceErrorCode.SOURCE_AUTH_UNSUPPORTED, SourceErrorCode.SOURCE_TLS_VERIFY_REQUIRED}) {
                    if (c.name().equals(code)) {
                        return new BusinessException(c, "ingress");
                    }
                }
            } catch (RuntimeException ex) {
                // 본문이 JSON이 아니면 아래 일반 오류
            }
            return new BusinessException(SourceErrorCode.SOURCE_CONFIG_INVALID, "ingress");
        }
        return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    /**
     * 원본 메시지 SSE 스트림을 연다(API-DSC-52). 응답 본문 스트림은 호출하는 쪽이 닫는다.
     *
     * @param topicFilter ingress에 넘기는 힌트(ingress가 모르면 core가 거른다)
     */
    public CompletableFuture<HttpResponse<InputStream>> openLive(long sourceId, String topicFilter) {
        String query = topicFilter == null || topicFilter.isBlank() ? ""
                : "?topicFilter=" + URLEncoder.encode(topicFilter, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/ingress/sources/" + sourceId + "/live" + query))
                .header(DataflowHeaders.CALLER_SERVICE, CALLER)
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    /** 응답 본문을 닫는다(오류는 무시) */
    static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ex) {
            // 이미 끊긴 연결
        }
    }
}
