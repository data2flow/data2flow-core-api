package net.java21.data2flow.core.retention.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 보관 정책·콜드 보관 오류 코드(spec/detail/TSD/domain-model.md §5). 문구는 {@code i18n/retention*.properties}의 {@code error.<코드>}.
 */
public enum RetentionErrorCode implements ErrorCode {
    /** 보관 기간을 줄이는데 미리 보기 확인 토큰이 없거나 맞지 않음(BR-TSD-03) */
    RETENTION_CONFIRM_REQUIRED(409),
    /** 허용 범위·범위 조합 위반 */
    RETENTION_INVALID(400),
    ARCHIVE_NOT_FOUND(404);

    private final int httpStatus;

    RetentionErrorCode(int httpStatus) {
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
