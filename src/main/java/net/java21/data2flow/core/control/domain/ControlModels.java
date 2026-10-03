package net.java21.data2flow.core.control.domain;

import net.java21.data2flow.contracts.capability.AttributeConstraint;
import net.java21.data2flow.contracts.capability.AttributeType;
import net.java21.data2flow.contracts.capability.CapabilityAttribute;
import net.java21.data2flow.contracts.capability.CapabilityCatalog;
import net.java21.data2flow.contracts.capability.CapabilityCommand;
import net.java21.data2flow.contracts.capability.CapabilityDefinition;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 제어 정의 도우미(ACT-01.03·06.04, BR-ACT-01·09).
 *
 * <ul>
 *   <li>모델 제약: {@code device_models.capabilities} jsonb {@code [{capability, constraints}]}. {@code constraints}는 속성별
 *       {@code {속성: {min, max, enum}}}이다. M2에 저장된 {@code {min, max}}(속성 이름 없음) 모양은 그 기능의 첫 숫자 쓰기 속성
 *       (예: Thermostat의 targetTemperature) 제약으로 읽는다</li>
 *   <li>조직 절대 한계: {@code control_settings.absolute_limits} {@code {기능: {속성: {min, max, enum}}}}</li>
 * </ul>
 */
public final class ControlModels {

    /** 드라이버 종류(API-ACT-30) */
    public static final Set<String> DRIVER_TYPES = Set.of("VIRTUAL", "MQTT", "LORAWAN", "LG_THINQ", "SMARTTHINGS");
    /** 드라이버 종류별 지원 기능(선언). 가상 장비·MQTT 일반 드라이버는 표준 기능 전부(장비 쪽 구현이 정함) */
    private static final List<String> ALL_STANDARD = List.of("Switch", "Thermostat", "FanSpeed", "Ventilation", "Dimmer", "Lock",
            "Contact");

    private ControlModels() {
    }

    /** 드라이버 종류의 기본 지원 기능 */
    public static List<String> defaultCapabilities(String type) {
        return switch (type) {
            case "LG_THINQ" -> List.of("Switch", "Thermostat", "FanSpeed");
            case "SMARTTHINGS" -> List.of("Switch", "Dimmer", "Lock", "Contact", "Thermostat");
            default -> ALL_STANDARD;
        };
    }

    /** 모델 jsonb의 기능 이름 목록(순서 유지) */
    public static List<String> capabilityNames(JsonNode modelCapabilities) {
        if (modelCapabilities == null || !modelCapabilities.isArray()) {
            return List.of();
        }
        return modelCapabilities.values().stream().map(n -> n.path("capability").asString(""))
                .filter(s -> !s.isBlank()).distinct().toList();
    }

    /** 기능 하나의 모델 제약(속성 → 제약). 없으면 빈 맵 */
    public static Map<String, AttributeConstraint> modelConstraints(JsonNode modelCapabilities, CapabilityDefinition definition) {
        if (modelCapabilities == null || !modelCapabilities.isArray() || definition == null) {
            return Map.of();
        }
        for (JsonNode item : modelCapabilities.values()) {
            if (definition.name().equals(item.path("capability").asString(""))) {
                return constraints(item.get("constraints"), definition);
            }
        }
        return Map.of();
    }

