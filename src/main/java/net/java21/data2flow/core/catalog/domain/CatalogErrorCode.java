package net.java21.data2flow.core.catalog.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 기기 모델·측정 항목 오류 코드(spec/detail/DEV/domain-model.md §5). 문구는 {@code i18n/catalog*.properties}의 {@code error.<코드>}.
 * {@code SCRIPT_NOT_FOUND}(SCR)·{@code ATTRIBUTE_SCHEMA_VIOLATION}(DEV-07)은 다른 기능 묶음과 코드 문자열이 같다(같은 뜻·같은 상태).
 */
public enum CatalogErrorCode implements ErrorCode {
    MODEL_NOT_FOUND(404),
    MODEL_CODE_DUPLICATE(409),
    MODEL_BUILTIN_READONLY(403),
    MODEL_IN_USE(409),
    METRIC_NOT_FOUND(404),
    METRIC_KEY_INVALID(400),
    METRIC_KEY_DUPLICATE(409),
    METRIC_ALIAS_INVALID(400),
    METRIC_STATE_CONFLICT(409),
    SCRIPT_NOT_FOUND(404),
    ATTRIBUTE_SCHEMA_VIOLATION(400);

    private final int httpStatus;

    CatalogErrorCode(int httpStatus) {
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
