package net.java21.data2flow.core.device.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 기기 한 행({@code data2flow_core.devices}, DEV-02.01). {@code sourceMeta}는 JSON 문자열(원본 deviceName·tags 등, 8KB 이하).
 */
public record Device(long id, long organizationId, long sourceId, String externalId, String name, String kind, Long modelId,
                     Long spaceId, Long suggestedSpaceId, String status, boolean virtual, Integer expectedIntervalSec,
                     BigDecimal offlineMultiplier, Instant approvedAt, Long approvedBy, boolean autoRegistered,
                     Instant firstSeenAt, String sourceMeta, Long logicalDeviceId, Long replacedByDeviceId, int version,
                     Instant createdAt, Instant updatedAt) {

    public boolean deleted() {
        return DeviceRules.DELETED.equals(status);
    }
}
