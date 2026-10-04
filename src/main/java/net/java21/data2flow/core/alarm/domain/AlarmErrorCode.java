package net.java21.data2flow.core.alarm.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/** 알람·알림(RUL-02·03·05) 오류 코드(00-error-codes.md RUL). 문구는 {@code i18n/alarm*.properties} */
public enum AlarmErrorCode implements ErrorCode {
    ALARM_NOT_FOUND(404),
    ALARM_STATE_CONFLICT(409),
    ALARM_BULK_LIMIT_EXCEEDED(400),
    POLICY_NOT_FOUND(404),
    POLICY_IN_USE(409),
    CHANNEL_NOT_CONFIGURED(409),
    MESSENGER_NOT_LINKED(403),
    SILENCE_RANGE_INVALID(400),
    /** 성공 응답의 경고(200, isSuccessful=true): 알 수 없는 템플릿 변수 */
    TEMPLATE_VARIABLE_UNKNOWN(200),
    /** OPS-06 알림 채널 */
    CHANNEL_NOT_FOUND(404),
    CHANNEL_IN_USE(409),
    CHANNEL_TEST_FAILED(502),
    /** OPS-05 유지보수 */
    MAINTENANCE_OVERLAP(409),
    MAINTENANCE_RANGE_INVALID(400);

    private final int httpStatus;

    AlarmErrorCode(int httpStatus) {
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
