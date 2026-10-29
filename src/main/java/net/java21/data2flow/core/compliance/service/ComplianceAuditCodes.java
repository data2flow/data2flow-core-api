package net.java21.data2flow.core.compliance.service;

/** 환경 기준 프로필·적용·점수 가중치·리포트·개선 조치·정기 발행 감사 action 코드(CMP-01). 공통 AuditCodes와 같은 UPPER_SNAKE 형식 */
public final class ComplianceAuditCodes {

    public static final String CMP_PROFILE_CREATED = "CMP_PROFILE_CREATED";
    public static final String CMP_PROFILE_VERSION_CREATED = "CMP_PROFILE_VERSION_CREATED";
    public static final String CMP_PROFILE_VERSION_UPDATED = "CMP_PROFILE_VERSION_UPDATED";
    public static final String CMP_PROFILE_CLONED = "CMP_PROFILE_CLONED";
    public static final String CMP_PROFILE_ARCHIVED = "CMP_PROFILE_ARCHIVED";
    public static final String CMP_PROFILE_UNARCHIVED = "CMP_PROFILE_UNARCHIVED";
    public static final String CMP_PROFILE_APPLIED = "CMP_PROFILE_APPLIED";
    public static final String CMP_PROFILE_UNAPPLIED = "CMP_PROFILE_UNAPPLIED";
    public static final String CMP_SCORE_WEIGHTS_CHANGED = "CMP_SCORE_WEIGHTS_CHANGED";
    public static final String CMP_REPORT_REQUESTED = "CMP_REPORT_REQUESTED";
    public static final String CMP_REPORT_PUBLISHED = "CMP_REPORT_PUBLISHED";
    public static final String CMP_REPORT_DOWNLOADED = "CMP_REPORT_DOWNLOADED";
    public static final String CMP_REPORT_DELETED = "CMP_REPORT_DELETED";
    public static final String CMP_ACTION_RECORDED = "CMP_ACTION_RECORDED";
    public static final String CMP_ACTION_UPDATED = "CMP_ACTION_UPDATED";
    public static final String CMP_ACTION_DELETED = "CMP_ACTION_DELETED";
    public static final String CMP_SCHEDULE_CREATED = "CMP_SCHEDULE_CREATED";
    public static final String CMP_SCHEDULE_UPDATED = "CMP_SCHEDULE_UPDATED";
    public static final String CMP_SCHEDULE_DELETED = "CMP_SCHEDULE_DELETED";
    public static final String CMP_SCHEDULE_RUN_REQUESTED = "CMP_SCHEDULE_RUN_REQUESTED";

    private ComplianceAuditCodes() {
    }
}
