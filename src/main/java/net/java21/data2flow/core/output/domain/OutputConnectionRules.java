package net.java21.data2flow.core.output.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.output.OutputConnectionType;
import net.java21.data2flow.contracts.output.OutputFilter;
import net.java21.data2flow.contracts.output.OutputFormat;
import net.java21.data2flow.contracts.output.OutputTopicTemplate;
import net.java21.data2flow.core.source.domain.SourceErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 출력 연결 설정 규칙(DSC-04.01, design/api/DSC-api.md §3, 출력 계약 API-DSC-73~77). 저장 전에 대상(target)·필터·형식·템플릿·비밀값 종류를
 * 검사하고 정리한 값을 돌려준다. 실패는 400 {@code SOURCE_CONFIG_INVALID} + {@code errors[{field, code}]}.
 * <ul>
 *   <li>MQTT_PUBLISH: {@code url}(mqtt·mqtts·ws·wss), {@code topicTemplate}(contracts {@link OutputTopicTemplate}, 허용 변수 4개),
 *       {@code qos}(0·1, 기본 1), {@code retain}(기본 false), {@code username?}, {@code clientId?}. 비밀값 PASSWORD·CA_CERT</li>
 *   <li>WEBHOOK: {@code url}(http·https), {@code method}(POST·PUT), {@code headers}(비밀 아님), {@code authHeaderName}(기본 Authorization),
 *       {@code batchSize}(1~500, 기본 100), {@code batchWaitMs}(0~10000, 기본 1000), {@code timeoutMs}(1000~30000, 기본 10000).
 *       비밀값 HEADER_VALUE·HMAC_KEY</li>
 *   <li>공용 브로커 {@code iot-data.java21.net}으로는 내보내지 않는다(CLAUDE.md §5): {@code target.url} {@code FORBIDDEN_HOST}</li>
 * </ul>
 */
public final class OutputConnectionRules {

    /** 플랫폼이 절대 발행하지 않는 공용 브로커(CLAUDE.md §5) */
    public static final String FORBIDDEN_HOST = "iot-data.java21.net";
    public static final Set<String> MQTT_SECRET_KINDS = Set.of("PASSWORD", "CA_CERT");
    public static final Set<String> WEBHOOK_SECRET_KINDS = Set.of("HEADER_VALUE", "HMAC_KEY");
    static final int MAX_TEMPLATE_BYTES = 8 * 1024;
    static final int MAX_SECRET_CHARS = 64 * 1024;
    static final int MAX_FILTER_IDS = 500;
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern METRIC_KEY = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    private OutputConnectionRules() {
    }

    /** 정리된 필터. ID는 숫자 */
    public record Filter(List<Long> deviceIds, List<Long> groupIds, List<Long> spaceIds, List<String> metrics, Integer qualityMin) {

        public OutputFilter toContract() {
            return new OutputFilter(deviceIds, groupIds, spaceIds, metrics, qualityMin);
        }

        /** 저장·응답용 JSON(ID는 문자열) */
        public ObjectNode toJson(boolean idsAsStrings) {
            ObjectNode n = JsonNodeFactory.instance.objectNode();
            n.set("deviceIds", ids(deviceIds, idsAsStrings));
            n.set("groupIds", ids(groupIds, idsAsStrings));
            n.set("spaceIds", ids(spaceIds, idsAsStrings));
            ArrayNode m = n.putArray("metrics");
            metrics.forEach(m::add);
            if (qualityMin == null) {
                n.putNull("qualityMin");
            } else {
                n.put("qualityMin", qualityMin);
            }
            return n;
        }

        private static ArrayNode ids(List<Long> ids, boolean asStrings) {
            ArrayNode a = JsonNodeFactory.instance.arrayNode();
            for (Long id : ids) {
                if (asStrings) {
                    a.add(Long.toString(id));
                } else {
                    a.add(id);
                }
            }
            return a;
        }
    }

    public static String type(JsonNode raw, List<FieldErrorDetail> errors) {
        String type = raw == null || raw.isNull() ? "" : raw.asString("").toUpperCase(Locale.ROOT);
        if (!OutputConnectionType.MQTT_PUBLISH.name().equals(type) && !OutputConnectionType.WEBHOOK.name().equals(type)) {
            errors.add(new FieldErrorDetail("type", "INVALID", null));
            return null;
        }
        return type;
    }

