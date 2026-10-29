package net.java21.data2flow.core.compliance.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 환경 기준·인증 보고 오류 코드(spec/detail/CMP/domain-model.md §5). 문구는 {@code i18n/compliance*.properties}의 {@code error.<코드>}.
 * {@code CMP_PROFILE_ARCHIVED}는 문서에 없던 코드다(보관된 프로필은 새 적용 불가, domain-model §2). 공간·측정 항목 코드는 다른 기능 묶음과 같은 이름이다.
 */
public enum ComplianceErrorCode implements ErrorCode {
    CMP_PROFILE_NOT_FOUND(404),
    CMP_PROFILE_READ_ONLY(409),
    CMP_PROFILE_NAME_DUPLICATE(409),
    CMP_PROFILE_VERSION_CONFLICT(409),
    CMP_PROFILE_ARCHIVED(409),
    CMP_THRESHOLD_INVALID(400),
    CMP_FACILITY_TYPE_REQUIRED(400),
    CMP_APPLICATION_DUPLICATE(409),
    CMP_APPLICATION_NOT_FOUND(404),
    SPACE_NOT_FOUND(404),
    METRIC_NOT_FOUND(404);

    private final int httpStatus;

    ComplianceErrorCode(int httpStatus) {
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
