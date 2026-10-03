package net.java21.data2flow.core.ingest.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.CancelJobResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.DiscardRequest;
import net.java21.data2flow.core.ingest.dto.IngestDtos.DiscardResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.GapItem;
import net.java21.data2flow.core.ingest.dto.IngestDtos.IngestSummaryResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.MetricsResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.QualityItem;
import net.java21.data2flow.core.ingest.dto.IngestDtos.RawMessageDetail;
import net.java21.data2flow.core.ingest.dto.IngestDtos.RawMessageListResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.RawPayloadResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ReprocessJobRequest;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ReprocessJobResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ReprocessPreviewResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ReprocessRequest;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ReprocessResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ThresholdsRequest;
import net.java21.data2flow.core.ingest.dto.IngestDtos.ThresholdsResponse;
import net.java21.data2flow.core.ingest.service.DataQualityService;
import net.java21.data2flow.core.ingest.service.FailureService;
import net.java21.data2flow.core.ingest.service.FailureService.FailureQuery;
import net.java21.data2flow.core.ingest.service.IngestMonitorService;
import net.java21.data2flow.core.ingest.service.IngestSettingsService;
import net.java21.data2flow.core.ingest.service.RawMessageService;
import net.java21.data2flow.core.ingest.service.RawMessageService.RawQuery;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 수집 관리 API(design/api/ING-api.md §1, 외부 경로 {@code /api/v1/core/ingest/**}). 모니터·원본 메시지·실패 메시지·재처리·품질·공백.
 * 권한: 조회 INGEST_READ(OPERATOR 이상), payload INGEST_PAYLOAD_READ, 재처리·폐기 INGEST_REPROCESS, 알람 기준 OPS_MANAGE(ADMIN).
 */
@RestController
public class IngestController {

    private final IngestMonitorService monitor;
    private final IngestSettingsService settings;
    private final RawMessageService rawMessages;
    private final FailureService failures;
    private final DataQualityService quality;

    public IngestController(IngestMonitorService monitor, IngestSettingsService settings, RawMessageService rawMessages,
                            FailureService failures, DataQualityService quality) {
        this.monitor = monitor;
        this.settings = settings;
        this.rawMessages = rawMessages;
        this.failures = failures;
        this.quality = quality;
    }

    /** API-ING-01 수집 요약(ING-01.02, ING-07.04, OPS-01.05) */
    @GetMapping("/core/ingest/summary")
    public ApiResponse<IngestSummaryResponse> summary(@RequestParam(name = "window", required = false) String window) {
        return ApiResponse.success(monitor.summary(window));
    }

    /** API-ING-02 수집 지표 시계열(OPS-01.02) */
    @GetMapping("/core/ingest/metrics")
    public ApiResponse<MetricsResponse> metrics(@RequestParam(name = "from", required = false) Instant from,
                                                @RequestParam(name = "to", required = false) Instant to,
                                                @RequestParam(name = "sourceId", required = false) Long sourceId,
                                                @RequestParam(name = "step", required = false) String step) {
        return ApiResponse.success(monitor.metrics(from, to, sourceId, step));
    }

    /** API-ING-04 수집 알람 기준 조회(문서에 추가할 GET, 웹 기준 편집 폼) */
    @GetMapping("/core/ingest/alert-thresholds")
    public ApiResponse<ThresholdsResponse> thresholds() {
        return ApiResponse.success(settings.ingestThresholds());
    }

    /** API-ING-04 수집 알람 기준 변경(ING-07.04·07.05) */
    @PutMapping("/core/ingest/alert-thresholds")
    public ApiResponse<ThresholdsResponse> updateThresholds(@RequestBody(required = false) ThresholdsRequest request) {
        return ApiResponse.success(settings.updateIngestThresholds(request));
    }

    /** API-ING-05 원본 메시지 목록(커서 목록, ING-01.01) */
    @GetMapping("/core/ingest/raw-messages")
    public RawMessageListResponse rawMessages(@RequestParam(name = "from", required = false) Instant from,
                                              @RequestParam(name = "to", required = false) Instant to,
                                              @RequestParam(name = "sourceId", required = false) List<Long> sourceIds,
                                              @RequestParam(name = "deviceId", required = false) List<Long> deviceIds,
                                              @RequestParam(name = "status", required = false) List<String> statuses,
                                              @RequestParam(name = "topicContains", required = false) String topicContains,
                                              @RequestParam(name = "includeVirtual", required = false) Boolean includeVirtual,
                                              @RequestParam(name = "cursor", required = false) String cursor,
                                              @RequestParam(name = "size", required = false) Integer size,
                                              @RequestParam(name = "format", required = false) String format) {
        return rawMessages.list(new RawQuery(from, to, sourceIds, deviceIds, statuses, topicContains, includeVirtual, cursor, size, format));
    }

    /** API-ING-06 원본 메시지 상세(ING-01.01) */
    @GetMapping("/core/ingest/raw-messages/{raw-message-id}")
    public ApiResponse<RawMessageDetail> rawMessage(@PathVariable("raw-message-id") long id) {
        return ApiResponse.success(rawMessages.detail(id));
    }

    /** API-ING-06 payload 원문(INGEST_PAYLOAD_READ, TC-ING-007) */
    @GetMapping("/core/ingest/raw-messages/{raw-message-id}/payload")
    public ApiResponse<RawPayloadResponse> rawPayload(@PathVariable("raw-message-id") long id) {
        return ApiResponse.success(rawMessages.payload(id));
    }

    /** API-ING-08 실패 메시지 목록(ING-07.03). groupBy=errorCode면 묶음, none이면 목록 */
    @GetMapping("/core/ingest/failures")
    public Object failures(@RequestParam(name = "stage", required = false) String stage,
                           @RequestParam(name = "errorCode", required = false) String errorCode,
                           @RequestParam(name = "status", required = false) String status,
                           @RequestParam(name = "from", required = false) Instant from,
                           @RequestParam(name = "to", required = false) Instant to,
                           @RequestParam(name = "groupBy", required = false) String groupBy,
                           @RequestParam(name = "page", required = false) Integer page,
                           @RequestParam(name = "size", required = false) Integer size) {
        return failures.list(new FailureQuery(stage, errorCode, status, from, to, groupBy, page, size));
    }

    /** API-ING-07 재처리(ING-07.03) — Idempotency-Key 필수 */
    @Idempotent(required = true)
    @PostMapping("/core/ingest/failures/reprocess")
    public ApiResponse<ReprocessResponse> reprocess(@RequestBody(required = false) ReprocessRequest request) {
        return ApiResponse.success(failures.reprocess(request));
    }

    /** API-ING-11 실패 메시지 폐기(ING-07.03) */
    @Idempotent
    @PostMapping("/core/ingest/failures/discard")
    public ApiResponse<DiscardResponse> discard(@RequestBody(required = false) DiscardRequest request) {
        return ApiResponse.success(failures.discard(request));
    }

    /** API-ING-09 재처리 미리 보기 */
    @PostMapping("/core/ingest/reprocess-jobs/preview")
    public ApiResponse<ReprocessPreviewResponse> preview(@RequestBody(required = false) ReprocessJobRequest request) {
        return ApiResponse.success(failures.preview(request));
    }

    /** API-ING-10 재처리 작업 생성 — 202(긴 작업) + Location */
    @Idempotent(required = true)
    @PostMapping("/core/ingest/reprocess-jobs")
    public ResponseEntity<ApiResponse<ReprocessJobResponse>> createJob(@RequestBody(required = false) ReprocessJobRequest request) {
        ReprocessJobResponse created = failures.createJob(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).location(URI.create("/api/v1/core/ingest/reprocess-jobs/" + created.jobId()))
                .body(ApiResponse.success(created));
    }

    /** API-ING-12 재처리 작업 취소 */
    @Idempotent
    @PostMapping("/core/ingest/reprocess-jobs/{job-id}/cancel")
    public ApiResponse<CancelJobResponse> cancelJob(@PathVariable("job-id") long jobId) {
        return ApiResponse.success(failures.cancelJob(jobId));
    }

    /** API-ING-13 데이터 품질 */
    @GetMapping("/core/ingest/quality")
    public ListApiResponse<QualityItem> quality(@RequestParam(name = "day", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                                                @RequestParam(name = "groupBy", required = false) String groupBy,
                                                @RequestParam(name = "spaceId", required = false) Long spaceId,
                                                @RequestParam(name = "page", required = false) Integer page,
                                                @RequestParam(name = "size", required = false) Integer size) {
        return quality.quality(day, groupBy, spaceId, page, size);
    }

    /** API-ING-15 수신 공백(ING-06.05) */
    @GetMapping("/core/ingest/gaps")
    public ListApiResponse<GapItem> gaps(@RequestParam(name = "deviceId", required = false) Long deviceId,
                                         @RequestParam(name = "from", required = false) Instant from,
                                         @RequestParam(name = "to", required = false) Instant to,
                                         @RequestParam(name = "page", required = false) Integer page,
                                         @RequestParam(name = "size", required = false) Integer size) {
        return quality.gaps(deviceId, from, to, page, size);
    }
}
