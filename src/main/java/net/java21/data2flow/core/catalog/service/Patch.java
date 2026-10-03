package net.java21.data2flow.core.catalog.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * PATCH 본문(JsonNode, api-rules §2: 온 키만 바꾼다) 읽기 도우미. 키가 없으면 현재 값, JSON null이면 null(지움)이다.
 * 형식이 틀린 필드는 모았다가 {@link #throwIfInvalid()}에서 한 번에 400 INVALID_REQUEST(errors[])로 돌려준다.
 */
final class Patch {

    private final JsonNode body;
    private final List<FieldErrorDetail> errors = new ArrayList<>();

    Patch(JsonNode body) {
        this.body = body;
    }

    boolean has(String field) {
        return body != null && body.has(field);
    }

    JsonNode node(String field) {
        return body == null ? null : body.get(field);
    }

    void error(String field, String code, String message) {
        errors.add(new FieldErrorDetail(field, code, message));
    }

    /** 문자열. required면 null·빈 값 금지, maxLength 초과 금지. 빈 문자열은 null(선택 필드) */
    String text(String field, String current, int maxLength, boolean required) {
        if (!has(field)) {
            return current;
        }
        JsonNode node = body.get(field);
        if (node.isNull()) {
            if (required) {
                error(field, "NotBlank", null);
                return current;
            }
            return null;
        }
        if (!node.isString()) {
            error(field, "Type", null);
            return current;
        }
        String value = node.asString().strip();
        if (value.isEmpty()) {
            if (required) {
                error(field, "NotBlank", null);
                return current;
            }
            return null;
        }
        if (value.length() > maxLength) {
            error(field, "Size", "≤" + maxLength);
            return current;
        }
        return value;
    }

    /** 허용 값 중 하나(대문자 enum) */
    String choice(String field, String current, List<String> allowed) {
        if (!has(field)) {
            return current;
        }
        JsonNode node = body.get(field);
        String value = node.isString() ? node.asString().strip() : null;
        if (value == null || !allowed.contains(value)) {
            error(field, "Invalid", String.join("|", allowed));
            return current;
        }
        return value;
    }

    Integer integer(String field, Integer current, int min, int max, boolean nullable) {
        if (!has(field)) {
            return current;
        }
        JsonNode node = body.get(field);
        if (node.isNull()) {
            if (!nullable) {
                error(field, "NotNull", null);
                return current;
            }
            return null;
        }
        if (!node.isIntegralNumber() || node.asLong() < min || node.asLong() > max) {
            error(field, "Range", min + "~" + max);
            return current;
        }
        return node.asInt();
    }

    Double decimal(String field, Double current, Double min, Double max, boolean nullable) {
        if (!has(field)) {
            return current;
        }
        JsonNode node = body.get(field);
        if (node.isNull()) {
            if (!nullable) {
                error(field, "NotNull", null);
                return current;
            }
            return null;
        }
        if (!node.isNumber() || !Double.isFinite(node.asDouble())
                || (min != null && node.asDouble() < min) || (max != null && node.asDouble() > max)) {
            error(field, "Range", (min == null ? "" : min) + "~" + (max == null ? "" : max));
            return current;
        }
        return node.asDouble();
    }

    boolean bool(String field, boolean current) {
        if (!has(field) || body.get(field).isNull()) {
            return current;
        }
        JsonNode node = body.get(field);
        if (!node.isBoolean()) {
            error(field, "Type", null);
            return current;
        }
        return node.asBoolean();
    }

    void throwIfInvalid() {
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.copyOf(errors));
        }
    }

    static BusinessException invalid(String field, String code, String message) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, message)));
    }
}
