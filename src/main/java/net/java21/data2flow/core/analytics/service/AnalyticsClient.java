package net.java21.data2flow.core.analytics.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.ErrorCode;
import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.common.RelayedErrorException;
import net.java21.data2flow.core.config.AnalyticsProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * data2flow-analytics 내부 API(ANA-api §2, {@code http://data2flow-analytics/internal/analytics/**}). 요청에 {@code X-ORG-ID}·{@code X-USER-ID}·
 * {@code X-CALLER-SERVICE: data2flow-core-api}·{@code X-REQUEST-ID}·{@code Accept-Language}를 붙인다({@link InternalHttp}). analytics의 4xx는
 * 코드·본문 그대로 전하고(ANALYSIS_PARAMS_INVALID 등, TC-ANA-085), 5xx·연결 실패는 503 SERVICE_UNAVAILABLE이다.
 * 공통 코드 {@code RESOURCE_NOT_FOUND}(404)는 부른 쪽이 정한 도메인 코드(TEMPLATE_NOT_FOUND 등)로 바꾼다.
 */
@Component
public class AnalyticsClient {

    private final InternalHttp http;

    public AnalyticsClient(AnalyticsProperties properties, JsonMapper json) {
        this.http = new InternalHttp("analytics", properties.analyticsBaseUrl(), properties.relayTimeout(), json);
    }

    /** {@code response}(204면 null). 404 RESOURCE_NOT_FOUND는 notFound로 바꾼다(null이면 그대로) */
    public JsonNode call(HttpMethod method, String path, Map<String, ?> query, Object body, ErrorCode notFound) {
        return mapNotFound(() -> http.call(method, path, query, body), notFound);
    }

    /** 봉투 전체(목록 {@code page·responses·totalCount}) */
    public JsonNode envelope(HttpMethod method, String path, Map<String, ?> query, Object body, ErrorCode notFound) {
        return mapNotFound(() -> http.envelope(method, path, query, body), notFound);
    }

    /** 응답 상태(200·201·202)와 {@code response}. 추가 헤더(Idempotency-Key 등) */
    public InternalHttp.Result send(HttpMethod method, String path, Map<String, ?> query, Object body, Consumer<HttpHeaders> headers,
                                    ErrorCode notFound) {
        return mapNotFound(() -> http.send(method, path, query, body, headers), notFound);
    }

    static <T> T mapNotFound(Supplier<T> call, ErrorCode notFound) {
        try {
            return call.get();
        } catch (RelayedErrorException ex) {
            if (notFound != null && ex.status() == 404 && CommonErrorCode.RESOURCE_NOT_FOUND.code().equals(ex.resultCode())) {
                throw new BusinessException(notFound);
            }
            throw ex;
        } catch (BusinessException ex) {
            if (notFound != null && ex.getErrorCode() == CommonErrorCode.RESOURCE_NOT_FOUND) {
                throw new BusinessException(notFound);
            }
            throw ex;
        }
    }
}