    /** {@code {속성: 제약}} 또는 M2 모양 {@code {min, max, enum}}을 읽는다 */
    public static Map<String, AttributeConstraint> constraints(JsonNode node, CapabilityDefinition definition) {
        Map<String, AttributeConstraint> result = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return result;
        }
        boolean flat = node.has("min") || node.has("max") || node.has("enum");
        if (flat) {
            primaryNumeric(definition).ifPresent(a -> result.put(a.name(), constraint(node)));
            return result;
        }
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (e.getValue() != null && e.getValue().isObject()) {
                result.put(e.getKey(), constraint(e.getValue()));
            }
        }
        return result;
    }

    /** 조직 절대 한계 중 기능 하나(속성 → 제약) */
    public static Map<String, AttributeConstraint> absoluteLimits(JsonNode limits, String capability) {
        Map<String, AttributeConstraint> result = new LinkedHashMap<>();
        JsonNode node = limits == null ? null : limits.get(capability);
        if (node == null || !node.isObject()) {
            return result;
        }
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (e.getValue() != null && e.getValue().isObject()) {
                result.put(e.getKey(), constraint(e.getValue()));
            }
        }
        return result;
    }

    public static AttributeConstraint constraint(JsonNode node) {
        Double min = node.hasNonNull("min") && node.get("min").isNumber() ? node.get("min").asDouble() : null;
        Double max = node.hasNonNull("max") && node.get("max").isNumber() ? node.get("max").asDouble() : null;
        List<String> values = null;
        if (node.hasNonNull("enum") && node.get("enum").isArray()) {
            values = node.get("enum").values().stream().map(v -> v.asString("")).toList();
        }
        try {
            return new AttributeConstraint(min, max, values);
        } catch (IllegalArgumentException ex) {
            throw invalid("constraints", "RANGE", ex.getMessage());
        }
    }

    /** 첫 숫자 쓰기 속성(명령이 설정하는 속성 중) */
    static Optional<CapabilityAttribute> primaryNumeric(CapabilityDefinition definition) {
        if (definition == null) {
            return Optional.empty();
        }
        for (CapabilityCommand command : definition.commands()) {
            for (String set : command.sets()) {
                Optional<CapabilityAttribute> a = definition.attribute(set);
                if (a.isPresent() && (a.get().type() == AttributeType.NUMBER || a.get().type() == AttributeType.INTEGER)) {
                    return a;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 조직 절대 한계 검사(BR-ACT-09): 기능·속성이 카탈로그에 있고, 각 한계가 표준 범위와 그 기능을 지원하는 모델들의 제약 안에 있어야 한다.
     * 넓으면 400 LIMIT_WIDER_THAN_MODEL. 반환값은 정규화한 한계 JSON 맵.
     */
    public static Map<String, Map<String, AttributeConstraint>> validateLimits(JsonNode limits, CapabilityCatalog catalog,
                                                                                List<JsonNode> modelCapabilities) {
        Map<String, Map<String, AttributeConstraint>> result = new LinkedHashMap<>();
        if (limits == null || limits.isNull()) {
            return result;
        }
        if (!limits.isObject()) {
            throw invalid("absoluteLimits", "TYPE", null);
        }
        for (Map.Entry<String, JsonNode> cap : limits.properties()) {
            CapabilityDefinition def = catalog.find(cap.getKey())
                    .orElseThrow(() -> invalid("absoluteLimits." + cap.getKey(), ControlErrorCode.CAPABILITY_NOT_SUPPORTED.name(), null));
            if (!cap.getValue().isObject()) {
                throw invalid("absoluteLimits." + cap.getKey(), "TYPE", null);
            }
            Map<String, AttributeConstraint> attrs = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> attr : cap.getValue().properties()) {
                String field = "absoluteLimits." + cap.getKey() + "." + attr.getKey();
                CapabilityAttribute a = def.attribute(attr.getKey()).orElseThrow(() -> invalid(field, "UNKNOWN_ATTRIBUTE", null));
                if (!attr.getValue().isObject()) {
                    throw invalid(field, "TYPE", null);
                }
                AttributeConstraint limit = constraint(attr.getValue());
                if (!limit.within(a.range())) {
                    throw new BusinessException(ControlErrorCode.LIMIT_WIDER_THAN_MODEL,
                            List.of(new FieldErrorDetail(field, ControlErrorCode.LIMIT_WIDER_THAN_MODEL.name(), null)));
                }
                for (JsonNode model : modelCapabilities) {
                    AttributeConstraint modelRange = modelConstraints(model, def).get(a.name());
                    if (modelRange != null && !limit.within(modelRange)) {
                        throw new BusinessException(ControlErrorCode.LIMIT_WIDER_THAN_MODEL,
                                List.of(new FieldErrorDetail(field, ControlErrorCode.LIMIT_WIDER_THAN_MODEL.name(), null)));
                    }
                }
                attrs.put(a.name(), limit);
            }
            result.put(def.name(), attrs);
        }
        return result;
    }

    /** API-ACT-03 effectiveConstraints: 표준 범위 ∩ 모델 제약 ∩ 조직 한계 */
    public static Map<String, AttributeConstraint> effective(CapabilityDefinition definition, Map<String, AttributeConstraint> model,
                                                             Map<String, AttributeConstraint> limits) {
        Map<String, AttributeConstraint> result = new LinkedHashMap<>();
        for (CapabilityAttribute a : definition.attributes()) {
            if (Boolean.TRUE.equals(a.readOnly())) {
                continue;
            }
            AttributeConstraint c = a.range();
            try {
                c = c.intersect(model.get(a.name())).intersect(limits.get(a.name()));
            } catch (IllegalArgumentException ex) {
                c = AttributeConstraint.range(0, 0);
            }
            if (!c.isEmpty()) {
                result.put(a.name(), c);
            }
        }
        return result;
    }

    public static Map<String, Object> toJson(Map<String, AttributeConstraint> constraints, JsonMapper json) {
        Map<String, Object> out = new LinkedHashMap<>();
        constraints.forEach((k, v) -> out.put(k, json.valueToTree(v)));
        return out;
    }

    public static BusinessException invalid(String field, String code, String message) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, message)));
    }
}
