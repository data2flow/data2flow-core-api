package net.java21.data2flow.core.catalog.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.catalog.domain.AttributeSchema;
import net.java21.data2flow.core.catalog.domain.AttributeSchema.Field;
import net.java21.data2flow.core.catalog.domain.CatalogErrorCode;
import net.java21.data2flow.core.catalog.repository.DeviceModelRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 모델 속성 스키마(DEV-07.05)를 읽고 기기 속성 입력값을 검사한다. 기기 속성 저장(DEV-07.01, API-DEV-80)이 이 서비스를 부른다.
 *
 * <ul>
 *   <li>{@link #parse}: 모델 패키지 저장(API-DEV-42) 때 스키마 형식 검사. 틀리면 400 INVALID_REQUEST(errors[].field=attributeSchema)</li>
 *   <li>{@link #validateValue}: 속성 하나 저장 때 타입 검사. number 속성 {@code tempOffset}에 "abc"면 400 ATTRIBUTE_SCHEMA_VIOLATION</li>
 *   <li>{@link #validateAndApplyDefaults}: 여러 속성을 한 번에 넣을 때(수동 등록·CSV) 필수·타입 검사와 기본값 채우기</li>
 * </ul>
 * 스키마에 없는 키는 막지 않는다(서버 속성은 자유 키를 허용, DEV-07.01). 모델이 없거나 스키마가 없으면 검사하지 않는다.
 */
@Service
public class ModelAttributeSchemaValidator {

    /** 속성 키 형식(device_attributes.key, domain-model §2.11) */
    static final String KEY_PATTERN = "^[a-zA-Z][a-zA-Z0-9_.]{0,63}$";
    static final int MAX_FIELDS = 100;

    private final DeviceModelRepository models;
    private final JsonMapper json;

    public ModelAttributeSchemaValidator(DeviceModelRepository models, JsonMapper json) {
        this.models = models;
        this.json = json;
    }

    /** 스키마 JSON을 읽는다. null·JSON null이면 빈 스키마 */
    public AttributeSchema parse(JsonNode schema) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            return AttributeSchema.empty();
        }
        if (!schema.isObject()) {
            throw invalid("Type");
        }
        JsonNode type = schema.get("type");
        if (type != null && !type.isNull() && !"object".equals(type.asString(""))) {
            throw invalid("Type");
        }
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            throw invalid("Properties");
        }
        Set<String> required = new HashSet<>();
        JsonNode requiredNode = schema.get("required");
        if (requiredNode != null && !requiredNode.isNull()) {
            if (!requiredNode.isArray()) {
                throw invalid("Required");
            }
            for (JsonNode item : requiredNode.values()) {
                if (!item.isString()) {
                    throw invalid("Required");
                }
                required.add(item.asString());
            }
        }
        List<Field> fields = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : properties.properties()) {
            String key = entry.getKey();
            JsonNode prop = entry.getValue();
            if (!key.matches(KEY_PATTERN) || prop == null || !prop.isObject()) {
                throw invalid("Key", key);
            }
            String fieldType = prop.path("type").asString("");
            if (!AttributeSchema.TYPES.contains(fieldType)) {
                throw invalid("FieldType", key);
            }
            JsonNode defaultValue = prop.get("default");
            if (defaultValue != null && defaultValue.isNull()) {
                defaultValue = null;
            }
            String unit = prop.hasNonNull("unit") ? prop.get("unit").asString("") : null;
            String title = prop.hasNonNull("title") ? prop.get("title").asString("") : null;
            Field field = new Field(key, fieldType, unit, defaultValue, required.contains(key), title);
            if (defaultValue != null && !field.accepts(defaultValue)) {
                throw invalid("Default", key);
            }
            fields.add(field);
        }
        if (fields.size() > MAX_FIELDS) {
            throw invalid("Size");
        }
        for (String r : required) {
            if (fields.stream().noneMatch(f -> f.key().equals(r))) {
                throw invalid("Required", r);
            }
        }
        return new AttributeSchema(List.copyOf(fields));
    }

    /** 저장된 스키마 문자열(jsonb)을 읽는다 */
    public AttributeSchema parseStored(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return AttributeSchema.empty();
        }
        return parse(json.readTree(schemaJson));
    }

    /** 모델의 속성 스키마. 모델이 없으면 빈 스키마 */
    public AttributeSchema schemaOf(long organizationId, Long modelId) {
        if (modelId == null) {
            return AttributeSchema.empty();
        }
        return models.find(organizationId, modelId).map(m -> parseStored(m.attributeSchemaJson())).orElse(AttributeSchema.empty());
    }

    /**
     * 속성 하나를 검사한다. 스키마에 있는 키인데 타입이 맞지 않거나 필수 속성을 null로 지우면 400 ATTRIBUTE_SCHEMA_VIOLATION
     * (errors[0].field=키, code=Type|Required).
     */
    public void validateValue(long organizationId, Long modelId, String key, JsonNode value) {
        Field field = schemaOf(organizationId, modelId).field(key).orElse(null);
        if (field == null) {
            return;
        }
        if (value == null || value.isNull() || value.isMissingNode()) {
            if (field.required()) {
                throw violation(List.of(new FieldErrorDetail(key, "Required", null)));
            }
            return;
        }
        if (!field.accepts(value)) {
            throw violation(List.of(new FieldErrorDetail(key, "Type", field.type())));
        }
    }

    /**
     * 여러 속성을 검사하고, 값을 넣지 않은 속성에는 기본값을 채운 새 맵을 돌려준다(DEV-07.05 "값을 넣지 않은 속성은 기본값이 적용된다").
     * 필수 속성에 값도 기본값도 없으면 Required, 타입이 맞지 않으면 Type.
     */
    public Map<String, JsonNode> validateAndApplyDefaults(long organizationId, Long modelId, Map<String, JsonNode> values) {
        return applyDefaults(schemaOf(organizationId, modelId), values);
    }

    /** 이미 읽은 스키마로 검사·기본값 채우기 */
    public Map<String, JsonNode> applyDefaults(AttributeSchema schema, Map<String, JsonNode> values) {
        Map<String, JsonNode> result = new LinkedHashMap<>(values == null ? Map.of() : values);
        List<FieldErrorDetail> errors = new ArrayList<>();
        for (Field field : schema.fields()) {
            JsonNode value = result.get(field.key());
            if (value == null || value.isNull() || value.isMissingNode()) {
                if (field.defaultValue() != null) {
                    result.put(field.key(), field.defaultValue());
                } else if (field.required()) {
                    errors.add(new FieldErrorDetail(field.key(), "Required", null));
                }
                continue;
            }
            if (!field.accepts(value)) {
                errors.add(new FieldErrorDetail(field.key(), "Type", field.type()));
            }
        }
        if (!errors.isEmpty()) {
            throw violation(errors);
        }
        return result;
    }

    private static BusinessException violation(List<FieldErrorDetail> errors) {
        return new BusinessException(CatalogErrorCode.ATTRIBUTE_SCHEMA_VIOLATION, errors);
    }

    private static BusinessException invalid(String code) {
        return invalid(code, null);
    }

    private static BusinessException invalid(String code, String detail) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("attributeSchema", code, detail)));
    }
}
