package net.java21.data2flow.core.ingest.dto;

import net.java21.data2flow.contracts.web.ApiHeader;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 수집 관리 API 요청·응답(design/api/ING-api.md API-ING-01·02·04~13·15, OPS-api.md API-OPS-02·05).
 * ID는 JSON 문자열, 시각은 UTC ISO-8601. 지연은 밀리초(…Ms)·초(…Sec), 오류율은 0~1 분수(…Rate, 오케스트레이터 결정).
 */
public final class IngestDtos {

    private IngestDtos() {
    }

    // ---------------------------------------------------------------- API-ING-01·02·04

    /**
     * API-ING-01 수집 요약.
     *
     * @param perMinute     분당 수신(최근 5분 평균)
     * @param latencyP50Ms  수신 → 처리 완료 지연 중앙값(최근 5분, raw_messages received_at → processed_at)
     * @param streamLagSec  처리 대기 중 가장 오래된 원본의 대기 초(처리 대기가 없으면 0). data2flow.raw 소비 지연의 근삿값
     * @param heartbeat     하트비트 카나리(ING-07.05, M3). 아직 원천이 없어 null
     * @param failuresToday 오늘(조직 시간대) 새 실패 메시지 수
     */
    public record IngestSummaryResponse(String window, double perMinute, Long latencyP50Ms, Long latencyP95Ms, Long streamLagSec,
                                        Object heartbeat, long failuresToday, List<AlertResponse> alerts, List<SourceSummary> sources) {
    }

    /** 운영 경고 */
    public record AlertResponse(String level, String code, String message, List<String> causeHints) {
    }

    /** 소스별 요약. counts는 기간(window) 안 처리 결과별 건수 */
    public record SourceSummary(String sourceId, String name, String type, String connection, double perMinute, Map<String, Long> counts,
                                Instant lastReceivedAt) {
    }

    /** API-ING-02 수집 지표 시계열 */
    public record MetricsResponse(String step, List<MetricPoint> points) {
    }

    /** 구간 하나 */
    public record MetricPoint(Instant t, long received, Map<String, Long> byStatus, Long latencyP50Ms, Long latencyP95Ms) {
    }

    /** API-ING-04 수집 알람 기준 변경 요청 */
    public record ThresholdsRequest(Integer lagWarnSec, Integer lagCriticalSec, Integer heartbeatCriticalSec, Integer baseVersion) {
    }

    /** API-ING-04 응답·조회 */
    public record ThresholdsResponse(int lagWarnSec, int lagCriticalSec, int heartbeatCriticalSec, int version, String updatedBy,
                                     Instant updatedAt) {
    }

    // ---------------------------------------------------------------- API-ING-05·06

    /** API-ING-05 커서 목록 {@code {header, size, responses, countsByStatus, nextCursor}} */
    public record RawMessageListResponse(ApiHeader header, int size, List<RawMessageSummary> responses, Map<String, Long> countsByStatus,
                                         String nextCursor) {
    }

    /** 원본 메시지 목록 한 행(payload 제외). qualitySummary는 원본 행에 품질 집계가 없어 null(문서 변경 필요) */
    public record RawMessageSummary(String id, Instant receivedAt, String sourceId, String sourceName, String topic, String deviceId,
                                    String deviceName, String externalId, String status, Integer metricCount, Object qualitySummary,
                                    int sizeBytes, boolean virtual) {
    }

    /**
     * API-ING-06 원본 메시지 상세. payload는 INGEST_PAYLOAD_READ(INTEGRATOR 이상)일 때만, 아니면 null + payloadMasked=true(TC-ING-007).
     * trace·canonical은 pipeline이 남긴 processing_trace(JSON) 그대로다.
     */
    public record RawMessageDetail(String id, Instant receivedAt, Instant processedAt, String sourceId, String sourceName, String sourceType,
                                   String topic, String deviceId, String deviceName, String externalId, String ingressInstance,
                                   String dedupKey, String payload, boolean payloadMasked, String payloadEncoding, int sizeBytes,
                                   String status, String errorCode, Object errorDetail, Object trace, Object canonical,
                                   List<StoredValue> stored, boolean virtual) {
    }

