package net.java21.data2flow.core.external.provider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 공공데이터포털(data.go.kr) 오픈 API 한 번 호출(기상청·에어코리아·특일 정보 어댑터 공통). 서비스 키는 디코딩된 원문을 받아 URL 인코딩한다.
 * 정상 응답은 JSON {@code response.header.resultCode = "00"}. 키·한도 오류는 공통 게이트웨이가 XML({@code OpenAPI_ServiceResponse})로 준다.
 * <ul>
 *   <li>resultCode 22 또는 LIMITED_NUMBER_OF_SERVICE_REQUESTS → QUOTA</li>
 *   <li>resultCode 20·30·31·32 또는 SERVICE_KEY_IS_NOT_REGISTERED·SERVICE ACCESS DENIED 등 → AUTH</li>
 *   <li>그 밖의 오류 코드·형식 오류 → PROTOCOL, 시간 초과 → TIMEOUT, 연결 실패 → OTHER</li>
 * </ul>
 * 응답 본문은 로그에 남기지 않는다(서비스 키가 에코될 수 있음).
 */
public class DataGoKrClient {

    private final String baseUrl;
    private final String serviceKey;
    private final HttpClient http;
    private final Duration timeout;
    private final JsonMapper json;

    public DataGoKrClient(String baseUrl, String serviceKey, Duration timeout, JsonMapper json) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.serviceKey = serviceKey;
        this.timeout = timeout;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public boolean hasKey() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** GET 한 번. 성공하면 {@code response.body} */
    public JsonNode get(String path, Map<String, String> params) {
        StringBuilder url = new StringBuilder(baseUrl).append(path).append("?serviceKey=")
                .append(URLEncoder.encode(serviceKey == null ? "" : serviceKey, StandardCharsets.UTF_8));
        params.forEach((k, v) -> url.append('&').append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(url.toString())).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException ex) {
            throw new ProviderException(ProviderException.Kind.TIMEOUT, "시간 초과", ex);
        } catch (IOException ex) {
            throw new ProviderException(ProviderException.Kind.OTHER, "연결 실패: " + ex.getClass().getSimpleName(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ProviderException.Kind.OTHER, "중단됨", ex);
        }
        return parse(response.statusCode(), response.body());
    }

    JsonNode parse(int status, String body) {
        String text = body == null ? "" : body.strip();
        if (text.startsWith("<")) {
            throw xmlError(text);
        }
        if (status == 401 || status == 403) {
            throw new ProviderException(ProviderException.Kind.AUTH, "HTTP " + status);
        }
        if (status == 429) {
            throw new ProviderException(ProviderException.Kind.QUOTA, "HTTP 429");
        }
        if (status >= 400) {
            throw new ProviderException(ProviderException.Kind.OTHER, "HTTP " + status);
        }
        JsonNode root;
        try {
            root = json.readTree(text);
        } catch (RuntimeException ex) {
            throw new ProviderException(ProviderException.Kind.PROTOCOL, "JSON 형식 오류", ex);
        }
        JsonNode response = root.path("response");
        String code = response.path("header").path("resultCode").asString("");
        if (!"00".equals(code)) {
            throw new ProviderException(kindOf(code), "resultCode " + code + " " + response.path("header").path("resultMsg").asString(""));
        }
        return response.path("body");
    }

    static ProviderException.Kind kindOf(String code) {
        return switch (code) {
            case "22" -> ProviderException.Kind.QUOTA;
            case "20", "30", "31", "32" -> ProviderException.Kind.AUTH;
            default -> ProviderException.Kind.PROTOCOL;
        };
    }

    static ProviderException xmlError(String xml) {
        String auth = between(xml, "returnAuthMsg");
        String reason = between(xml, "returnReasonCode");
        String code = reason == null ? "" : reason.strip();
        ProviderException.Kind kind = kindOf(code);
        if (auth != null && auth.contains("LIMITED_NUMBER")) {
            kind = ProviderException.Kind.QUOTA;
        } else if (auth != null && (auth.contains("SERVICE_KEY") || auth.contains("ACCESS DENIED") || auth.contains("NOT_REGISTERED"))) {
            kind = ProviderException.Kind.AUTH;
        }
        return new ProviderException(kind, "data.go.kr 오류 " + code + " " + (auth == null ? "" : auth.strip()));
    }

    private static String between(String xml, String tag) {
        int start = xml.indexOf("<" + tag + ">");
        int end = xml.indexOf("</" + tag + ">");
        return start < 0 || end < start ? null : xml.substring(start + tag.length() + 2, end);
    }

    /** {@code items.item}이 배열·객체 하나·빈 문자열(결과 없음) 어느 모양이든 목록으로 */
    public static List<JsonNode> items(JsonNode body) {
        JsonNode items = body.path("items");
        JsonNode item = items.isArray() ? items : items.path("item");
        List<JsonNode> out = new ArrayList<>();
        if (item.isArray()) {
            item.forEach(out::add);
        } else if (item.isObject()) {
            out.add(item);
        }
        return out;
    }

    /** 숫자 문자열 → 값. "-"·빈 값·"강수없음"은 각각 null·null·0 */
    public static Double number(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        String s = node.asString("").strip();
        if (s.isEmpty() || "-".equals(s)) {
            return null;
        }
        if (s.contains("없음")) {
            return 0.0;
        }
        String digits = s.replaceAll("[^0-9.+-]", "");
        try {
            return digits.isEmpty() ? null : Double.valueOf(digits);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
