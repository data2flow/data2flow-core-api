package net.java21.data2flow.core.common;

import net.java21.data2flow.contracts.error.ErrorCode;

/**
 * core-api의 IAM·OPS 도메인 오류 코드(spec/detail/IAM/domain-model.md §6, OPS/domain-model.md 오류 표).
 * 공통 코드는 {@link net.java21.data2flow.contracts.error.CommonErrorCode}를 쓴다. 문구는 messages*.properties의 {@code error.<코드>}.
 */
public enum CoreErrorCode implements ErrorCode {
    // IAM
    AUTH_PENDING_APPROVAL(403),
    PASSWORD_POLICY_VIOLATION(400),
    PASSWORD_CURRENT_MISMATCH(400),
    LOGIN_ID_INVALID(400),
    LOGIN_ID_DUPLICATED(409),
    EMAIL_DUPLICATED(409),
    INVITATION_INVALID(410),
    INVITATION_PENDING_EXISTS(409),
    INVITATION_RESEND_LIMIT(429),
    RESET_TOKEN_INVALID(410),
    USER_NOT_FOUND(404),
    USER_STATE_CONFLICT(409),
    LAST_ADMIN_REQUIRED(409),
    SELF_MODIFICATION_FORBIDDEN(409),
    SPACE_SCOPE_INVALID(400),
    MFA_SETUP_REQUIRED(403),
    MFA_CODE_INVALID(401),
    SIGNUP_DISABLED(404),
    SIGNUP_DOMAIN_NOT_ALLOWED(400),
    SIGNUP_RATE_LIMITED(429),
    SIGNUP_REQUEST_INVALID(410),
    CUSTOM_ROLE_IN_USE(409),
    CUSTOM_ROLE_PERMISSION_FORBIDDEN(400),
    // OPS
    SETTING_INVALID(400),
    EXTERNAL_SERVICE_TEST_FAILED(502);

    private final int httpStatus;

    CoreErrorCode(int httpStatus) {
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
