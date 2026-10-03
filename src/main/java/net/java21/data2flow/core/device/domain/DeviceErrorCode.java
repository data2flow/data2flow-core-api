package net.java21.data2flow.core.device.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 기기·그룹·속성·게이트웨이·기기 자격 오류 코드(spec/detail/DEV/domain-model.md §5, DSC/domain-model.md 오류 표).
 * 문구는 {@code i18n/device*.properties}의 {@code error.<코드>}. 공통 코드는 {@code CommonErrorCode}를 쓴다.
 * 공간·모델·소스 코드(SPACE_NOT_FOUND 등)는 다른 기능 묶음과 같은 이름·같은 HTTP 상태다(코드는 플랫폼 전체에서 한 뜻).
 */
public enum DeviceErrorCode implements ErrorCode {
    DEVICE_NOT_FOUND(404),
    DEVICE_DUPLICATE(409),
    DEVICE_STATE_CONFLICT(409),
    DEVICE_MODEL_REQUIRED(400),
    DEVICE_SPACE_REQUIRED(400),
    DEVICE_IN_USE(409),
    DEVICE_TAG_LIMIT(400),
    /** 소스 자동 등록 한도 초과(내부 API-DEV-121, BR-ING-09) */
    DEVICE_AUTOREG_LIMIT(429),
    /** 거부 정책·무시 목록(내부 API-DEV-121, BR-DEV-07) */
    DEVICE_REJECTED(409),
    MODEL_NOT_FOUND(404),
    SPACE_NOT_FOUND(404),
    SOURCE_NOT_FOUND(404),
    /** 플랫폼 브로커 소스가 아닌 기기에 자격 발급(API-DSC-20) */
    SOURCE_STATE_CONFLICT(409),
    GROUP_NOT_FOUND(404),
    GROUP_SIZE_EXCEEDED(400),
    GROUP_NAME_DUPLICATE(409),
    GROUP_IN_USE(409),
    ATTRIBUTE_READONLY(403),
    ATTRIBUTE_SCHEMA_VIOLATION(400),
    ATTRIBUTE_NOT_APPLICABLE(409),
    CREDENTIAL_NOT_FOUND(404),
    CREDENTIAL_REVOKED(409);

    private final int httpStatus;

    DeviceErrorCode(int httpStatus) {
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
