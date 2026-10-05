package net.java21.data2flow.core.source.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 소스 payload 형식·토픽 템플릿 설정 판정(DSC-09.07·09.08, ADR-056, DSC domain-model §6.3). 결과는 API-DSC-50 {@code config.payload}·
 * {@code config.topicTemplate}로 ingress에 그대로 간다(변환은 ingress가 기록 직전에).
 * <ul>
 *   <li>{@code payload}: {@code {format, compression, schemaRef?, messageType?, registryUrl?, csv?: {delimiter, header}}}. format 기본 JSON
 *       ({@code sparkplug-b} 커넥터는 SPARKPLUG_B), compression 기본 NONE. 모르는 키는 거부</li>
 *   <li>PROTOBUF는 schemaRef 필수, AVRO는 schemaRef 또는 registryUrl(http·https). schemaRef는 이 조직이 올린 스키마여야 한다(API-DSC-59)</li>
 *   <li>{@code topicTemplate}: 256자 이하 문자열(문법은 ingress가 판정 — 미리보기 API-DSC-83). 빈 값·null이면 지운다</li>
 * </ul>
 * 위반은 400 SOURCE_CONFIG_INVALID + {@code errors[].field}({@code payload.format} 등).
 */
public final class PayloadSettings {

    public static final Set<String> FORMATS = Set.of("JSON", "CBOR", "MSGPACK", "PROTOBUF", "AVRO", "CSV", "TEXT", "BINARY", "SPARKPLUG_B");
    public static final Set<String> COMPRESSIONS = Set.of("NONE", "GZIP", "DEFLATE");
    static final Set<String> KEYS = Set.of("format", "compression", "schemaRef", "messageType", "registryUrl", "csv");
    public static final int MAX_TEMPLATE = 256;

    private PayloadSettings() {
    }

    /** 기본 형식(커넥터별) */
    public static String defaultFormat(String connectorKey) {
        return "sparkplug-b".equals(connectorKey) ? "SPARKPLUG_B" : "JSON";
    }

    /**
     * @param raw          요청의 payload(null이면 null을 돌려준다 — 설정 없음)
     * @param schemaExists 이 조직의 schemaRef인가
     */
    public static ObjectNode normalize(JsonNode raw, String connectorKey, Predicate<String> schemaExists) {
        if (raw == null || raw.isNull()) {
            return null;
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (!raw.isObject()) {
            throw invalid(List.of(new FieldErrorDetail("payload", "TYPE", "object")));
        }
        for (String key : raw.propertyNames()) {
            if (!KEYS.contains(key)) {
                errors.add(new FieldErrorDetail("payload." + key, "UNKNOWN_FIELD", null));
            }
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        String format = upper(raw.get("format"), defaultFormat(connectorKey));
        if (!FORMATS.contains(format)) {
            errors.add(new FieldErrorDetail("payload.format", "INVALID", String.join("|", FORMATS)));
        }
        String compression = upper(raw.get("compression"), "NONE");
        if (!COMPRESSIONS.contains(compression)) {
            errors.add(new FieldErrorDetail("payload.compression", "INVALID", "NONE|GZIP|DEFLATE"));
        }
        out.put("format", format);
        out.put("compression", compression);
        String schemaRef = text(raw.get("schemaRef"));
        if (schemaRef != null) {
            if (schemaRef.length() > 40 || !schemaExists.test(schemaRef)) {
                errors.add(new FieldErrorDetail("payload.schemaRef", "NOT_FOUND", null));
            }
            out.put("schemaRef", schemaRef);
        }
        String messageType = text(raw.get("messageType"));
        if (messageType != null) {
            if (messageType.length() > 200) {
                errors.add(new FieldErrorDetail("payload.messageType", "SIZE", "≤200"));
            }
            out.put("messageType", messageType);
        }
        String registryUrl = text(raw.get("registryUrl"));
        if (registryUrl != null) {
            if (!httpUrl(registryUrl)) {
                errors.add(new FieldErrorDetail("payload.registryUrl", "INVALID", "http|https"));
            }
            out.put("registryUrl", registryUrl);
        }
        if ("PROTOBUF".equals(format) && schemaRef == null) {
            errors.add(new FieldErrorDetail("payload.schemaRef", "REQUIRED", "PROTOBUF"));
        }
        if ("AVRO".equals(format) && schemaRef == null && registryUrl == null) {
            errors.add(new FieldErrorDetail("payload.schemaRef", "REQUIRED", "AVRO: schemaRef|registryUrl"));
        }
        JsonNode csv = raw.get("csv");
        if (csv != null && !csv.isNull()) {
            if (!csv.isObject()) {
                errors.add(new FieldErrorDetail("payload.csv", "TYPE", "object"));
            } else {
                String delimiter = csv.has("delimiter") ? csv.get("delimiter").asString("") : ",";
                if (delimiter.length() != 1) {
                    errors.add(new FieldErrorDetail("payload.csv.delimiter", "SIZE", "1"));
                }
                if (csv.has("header") && !csv.get("header").isBoolean()) {
                    errors.add(new FieldErrorDetail("payload.csv.header", "TYPE", "boolean"));
                }
                ObjectNode c = out.putObject("csv");
                c.put("delimiter", delimiter);
                c.put("header", csv.path("header").asBoolean(true));
            }
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        return out;
    }

    /** 토픽 템플릿(빈 값이면 null) */
    public static String topicTemplate(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            return null;
        }
        if (!raw.isString()) {
            throw invalid(List.of(new FieldErrorDetail("topicTemplate", "TYPE", "string")));
        }
        String t = raw.asString().strip();
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() > MAX_TEMPLATE) {
            throw invalid(List.of(new FieldErrorDetail("topicTemplate", "SIZE", "≤" + MAX_TEMPLATE)));
        }
        return t;
    }

    private static boolean httpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return value.length() <= 500 && uri.getHost() != null && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String upper(JsonNode v, String fallback) {
        String t = text(v);
        return t == null ? fallback : t.toUpperCase(Locale.ROOT);
    }

    private static String text(JsonNode v) {
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asString().strip();
        return s.isEmpty() ? null : s;
    }

    private static BusinessException invalid(List<FieldErrorDetail> errors) {
        return new BusinessException(SourceErrorCode.SOURCE_CONFIG_INVALID, errors, errors.getFirst().field());
    }
}
