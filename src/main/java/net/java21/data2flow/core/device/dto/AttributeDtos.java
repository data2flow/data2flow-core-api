package net.java21.data2flow.core.device.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

/** 기기 속성 API(design/api/DEV-api.md §8, API-DEV-80~83) */
public final class AttributeDtos {

    private AttributeDtos() {
    }

    /** API-DEV-80 요청 {@code {value}} */
    public record AttributeValueRequest(JsonNode value) {
    }

    /** API-DEV-80 응답. SHARED만 desired·reported·applyState */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AttributeResponse(String scope, String key, JsonNode value, JsonNode desired, JsonNode reported, String applyState,
                                    Instant updatedAt) {
    }

    /** API-DEV-83 키별 값 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AttributeEntry(JsonNode value, JsonNode desired, JsonNode reported, String applyState, Instant updatedAt) {
    }

    /** API-DEV-83 */
    public record AttributesResponse(Map<String, AttributeEntry> server, Map<String, AttributeEntry> shared,
                                     Map<String, AttributeEntry> client) {
    }

    /** API-DEV-82 항목 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AttributeHistoryResponse(String id, String scope, String key, JsonNode oldValue, JsonNode newValue, String changedBy,
                                           String changedByName, Instant changedAt) {
    }
}
