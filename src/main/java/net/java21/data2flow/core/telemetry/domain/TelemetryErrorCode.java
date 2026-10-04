package net.java21.data2flow.core.telemetry.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 시계열 조회 오류 코드(spec/detail/TSD/domain-model.md §5). 문구는 {@code i18n/telemetry*.properties}의 {@code error.<코드>}.
 * DEVICE_NOT_FOUND는 DEV 도메인 코드와 같은 이름·같은 뜻이다(API-TSD-02 오류 목록, AT-TSD-01.7).
 */
public enum TelemetryErrorCode implements ErrorCode {
    TSD_RANGE_TOO_LARGE(400),
    TSD_TOO_MANY_SERIES(400),
    TSD_RESOLUTION_UNAVAILABLE(400),
    TSD_INVALID_AGG(400),
    DEVICE_NOT_FOUND(404),
    /** 공간 비교 7곳 이상 등 위젯 조회 조건 오류(DSH-02.04, TC-DSH-018) */
    WIDGET_QUERY_INVALID(400);

    private final int httpStatus;

    TelemetryErrorCode(int httpStatus) {
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
