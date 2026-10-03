package net.java21.data2flow.core.telemetry.dto;

import java.time.Instant;
import java.util.List;

/**
 * 시계열 조회 요청·응답(design/api/TSD-api.md API-TSD-01~05·08). ID는 JSON 문자열, 시각은 UTC ISO-8601.
 * 점은 {@code [시각, 값, 품질(원본) 또는 표본 수·기여 기기 수(집계)]} 배열이다.
 */
public final class TelemetryDtos {

    private TelemetryDtos() {
    }

    /** API-TSD-01 현재값 한 기기 */
    public record LatestDeviceResponse(String deviceId, String deviceName, boolean virtual, String connectivity, Instant lastSeenAt,
                                       List<LatestMetricResponse> metrics) {
    }

    /** API-TSD-01 측정 항목 하나({@code device_state.latest}) */
    public record LatestMetricResponse(String key, Double value, String unit, Instant measuredAt, Integer quality) {
    }

    /**
     * API-TSD-02·04 응답.
     *
     * @param resolutionUsed      raw·1m·1h·1d
     * @param reason              AUTO·REQUESTED·CAPPED
     * @param truncated           원본 계열 하나라도 10,000점을 넘어 잘렸는가
     * @param timezone            표시 시간대(BR-TSD-21: tz → 사용자 설정 → 조직 → Asia/Seoul). 시각 값은 모두 UTC다
     * @param excludedDeviceCount 권한 범위 밖이거나 없는 기기라 뺀 수(BR-TSD-13, ID는 드러내지 않음)
     */
    public record SeriesResponse(String resolutionUsed, String reason, boolean truncated, String timezone, int excludedDeviceCount,
                                 List<SeriesItem> series) {
    }

    /**
     * 계열 하나. 기기 계열이면 deviceId, 공간 계열이면 spaceId와 기여 기기 수(contributingDevices = 전체 대상 기기 수).
     * 원본이 잘리면 nextCursor(API-TSD-02 원본 점 넘겨 보기의 cursor)와 남은 범위를 준다(TSD-06.01).
     */
    public record SeriesItem(String deviceId, String spaceId, String metric, String label, String unit, String agg, boolean virtual,
                             List<List<Object>> points, List<GapResponse> gaps, Integer deviceCount, String nextCursor,
                             RangeResponse remaining) {
    }

    /** 공백·범위 {from, to} */
    public record GapResponse(Instant from, Instant to) {
    }

    /** 남은 범위 */
    public record RangeResponse(Instant from, Instant to) {
    }

    /** API-TSD-03 공간 집계 응답(BR-TSD-11) */
    public record SpaceSeriesResponse(String spaceId, String metric, String unit, String func, String resolutionUsed, String reason,
                                      String timezone, int deviceCount, int excludedDeviceCount, List<List<Object>> points) {
    }

    /**
     * API-TSD-04 요청. {@code virtual}과 {@code includeVirtual}은 같은 뜻이다(웹 query.ts는 virtual로 보낸다, TSD-03.05).
     */
    public record QueryRequest(List<QuerySeries> series, Instant from, Instant to, String resolution, String fill, String quality,
                               Boolean virtual, Boolean includeVirtual, Boolean includeForecast, String tz) {
    }

    /** API-TSD-04 계열 하나: deviceId 또는 spaceId 중 하나 */
    public record QuerySeries(Long deviceId, Long spaceId, String metric, String agg, String label) {
    }

    /** 원본 점(커서 목록, api-rules §3 시계열 원본 점) */
    public record RawPointResponse(Instant t, Double value, Integer quality, String metric) {
    }

    /** API-TSD-05 상태 구간 */
    public record StateIntervalResponse(Instant from, Instant to, Double value, String label, Object source) {
    }

    /** API-TSD-08 통신 품질 점(게이트웨이별). 게이트웨이 정보가 없으면 gatewayEui null */
    public record LinkQualityResponse(Instant t, String gatewayEui, Double rssi, Double snr) {
    }
}
