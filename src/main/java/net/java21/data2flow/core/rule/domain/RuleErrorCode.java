package net.java21.data2flow.core.rule.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/** 규칙(RUL-01·06) 오류 코드(00-error-codes.md RUL). 문구는 {@code i18n/alarm*.properties} */
public enum RuleErrorCode implements ErrorCode {
    RULE_NOT_FOUND(404),
    RULE_NAME_DUPLICATED(409),
    RULE_CONDITION_INVALID(400),
    RULE_LIMIT_EXCEEDED(409),
    RULE_TARGET_LIMIT_EXCEEDED(400),
    RULE_STATE_CONFLICT(409),
    RULE_SIMULATION_RANGE_INVALID(400);

    private final int httpStatus;

    RuleErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
