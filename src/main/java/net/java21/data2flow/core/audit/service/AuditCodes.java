package net.java21.data2flow.core.audit.service;

/**
 * core-api가 남기는 감사 action 코드(IAM domain-model §5, OPS domain-model 감사 목록).
 * {@code PERSONAL_INFO_ACCESSED}는 NFR-12.02(개인정보 접속 기록)를 위해 더한 코드다(관리자 회원 목록·상세 조회).
 */
public final class AuditCodes {

    public static final String USER_LOGIN_FAILED = "USER_LOGIN_FAILED";
    public static final String REFRESH_REUSED = "REFRESH_REUSED";
    public static final String SESSION_REVOKED = "SESSION_REVOKED";
    public static final String USER_LOCKED = "USER_LOCKED";
    public static final String USER_UNLOCKED = "USER_UNLOCKED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
    public static final String PASSWORD_RESET_COMPLETED = "PASSWORD_RESET_COMPLETED";
    public static final String USER_INVITED = "USER_INVITED";
    public static final String INVITATION_RESENT = "INVITATION_RESENT";
    public static final String INVITATION_CANCELED = "INVITATION_CANCELED";
    public static final String INVITATION_ACCEPTED = "INVITATION_ACCEPTED";
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_DISABLED = "USER_DISABLED";
    public static final String USER_ENABLED = "USER_ENABLED";
    public static final String USER_DELETED = "USER_DELETED";
    public static final String ROLE_CHANGED = "ROLE_CHANGED";
    public static final String SPACE_SCOPE_CHANGED = "SPACE_SCOPE_CHANGED";
    public static final String SECURITY_POLICY_CHANGED = "SECURITY_POLICY_CHANGED";
    public static final String AUDIT_EXPORTED = "AUDIT_EXPORTED";
    public static final String MFA_ENABLED = "MFA_ENABLED";
    public static final String MFA_DISABLED = "MFA_DISABLED";
    public static final String MFA_RECOVERY_USED = "MFA_RECOVERY_USED";
    public static final String SIGNUP_REQUESTED = "SIGNUP_REQUESTED";
    public static final String SIGNUP_APPROVED = "SIGNUP_APPROVED";
    public static final String SIGNUP_REJECTED = "SIGNUP_REJECTED";
    public static final String CUSTOM_ROLE_CREATED = "CUSTOM_ROLE_CREATED";
    public static final String CUSTOM_ROLE_UPDATED = "CUSTOM_ROLE_UPDATED";
    public static final String CUSTOM_ROLE_DELETED = "CUSTOM_ROLE_DELETED";
    public static final String ORG_SETTING_CHANGED = "ORG_SETTING_CHANGED";
    public static final String EXTERNAL_SERVICE_CHANGED = "EXTERNAL_SERVICE_CHANGED";
    public static final String PERSONAL_INFO_ACCESSED = "PERSONAL_INFO_ACCESSED";

    private AuditCodes() {
    }
}
