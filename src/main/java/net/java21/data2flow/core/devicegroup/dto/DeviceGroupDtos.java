package net.java21.data2flow.core.devicegroup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** 기기 그룹 API(design/api/DEV-api.md §3, API-DEV-30~35) */
public final class DeviceGroupDtos {

    private DeviceGroupDtos() {
    }

    /** 사용처(규칙·플로우·대시보드). 그 기능들이 생기는 M3~M5 전까지 0 */
    public record Usage(int rules, int flows, int dashboards) {
    }

    public record GroupResponse(String id, String name, String type, String description, JsonNode criteria, int memberCount, Usage usage,
                                int version, Instant updatedAt) {
    }

    /** API-DEV-30. STATIC은 deviceIds(≤1,000), DYNAMIC은 criteria 필수 */
    public record CreateGroupRequest(@NotBlank @Size(max = 100) String name, @NotNull @Pattern(regexp = "STATIC|DYNAMIC") String type,
                                     @Size(max = 500) String description, JsonNode criteria,
                                     List<@Pattern(regexp = "\\d{1,18}") String> deviceIds) {
    }

    /** API-DEV-33 */
    public record PreviewRequest(JsonNode criteria) {
    }

    public record DeviceSample(String id, String name) {
    }

    public record PreviewResponse(long count, List<DeviceSample> sample) {
    }

    /** API-DEV-35 */
    public record MembersRequest(@NotEmpty @Size(max = 1000) List<@Pattern(regexp = "\\d{1,18}") String> deviceIds) {
    }

    public record MembersAddedResponse(String groupId, int memberCount, int added) {
    }

    public record MembersRemovedResponse(String groupId, int memberCount, int removed) {
    }
}
