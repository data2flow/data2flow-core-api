package net.java21.data2flow.core.catalog.service;

/** 기기 모델·측정 항목 감사 action 코드(DEV-03·04). 공통 AuditCodes와 같은 UPPER_SNAKE 형식 */
public final class CatalogAuditCodes {

    public static final String DEVICE_MODEL_CREATED = "DEVICE_MODEL_CREATED";
    public static final String DEVICE_MODEL_UPDATED = "DEVICE_MODEL_UPDATED";
    public static final String DEVICE_MODEL_PACKAGE_UPDATED = "DEVICE_MODEL_PACKAGE_UPDATED";
    public static final String DEVICE_MODEL_DEPRECATED = "DEVICE_MODEL_DEPRECATED";
    public static final String DEVICE_MODEL_DELETED = "DEVICE_MODEL_DELETED";
    public static final String METRIC_CREATED = "METRIC_CREATED";
    public static final String METRIC_UPDATED = "METRIC_UPDATED";
    public static final String METRIC_VERIFIED = "METRIC_VERIFIED";
    public static final String METRIC_ALIASED = "METRIC_ALIASED";
    public static final String METRIC_IGNORED = "METRIC_IGNORED";
    public static final String METRIC_RESTORED = "METRIC_RESTORED";
    public static final String METRIC_ALIAS_CREATED = "METRIC_ALIAS_CREATED";
    public static final String METRIC_ALIAS_DELETED = "METRIC_ALIAS_DELETED";

    private CatalogAuditCodes() {
    }
}
