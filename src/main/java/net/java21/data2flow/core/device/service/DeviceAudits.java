package net.java21.data2flow.core.device.service;

/** 기기 기능 감사 행위 코드(audit_logs.action). 공용 AuditCodes는 고치지 않고 여기 둔다 */
public final class DeviceAudits {

    public static final String DEVICE_CREATED = "DEVICE_CREATED";
    public static final String DEVICE_UPDATED = "DEVICE_UPDATED";
    public static final String DEVICE_APPROVED = "DEVICE_APPROVED";
    public static final String DEVICE_REJECTED = "DEVICE_REJECTED";
    public static final String DEVICE_ACTIVATED = "DEVICE_ACTIVATED";
    public static final String DEVICE_DEACTIVATED = "DEVICE_DEACTIVATED";
    public static final String DEVICE_DELETED = "DEVICE_DELETED";
    public static final String DEVICE_TAGS_CHANGED = "DEVICE_TAGS_CHANGED";
    public static final String DEVICE_IMPORTED = "DEVICE_IMPORTED";
    public static final String DEVICE_ATTRIBUTE_CHANGED = "DEVICE_ATTRIBUTE_CHANGED";
    /** API-ING-16 */
    public static final String AUTO_REGISTER_QUOTA_RESET = "AUTO_REGISTER_QUOTA_RESET";

    private DeviceAudits() {
    }
}
