package net.java21.data2flow.core.catalog;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.core.catalog.domain.AttributeSchema;
import net.java21.data2flow.core.catalog.domain.CatalogErrorCode;
import net.java21.data2flow.core.catalog.domain.CatalogModels.DeviceModel;
import net.java21.data2flow.core.catalog.repository.DeviceModelRepository;
import net.java21.data2flow.core.catalog.service.ModelAttributeSchemaValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** DEV-07.05 모델 속성 스키마와 기기 입력값 검증 — TC-DEV-198 (core 검증 도우미 부분) */
class ModelAttributeSchemaValidatorTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SCHEMA = """
            {"type":"object","properties":{
              "tempOffset":{"type":"number","unit":"℃","default":0,"title":"온도 보정"},
              "maxTemp":{"type":"integer"},
              "label":{"type":"string"},
              "enabled":{"type":"boolean","default":true}},
             "required":["tempOffset","maxTemp"]}""";

    final DeviceModelRepository models = mock(DeviceModelRepository.class);
    final ModelAttributeSchemaValidator validator = new ModelAttributeSchemaValidator(models, JSON);

    private static JsonNode node(String json) {
        return JSON.readTree(json);
    }

    private void modelWithSchema(String schema) {
        DeviceModel model = new DeviceModel(7, 1, "ESP32-TH", "자체", "ESP32", "MQTT", "SENSOR", 600, 3.0, null, null, false,
                null, null, null, null, List.of(), schema, "[]", "ACTIVE", 0, Instant.EPOCH);
        when(models.find(1, 7)).thenReturn(Optional.of(model));
    }

    @Test
    @DisplayName("[DEV-07.05] 스키마 읽기: 키·타입·단위·기본값·필수 — TC-DEV-198")
    void parse() {
        AttributeSchema schema = validator.parse(node(SCHEMA));
        assertThat(schema.fields()).extracting(AttributeSchema.Field::key).containsExactly("tempOffset", "maxTemp", "label", "enabled");
        AttributeSchema.Field offset = schema.field("tempOffset").orElseThrow();
        assertThat(offset.type()).isEqualTo("number");
        assertThat(offset.unit()).isEqualTo("℃");
        assertThat(offset.required()).isTrue();
        assertThat(offset.defaultValue().asInt()).isZero();
        assertThat(offset.title()).isEqualTo("온도 보정");
        assertThat(validator.parse(null).fields()).isEmpty();
        assertThat(validator.parse(node("null")).fields()).isEmpty();
        assertThat(validator.parseStored(null).fields()).isEmpty();
    }

    @Test
    @DisplayName("[DEV-07.05] 형식이 틀린 스키마는 400 INVALID_REQUEST(field=attributeSchema): 타입·필수·기본값·키 — TC-DEV-198")
    void invalidSchemas() {
        String[] bad = {"[]", "{\"type\":\"array\",\"properties\":{}}", "{\"type\":\"object\"}",
                "{\"properties\":{\"a\":{\"type\":\"date\"}}}", "{\"properties\":{\"a\":{\"type\":\"number\",\"default\":\"x\"}}}",
                "{\"properties\":{\"a\":{\"type\":\"number\"}},\"required\":[\"b\"]}",
                "{\"properties\":{\"a\":{\"type\":\"number\"}},\"required\":\"a\"}",
                "{\"properties\":{\"a\":{\"type\":\"number\"}},\"required\":[1]}",
                "{\"properties\":{\"1bad\":{\"type\":\"number\"}}}", "{\"properties\":{\"a\":1}}"};
        for (String raw : bad) {
            assertThatThrownBy(() -> validator.parse(node(raw))).as(raw).isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST);
                assertThat(ex.getErrors().get(0).field()).isEqualTo("attributeSchema");
            });
        }
    }

    @Test
    @DisplayName("[DEV-07.05] number 속성 tempOffset에 \"abc\"면 400 ATTRIBUTE_SCHEMA_VIOLATION, -0.5는 통과, 스키마 밖 키는 막지 않음 — TC-DEV-198")
    void validateValue() {
        modelWithSchema(SCHEMA);
        assertThatThrownBy(() -> validator.validateValue(1, 7L, "tempOffset", node("\"abc\"")))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(CatalogErrorCode.ATTRIBUTE_SCHEMA_VIOLATION);
                    assertThat(ex.getErrors().get(0).field()).isEqualTo("tempOffset");
                    assertThat(ex.getErrors().get(0).code()).isEqualTo("Type");
                });
        assertThatNoException().isThrownBy(() -> validator.validateValue(1, 7L, "tempOffset", node("-0.5")));
        assertThatNoException().isThrownBy(() -> validator.validateValue(1, 7L, "maxTemp", node("30.0")));
        assertThatThrownBy(() -> validator.validateValue(1, 7L, "maxTemp", node("30.5"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validateValue(1, 7L, "maxTemp", node("null")))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrors().get(0).code()).isEqualTo("Required"));
        assertThatNoException().isThrownBy(() -> validator.validateValue(1, 7L, "label", null));
        assertThatThrownBy(() -> validator.validateValue(1, 7L, "enabled", node("\"yes\""))).isInstanceOf(BusinessException.class);
        assertThatNoException().isThrownBy(() -> validator.validateValue(1, 7L, "freeKey", node("\"anything\"")));
        assertThatNoException().isThrownBy(() -> validator.validateValue(1, null, "tempOffset", node("\"abc\"")));
        assertThatNoException().isThrownBy(() -> validator.validateValue(1, 99L, "tempOffset", node("\"abc\"")));
    }

    @Test
    @DisplayName("[DEV-07.05] 값을 넣지 않은 속성은 기본값, 기본값 없는 필수 속성이 빠지면 Required — TC-DEV-198")
    void defaults() {
        modelWithSchema(SCHEMA);
        Map<String, JsonNode> values = new LinkedHashMap<>();
        values.put("maxTemp", node("28"));
        Map<String, JsonNode> applied = validator.validateAndApplyDefaults(1, 7L, values);
        assertThat(applied.get("tempOffset").asDouble()).isZero();
        assertThat(applied.get("enabled").asBoolean()).isTrue();
        assertThat(applied).doesNotContainKey("label");
        assertThatThrownBy(() -> validator.validateAndApplyDefaults(1, 7L, Map.of("label", node("1"))))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrors())
                        .extracting(e -> e.field() + ":" + e.code()).containsExactly("maxTemp:Required", "label:Type"));
        assertThat(validator.validateAndApplyDefaults(1, null, null)).isEmpty();
    }
}
