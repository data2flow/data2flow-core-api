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
    SCRIPT_IN_USE(409),
    /** 설정값 이름이 비밀값처럼 보임(BR-SCR-12, API-SCR-22) */
    SCRIPT_CONFIG_SECRET_FORBIDDEN(400),
    /** 없는 모듈·모듈 버전(API-SCR-18·19) */
    SCRIPT_MODULE_NOT_FOUND(404),
    /** 사용 중인 모듈 버전 삭제(BR-SCR-15) */
    SCRIPT_MODULE_IN_USE(409),
    /** 수식 문법·측정 키 오류(API-SCR-20·21, pipeline API-SCR-37·38) */
    SCRIPT_FORMULA_INVALID(400),
    /** 결과 키가 이미 있는 측정 항목·수식(API-SCR-20) */
    SCRIPT_FORMULA_KEY_CONFLICT(409),
    /** 수식 항목이 없음 */
    SCRIPT_FORMULA_NOT_FOUND(404);

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
