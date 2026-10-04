package net.java21.data2flow.core.ingest.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.ingest.dto.IngestInsightDtos.GapsResponse;
import net.java21.data2flow.core.ingest.dto.IngestInsightDtos.QualitySummary;
import net.java21.data2flow.core.ingest.dto.IngestInsightDtos.QualityTrend;
import net.java21.data2flow.core.ingest.dto.IngestInsightDtos.ReprocessJobItem;
import net.java21.data2flow.core.ingest.service.IngestInsightService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;

/** M5 수집 관리 조회: 재처리 작업(API-ING-14), 품질 추이·요약(ING-06.02), 공백·완전성(API-TSD-09, TSD-02.04) */
@RestController
public class IngestInsightController {

    private final IngestInsightService service;

    public IngestInsightController(IngestInsightService service) {
        this.service = service;
    }

    /** API-ING-14 재처리 작업 목록(status?, sourceId?) */
    @GetMapping("/core/ingest/reprocess-jobs")
    public ListApiResponse<ReprocessJobItem> jobs(@RequestParam(required = false) String status, @RequestParam(required = false) Long sourceId,
                                                  @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.jobs(status, sourceId, page, size);
    }

    /** API-ING-14 재처리 작업 상세 */
    @GetMapping("/core/ingest/reprocess-jobs/{job-id}")
    public ApiResponse<ReprocessJobItem> job(@PathVariable("job-id") long jobId) {
        return ApiResponse.success(service.job(jobId));
    }

    /** ING-06.02 품질 추이(groupBy=device|space|model, targetId, from·to 날짜, 최대 31일) */
    @GetMapping("/core/ingest/quality/trend")
    public ApiResponse<QualityTrend> trend(@RequestParam(required = false) String groupBy, @RequestParam(required = false) Long targetId,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(service.trend(groupBy, targetId, from, to));
    }

    /** ING-06.02 품질 요약(최하위 10개·문제 유형 분포) */
    @GetMapping("/core/ingest/quality/summary")
    public ApiResponse<QualitySummary> summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                                               @RequestParam(required = false) String groupBy, @RequestParam(required = false) Long spaceId) {
        return ApiResponse.success(service.summary(day, groupBy, spaceId));
    }

    /** API-TSD-09 공백·완전성(deviceId 또는 spaceId, from·to) */
    @GetMapping("/core/telemetry/gaps")
    public ApiResponse<GapsResponse> gaps(@RequestParam(required = false) Long deviceId, @RequestParam(required = false) Long spaceId,
                                          @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return ApiResponse.success(service.gaps(deviceId, spaceId, from, to));
    }
}
