package net.java21.data2flow.core.external.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 외부 맥락 소스 오류 코드(spec/detail/00-error-codes.md DSC). 문구는 {@code i18n/external*.properties}의 {@code error.<코드>}.
 * 소스·공간 코드는 다른 기능 묶음과 같은 이름·같은 HTTP 상태다(코드는 플랫폼 전체에서 한 뜻).
 */
public enum ExternalErrorCode implements ErrorCode {
    /** 공공 API 일일 한도 도달(BR-DSC-17). 다음 날 00:00(KST)까지 호출하지 않는다 */
    EXTERNAL_API_QUOTA_EXCEEDED(429),
    /** 외부 맥락 소스에 사이트 좌표가 없음 */
    SITE_LOCATION_REQUIRED(400),
    SOURCE_CONFIG_INVALID(400),
    SOURCE_NOT_FOUND(404),
    SPACE_NOT_FOUND(404);

    private final int httpStatus;

    ExternalErrorCode(int httpStatus) {
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
