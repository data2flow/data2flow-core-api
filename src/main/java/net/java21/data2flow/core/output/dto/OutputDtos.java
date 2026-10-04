package net.java21.data2flow.core.output.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 출력 연결 API(design/api/DSC-api.md §3 API-DSC-30~33, 내부 API-DSC-73~75) */
public final class OutputDtos {

    private OutputDtos() {
    }

    /** API-DSC-30·31 항목. 비밀값은 종류만(secretConfigured·secretKinds) */
    public record OutputConnectionResponse(String id, String name, String type, JsonNode target, JsonNode filter, String format,
                                           String template, boolean secretConfigured, List<String> secretKinds, boolean enabled, int version,
                                           Instant createdAt, Instant updatedAt) {
    }

    /** API-DSC-31 stats 항목 */
    public record StatPoint(Instant t, int sent, int failed, int retried, Integer lagMs) {
    }

    /** API-DSC-73 연결 하나(비밀값은 복호화한 원문 — 로그 금지) */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RuntimeConnection(String id, String organizationId, String name, String type, JsonNode target, JsonNode filter,
                                    String format, String template, Map<String, String> secrets, boolean enabled, int version) {
    }

    public record RuntimeResponse(long version, List<RuntimeConnection> connections) {
    }

    /** API-DSC-74 항목 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DeviceContext(String deviceId, String organizationId, String deviceName, String spaceId, String spaceCode,
                                List<String> spacePathIds, List<String> groupIds) {
    }

    public record DeviceContextsResponse(List<DeviceContext> devices) {
    }

    public record ReplayResponse(int queued) {
    }
}
