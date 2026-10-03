package net.java21.data2flow.core.devicegroup.domain;

import java.time.Instant;

/** 기기 그룹 한 행({@code device_groups}, DEV-06). criteria는 JSON 문자열(STATIC이면 null) */
public record DeviceGroup(long id, long organizationId, String name, String type, String criteria, int memberCount, String description,
                          int version, Instant createdAt, Instant updatedAt) {

    public static final String STATIC = "STATIC";
    public static final String DYNAMIC = "DYNAMIC";
    /** BR-DEV-12 */
    public static final int MAX_MEMBERS = 1000;

    public boolean dynamic() {
        return DYNAMIC.equals(type);
    }
}
