package net.java21.data2flow.core.compliance.service;

/** 환경 기준 프로필·적용 감사 action 코드(CMP-01). 공통 AuditCodes와 같은 UPPER_SNAKE 형식 */
public final class ComplianceAuditCodes {

    public static final String CMP_PROFILE_CREATED = "CMP_PROFILE_CREATED";
    public static final String CMP_PROFILE_VERSION_CREATED = "CMP_PROFILE_VERSION_CREATED";
    public static final String CMP_PROFILE_VERSION_UPDATED = "CMP_PROFILE_VERSION_UPDATED";
    public static final String CMP_PROFILE_CLONED = "CMP_PROFILE_CLONED";
    public static final String CMP_PROFILE_ARCHIVED = "CMP_PROFILE_ARCHIVED";
    public static final String CMP_PROFILE_UNARCHIVED = "CMP_PROFILE_UNARCHIVED";
    public static final String CMP_PROFILE_APPLIED = "CMP_PROFILE_APPLIED";
    public static final String CMP_PROFILE_UNAPPLIED = "CMP_PROFILE_UNAPPLIED";

    private ComplianceAuditCodes() {
    }
}
