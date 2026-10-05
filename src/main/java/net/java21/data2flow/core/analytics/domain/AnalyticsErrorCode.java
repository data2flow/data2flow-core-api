package net.java21.data2flow.core.analytics.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 분석(ANA) 오류 코드(spec/detail/00-error-codes.md ANA 절). 문구는 {@code i18n/analytics*.properties}의 {@code error.<코드>}.
 * analytics가 돌려준 4xx는 그 코드·본문을 그대로 전하고(RelayedErrorException), core가 직접 판정하는 것(권한·바인딩·일정·충분성 재확인·
 * 비활성 템플릿)만 이 코드로 만든다. SCRIPT_AI_UNAVAILABLE은 스크립트 AI 초안(API-SCR-16) 중계 실패다.
 */
public enum AnalyticsErrorCode implements ErrorCode {
    TEMPLATE_NOT_FOUND(404),
    TEMPLATE_DISABLED(409),
    ANALYSIS_NOT_FOUND(404),
    ANALYSIS_BINDING_INVALID(400),
    ANALYSIS_PARAMS_INVALID(400),
    ANALYSIS_INSUFFICIENT_DATA(409),
    ANALYSIS_WARNING_NOT_ACKNOWLEDGED(400),
    ANALYSIS_LIMIT_EXCEEDED(409),
    ANALYSIS_RUN_NOT_FOUND(404),
    ANALYSIS_RUN_STATE_CONFLICT(409),
    ANALYSIS_REALTIME_NOT_SUPPORTED(400),
    ANALYSIS_SCHEDULE_INVALID(400),
    ML_MODEL_NOT_FOUND(404),
    DATASET_NOT_FOUND(404),
    SCRIPT_AI_UNAVAILABLE(503);

    private final int httpStatus;

    AnalyticsErrorCode(int httpStatus) {
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
