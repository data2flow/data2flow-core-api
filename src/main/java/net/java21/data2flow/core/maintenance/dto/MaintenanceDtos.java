package net.java21.data2flow.core.maintenance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** 유지보수(API-OPS-20~24) 모양 */
public final class MaintenanceDtos {

    private MaintenanceDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Window(String id, String targetType, String targetId, String targetName, Instant startsAt, Instant endsAt,
                         boolean pauseAutomation, boolean excludeFromAnalytics, String reason, String status, int version, String createdBy,
                         Instant createdAt) {
    }

    public record Created(String id, String status) {
    }

    /** API-OPS-24 내부: 대상(공간이면 하위 펼침) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActiveWindow(String windowId, String targetType, String targetId, List<String> descendantSpaceIds, boolean pauseAutomation,
                               boolean excludeFromAnalytics, Instant startsAt, Instant endsAt) {
    }
}
