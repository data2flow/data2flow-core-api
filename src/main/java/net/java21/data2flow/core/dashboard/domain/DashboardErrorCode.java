package net.java21.data2flow.core.dashboard.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 대시보드·실시간 화면 오류 코드(spec/detail/DSH/domain-model.md 오류 표). 공간·소스가 없거나 권한 범위 밖이면 존재를 숨기고 404다
 * (BR-IAM-16). 코드의 뜻은 DEV·DSC의 같은 이름과 같다. 문구는 {@code i18n/live*.properties}의 {@code error.<코드>}.
 */
public enum DashboardErrorCode implements ErrorCode {
    SPACE_NOT_FOUND(404),
    SOURCE_NOT_FOUND(404);

    private final int httpStatus;

    DashboardErrorCode(int httpStatus) {
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
