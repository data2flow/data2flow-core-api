package net.java21.data2flow.core.energy.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 에너지·설비 최적화 오류 코드(spec/detail/ENE/domain-model.md §6). 문구는 {@code i18n/energy*.properties}의 {@code error.<코드>}.
 * {@code ENE_TARIFF_NOT_FOUND}·{@code ENE_EMISSION_FACTOR_NOT_FOUND}·{@code ENE_SOURCE_NOT_FOUND}·{@code ENE_BILL_NOT_FOUND}는 문서에 없던 코드다
 * (API-ENE-01·02·04·05 수정·삭제 대상 404). {@code ENE_BASELINE_NOT_FOUND}도 같다(API-ENE-12 기준선·버전 404). 공간·기기 코드는 다른 기능 묶음과 같은 이름이다.
 */
public enum EnergyErrorCode implements ErrorCode {
    ENE_RATE_PERIOD_OVERLAP(409),
    ENE_EMISSION_FACTOR_SYSTEM_READONLY(409),
    ENE_RATE_INVALID(400),
    ENE_SOURCE_CONFLICT(409),
    ENE_SOURCE_METRIC_INVALID(400),
    ENE_BILL_DUPLICATE(409),
    ENE_TARIFF_NOT_FOUND(404),
    ENE_EMISSION_FACTOR_NOT_FOUND(404),
    ENE_SOURCE_NOT_FOUND(404),
    ENE_BILL_NOT_FOUND(404),
    ENE_BASELINE_INSUFFICIENT_DATA(409),
    ENE_BASELINE_NOT_READY(409),
    ENE_BASELINE_NOT_FOUND(404),
    SPACE_NOT_FOUND(404),
    DEVICE_NOT_FOUND(404);

    private final int httpStatus;

    EnergyErrorCode(int httpStatus) {
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
