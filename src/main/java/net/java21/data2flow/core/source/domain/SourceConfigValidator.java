package net.java21.data2flow.core.source.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.message.SourceTypes;
import net.java21.data2flow.contracts.secret.SecretMasker;
import net.java21.data2flow.core.source.domain.SourceModels.SourceTopic;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 소스 설정 검증(UI-DSC-02 입력 표, domain-model §2.2, BR-DSC-01·06·22). 문제가 있으면 모은 뒤 한 번에
 * {@code SOURCE_CONFIG_INVALID}(400, {@code errors[{field, code}]})로 알린다. 비밀값 이름의 키(password·token 등)는
 * connection에 둘 수 없다(비밀값은 {@code secret}으로만, BR-DSC-02).
 *
 * <p>MQTT connection 필드 이름은 웹(UI-DSC-02)과 맞췄다: {@code url, clientIdBase, protocolVersion, qos, keepaliveSec, cleanStart,
 * sessionExpirySec, receiveMaximum, sharedGroup, retainHandling, auth, username, headerName, headerScheme, tlsInsecure}.
 * 구독 토픽은 connection이 아니라 {@code topics[]}(source_topics)에 둔다.
 */
public final class SourceConfigValidator {

    public static final Set<String> MQTT_KEYS = Set.of("url", "clientIdBase", "protocolVersion", "qos", "keepaliveSec", "cleanStart",
            "sessionExpirySec", "receiveMaximum", "sharedGroup", "retainHandling", "auth", "username", "headerName", "headerScheme",
            "tlsInsecure");
    public static final Set<String> PLATFORM_BROKER_KEYS = Set.of("deviceKeyPattern", "allowedFormats");
    public static final Set<String> SIMULATION_KEYS = Set.of("scenarioId");

    /** client-id base: contracts ClientIds가 받는 모양(소문자·숫자·하이픈) + 뒤에 붙는 {@code -{env}-{n}} 여유 */
    public static final Pattern CLIENT_ID_BASE = Pattern.compile("[a-z0-9][a-z0-9-]{0,80}");
    public static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{1,49}");
    public static final Pattern METRIC_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Pattern URL = Pattern.compile("(?i)(tcp|ssl|ws|wss)://([^/:\\s]+)(?::(\\d{1,5}))?(/\\S*)?");
    private static final Pattern SHARED_GROUP = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern TOPIC_REF = Pattern.compile("topic\\[(\\d{1,2})]");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Set<String> RETAIN = Set.of("SEND", "SEND_IF_SUBSCRIPTION_DOES_NOT_EXIST", "DO_NOT_SEND");
    private static final Set<String> TIME_FORMATS = Set.of("AUTO", "EPOCH_S", "EPOCH_MS", "ISO8601");
    private static final Set<String> GENERIC_JSON_KEYS = Set.of("deviceIdFrom", "timePath", "timeFormat", "metrics");
    private static final Set<String> GENERIC_JSON_METRIC_KEYS = Set.of("path", "key", "unit");
    private static final Set<String> SINGLE_VALUE_KEYS = Set.of("deviceIdFrom", "metricFrom", "metricKey");
    private static final int MAX_MAPPED_METRICS = 200;

    private final List<FieldErrorDetail> errors = new ArrayList<>();

    private SourceConfigValidator() {
    }

    public static SourceConfigValidator start() {
        return new SourceConfigValidator();
    }

