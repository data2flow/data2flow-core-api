package net.java21.data2flow.core.script.domain;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * 정제 스크립트(SCR) 오류 코드(spec/detail/SCR/domain-model.md "오류 코드"). 문구는 {@code i18n/script*.properties}의 {@code error.<코드>}.
 * 실행 결과 코드(SCRIPT_TIMEOUT·SCRIPT_RUNTIME_ERROR·SCRIPT_OUTPUT_INVALID 등)는 pipeline 테스트 실행 응답 본문에만 나오므로 여기 두지 않는다.
 */
public enum ScriptErrorCode implements ErrorCode {
    SCRIPT_NOT_FOUND(404),
    SCRIPT_NAME_DUPLICATED(409),
    SCRIPT_QUOTA_EXCEEDED(409),
    SCRIPT_CODE_TOO_LARGE(400),
    SCRIPT_STATIC_CHECK_FAILED(400),
    SCRIPT_TEST_FAILED(400),
    SCRIPT_VERSION_CONFLICT(409),
    SCRIPT_BINDING_INVALID(400),
    SCRIPT_BINDING_DUPLICATED(409),
    SCRIPT_IN_USE(409);

    private final int httpStatus;

    ScriptErrorCode(int httpStatus) {
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