    public static String format(JsonNode raw, List<FieldErrorDetail> errors) {
        if (raw == null || raw.isNull()) {
            return OutputFormat.CANONICAL.name();
        }
        String format = raw.asString("").toUpperCase(Locale.ROOT);
        if (!OutputFormat.CANONICAL.name().equals(format) && !OutputFormat.TEMPLATE.name().equals(format)) {
            errors.add(new FieldErrorDetail("format", "INVALID", null));
            return OutputFormat.CANONICAL.name();
        }
        return format;
    }

    /** TEMPLATE 형식이면 필수(8KB 이하), CANONICAL이면 버린다(null) */
    public static String template(String format, JsonNode raw, List<FieldErrorDetail> errors) {
        String t = raw == null || raw.isNull() ? null : raw.asString(null);
        if (!OutputFormat.TEMPLATE.name().equals(format)) {
            return null;
        }
        if (t == null || t.isBlank()) {
            errors.add(new FieldErrorDetail("template", "NotBlank", null));
            return null;
        }
        if (t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TEMPLATE_BYTES) {
            errors.add(new FieldErrorDetail("template", "Size", null));
        }
        return t;
    }

    /** 대상 설정 검사·기본값 채우기 */
    public static ObjectNode target(String type, JsonNode raw, List<FieldErrorDetail> errors) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (raw == null || !raw.isObject()) {
            errors.add(new FieldErrorDetail("target", raw == null || raw.isNull() ? "NotNull" : "Type", null));
            return out;
        }
        if (type == null) {
            return out;
        }
        boolean mqtt = OutputConnectionType.MQTT_PUBLISH.name().equals(type);
        Set<String> allowed = mqtt ? Set.of("url", "topicTemplate", "qos", "retain", "username", "clientId")
                : Set.of("url", "method", "headers", "authHeaderName", "batchSize", "batchWaitMs", "timeoutMs");
        for (String key : raw.propertyNames()) {
            if (!allowed.contains(key)) {
                errors.add(new FieldErrorDetail("target." + key, "UNKNOWN_FIELD", null));
            }
        }
        String url = text(raw, "url");
        URI uri = url == null ? null : parse(url);
        Set<String> schemes = mqtt ? Set.of("mqtt", "mqtts", "ws", "wss", "tcp", "ssl") : Set.of("http", "https");
        if (url == null) {
            errors.add(new FieldErrorDetail("target.url", "NotBlank", null));
        } else if (uri == null || uri.getScheme() == null || uri.getHost() == null
                || !schemes.contains(uri.getScheme().toLowerCase(Locale.ROOT)) || url.length() > 500) {
            errors.add(new FieldErrorDetail("target.url", "INVALID", null));
        } else if (forbiddenHost(uri.getHost())) {
            errors.add(new FieldErrorDetail("target.url", "FORBIDDEN_HOST", null));
        }
        if (url != null) {
            out.put("url", url.strip());
        }
        if (mqtt) {
            String template = text(raw, "topicTemplate");
            if (template == null) {
                errors.add(new FieldErrorDetail("target.topicTemplate", "NotBlank", null));
            } else if (!OutputTopicTemplate.unknownVariables(template).isEmpty()) {
                errors.add(new FieldErrorDetail("target.topicTemplate", "UNKNOWN_VARIABLE", null));
            } else {
                try {
                    out.put("topicTemplate", OutputTopicTemplate.of(template).template());
                } catch (IllegalArgumentException ex) {
                    errors.add(new FieldErrorDetail("target.topicTemplate", "INVALID", null));
                }
            }
            out.put("qos", intIn(raw, "qos", 1, 0, 1, "target.qos", errors));
            out.put("retain", raw.path("retain").asBoolean(false));
            String username = text(raw, "username");
            if (username != null && username.length() > 200) {
                errors.add(new FieldErrorDetail("target.username", "Size", null));
            }
            putOrNull(out, "username", username);
            String clientId = text(raw, "clientId");
            if (clientId != null && !clientId.matches("[A-Za-z0-9_.-]{1,64}")) {
                errors.add(new FieldErrorDetail("target.clientId", "Pattern", null));
            }
            putOrNull(out, "clientId", clientId);
        } else {
            String method = text(raw, "method") == null ? "POST" : text(raw, "method").toUpperCase(Locale.ROOT);
            if (!method.equals("POST") && !method.equals("PUT")) {
                errors.add(new FieldErrorDetail("target.method", "INVALID", null));
            }
            out.put("method", method);
            ObjectNode headers = out.putObject("headers");
            JsonNode h = raw.get("headers");
            if (h != null && !h.isNull()) {
                if (!h.isObject() || h.size() > 20) {
                    errors.add(new FieldErrorDetail("target.headers", "INVALID", null));
                } else {
                    for (String name : h.propertyNames()) {
                        JsonNode v = h.get(name);
                        if (!HEADER_NAME.matcher(name).matches() || !v.isString() || v.asString().length() > 1000
                                || isSensitiveHeader(name)) {
                            errors.add(new FieldErrorDetail("target.headers." + name, isSensitiveHeader(name) ? "USE_SECRET" : "INVALID", null));
                        } else {
                            headers.put(name, v.asString());
                        }
                    }
                }
            }
            String authHeader = text(raw, "authHeaderName") == null ? "Authorization" : text(raw, "authHeaderName");
            if (!HEADER_NAME.matcher(authHeader).matches()) {
                errors.add(new FieldErrorDetail("target.authHeaderName", "Pattern", null));
            }
            out.put("authHeaderName", authHeader);
            out.put("batchSize", intIn(raw, "batchSize", 100, 1, 500, "target.batchSize", errors));
            out.put("batchWaitMs", intIn(raw, "batchWaitMs", 1000, 0, 10000, "target.batchWaitMs", errors));
            out.put("timeoutMs", intIn(raw, "timeoutMs", 10000, 1000, 30000, "target.timeoutMs", errors));
        }
        return out;
    }

    /** 공용 브로커 호스트인가(대소문자·끝 점 무시) */
    public static boolean forbiddenHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return FORBIDDEN_HOST.equals(h);
    }

    private static boolean isSensitiveHeader(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("authorization") || n.equals("proxy-authorization") || n.contains("token") || n.contains("secret")
                || n.contains("api-key") || n.contains("apikey") || n.equals("cookie") || n.startsWith("x-d2f-");
    }

    /** 필터 검사. ID는 문자열·숫자 모두 받는다 */
    public static Filter filter(JsonNode raw, List<FieldErrorDetail> errors) {
        if (raw != null && !raw.isNull() && !raw.isObject()) {
            errors.add(new FieldErrorDetail("filter", "Type", null));
            raw = null;
        }
        JsonNode f = raw == null || raw.isNull() ? JsonNodeFactory.instance.objectNode() : raw;
        for (String key : f.propertyNames()) {
            if (!Set.of("deviceIds", "groupIds", "spaceIds", "metrics", "qualityMin").contains(key)) {
                errors.add(new FieldErrorDetail("filter." + key, "UNKNOWN_FIELD", null));
            }
        }
        List<Long> devices = idList(f.get("deviceIds"), "filter.deviceIds", errors);
        List<Long> groups = idList(f.get("groupIds"), "filter.groupIds", errors);
        List<Long> spaces = idList(f.get("spaceIds"), "filter.spaceIds", errors);
        List<String> metrics = new ArrayList<>();
        JsonNode m = f.get("metrics");
        if (m != null && !m.isNull()) {
            if (!m.isArray() || m.size() > 100) {
                errors.add(new FieldErrorDetail("filter.metrics", "INVALID", null));
            } else {
                Set<String> seen = new LinkedHashSet<>();
                for (JsonNode k : m) {
                    if (!k.isString() || !METRIC_KEY.matcher(k.asString()).matches()) {
                        errors.add(new FieldErrorDetail("filter.metrics", "INVALID", null));
                        break;
                    }
                    seen.add(k.asString());
                }
                metrics.addAll(seen);
            }
        }
        Integer quality = null;
        JsonNode q = f.get("qualityMin");
        if (q != null && !q.isNull()) {
            if (!q.isIntegralNumber() || q.asInt() < 0 || q.asInt() > 5) {
                errors.add(new FieldErrorDetail("filter.qualityMin", "Range", null));
            } else {
                quality = q.asInt();
            }
        }
        return new Filter(devices, groups, spaces, metrics, quality);
    }

    /** 저장된 필터 JSON(숫자 ID) → 정리된 필터 */
    public static Filter storedFilter(JsonNode stored) {
        List<FieldErrorDetail> ignored = new ArrayList<>();
        return filter(stored, ignored);
    }

    /**
     * 요청의 {@code secret}: {@code {"PASSWORD": "값", "CA_CERT": null}}. 값이 null이면 그 종류를 지운다(맵 값 null).
     * 연결 종류에 맞지 않는 종류는 {@code secret.<kind>} {@code NOT_FOR_TYPE}
     */
    public static Map<String, String> secrets(String type, JsonNode raw, List<FieldErrorDetail> errors) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isNull()) {
            return out;
        }
        if (!raw.isObject()) {
            errors.add(new FieldErrorDetail("secret", "Type", null));
            return out;
        }
        Set<String> allowed = OutputConnectionType.MQTT_PUBLISH.name().equals(type) ? MQTT_SECRET_KINDS : WEBHOOK_SECRET_KINDS;
        for (String key : raw.propertyNames()) {
            String kind = key.toUpperCase(Locale.ROOT);
            JsonNode v = raw.get(key);
            if (!allowed.contains(kind)) {
                errors.add(new FieldErrorDetail("secret." + key, "NOT_FOR_TYPE", null));
            } else if (v == null || v.isNull()) {
                out.put(kind, null);
            } else if (!v.isString() || v.asString().isEmpty() || v.asString().length() > MAX_SECRET_CHARS) {
                errors.add(new FieldErrorDetail("secret." + key, "INVALID", null));
            } else {
                out.put(kind, v.asString());
            }
        }
        return out;
    }

    public static String name(JsonNode raw, List<FieldErrorDetail> errors) {
        String name = raw == null || raw.isNull() ? null : raw.asString("").strip();
        if (name == null || name.isEmpty()) {
            errors.add(new FieldErrorDetail("name", "NotBlank", null));
            return null;
        }
        if (name.length() > 100) {
            errors.add(new FieldErrorDetail("name", "Size", null));
        }
        return name;
    }

    public static void throwIfInvalid(List<FieldErrorDetail> errors) {
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
    }

    public static BusinessException invalid(List<FieldErrorDetail> errors) {
        return new BusinessException(SourceErrorCode.SOURCE_CONFIG_INVALID, errors, errors.getFirst().field());
    }

    private static List<Long> idList(JsonNode raw, String field, List<FieldErrorDetail> errors) {
        List<Long> ids = new ArrayList<>();
        if (raw == null || raw.isNull()) {
            return ids;
        }
        if (!raw.isArray() || raw.size() > MAX_FILTER_IDS) {
            errors.add(new FieldErrorDetail(field, "INVALID", null));
            return ids;
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (JsonNode n : raw) {
            if (n.isIntegralNumber() && n.asLong() > 0) {
                seen.add(n.asLong());
            } else if (n.isString() && n.asString().strip().matches("\\d{1,18}")) {
                seen.add(Long.parseLong(n.asString().strip()));
            } else {
                errors.add(new FieldErrorDetail(field, "Type", null));
                return ids;
            }
        }
        ids.addAll(seen);
        return ids;
    }

    private static int intIn(JsonNode raw, String key, int def, int min, int max, String field, List<FieldErrorDetail> errors) {
        JsonNode n = raw.get(key);
        if (n == null || n.isNull()) {
            return def;
        }
        if (!n.isIntegralNumber() || n.asLong() < min || n.asLong() > max) {
            errors.add(new FieldErrorDetail(field, "Range", null));
            return def;
        }
        return n.asInt();
    }

    private static String text(JsonNode raw, String key) {
        JsonNode v = raw.get(key);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asString("").strip();
        return s.isEmpty() ? null : s;
    }

    private static void putOrNull(ObjectNode out, String key, String value) {
        if (value == null) {
            out.putNull(key);
        } else {
            out.put(key, value);
        }
    }

    private static URI parse(String url) {
        try {
            return new URI(url.strip());
        } catch (URISyntaxException ex) {
            return null;
        }
    }
}
