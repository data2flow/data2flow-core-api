package net.java21.data2flow.core.dataexchange.dto;

import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.QueryRequest;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 내보내기·가져오기·정기 내보내기·데이터 사전 API(design/api/TSD-api.md §2·§3·§4-2). ID는 JSON 문자열, 시각은 UTC ISO-8601 */
public final class ExchangeDtos {

    private ExchangeDtos() {
    }

    /**
     * API-TSD-20 요청. query는 API-TSD-04 본문 그대로.
     *
     * @param format         CSV(기본)·XLSX·PARQUET
     * @param columns        LONG(기본)·WIDE
     * @param includeQuality 품질 열 포함(기본 true)
     * @param tz             파일 시각 표기 시간대(기본: 사용자 → 조직 → Asia/Seoul)
     */
    public record ExportRequest(QueryRequest query, String format, String columns, Boolean includeQuality, String tz) {
    }

    /** API-TSD-20 응답: 동기면 mode=SYNC + downloadUrl, 아니면 mode=ASYNC + jobId·estimatedRows */
    public record ExportCreatedResponse(String mode, String jobId, String downloadUrl, long estimatedRows) {
    }

    /** API-TSD-21·22 작업 */
    public record ExportJobResponse(String id, String status, String format, JsonNode query, Long rows, Long bytes, Integer dictionaryVersion,
                                    Instant expiresAt, String downloadUrl, String error, String requestedBy, String scheduleId,
                                    Long estimatedRows, Instant createdAt, Instant finishedAt) {
    }

    /** API-TSD-23 정기 내보내기 */
    public record ExportScheduleResponse(String id, String name, JsonNode query, String format, String cron, String relativePeriod,
                                         String delivery, List<String> recipients, String targetType, JsonNode target, String credentialRef,
                                         boolean credentialConfigured, boolean enabled, Instant lastRunAt, String lastStatus,
                                         String lastError, Instant nextRunAt, int lastFileVersion, int version, String createdBy) {
    }

    /** API-TSD-56 요청: 대상 종류·설정·자격(저장하지 않음) */
    public record TestTargetRequest(String targetType, JsonNode target, JsonNode credential) {
    }

    /** API-TSD-56 단계 결과(CONNECT·WRITE·DELETE) */
    public record TestStep(String name, boolean ok, String detail) {
    }

    /** API-TSD-56 응답 */
    public record TestTargetResponse(boolean ok, List<TestStep> steps) {
    }

    /** API-TSD-30 응답: 만든 작업 */
    public record ImportCreatedResponse(String jobId, ImportJobResponse job) {
    }

    /** API-TSD-31·32 가져오기 작업 */
    public record ImportJobResponse(String id, String sourceKind, String status, boolean dryRun, Long total, Long inserted,
                                    Long skippedDuplicate, Long failed, String originLabel, List<Map<String, Object>> sample, String error,
                                    Instant rangeFrom, Instant rangeTo, Instant startedAt, Instant finishedAt, Instant createdAt) {
    }

    /** API-TSD-31 오류 목록 항목 */
    public record ImportErrorResponse(String lineOrPoint, String errorCode, String message) {
    }
}
