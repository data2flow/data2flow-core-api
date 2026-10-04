package net.java21.data2flow.core.source.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 데이터 소스(DSC) 오류 코드(spec/detail/DSC/domain-model.md §5·§8). 문구는 {@code i18n/source*.properties}의 {@code error.<코드>}.
 * {@code SCRIPT_NOT_FOUND}는 SCR 도메인 코드지만 API-DSC-02가 디코드 스크립트 확인에서 낸다.
 */
public enum SourceErrorCode implements ErrorCode {
    SOURCE_NOT_FOUND(404),
    SOURCE_CODE_DUPLICATE(409),
    SOURCE_CLIENT_ID_DUPLICATE(409),
    SOURCE_CONFIG_INVALID(400),
    SOURCE_STATE_CONFLICT(409),
    SOURCE_IN_USE(409),
    SOURCE_LIMIT_EXCEEDED(429),
    SOURCE_SECRET_REQUIRED(400),
    SOURCE_AUTH_UNSUPPORTED(400),
    SOURCE_TLS_VERIFY_REQUIRED(400),
    SITE_LOCATION_REQUIRED(400),
    CONNECTOR_NOT_FOUND(404),
    CONNECTOR_UNAVAILABLE(409),
    SCRIPT_NOT_FOUND(404),
    /** 무중단 자격증명 교체 실패: 새 값으로 연결하지 못해 이전 값을 유지(DSC-07.02, BR-DSC-09) */
    SOURCE_ROTATION_FAILED(409);

    private final int httpStatus;

    SourceErrorCode(int httpStatus) {
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
