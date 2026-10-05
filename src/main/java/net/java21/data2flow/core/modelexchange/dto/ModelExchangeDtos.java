package net.java21.data2flow.core.modelexchange.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.ModelResponse;
import net.java21.data2flow.core.modelexchange.domain.ModelDocument.Unmapped;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** 모델 가져오기·표준 형식 내보내기·NGSI-LD 주기 전송 API(design/api/DEV-api.md API-DEV-44·45·135·136) */
public final class ModelExchangeDtos {

    private ModelExchangeDtos() {
    }

    public record ImportedScript(String id, String name, String kind, String status) {
    }

    /** API-DEV-45 응답. dryRun이면 model은 null이고 createdMetrics는 만들 예정인 키 */
    public record ImportResponse(boolean dryRun, String format, ModelResponse model, List<String> createdMetrics, List<Unmapped> unmapped,
                                 List<ImportedScript> scripts) {
    }

    public record Scope(List<String> spaceIds, List<String> deviceIds) {
    }

    /** API-DEV-135 요청(format: DTDL·NGSI_LD·BRICK_TTL·BRICK_JSONLD) */
    public record StandardExportRequest(@NotBlank String format, Scope scope, Boolean includeValues) {
    }

    public record ExportJobCreated(String jobId, String status) {
    }

    /** API-DEV-135 작업 상태 */
    public record ExportJobResponse(String id, String format, String status, String downloadUrl, JsonNode report, Instant createdAt,
                                    Instant updatedAt) {
    }

    /** API-DEV-136 요청 */
    public record NgsiPushRequest(@NotBlank String outputConnectionId, Scope scope, @NotNull Integer intervalSec) {
    }

    public record NgsiPushResponse(String id, String outputConnectionId, Scope scope, int intervalSec, boolean enabled, Instant lastSentAt,
                                   String lastError) {
    }
}
