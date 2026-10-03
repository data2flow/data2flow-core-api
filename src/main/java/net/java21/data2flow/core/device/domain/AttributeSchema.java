package net.java21.data2flow.core.device.domain;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 모델 속성 스키마의 작은 부분집합(DEV-07.05, 웹 {@code parseAttributeSchema}와 같은 모양):
 * {@code {type:"object", properties:{key:{type, unit?, default?, title?}}, required:[…], additionalProperties?}}.
 * type은 string·number·integer·boolean·object·array. 스키마가 형식에 맞지 않으면 검사하지 않는다(모델 저장 쪽 WP-B1이 검증).
 * 스키마에 없는 키는 {@code additionalProperties:false}일 때만 막는다(서버 속성에 설치일 같은 자유 키를 둘 수 있게).
 */
public final class AttributeSchema {

    private final Map<String, JsonNode> properties;
    private final Set<String> required;
    private final boolean closed;

    private AttributeSchema(Map<String, JsonNode> properties, Set<String> required, boolean closed) {
        this.properties = properties;
        this.required = required;
        this.closed = closed;
    }

    /** 스키마 JSON. 없거나 형식이 틀리면 아무것도 막지 않는 스키마 */
    public static AttributeSchema of(JsonNode schema) {
        Map<String, JsonNode> props = new LinkedHashMap<>();
        Set<String> required = new HashSet<>();
        if (schema == null || !schema.isObject() || schema.get("properties") == null || !schema.get("properties").isObject()) {
            return new AttributeSchema(props, required, false);
        }
        JsonNode p = schema.get("properties");
        for (String key : p.propertyNames()) {
            props.put(key, p.get(key));
        }
        JsonNode r = schema.get("required");
        if (r != null && r.isArray()) {
            r.values().forEach(v -> {
                if (v.isString()) {
                    required.add(v.stringValue());
                }
            });
        }
        JsonNode additional = schema.get("additionalProperties");
        return new AttributeSchema(props, required, additional != null && additional.isBoolean() && !additional.booleanValue());
    }

    /** 이 키에 이 값을 둘 수 있는가. value가 null(JSON null 포함)이면 지우기 */
    public boolean accepts(String key, JsonNode value) {
        boolean empty = value == null || value.isNull();
        if (empty) {
            return !required.contains(key) || defaultOf(key) != null;
        }
        JsonNode prop = properties.get(key);
        if (prop == null) {
            return !closed;
        }
        JsonNode type = prop.get("type");
        if (type == null || !type.isString()) {
            return true;
        }
        return switch (type.stringValue()) {
            case "string" -> value.isString();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber() || (value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0);
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> true;
        };
    }

    /** 기본값(없으면 null) */
    public JsonNode defaultOf(String key) {
        JsonNode prop = properties.get(key);
        JsonNode d = prop == null ? null : prop.get("default");
        return d == null || d.isNull() ? null : d;
    }

    /** 기본값이 있는 키들 */
    public Map<String, JsonNode> defaults() {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        properties.keySet().forEach(k -> {
            JsonNode d = defaultOf(k);
            if (d != null) {
                result.put(k, d);
            }
        });
        return result;
    }
}