    public SourceConfigValidator reject(String field, String code) {
        errors.add(new FieldErrorDetail(field, code, null));
        return this;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** 모은 문제가 있으면 SOURCE_CONFIG_INVALID */
    public void throwIfInvalid() {
        if (!errors.isEmpty()) {
            throw new BusinessException(SourceErrorCode.SOURCE_CONFIG_INVALID, List.copyOf(errors), errors.getFirst().field());
        }
    }

    /**
     * 유형별 connection 검증(기본 유형). 정리한 사본을 돌려준다(null이면 빈 객체).
     *
     * @param isDev TLS 검증 끄기 허용(BR-DSC-29). 아니면 tlsInsecure=true는 SOURCE_TLS_VERIFY_REQUIRED
     */
    public ObjectNode connection(String type, JsonNode raw, boolean isDev) {
        if (raw != null && !raw.isNull() && !raw.isObject()) {
            reject("connection", "Type");
            return JsonNodeFactory.instance.objectNode();
        }
        ObjectNode c = raw == null || raw.isNull() ? JsonNodeFactory.instance.objectNode() : ((ObjectNode) raw).deepCopy();
        for (String key : c.propertyNames()) {
            if (SecretMasker.isSensitiveKey(key)) {
                reject("connection." + key, "SECRET_FIELD");
            }
        }
        switch (type) {
            case SourceTypes.MQTT_SUBSCRIBE -> mqtt(c, isDev);
            case SourceTypes.PLATFORM_BROKER -> platformBroker(c);
            case SourceTypes.SIMULATION -> unknownKeys(c, SIMULATION_KEYS);
            default -> reject("type", "UNSUPPORTED");
        }
        return c;
    }

    private void mqtt(ObjectNode c, boolean isDev) {
        unknownKeys(c, MQTT_KEYS);
        String url = text(c, "url");
        if (url == null || !checkUrl(url)) {
            reject("connection.url", url == null ? "NotBlank" : "Pattern");
        }
        String base = text(c, "clientIdBase");
        if (base != null && !base.isEmpty() && !CLIENT_ID_BASE.matcher(base).matches()) {
            reject("connection.clientIdBase", "Pattern");
        }
        String version = text(c, "protocolVersion");
        if (version != null && !version.equals("5.0") && !version.equals("3.1.1")) {
            reject("connection.protocolVersion", "INVALID");
        }
        intRange(c, "qos", 0, 2);
        intRange(c, "keepaliveSec", 10, 600);
        longRange(c, "sessionExpirySec", 0, 4294967295L);
        intRange(c, "receiveMaximum", 1, 65535);
        bool(c, "cleanStart");
        String shared = text(c, "sharedGroup");
        if (shared != null && !shared.isEmpty()) {
            if (!SHARED_GROUP.matcher(shared).matches()) {
                reject("connection.sharedGroup", "Pattern");
            } else if ("3.1.1".equals(version)) {
                reject("connection.sharedGroup", "MQTT5_ONLY");
            }
        }
        String retain = text(c, "retainHandling");
        if (retain != null && !RETAIN.contains(retain)) {
            reject("connection.retainHandling", "INVALID");
        }
        String auth = text(c, "auth");
        if (auth == null) {
            c.put("auth", SourceModels.AUTH_NONE);
            auth = SourceModels.AUTH_NONE;
        }
        auth = auth.toUpperCase(Locale.ROOT);
        if (!SourceModels.AUTH_METHODS.contains(auth)) {
            throw new BusinessException(SourceErrorCode.SOURCE_AUTH_UNSUPPORTED, List.of(new FieldErrorDetail("connection.auth", "INVALID", null)));
        }
        c.put("auth", auth);
        if (SourceModels.AUTH_USERPASS.equals(auth)) {
            String username = text(c, "username");
            if (username == null || username.isBlank() || username.length() > 256) {
                reject("connection.username", "NotBlank");
            }
        }
        if (SourceModels.AUTH_HEADER.equals(auth)) {
            String scheme = url == null ? "" : url.toLowerCase(Locale.ROOT);
            if (!scheme.startsWith("ws://") && !scheme.startsWith("wss://")) {
                // HTTP 헤더 인증은 WebSocket(ws·wss) 업그레이드 요청에만 실을 수 있다
                throw new BusinessException(SourceErrorCode.SOURCE_AUTH_UNSUPPORTED,
                        List.of(new FieldErrorDetail("connection.auth", "WEBSOCKET_ONLY", null)));
            }
            String header = text(c, "headerName");
            if (header == null) {
                c.put("headerName", "Authorization");
            } else if (!HEADER_NAME.matcher(header).matches()) {
                reject("connection.headerName", "Pattern");
            }
        }
        if (bool(c, "tlsInsecure") && !isDev) {
            throw new BusinessException(SourceErrorCode.SOURCE_TLS_VERIFY_REQUIRED,
                    List.of(new FieldErrorDetail("connection.tlsInsecure", "TLS_VERIFY_REQUIRED", null)));
        }
    }

    private void platformBroker(ObjectNode c) {
        unknownKeys(c, PLATFORM_BROKER_KEYS);
        String pattern = text(c, "deviceKeyPattern");
        if (pattern == null) {
            c.put("deviceKeyPattern", "{externalId}");
        } else if (pattern.isBlank() || pattern.length() > 64 || !pattern.contains("{externalId}")) {
            reject("connection.deviceKeyPattern", "Pattern");
        }
        JsonNode formats = c.get("allowedFormats");
        if (formats != null && !formats.isNull()) {
            if (!formats.isArray()) {
                reject("connection.allowedFormats", "Type");
            } else {
                for (JsonNode f : formats) {
                    if (!f.isString() || !Set.of("CANONICAL", "GENERIC_JSON").contains(f.asString())) {
                        reject("connection.allowedFormats", "INVALID");
                    }
                }
            }
        }
    }

    /**
     * 구독 토픽(BR-DSC-06): 1~256자 MQTT 토픽 필터, {@code #}은 마지막 단계에만, {@code +}는 단계 전체로만, QoS 0·1·2, 중복 금지.
     * 개수 한도({@code maxTopics}) 초과는 SOURCE_LIMIT_EXCEEDED.
     */
    public List<SourceTopic> topics(JsonNode raw, int defaultQos, int maxTopics, boolean required) {
        List<SourceTopic> result = new ArrayList<>();
        if (raw == null || raw.isNull()) {
            if (required) {
                reject("topics", "NotEmpty");
            }
            return result;
        }
        if (!raw.isArray()) {
            reject("topics", "Type");
            return result;
        }
        if (raw.size() > maxTopics) {
            throw new BusinessException(SourceErrorCode.SOURCE_LIMIT_EXCEEDED, List.of(new FieldErrorDetail("topics", "MAX_TOPICS", null)));
        }
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (JsonNode t : raw) {
            String field = "topics[" + i + "]";
            String topic = t.isString() ? t.asString() : text(t, "topic");
            int qos = defaultQos;
            if (t.isObject() && t.has("qos") && !t.get("qos").isNull()) {
                JsonNode q = t.get("qos");
                qos = q.isIntegralNumber() ? q.asInt() : -1;
            }
            if (topic == null || !checkTopic(topic)) {
                reject(field + ".topic", "Pattern");
            } else if (!seen.add(topic)) {
                reject(field + ".topic", "DUPLICATE");
            }
            if (qos < 0 || qos > 2) {
                reject(field + ".qos", "Range");
            }
            result.add(new SourceTopic(topic, qos));
            i++;
        }
        if (result.isEmpty() && required) {
            reject("topics", "NotEmpty");
        }
        return result;
    }

    /**
     * 디코더 설정(DSC-01.06, 32KB 이하). generic-json은 매핑이 반드시 있어야 하고, single-value는 선택, 나머지는 설정을 쓰지 않는다(null).
     * <pre>
     * generic-json: {"deviceIdFrom": "topic[1]" | "$.dev", "timePath"?: "$.ts", "timeFormat"?: AUTO|EPOCH_S|EPOCH_MS|ISO8601,
     *                "metrics": [{"path": "$.temp", "key": "temperature", "unit"?: "°C"}]}   (1~200개, key 중복 금지)
     * single-value: {"deviceIdFrom"?: "topic[1]", "metricFrom"?: "topic[2]", "metricKey"?: "temperature"}
     * </pre>
     */
    public JsonNode decoderConfig(String decoderKey, JsonNode raw) {
        if (raw != null && !raw.isNull()) {
            int size = raw.toString().getBytes(StandardCharsets.UTF_8).length;
            if (size > SourceModels.MAX_DECODER_CONFIG_BYTES) {
                reject("decoderConfig", "MAX_SIZE");
                return raw;
            }
        }
        switch (decoderKey) {
            case SourceModels.DECODER_GENERIC_JSON -> {
                if (raw == null || raw.isNull() || !raw.isObject()) {
                    reject("decoderConfig", raw == null || raw.isNull() ? "NotNull" : "Type");
                    return raw;
                }
                genericJson(raw);
                return raw;
            }
            case SourceModels.DECODER_SINGLE_VALUE -> {
                if (raw == null || raw.isNull()) {
                    return null;
                }
                if (!raw.isObject()) {
                    reject("decoderConfig", "Type");
                    return raw;
                }
                unknownKeys(raw, SINGLE_VALUE_KEYS, "decoderConfig.");
                topicRef(raw, "deviceIdFrom");
                topicRef(raw, "metricFrom");
                String key = text(raw, "metricKey");
                if (key != null && !METRIC_KEY.matcher(key).matches()) {
                    reject("decoderConfig.metricKey", "Pattern");
                }
                return raw;
            }
            default -> {
                return null;
            }
        }
    }

    private void genericJson(JsonNode c) {
        unknownKeys(c, GENERIC_JSON_KEYS, "decoderConfig.");
        String device = text(c, "deviceIdFrom");
        if (device == null || device.isBlank()) {
            reject("decoderConfig.deviceIdFrom", "NotBlank");
        } else if (!TOPIC_REF.matcher(device).matches() && !JsonPathLite.valid(device)) {
            reject("decoderConfig.deviceIdFrom", "PATH");
        }
        String timePath = text(c, "timePath");
        if (timePath != null && !timePath.isEmpty() && !JsonPathLite.valid(timePath)) {
            reject("decoderConfig.timePath", "PATH");
        }
        String timeFormat = text(c, "timeFormat");
        if (timeFormat != null && !TIME_FORMATS.contains(timeFormat)) {
            reject("decoderConfig.timeFormat", "INVALID");
        }
        JsonNode metrics = c.get("metrics");
        if (metrics == null || !metrics.isArray() || metrics.isEmpty()) {
            reject("decoderConfig.metrics", "NotEmpty");
            return;
        }
        if (metrics.size() > MAX_MAPPED_METRICS) {
            reject("decoderConfig.metrics", "MAX_SIZE");
            return;
        }
        Set<String> keys = new HashSet<>();
        int i = 0;
        for (JsonNode m : metrics) {
            String field = "decoderConfig.metrics[" + i++ + "]";
            if (!m.isObject()) {
                reject(field, "Type");
                continue;
            }
            unknownKeys(m, GENERIC_JSON_METRIC_KEYS, field + ".");
            String path = text(m, "path");
            if (path == null || !JsonPathLite.valid(path)) {
                reject(field + ".path", "PATH");
            }
            String key = text(m, "key");
            if (key == null || !METRIC_KEY.matcher(key).matches()) {
                reject(field + ".key", "Pattern");
            } else if (!keys.add(key)) {
                reject(field + ".key", "DUPLICATE");
            }
            String unit = text(m, "unit");
            if (unit != null && unit.length() > 16) {
                reject(field + ".unit", "Size");
            }
        }
    }

    private void topicRef(JsonNode c, String field) {
        String value = text(c, field);
        if (value != null && !TOPIC_REF.matcher(value).matches()) {
            reject("decoderConfig." + field, "Pattern");
        }
    }

    /** 브로커 주소: tcp·ssl·ws·wss 스킴, 호스트 필수, 포트 1~65535 */
    public static boolean checkUrl(String raw) {
        var m = URL.matcher(raw.strip());
        if (!m.matches()) {
            return false;
        }
        if (m.group(3) != null) {
            int port = Integer.parseInt(m.group(3));
            if (port < 1 || port > 65535) {
                return false;
            }
        }
        try {
            return new URI(raw.strip()).getHost() != null;
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    /** MQTT 토픽 필터: 1~256자, {@code #}은 마지막 단계에만 홀로, {@code +}는 단계 전체로만, 공유 구독 접두사는 sharedGroup으로 */
    public static boolean checkTopic(String topic) {
        if (topic.isEmpty() || topic.length() > 256 || topic.startsWith("$share/") || topic.indexOf('\0') >= 0) {
            return false;
        }
        String[] levels = topic.split("/", -1);
        for (int i = 0; i < levels.length; i++) {
            String level = levels[i];
            if (level.contains("#") && (!level.equals("#") || i != levels.length - 1)) {
                return false;
            }
            if (level.contains("+") && !level.equals("+")) {
                return false;
            }
        }
        return true;
    }

    /** 토픽 필터에 토픽이 맞는가(실시간 메시지 보기 토픽 필터, DSC-02.06) */
    public static boolean topicMatches(String filter, String topic) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        if (topic == null) {
            return false;
        }
        String[] f = filter.split("/", -1);
        String[] t = topic.split("/", -1);
        for (int i = 0; i < f.length; i++) {
            if (f[i].equals("#")) {
                return true;
            }
            if (i >= t.length) {
                return false;
            }
            if (!f[i].equals("+") && !f[i].equals(t[i])) {
                return false;
            }
        }
        return f.length == t.length;
    }

    private void unknownKeys(JsonNode c, Set<String> allowed) {
        unknownKeys(c, allowed, "connection.");
    }

    private void unknownKeys(JsonNode c, Set<String> allowed, String prefix) {
        for (String key : c.propertyNames()) {
            if (!allowed.contains(key) && !SecretMasker.isSensitiveKey(key)) {
                reject(prefix + key, "UNKNOWN_FIELD");
            }
        }
    }

    private void intRange(ObjectNode c, String field, int min, int max) {
        longRange(c, field, min, max);
    }

    private void longRange(ObjectNode c, String field, long min, long max) {
        JsonNode v = c.get(field);
        if (v == null || v.isNull()) {
            return;
        }
        if (!v.isIntegralNumber() || v.asLong() < min || v.asLong() > max) {
            reject("connection." + field, "Range");
        }
    }

    private boolean bool(ObjectNode c, String field) {
        JsonNode v = c.get(field);
        if (v == null || v.isNull()) {
            return false;
        }
        if (!v.isBoolean()) {
            reject("connection." + field, "Type");
            return false;
        }
        return v.asBoolean();
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.isValueNode() ? v.asString("") : null;
    }

    /** 응답 없이 쓰는 요약: 문제 목록(테스트·로그용) */
    public List<FieldErrorDetail> errors() {
        return List.copyOf(errors);
    }
}
