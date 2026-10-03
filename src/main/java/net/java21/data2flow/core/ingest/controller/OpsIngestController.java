package net.java21.data2flow.core.ingest.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.OpsIngestMetricsResponse;
import net.java21.data2flow.core.ingest.dto.IngestDtos.OpsThresholdsRequest;
import net.java21.data2flow.core.ingest.dto.IngestDtos.OpsThresholdsResponse;
import net.java21.data2flow.core.ingest.service.IngestMonitorService;
import net.java21.data2flow.core.ingest.service.IngestSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영 화면의 수집 부분(design/api/OPS-api.md §1, ADMIN·OPS_MANAGE): API-OPS-02 수집 지표(OPS-01.02), API-OPS-05 운영 알람 기준(OPS-01.05).
 * 구성 요소 상태(API-OPS-01)·로그 레벨(API-OPS-07)·진단 묶음(API-OPS-90)은 k8s·Prometheus 연동이 필요해 이 묶음(WP-E)에 넣지 않았다.
 */
@RestController
public class OpsIngestController {

    private final IngestMonitorService monitor;
    private final IngestSettingsService settings;

    public OpsIngestController(IngestMonitorService monitor, IngestSettingsService settings) {
        this.monitor = monitor;
        this.settings = settings;
    }

    /** API-OPS-02 수집 지표(range 24h 기본·7d) */
    @GetMapping("/core/ops/metrics/ingest")
    public ApiResponse<OpsIngestMetricsResponse> ingestMetrics(@RequestParam(name = "range", required = false) String range) {
        return ApiResponse.success(monitor.opsMetrics(range));
    }

    /** API-OPS-05 운영 알람 기준 조회 */
    @GetMapping("/core/ops/thresholds")
    public ApiResponse<OpsThresholdsResponse> thresholds() {
        return ApiResponse.success(settings.opsThresholds());
    }

    /** API-OPS-05 운영 알람 기준 변경 */
    @PutMapping("/core/ops/thresholds")
    public ApiResponse<OpsThresholdsResponse> updateThresholds(@RequestBody(required = false) OpsThresholdsRequest request) {
        return ApiResponse.success(settings.updateOpsThresholds(request));
    }
}