    /** 원본 payload만(TC-ING-007 {@code GET …/{id}/payload}) */
    public record RawPayloadResponse(String id, String payload, String payloadEncoding) {
    }

    /** 이 원본에서 저장된 측정값 */
    public record StoredValue(String metricKey, double value, String unit, int quality, boolean late) {
    }

    // ---------------------------------------------------------------- API-ING-07~12

    /** API-ING-08 groupBy=errorCode 응답 */
    public record FailureGroupsResponse(List<FailureGroup> groups, Map<String, Long> countsByStage) {
    }

    /** 오류 코드별 묶음 */
    public record FailureGroup(String errorCode, long count, Instant firstAt, Instant lastAt, String sampleMessage) {
    }

    /** API-ING-08 groupBy=none 목록 한 행 */
    public record FailureItem(String id, String rawMessageId, String stage, String errorCode, String errorMessage, int attempts,
                              String status, String sourceId, String sourceName, String deviceId, String deviceName, Instant createdAt,
                              String lockedBy) {
    }

    /** API-ING-07 요청: 둘 중 하나, 합계 1~5,000 */
    public record ReprocessRequest(List<Long> dlqItemIds, List<Long> rawMessageIds) {
    }

    /** API-ING-07 응답 */
    public record ReprocessResponse(List<ReprocessResult> results, ReprocessSummary summary) {
    }

    /** 항목별 결과: RESOLVED·SAME_ERROR·OTHER_ERROR·LOCKED */
    public record ReprocessResult(String id, String outcome, String errorCode) {
    }

    /** 결과별 건수 */
    public record ReprocessSummary(int resolved, int sameError, int otherError, int locked) {
    }

    /** API-ING-11 요청 */
    public record DiscardRequest(List<Long> dlqItemIds, String reason) {
    }

    /** API-ING-11 응답 */
    public record DiscardResponse(int discarded) {
    }

    /** API-ING-09·10 요청 */
    public record ReprocessJobRequest(Long sourceId, List<Long> deviceIds, Instant from, Instant to, String memo) {
    }

    /** API-ING-09 응답 */
    public record ReprocessPreviewResponse(long total, Map<String, Long> byStatus, long estimatedSeconds, DecoderInfo decoder,
                                           List<ScriptInfo> scripts) {
    }

    /** 디코더 */
    public record DecoderInfo(String key, String version) {
    }

    /** 연결 스크립트(scope = SOURCE·MODEL·DEVICE) */
    public record ScriptInfo(String scope, String scriptId, String name, Integer version) {
    }

    /** API-ING-10 응답 */
    public record ReprocessJobResponse(String jobId, String status, long total) {
    }

    /** API-ING-12 응답 */
    public record CancelJobResponse(String jobId, String status, long processed) {
    }

    // ---------------------------------------------------------------- API-ING-13·15

    /** API-ING-13 데이터 품질 한 대상(점수 0~100) */
    public record QualityItem(String targetId, String targetName, int score, int completeness, int timeliness, int validity, int stability,
                              long gaps, boolean clockSkewSuspect, Evidence evidence) {
    }

    /** 점수 근거 건수 */
    public record Evidence(long expected, long received, long late, long outOfRange, long suspect) {
    }

    /** API-ING-15 수신 공백 */
    public record GapItem(String deviceId, String deviceName, Instant from, Instant to, long estimatedMissing) {
    }

    // ---------------------------------------------------------------- API-OPS-02·05

    /** API-OPS-05 기준 한 항목 */
    public record OpsThresholdItem(String key, Boolean enabled, Double value, String severity, List<String> channelIds) {
    }

    /** API-OPS-05 PUT 요청 */
    public record OpsThresholdsRequest(List<OpsThresholdItem> items, Integer baseVersion) {
    }

    /** API-OPS-05 응답 */
    public record OpsThresholdsResponse(List<OpsThresholdItem> items, int version) {
    }

    /** API-OPS-02 운영 수집 지표 */
    public record OpsIngestMetricsResponse(String range, OpsIngestSeries series, Map<String, Long> resultCounts) {
    }

    /** 점은 {@code [시각, 값]} */
    public record OpsIngestSeries(List<List<Object>> receivedPerMin, List<List<Object>> latencyP50, List<List<Object>> latencyP95,
                                  List<List<Object>> backlog) {
    }
}
