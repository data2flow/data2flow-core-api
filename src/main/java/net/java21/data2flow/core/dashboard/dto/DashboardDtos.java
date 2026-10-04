package net.java21.data2flow.core.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** 홈·공간 보기·수집 흐름·내 화면 설정 API DTO(API-DSH-01·02·05·12) */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    // ---- 공간 쾌적도(DSH-01.02) ----

    /** 판정에 쓴 목표 범위 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TargetRange(Double min, Double max) {
    }

    /** 원인 측정 항목(가장 많이 벗어난 순) */
    public record ComfortCause(String metricKey, double value, String unit, TargetRange target) {
    }

    /** 공간 하나의 쾌적도. state: NORMAL·CAUTION·WARNING·UNKNOWN */
    public record ComfortView(String state, List<ComfortCause> causes, Instant updatedAt) {
    }

    /** 홈 쾌적도 목록의 한 줄(API-DSH-01 comfort[]) */
    public record ComfortRow(String spaceId, String spaceName, String state, List<ComfortCause> causes, Instant updatedAt) {
    }

    // ---- 홈 요약(API-DSH-01) ----

    /** 열린 알람 수(심각도별). 알람은 M4라 M2에서는 모두 0 */
    public record AlarmCounts(int critical, int major, int minor, int warning, int info) {
        public static final AlarmCounts NONE = new AlarmCounts(0, 0, 0, 0, 0);
    }

    public record SourceSummary(long connected, long total) {
    }

    /**
     * 홈 요약. pendingDevices는 기기 배치 권한(DEV_PLACE)이 있을 때만, sources는 소스 조회 권한(SRC_READ)이 있을 때만 싣는다.
     * timeline은 최근 7일 알람 발생·해제와 제어 20건(M4 DSH-01.03), aiSummary(M7)는 아직 원천이 없어 생략이다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HomeSummaryResponse(AlarmCounts alarms, long offlineDevices, Long pendingDevices, double ingestPerMinute,
                                      SourceSummary sources, List<ComfortRow> comfort, int comfortTotal,
                                      List<TimelineItem> timeline, Object aiSummary) {
    }

    /** 타임라인 항목(ALARM_RAISED·ALARM_CLEARED·CONTROL). M4에서 채운다 */
    public record TimelineItem(String type, Instant at, String title, String severity, String origin, String link) {
    }

    // ---- 공간 요약(API-DSH-02) ----

    public record PathItem(String id, String name) {
    }

    /** 목표 환경 한 줄. inheritedFromSpaceId는 상위에서 물려받았을 때 그 공간 */
    public record TargetEnv(String metricKey, Double min, Double max, String inheritedFromSpaceId) {
    }

    public record SpaceInfo(String id, String name, String type, List<PathItem> path, List<TargetEnv> targetEnv) {
    }

    public record MetricView(String key, double value, String unit, Integer quality, Instant at) {
    }

    public record OverviewDevice(String id, String name, String modelId, String modelName, String status, String connection,
                                 Instant lastSeenAt, Double battery, Double rssi, boolean virtual, List<MetricView> metrics) {
    }

    public record ChildSpace(String id, String name, String type, String comfortState) {
    }

    /** openAlarms: 이 공간과 하위의 열린 알람(API-RUL-10 Alarm, M4 DSH-02.01) */
    public record SpaceOverviewResponse(SpaceInfo space, ComfortView comfort, List<OverviewDevice> devices, List<Object> openAlarms,
                                        boolean hasFloorplan, List<ChildSpace> children) {
    }

    // ---- 수집 흐름(API-DSH-05, API-DSH-20 ingest) ----

    /**
     * 단계 하나. key: SOURCE·DECODE·SCRIPT·VALIDATE·STORE·PUBLISH(EVENT는 PUBLISH로 바뀜, 2026-10-04 결정).
     * inPerMin·failPerMin은 최근 5분 평균(분당). latencyP95Ms는 원천(파이프라인 지표 집계)이 생길 때까지 null
     */
    public record StageSnapshot(String key, double inPerMin, double failPerMin, Long latencyP95Ms, String failureLink) {
    }

    /** 소스 하나. state: CONNECTED·CONNECTING·ERROR·DISCONNECTED·DISABLED(운영 중지·초안) */
    public record SourceSnapshot(String id, String name, String state, double perMin, Instant lastMessageAt) {
    }

    /** 소스별 분당 처리량. points = [[t(ISO-8601 UTC), perMin]] */
    public record Throughput(String sourceId, List<List<Object>> points) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IngestMonitorResponse(String window, List<StageSnapshot> stages, List<SourceSnapshot> sources,
                                        List<Throughput> throughput) {
    }

    // ---- 내 화면 설정(API-DSH-12) ----

    /** 즐겨찾기·최근 항목. name은 보이는 대상만(대시보드·플로우·분석은 그 기능이 생기기 전까지 null) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemRef(String type, String id, String name, Instant at) {
    }

    /**
     * 내 화면 설정. timeZone·locale·temperatureUnit은 사용자가 직접 정한 값(null이면 조직·계정 값을 따름)이고
     * effective*는 실제로 쓰는 값이다(DSH-07.04: 사용자 시간대가 없으면 조직 기본값).
     */
    public record PreferencesResponse(String theme, String locale, String effectiveLocale, String timeZone, String effectiveTimeZone,
                                      String organizationTimeZone, String home, String defaultDashboardId, List<ItemRef> favorites,
                                      List<ItemRef> recent, List<String> toursDismissed, String temperatureUnit,
                                      String effectiveTemperatureUnit, int version) {
    }

    /** 최근 본 항목 기록(API-DSH-12 POST …/recent) */
    public record RecentRequest(String type, String id) {
    }

    public record RecentResponse(List<ItemRef> recent) {
    }
}
