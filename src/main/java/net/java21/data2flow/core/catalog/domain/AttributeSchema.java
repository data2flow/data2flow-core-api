package net.java21.data2flow.core.catalog.domain;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/**
 * 모델 속성 스키마(DEV-07.05, API-DEV-42 {@code attributeSchema})를 읽은 결과. JSON Schema의 작은 부분집합이다(web과 같은 규칙):
 * <pre>{@code {"type":"object","properties":{"tempOffset":{"type":"number","unit":"℃","default":0,"title":"온도 보정"}},
 *  "required":["tempOffset"]}}</pre>
 * 타입은 string·number·integer·boolean만 쓴다.
 */
public record AttributeSchema(List<Field> fields) {

    public static final List<String> TYPES = List.of("string", "number", "integer", "boolean");

    public static AttributeSchema empty() {
        return new AttributeSchema(List.of());
    }

    public Optional<Field> field(String key) {
        return fields.stream().filter(f -> f.key().equals(key)).findFirst();
    }

    /** 속성 하나. {@code defaultValue}는 없으면 null */
    public record Field(String key, String type, String unit, JsonNode defaultValue, boolean required, String title) {

        /** 값이 이 필드 타입에 맞는가(number는 정수도 받는다) */
        public boolean accepts(JsonNode value) {
            return switch (type) {
                case "string" -> value.isString();
                case "number" -> value.isNumber();
                case "integer" -> value.isIntegralNumber() || (value.isNumber() && value.asDouble() == Math.rint(value.asDouble())
                        && !Double.isInfinite(value.asDouble()));
                case "boolean" -> value.isBoolean();
                default -> false;
            };
        }
    }
}
