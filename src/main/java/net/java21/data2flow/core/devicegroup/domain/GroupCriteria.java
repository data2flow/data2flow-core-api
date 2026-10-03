package net.java21.data2flow.core.devicegroup.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 동적 그룹 조건(DEV-06.02, domain-model §2.10 {@code criteria}). 문서 모양
 * {@code {models[], spaceIds[], includeDescendants, tags{any[],all[]}, status[], attributes[{key,op,value}]}}과 화면 모양
 * {@code {modelIds[], spaceIds[], includeDescendants, tags{match:any|all, values[]}, statuses[]}}을 모두 받는다(저장은 받은 그대로).
 * 모르는 키는 400 INVALID_REQUEST(TC-DEV-169). 조건이 하나도 없으면 400.
 *
 * @param modelIds    모델 ID(models에 숫자로 온 값 포함)
 * @param modelCodes  모델 코드(models에 문자로 온 값)
 * @param statuses    기기 상태(PENDING·ACTIVE·INACTIVE). 비면 모든 상태(삭제 제외)
 */
public record GroupCriteria(List<Long> modelIds, List<String> modelCodes, List<Long> spaceIds, boolean includeDescendants,
                            List<String> tagsAny, List<String> tagsAll, List<String> statuses, List<AttributeCondition> attributes) {

    private static final Set<String> KEYS = Set.of("models", "modelIds", "spaceIds", "includeDescendants", "tags", "status", "statuses",
            "attributes");
    private static final Set<String> STATUSES = Set.of("PENDING", "ACTIVE", "INACTIVE");
    private static final Map<String, String> OPS = Map.ofEntries(Map.entry("EQ", "EQ"), Map.entry("=", "EQ"), Map.entry("==", "EQ"),
            Map.entry("NE", "NE"), Map.entry("!=", "NE"), Map.entry("GT", "GT"), Map.entry(">", "GT"), Map.entry("GTE", "GTE"),
            Map.entry(">=", "GTE"), Map.entry("LT", "LT"), Map.entry("<", "LT"), Map.entry("LTE", "LTE"), Map.entry("<=", "LTE"),
            Map.entry("EXISTS", "EXISTS"));

    /** 속성 조건. op는 EQ·NE·GT·GTE·LT·LTE·EXISTS, valueJson은 비교할 JSON 값(EXISTS면 null) */
    public record AttributeCondition(String key, String op, String valueJson, BigDecimal number) {
    }

    public int size() {
        return modelIds.size() + modelCodes.size() + spaceIds.size() + tagsAny.size() + tagsAll.size() + statuses.size()
                + attributes.size();
    }

    public static GroupCriteria parse(JsonNode node, String field) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (node == null || node.isNull() || !node.isObject()) {
            throw invalid(field, "REQUIRED");
        }
        for (String key : node.propertyNames()) {
            if (!KEYS.contains(key)) {
                errors.add(new FieldErrorDetail(field + "." + key, "UNKNOWN_FIELD", null));
            }
        }
        List<Long> modelIds = new ArrayList<>();
        List<String> modelCodes = new ArrayList<>();
        for (String name : List.of("models", "modelIds")) {
            for (JsonNode item : array(node, name, field, errors)) {
                String value = scalar(item);
                if (value == null || value.isBlank()) {
                    errors.add(new FieldErrorDetail(field + "." + name, "INVALID", null));
                } else if (value.matches("\\d{1,18}")) {
                    modelIds.add(Long.valueOf(value));
                } else {
                    modelCodes.add(value);
                }
            }
        }
        List<Long> spaceIds = new ArrayList<>();
        for (JsonNode item : array(node, "spaceIds", field, errors)) {
            String value = scalar(item);
            if (value == null || !value.matches("\\d{1,18}")) {
                errors.add(new FieldErrorDetail(field + ".spaceIds", "INVALID", null));
            } else {
                spaceIds.add(Long.valueOf(value));
            }
        }
        boolean includeDescendants = true;
        JsonNode inc = node.get("includeDescendants");
        if (inc != null && !inc.isNull()) {
            if (!inc.isBoolean()) {
                errors.add(new FieldErrorDetail(field + ".includeDescendants", "INVALID", null));
            } else {
                includeDescendants = inc.booleanValue();
            }
        }
        List<String> any = new ArrayList<>();
        List<String> all = new ArrayList<>();
        JsonNode tags = node.get("tags");
        if (tags != null && !tags.isNull()) {
            if (!tags.isObject()) {
                errors.add(new FieldErrorDetail(field + ".tags", "INVALID", null));
            } else if (tags.has("match") || tags.has("values")) {
                String match = scalar(tags.get("match"));
                List<String> values = strings(tags.get("values"), field + ".tags.values", errors);
                if (match == null || "any".equalsIgnoreCase(match)) {
                    any.addAll(values);
                } else if ("all".equalsIgnoreCase(match)) {
                    all.addAll(values);
                } else {
                    errors.add(new FieldErrorDetail(field + ".tags.match", "INVALID", null));
                }
            } else {
                for (String key : tags.propertyNames()) {
                    if (!"any".equals(key) && !"all".equals(key)) {
                        errors.add(new FieldErrorDetail(field + ".tags." + key, "UNKNOWN_FIELD", null));
                    }
                }
                any.addAll(strings(tags.get("any"), field + ".tags.any", errors));
                all.addAll(strings(tags.get("all"), field + ".tags.all", errors));
            }
        }
        List<String> statuses = new ArrayList<>();
        for (String name : List.of("status", "statuses")) {
            for (JsonNode item : array(node, name, field, errors)) {
                String value = scalar(item);
                String status = value == null ? "" : value.toUpperCase(Locale.ROOT);
                if (!STATUSES.contains(status)) {
                    errors.add(new FieldErrorDetail(field + "." + name, "INVALID", value));
                } else if (!statuses.contains(status)) {
                    statuses.add(status);
                }
            }
        }
        List<AttributeCondition> attributes = new ArrayList<>();
        int i = 0;
        for (JsonNode item : array(node, "attributes", field, errors)) {
            String path = field + ".attributes[" + i++ + "]";
            if (!item.isObject()) {
                errors.add(new FieldErrorDetail(path, "INVALID", null));
                continue;
            }
            String key = scalar(item.get("key"));
            String op = OPS.get(scalar(item.get("op")) == null ? "EQ" : scalar(item.get("op")).toUpperCase(Locale.ROOT));
            JsonNode value = item.get("value");
            if (key == null || !key.matches("^[a-zA-Z][a-zA-Z0-9_.]{0,63}$")) {
                errors.add(new FieldErrorDetail(path + ".key", "INVALID", null));
            } else if (op == null) {
                errors.add(new FieldErrorDetail(path + ".op", "INVALID", null));
            } else if (op.equals("EXISTS")) {
                attributes.add(new AttributeCondition(key, op, null, null));
            } else if (value == null || value.isMissingNode()) {
                errors.add(new FieldErrorDetail(path + ".value", "REQUIRED", null));
            } else if (Set.of("GT", "GTE", "LT", "LTE").contains(op)) {
                if (!value.isNumber()) {
                    errors.add(new FieldErrorDetail(path + ".value", "NUMBER_REQUIRED", null));
                } else {
                    attributes.add(new AttributeCondition(key, op, value.toString(), value.decimalValue()));
                }
            } else {
                attributes.add(new AttributeCondition(key, op, value.toString(), null));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        GroupCriteria criteria = new GroupCriteria(List.copyOf(modelIds), List.copyOf(modelCodes), List.copyOf(spaceIds),
                includeDescendants, List.copyOf(any), List.copyOf(all), List.copyOf(statuses), List.copyOf(attributes));
        if (criteria.size() == 0) {
            throw invalid(field, "EMPTY");
        }
        return criteria;
    }

    private static Iterable<JsonNode> array(JsonNode node, String name, String field, List<FieldErrorDetail> errors) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            errors.add(new FieldErrorDetail(field + "." + name, "INVALID", null));
            return List.of();
        }
        return value.values();
    }

    private static List<String> strings(JsonNode value, String path, List<FieldErrorDetail> errors) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isNull()) {
            return result;
        }
        if (!value.isArray()) {
            errors.add(new FieldErrorDetail(path, "INVALID", null));
            return result;
        }
        for (JsonNode item : value.values()) {
            String s = scalar(item);
            if (s == null || s.isBlank() || s.length() > 40) {
                errors.add(new FieldErrorDetail(path, "INVALID", null));
            } else {
                result.add(s.strip());
            }
        }
        return result;
    }

    private static String scalar(JsonNode item) {
        if (item == null || item.isNull()) {
            return null;
        }
        if (item.isString()) {
            return item.stringValue();
        }
        if (item.isNumber() || item.isBoolean()) {
            return item.toString();
        }
        return null;
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
