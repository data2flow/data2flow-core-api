package net.java21.data2flow.core.source.domain;

import net.java21.data2flow.contracts.error.FieldErrorDetail;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 커넥터 설정 스키마(JSON Schema 2020-12) 검증의 필요한 부분만 구현한 것(BR-DSC-22: 스키마로 검증하고 스키마에 없는 필드는 거부).
 * core-api 런타임에는 JSON Schema 라이브러리가 없어서(contracts의 networknt 의존성은 테스트 전용 선택 의존성) 커넥터 설정 폼이 쓰는
 * 키워드만 본다: {@code type, required, properties, additionalProperties(false), enum, const, pattern, minLength, maxLength,
 * minimum, maximum, minItems, maxItems, items}. 그 밖의 키워드는 무시한다(통과).
 */
public final class JsonSchemaLite {

    private JsonSchemaLite() {
    }

    /** 문제 목록(필드 경로 + 키워드). 비면 통과 */
    public static List<FieldErrorDetail> validate(JsonNode schema, JsonNode value, String path) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        check(schema, value, path, errors);
        return errors;
    }

    private static void check(JsonNode schema, JsonNode value, String path, List<FieldErrorDetail> errors) {
        if (schema == null || !schema.isObject()) {
            return;
        }
        JsonNode type = schema.get("type");
        if (type != null && !typeMatches(type, value)) {
            errors.add(new FieldErrorDetail(path, "Type", null));
            return;
        }
        JsonNode en = schema.get("enum");
        if (en != null && en.isArray() && !contains(en, value)) {
            errors.add(new FieldErrorDetail(path, "INVALID", null));
        }
        JsonNode cn = schema.get("const");
        if (cn != null && !cn.equals(value)) {
            errors.add(new FieldErrorDetail(path, "INVALID", null));
        }
        if (value.isString()) {
            string(schema, value.asString(), path, errors);
        }
        if (value.isNumber()) {
            number(schema, value.asDouble(), path, errors);
        }
        if (value.isArray()) {
            array(schema, value, path, errors);
        }
        if (value.isObject()) {
            object(schema, value, path, errors);
        }
    }

    private static void string(JsonNode schema, String s, String path, List<FieldErrorDetail> errors) {
        if (schema.has("minLength") && s.codePointCount(0, s.length()) < schema.get("minLength").asInt()) {
            errors.add(new FieldErrorDetail(path, "Size", null));
        }
        if (schema.has("maxLength") && s.codePointCount(0, s.length()) > schema.get("maxLength").asInt()) {
            errors.add(new FieldErrorDetail(path, "Size", null));
        }
        if (schema.has("pattern")) {
            try {
                if (!Pattern.compile(schema.get("pattern").asString()).matcher(s).find()) {
                    errors.add(new FieldErrorDetail(path, "Pattern", null));
                }
            } catch (PatternSyntaxException ex) {
                // 스키마 자체의 잘못된 정규식은 커넥터 쪽 문제라 사용자 입력을 막지 않는다
            }
        }
    }

    private static void number(JsonNode schema, double d, String path, List<FieldErrorDetail> errors) {
        if (schema.has("minimum") && d < schema.get("minimum").asDouble()) {
            errors.add(new FieldErrorDetail(path, "Range", null));
        }
        if (schema.has("maximum") && d > schema.get("maximum").asDouble()) {
            errors.add(new FieldErrorDetail(path, "Range", null));
        }
    }

    private static void array(JsonNode schema, JsonNode value, String path, List<FieldErrorDetail> errors) {
        if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
            errors.add(new FieldErrorDetail(path, "Size", null));
        }
        if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) {
            errors.add(new FieldErrorDetail(path, "Size", null));
        }
        JsonNode items = schema.get("items");
        if (items != null && items.isObject()) {
            for (int i = 0; i < value.size(); i++) {
                check(items, value.get(i), path + "[" + i + "]", errors);
            }
        }
    }

    private static void object(JsonNode schema, JsonNode value, String path, List<FieldErrorDetail> errors) {
        JsonNode props = schema.get("properties");
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode r : required) {
                JsonNode v = value.get(r.asString());
                if (v == null || v.isNull()) {
                    errors.add(new FieldErrorDetail(join(path, r.asString()), "NotNull", null));
                }
            }
        }
        boolean closed = schema.has("additionalProperties") && schema.get("additionalProperties").isBoolean()
                && !schema.get("additionalProperties").asBoolean();
        for (String key : value.propertyNames()) {
            JsonNode sub = props == null ? null : props.get(key);
            if (sub == null) {
                if (closed) {
                    errors.add(new FieldErrorDetail(join(path, key), "UNKNOWN_FIELD", null));
                }
                continue;
            }
            JsonNode v = value.get(key);
            if (v != null && !v.isNull()) {
                check(sub, v, join(path, key), errors);
            }
        }
    }

    private static String join(String path, String key) {
        return path == null || path.isEmpty() ? key : path + "." + key;
    }

    private static boolean contains(JsonNode array, JsonNode value) {
        for (JsonNode e : array) {
            if (e.equals(value) || (e.isNumber() && value.isNumber() && e.asDouble() == value.asDouble())) {
                return true;
            }
        }
        return false;
    }

    private static boolean typeMatches(JsonNode type, JsonNode value) {
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (typeMatches(t, value)) {
                    return true;
                }
            }
            return false;
        }
        return switch (type.asString()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber() || (value.isNumber() && value.asDouble() == Math.rint(value.asDouble()));
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }
}
