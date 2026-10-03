package net.java21.data2flow.core.telemetry.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.CursorListApiResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.LatestDeviceResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.LinkQualityResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.QueryRequest;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.RawPointResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.SeriesResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.SpaceSeriesResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.StateIntervalResponse;
import net.java21.data2flow.core.telemetry.service.TelemetryQueryService;
import net.java21.data2flow.core.telemetry.service.TelemetryQueryService.SeriesQuery;
import net.java21.data2flow.core.telemetry.service.TelemetryQueryService.SpaceQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 시계열 조회 REST API(TSD-03.06, design/api/TSD-api.md §1) — TS_READ(VIEWER 이상). 외부 경로 {@code /api/v1/core/telemetry/**}.
 * 여러 값 파라미터(metrics, deviceIds, models, tags)는 쉼표 구분과 반복 둘 다 받는다.
 * 가상 데이터 포함은 {@code virtual}(웹 query.ts)과 {@code includeVirtual} 둘 다 받는다(TSD-03.05).
 */
@RestController
public class TelemetryController {

    private final TelemetryQueryService service;

    public TelemetryController(TelemetryQueryService service) {
        this.service = service;
    }

    /** API-TSD-01 현재값(NFR-01.05) */
    @GetMapping("/core/telemetry/latest")
    public ApiResponse<List<LatestDeviceResponse>> latest(@RequestParam(name = "deviceIds", required = false) List<Long> deviceIds,
                                                          @RequestParam(name = "spaceId", required = false) Long spaceId,
                                                          @RequestParam(name = "includeDescendants", required = false) Boolean includeDescendants,
                                                          @RequestParam(name = "metrics", required = false) List<String> metrics,
                                                          @RequestParam(name = "virtual", required = false) Boolean virtual,
                                                          @RequestParam(name = "includeVirtual", required = false) Boolean includeVirtual) {
        return ApiResponse.success(service.latest(deviceIds, spaceId, includeDescendants, metrics, virtual != null ? virtual : includeVirtual));
    }

    /** API-TSD-02 기기 한 대의 시계열(TSD-03.01·03.04·03.05, TSD-06.01·06.02, TSD-01.05) */
    @GetMapping("/core/telemetry/series")
    public ApiResponse<SeriesResponse> series(@RequestParam(name = "deviceId", required = false) Long deviceId,
                                              @RequestParam(name = "metrics", required = false) List<String> metrics,
                                              @RequestParam(name = "from", required = false) Instant from,
                                              @RequestParam(name = "to", required = false) Instant to,
                                              @RequestParam(name = "resolution", required = false) String resolution,
                                              @RequestParam(name = "agg", required = false) String agg,
                                              @RequestParam(name = "fill", required = false) String fill,
                                              @RequestParam(name = "quality", required = false) String quality,
                                              @RequestParam(name = "includeForecast", required = false) Boolean includeForecast,
                                              @RequestParam(name = "virtual", required = false) Boolean virtual,
                                              @RequestParam(name = "includeVirtual", required = false) Boolean includeVirtual,
                                              @RequestParam(name = "tz", required = false) String tz) {
        return ApiResponse.success(service.series(new SeriesQuery(deviceId, metrics, from, to, resolution, agg, fill, quality, includeForecast,
                virtual != null ? virtual : includeVirtual, tz)));
    }

    /** API-TSD-02 원본 점 넘겨 보기(커서 목록, TSD-06.01) */
    @GetMapping("/core/telemetry/raw-points")
    public CursorListApiResponse<RawPointResponse> rawPoints(@RequestParam(name = "deviceId", required = false) Long deviceId,
                                                             @RequestParam(name = "metric", required = false) String metric,
                                                             @RequestParam(name = "from", required = false) Instant from,
                                                             @RequestParam(name = "to", required = false) Instant to,
                                                             @RequestParam(name = "quality", required = false) String quality,
                                                             @RequestParam(name = "includeForecast", required = false) Boolean includeForecast,
                                                             @RequestParam(name = "virtual", required = false) Boolean virtual,
                                                             @RequestParam(name = "includeVirtual", required = false) Boolean includeVirtual,
                                                             @RequestParam(name = "cursor", required = false) String cursor,
                                                             @RequestParam(name = "size", required = false) Integer size) {
        return service.rawPoints(deviceId, metric, from, to, quality, includeForecast, virtual != null ? virtual : includeVirtual, cursor, size);
    }

    /** API-TSD-03 공간 집계(TSD-03.02, BR-TSD-11) */
    @GetMapping("/core/telemetry/space-series")
    public ApiResponse<SpaceSeriesResponse> spaceSeries(@RequestParam(name = "spaceId", required = false) Long spaceId,
                                                        @RequestParam(name = "metric", required = false) String metric,
                                                        @RequestParam(name = "includeDescendants", required = false) Boolean includeDescendants,
                                                        @RequestParam(name = "func", required = false) String func,
                                                        @RequestParam(name = "from", required = false) Instant from,
                                                        @RequestParam(name = "to", required = false) Instant to,
                                                        @RequestParam(name = "resolution", required = false) String resolution,
                                                        @RequestParam(name = "models", required = false) List<Long> models,
                                                        @RequestParam(name = "tags", required = false) List<String> tags,
                                                        @RequestParam(name = "virtual", required = false) Boolean virtual,
                                                        @RequestParam(name = "includeVirtual", required = false) Boolean includeVirtual,
                                                        @RequestParam(name = "tz", required = false) String tz) {
        return ApiResponse.success(service.spaceSeries(new SpaceQuery(spaceId, metric, includeDescendants, func, from, to, resolution, models,
                tags, virtual != null ? virtual : includeVirtual, tz)));
    }

    /** API-TSD-04 여러 계열 조회(≤50, TSD-03.03) */
    @PostMapping("/core/telemetry/query")
    public ApiResponse<SeriesResponse> query(@RequestBody QueryRequest request) {
        return ApiResponse.success(service.query(request));
    }

    /** API-TSD-05 상태 구간 */
    @GetMapping("/core/telemetry/state-intervals")
    public ApiResponse<List<StateIntervalResponse>> stateIntervals(@RequestParam(name = "deviceId", required = false) Long deviceId,
                                                                   @RequestParam(name = "metric", required = false) String metric,
                                                                   @RequestParam(name = "actuator", required = false) Boolean actuator,
                                                                   @RequestParam(name = "from", required = false) Instant from,
                                                                   @RequestParam(name = "to", required = false) Instant to,
                                                                   @RequestParam(name = "virtual", required = false) Boolean virtual) {
        return ApiResponse.success(service.stateIntervals(deviceId, metric, actuator, from, to, virtual));
    }

    /** API-TSD-08 통신 품질(TSD-01.02) */
    @GetMapping("/core/telemetry/link-quality")
    public ApiResponse<List<LinkQualityResponse>> linkQuality(@RequestParam(name = "deviceId", required = false) Long deviceId,
                                                              @RequestParam(name = "from", required = false) Instant from,
                                                              @RequestParam(name = "to", required = false) Instant to,
                                                              @RequestParam(name = "resolution", required = false) String resolution) {
        return ApiResponse.success(service.linkQuality(deviceId, from, to, resolution));
    }
}
