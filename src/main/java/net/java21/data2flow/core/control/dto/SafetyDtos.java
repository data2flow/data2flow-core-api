package net.java21.data2flow.core.control.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** 인터락·비상 정지·장면·예약(API-ACT-10~21) 모양. ID는 문자열 */
public final class SafetyDtos {

    private SafetyDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Interlock(String interlockId, String name, String spaceId, String spaceName, boolean includeChildren, boolean enabled,
                           JsonNode condition, JsonNode forbid, String message, int version, Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EmergencyStop(String id, JsonNode scope, String reason, String startedBy, Instant startedAt, String releasedBy,
                                Instant releasedAt, String releaseNote, Integer cancelledCommands) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Scene(String sceneId, String name, String description, String spaceId, int itemCount, List<JsonNode> items, int version,
                        Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Schedule(String id, String name, JsonNode target, String kind, Instant at, String cron, JsonNode spaceHours,
                           String validFrom, String validTo, boolean skipHolidays, String timezone, boolean enabled, Instant nextRunAt,
                           JsonNode lastRun, int version, Instant updatedAt) {
    }
}
