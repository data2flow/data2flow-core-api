package net.java21.data2flow.core.workorder.domain;

import java.time.Instant;

/** 작업 지시 한 행({@code work_orders}). result·linkedOrigins는 JSON 문자열 */
public record WorkOrder(long id, long organizationId, String title, String type, String status, String priority, Long assigneeId,
                        Long requesterId, Instant dueAt, String origin, String originRef, String linkedOriginsJson,
                        Long maintenancePlanId, String resultJson, Instant completedAt, int version, Instant createdAt,
                        Instant updatedAt) {
}
