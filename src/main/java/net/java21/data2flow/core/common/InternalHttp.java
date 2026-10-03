package net.java21.data2flow.core.common;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;
import net.java21.data2flow.contracts.identity.DataflowHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 내부 API 호출 도우미(ADR-021: 내부망 HTTP 80, 토큰 없이 {@code X-CALLER-SERVICE}만). core-api가 외부 경로를 대신 열고
 * action·flow-engine·simulator의 내부 API로 넘길 때 쓴다(ACT-api §5.2, SIM-api §2, FLW-api 머리말).
 *
 * <ul>
 *   <li>요청: {@code X-CALLER-SERVICE: data2flow-core-api}, 요청자 신원 {@code X-ORG-ID}·{@code X-USER-ID}, {@code X-REQUEST-ID},
 *       {@code Accept-Language}(하위 서비스가 같은 언어로 resultMessage를 만든다)</li>
 *   <li>2xx: 공통 봉투 {@code {header, response}}에서 {@code response}를 꺼낸다({@link #call}). 목록 봉투는 그대로({@link #envelope})</li>
 *   <li>4xx: 하위 서비스의 오류(상태·resultCode·errors·response)를 그대로 돌려준다({@link RelayedErrorException}). 화면이 같은 코드로 분기한다</li>
 *   <li>5xx·연결 실패·형식 오류: 503 {@code SERVICE_UNAVAILABLE}</li>
 * </ul>
 */
public final class InternalHttp {

    public static final String CALLER = "data2flow-core-api";
    private static final Logger log = LoggerFactory.getLogger(InternalHttp.class);

    private final String name;
    private final RestClient client;
    private final JsonMapper json;

    public InternalHttp(String name, String baseUrl, Duration readTimeout, JsonMapper json) {
        this.name = name;
        this.json = json;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 응답 원문 */
    public record Raw(int status, byte[] body, HttpHeaders headers) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** 성공 상태와 {@code response} */
    public record Result(int status, JsonNode response) {
    }

    /** 2xx 상태 코드도 함께(200·202를 그대로 전할 때). 추가 헤더(예: Idempotency-Key)를 붙일 수 있다 */
    public Result send(HttpMethod method, String path, Map<String, ?> query, Object body, Consumer<HttpHeaders> extraHeaders) {
        Raw raw = exchange(method, path, query, body == null ? null : json.writeValueAsBytes(body), MediaType.APPLICATION_JSON,
                extraHeaders);
        JsonNode envelope = parse(raw, path);
        JsonNode response = envelope == null ? null : envelope.get("response");
        return new Result(raw.status(), response == null || response.isNull() ? envelope : response);
    }

    /** 2xx면 {@code response}(없으면 봉투 전체, 204면 null) */
    public JsonNode call(HttpMethod method, String path, Map<String, ?> query, Object body) {
        JsonNode envelope = envelope(method, path, query, body);
        if (envelope == null) {
            return null;
        }
        JsonNode response = envelope.get("response");
        return response == null || response.isNull() ? envelope : response;
    }

    /** 2xx 봉투 전체(목록 {@code page·responses·totalCount}). 204면 null */
    public JsonNode envelope(HttpMethod method, String path, Map<String, ?> query, Object body) {
        Raw raw = exchange(method, path, query, body == null ? null : json.writeValueAsBytes(body), MediaType.APPLICATION_JSON, null);
        return parse(raw, path);
    }

    /** 이미 만든 본문(예: multipart)을 그대로 넘긴다 */
    public JsonNode callRaw(HttpMethod method, String path, Map<String, ?> query, byte[] body, MediaType contentType) {
        Raw raw = exchange(method, path, query, body, contentType, null);
        JsonNode envelope = parse(raw, path);
        if (envelope == null) {
            return null;
        }
        JsonNode response = envelope.get("response");
        return response == null || response.isNull() ? envelope : response;
    }

    /** 파일 내려받기(봉투 없음). 4xx는 그대로 돌려준다 */
    public Raw download(String path, Map<String, ?> query) {
        Raw raw = exchange(HttpMethod.GET, path, query, null, null, null);
        if (!raw.ok()) {
            throw relay(raw, path);
        }
        return raw;
    }

    private JsonNode parse(Raw raw, String path) {
        if (!raw.ok()) {
            throw relay(raw, path);
        }
        if (raw.status() == 204 || raw.body() == null || raw.body().length == 0) {
            return null;
        }
        try {
            return json.readTree(raw.body());
        } catch (tools.jackson.core.JacksonException ex) {
            log.warn("{} 내부 API 응답 형식 오류: {}", name, path);
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private RuntimeException relay(Raw raw, String path) {
        if (raw.status() >= 400 && raw.status() < 500 && raw.body() != null && raw.body().length > 0) {
            try {
                JsonNode body = json.readTree(raw.body());
                if (body.has("header")) {
                    return new RelayedErrorException(raw.status(), body);
                }
            } catch (tools.jackson.core.JacksonException ignored) {
                // 아래 503
            }
        }
        if (raw.status() == 404) {
            return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        log.warn("{} 내부 API 실패: {} → {}", name, path, raw.status());
        return new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    /** 요청 하나. 연결 실패·시간 초과는 503 */
    public Raw exchange(HttpMethod method, String path, Map<String, ?> query, byte[] body, MediaType contentType,
                        Consumer<HttpHeaders> extraHeaders) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
        if (query != null) {
            query.forEach((k, v) -> {
                if (v instanceof Iterable<?> values) {
                    for (Object one : values) {
                        uri.queryParam(k, one);
                    }
                } else if (v != null) {
                    uri.queryParam(k, v);
                }
            });
        }
        String target = uri.build().encode(StandardCharsets.UTF_8).toUriString();
        try {
            RestClient.RequestBodySpec spec = client.method(method).uri(target)
                    .headers(h -> {
                        h.set(DataflowHeaders.CALLER_SERVICE, CALLER);
                        String requestId = MDC.get("requestId");
                        if (requestId != null) {
                            h.set(DataflowHeaders.REQUEST_ID, requestId);
                        }
                        CurrentUser user = CurrentUserHolder.find().orElse(null);
                        if (user != null) {
                            h.set(DataflowHeaders.ORG_ID, Long.toString(user.organizationId()));
                            h.set(DataflowHeaders.USER_ID, Long.toString(user.userId()));
                        }
                        h.set(HttpHeaders.ACCEPT_LANGUAGE, LocaleContextHolder.getLocale().toLanguageTag());
                        if (extraHeaders != null) {
                            extraHeaders.accept(h);
                        }
                    });
            if (body != null) {
                spec = spec.contentType(contentType == null ? MediaType.APPLICATION_JSON : contentType).body(body);
            }
            return spec.exchange((request, response) -> new Raw(response.getStatusCode().value(),
                    response.getBody().readAllBytes(), response.getHeaders()), true);
        } catch (RestClientException ex) {
            log.warn("{} 내부 API 호출 실패: {} {} ({})", name, method, path, ex.getClass().getSimpleName());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    /** null 값을 뺀 쿼리 맵 */
    public static Map<String, Object> query(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null && !(value instanceof String s && s.isBlank())) {
                map.put((String) pairs[i], value);
            }
        }
        return map;
    }
}
