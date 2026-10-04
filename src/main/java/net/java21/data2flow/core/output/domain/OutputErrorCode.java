package net.java21.data2flow.core.output.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 출력 연결 오류 코드(design/api/DSC-api.md §3, DSC test-plan "OUTPUT_NOT_FOUND"). 설정 검증 실패는 소스와 같은 SOURCE_CONFIG_INVALID를 쓴다.
 * 문구는 {@code i18n/output*.properties}의 {@code error.<코드>}.
 */
public enum OutputErrorCode implements ErrorCode {
    OUTPUT_NOT_FOUND(404),
    /** 샘플 기기에 보낼 현재값이 없음(API-DSC-32) */
    OUTPUT_SAMPLE_UNAVAILABLE(400);

    private final int httpStatus;

    OutputErrorCode(int httpStatus) {
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
