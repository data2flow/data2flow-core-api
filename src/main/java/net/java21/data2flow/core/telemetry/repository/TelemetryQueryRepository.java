package net.java21.data2flow.core.telemetry.repository;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.telemetry.domain.AggFunction;
import net.java21.data2flow.core.telemetry.domain.FillMode.Gap;
import net.java21.data2flow.core.telemetry.domain.Resolution;
import net.java21.data2flow.core.telemetry.domain.SeriesPoint;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 시계열 조회(TSD-03). pipeline 소유 테이블({@code data2flow_pipeline.telemetry}·집계·{@code link_qualities}·{@code device_state}·
 * {@code data_gaps})은 읽기만 한다(conventions §6 예외, domain-model "시계열은 읽기 전용 계정으로 조회"). 기기·공간·측정 항목은 core 테이블을
 * 읽기 전용으로 함께 본다. 모든 조회에 organization_id 조건을 건다(BR-IAM-01).
 * 집계 테이블 이름과 열 이름은 {@link Resolution}·{@link AggFunction}의 정해진 값만 SQL에 넣는다.
 */
@Repository
public class TelemetryQueryRepository {

    private static final String P = "data2flow_pipeline.";

    private final JdbcClient jdbc;

    public TelemetryQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 기기 한 대(DELETED 제외). 사이트 시간대는 공간 경로의 최상위(SITE) 공간 값 */
    public Optional<DeviceInfo> findDevice(long organizationId, long deviceId) {
        return jdbc.sql("""
                        SELECT d.id, d.name, d.space_id, d.status, d.is_virtual,
                               coalesce(d.expected_interval_sec, m.default_interval_sec, 600) AS interval_sec,
                               (SELECT site.timezone FROM data2flow_core.spaces sp
                                  JOIN data2flow_core.spaces site ON site.id = CAST(split_part(sp.path, '/', 2) AS bigint)
                                 WHERE sp.id = d.space_id) AS site_tz
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_core.device_models m ON m.id = d.model_id
                         WHERE d.organization_id = :org AND d.id = :id AND d.status <> 'DELETED'""")
                .param("org", organizationId).param("id", deviceId)
                .query(TelemetryQueryRepository::device).optional();
    }

    /** 여러 기기(DELETED 제외), ID 순 */
    public List<DeviceInfo> findDevices(long organizationId, Collection<Long> deviceIds) {
        return jdbc.sql("""
                        SELECT d.id, d.name, d.space_id, d.status, d.is_virtual,
                               coalesce(d.expected_interval_sec, m.default_interval_sec, 600) AS interval_sec, NULL AS site_tz
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_core.device_models m ON m.id = d.model_id
                         WHERE d.organization_id = :org AND d.id = ANY(CAST(:ids AS bigint[])) AND d.status <> 'DELETED'
                         ORDER BY d.id""")
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds))
                .query(TelemetryQueryRepository::device).list();
    }

    /** 측정 항목 정의(키 → 단위·값 종류·기본 집계) */
    public Map<String, MetricInfo> findMetrics(long organizationId, Collection<String> keys) {
        Map<String, MetricInfo> result = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT key, unit, value_type, agg_default, enum_map::text AS enum_map FROM data2flow_core.metrics
                         WHERE organization_id = :org AND key = ANY(CAST(:keys AS varchar[]))""")
                .param("org", organizationId).param("keys", Pg.textArray(keys))
                .query((rs, n) -> new MetricInfo(rs.getString("key"), rs.getString("unit"), rs.getString("value_type"),
                        rs.getString("agg_default"), rs.getString("enum_map")))
                .list().forEach(m -> result.put(m.key(), m));
        return result;
    }

    /** 조직 측정 항목 전체의 단위(현재값 응답용) */
    public Map<String, String> findUnits(long organizationId) {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.sql("SELECT key, unit FROM data2flow_core.metrics WHERE organization_id = :org")
                .param("org", organizationId)
                .query((rs, n) -> Map.entry(rs.getString("key"), rs.getString("unit") == null ? "" : rs.getString("unit")))
                .list().forEach(e -> result.put(e.getKey(), e.getValue().isEmpty() ? null : e.getValue()));
        return result;
    }

    /** 원본 점 수(최대 limit까지만 센다) */
    public long countRaw(long organizationId, long deviceId, String metric, Instant from, Instant to, String qualities, boolean virtual,
                         int limit) {
        return jdbc.sql("""
                        SELECT count(*) FROM (SELECT 1 FROM data2flow_pipeline.telemetry
                         WHERE organization_id = :org AND device_id = :device AND metric_key = :metric AND time >= :from AND time < :to
                           AND quality = ANY(CAST(:q AS smallint[])) AND (:virtual OR NOT is_virtual) LIMIT :limit) x""")
                .param("org", organizationId).param("device", deviceId).param("metric", metric).param("from", Pg.ts(from))
                .param("to", Pg.ts(to)).param("q", qualities).param("virtual", virtual).param("limit", limit)
                .query(Long.class).single();
    }

    /** 원본 점(시각 오름차순). after가 있으면 그 시각 다음부터(커서) */
    public List<SeriesPoint> findRawPoints(long organizationId, long deviceId, String metric, Instant from, Instant to, String qualities,
                                           boolean virtual, Instant after, int limit) {
        return jdbc.sql("""
                        SELECT time, value, quality FROM data2flow_pipeline.telemetry
                         WHERE organization_id = :org AND device_id = :device AND metric_key = :metric AND time >= :from AND time < :to
                           AND quality = ANY(CAST(:q AS smallint[])) AND (:virtual OR NOT is_virtual)
                           AND time > :after
                         ORDER BY time LIMIT :limit""")
                .param("org", organizationId).param("device", deviceId).param("metric", metric).param("from", Pg.ts(from))
                .param("to", Pg.ts(to)).param("q", qualities).param("virtual", virtual)
                .param("after", Pg.ts(after == null ? from.minusNanos(1000) : after))
                .param("limit", limit)
                .query((rs, n) -> new SeriesPoint(Pg.instant(rs, "time"), rs.getDouble("value"), rs.getInt("quality"))).list();
    }

    /** 이 시각 직전의 원본 값(상태 구간의 시작 값). 7일 안에서만 찾는다 */
    public Optional<SeriesPoint> findLastRawBefore(long organizationId, long deviceId, String metric, Instant before, boolean virtual) {
        return jdbc.sql("""
                        SELECT time, value, quality FROM data2flow_pipeline.telemetry
                         WHERE organization_id = :org AND device_id = :device AND metric_key = :metric
                           AND time < :before AND time >= :since AND quality IN (0, 4) AND (:virtual OR NOT is_virtual)
                         ORDER BY time DESC LIMIT 1""")
                .param("org", organizationId).param("device", deviceId).param("metric", metric).param("before", Pg.ts(before))
                .param("since", Pg.ts(before.minusSeconds(7 * 86400L))).param("virtual", virtual)
                .query((rs, n) -> new SeriesPoint(Pg.instant(rs, "time"), rs.getDouble("value"), rs.getInt("quality"))).optional();
    }

    /**
     * 기기 한 대의 집계 점. 세 번째 값은 표본 수(quality=all이면 품질 무관 count_all). 구간이 기간에 걸치면 포함한다.
     */
    public List<SeriesPoint> findAggregatePoints(long organizationId, Resolution level, long deviceId, String metric, Instant lowerBound,
                                                 Instant to, AggFunction agg, boolean allQualities, boolean virtual) {
        String countColumn = allQualities ? "count_all" : "count";
        String valueColumn = agg == AggFunction.COUNT ? countColumn : agg.column();
        return jdbc.sql("""
                        SELECT bucket, %s AS v, %s AS n FROM %s
                         WHERE organization_id = :org AND device_id = :device AND metric_key = :metric AND bucket > :lower AND bucket < :to
                           AND (:virtual OR NOT is_virtual)
                         ORDER BY bucket""".formatted(valueColumn, countColumn, P + level.table()))
                .param("org", organizationId).param("device", deviceId).param("metric", metric).param("lower", Pg.ts(lowerBound))
                .param("to", Pg.ts(to)).param("virtual", virtual)
                .query((rs, n) -> new SeriesPoint(Pg.instant(rs, "bucket"), doubleOrNull(rs, "v"), rs.getInt("n"))).list();
    }

    /** 기기의 데이터 공백(BR-ING-17) 중 기간에 걸치는 것 */
    public List<Gap> findGaps(long organizationId, long deviceId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT gap_start, gap_end FROM data2flow_pipeline.data_gaps
                         WHERE organization_id = :org AND device_id = :device AND gap_start < :to AND gap_end > :from
                         ORDER BY gap_start LIMIT 1000""")
                .param("org", organizationId).param("device", deviceId).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query((rs, n) -> new Gap(Pg.instant(rs, "gap_start"), Pg.instant(rs, "gap_end"))).list();
    }

    /** 공간(ACTIVE)과 사이트 시간대 */
    public Optional<SpaceInfo> findSpace(long organizationId, long spaceId) {
        return jdbc.sql("""
                        SELECT sp.id, sp.path, site.timezone AS site_tz FROM data2flow_core.spaces sp
                          LEFT JOIN data2flow_core.spaces site ON site.id = CAST(split_part(sp.path, '/', 2) AS bigint)
                         WHERE sp.organization_id = :org AND sp.id = :id AND sp.status = 'ACTIVE'""")
                .param("org", organizationId).param("id", spaceId)
                .query((rs, n) -> new SpaceInfo(rs.getLong("id"), rs.getString("path"), rs.getString("site_tz"))).optional();
    }

    /**
     * 공간 집계 대상 기기(BR-TSD-11): 공간(하위 포함 옵션)의 ACTIVE 기기. 가상 기기는 virtual=true일 때만. 모델·태그 조건은 있으면 건다.
     * 권한 범위(IAM-04.06)는 서비스가 공간 ID로 나눈다.
     */
    public List<SpaceDevice> findSpaceDevices(long organizationId, SpaceInfo space, boolean includeDescendants, boolean virtual,
                                              List<Long> modelIds, List<String> tags) {
        return jdbc.sql("""
                        SELECT d.id, d.space_id FROM data2flow_core.devices d
                          JOIN data2flow_core.spaces s ON s.id = d.space_id
                         WHERE d.organization_id = :org AND d.status = 'ACTIVE' AND (:virtual OR NOT d.is_virtual)
                           AND (CASE WHEN :desc THEN s.path LIKE :path || '%' ELSE d.space_id = :space END)
                           AND (cardinality(CAST(:models AS bigint[])) = 0 OR d.model_id = ANY(CAST(:models AS bigint[])))
                           AND (cardinality(CAST(:tags AS varchar[])) = 0 OR EXISTS (SELECT 1 FROM data2flow_core.device_tags t
                                 WHERE t.device_id = d.id AND t.tag = ANY(CAST(:tags AS varchar[]))))
                         ORDER BY d.id""")
                .param("org", organizationId).param("virtual", virtual).param("desc", includeDescendants).param("path", space.path())
                .param("space", space.id()).param("models", Pg.bigintArray(modelIds)).param("tags", Pg.textArray(tags))
                .query((rs, n) -> new SpaceDevice(rs.getLong("id"), Pg.longOrNull(rs, "space_id"))).list();
    }

    /**
     * 공간 집계 점: 기기마다의 구간 값({@code column})을 함수({@code func})로 합치고, 세 번째 값은 그 구간에 값이 있던 기기 수
     * (기여 기기 수, AT-TSD-02.1·02.2).
     */
    public List<SeriesPoint> findSpaceAggregate(long organizationId, Resolution level, String metric, Collection<Long> deviceIds,
                                                Instant lowerBound, Instant to, AggFunction column, AggFunction func, boolean virtual) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT bucket, %s(%s) AS v, count(DISTINCT device_id) AS n FROM %s
                         WHERE organization_id = :org AND metric_key = :metric AND device_id = ANY(CAST(:ids AS bigint[]))
                           AND bucket > :lower AND bucket < :to AND (:virtual OR NOT is_virtual) AND %s IS NOT NULL
                         GROUP BY bucket ORDER BY bucket""".formatted(func.key(), column.column(), P + level.table(), column.column()))
                .param("org", organizationId).param("metric", metric).param("ids", Pg.bigintArray(deviceIds))
                .param("lower", Pg.ts(lowerBound)).param("to", Pg.ts(to)).param("virtual", virtual)
                .query((rs, n) -> new SeriesPoint(Pg.instant(rs, "bucket"), doubleOrNull(rs, "v"), rs.getInt("n"))).list();
    }

    /** 현재값 대상 기기(ID 목록) + device_state */
    public List<LatestRow> findLatestByIds(long organizationId, Collection<Long> deviceIds, boolean virtual) {
        return jdbc.sql(LATEST_SELECT + """
                         WHERE d.organization_id = :org AND d.id = ANY(CAST(:ids AS bigint[])) AND d.status <> 'DELETED'
                           AND (:virtual OR NOT d.is_virtual)
                         ORDER BY d.id""")
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds)).param("virtual", virtual)
                .query(TelemetryQueryRepository::latest).list();
    }

    /** 현재값 대상 기기(공간, 하위 포함 옵션) + device_state */
    public List<LatestRow> findLatestBySpace(long organizationId, SpaceInfo space, boolean includeDescendants, boolean virtual) {
        return jdbc.sql(LATEST_SELECT + """
                          JOIN data2flow_core.spaces s ON s.id = d.space_id
                         WHERE d.organization_id = :org AND d.status <> 'DELETED' AND (:virtual OR NOT d.is_virtual)
                           AND (CASE WHEN :desc THEN s.path LIKE :path || '%' ELSE d.space_id = :space END)
                         ORDER BY d.id LIMIT 500""")
                .param("org", organizationId).param("virtual", virtual).param("desc", includeDescendants).param("path", space.path())
                .param("space", space.id())
                .query(TelemetryQueryRepository::latest).list();
    }

    private static final String LATEST_SELECT = """
            SELECT d.id, d.name, d.space_id, d.is_virtual, ds.connectivity, ds.last_seen_at, ds.latest::text AS latest
              FROM data2flow_core.devices d
              LEFT JOIN data2flow_pipeline.device_state ds ON ds.device_id = d.id AND ds.organization_id = d.organization_id
            """;

    /** 통신 품질 원본(게이트웨이별, TSD-01.02) */
    public List<LinkRow> findLinkRaw(long organizationId, long deviceId, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT time AS t, gateway_eui, rssi::float8 AS rssi, snr::float8 AS snr FROM data2flow_pipeline.link_qualities
                         WHERE organization_id = :org AND device_id = :device AND time >= :from AND time < :to
                         ORDER BY time, gateway_eui LIMIT :limit""")
                .param("org", organizationId).param("device", deviceId).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .param("limit", limit)
                .query(TelemetryQueryRepository::link).list();
    }

    /** 통신 품질 구간 평균(게이트웨이별, AT-TSD-15.6). step은 '1 minute'·'1 hour'·'1 day' */
    public List<LinkRow> findLinkAggregated(long organizationId, long deviceId, Instant from, Instant to, String step, int limit) {
        return jdbc.sql("""
                        SELECT date_bin(CAST(:step AS interval), time, TIMESTAMPTZ '2000-01-01 00:00:00+00') AS t, gateway_eui,
                               avg(rssi)::float8 AS rssi, avg(snr)::float8 AS snr
                          FROM data2flow_pipeline.link_qualities
                         WHERE organization_id = :org AND device_id = :device AND time >= :from AND time < :to
                         GROUP BY 1, 2 ORDER BY 1, 2 LIMIT :limit""")
                .param("step", step).param("org", organizationId).param("device", deviceId).param("from", Pg.ts(from))
                .param("to", Pg.ts(to)).param("limit", limit)
                .query(TelemetryQueryRepository::link).list();
    }

    /** 표시 시간대(BR-TSD-21): 사용자 설정 → 조직 설정 → Asia/Seoul */
    public String findTimezone(long organizationId, long userId) {
        return jdbc.sql("""
                        SELECT coalesce((SELECT nullif(time_zone, '') FROM data2flow_core.user_dashboard_prefs
                                          WHERE organization_id = :org AND user_id = :user),
                                        (SELECT timezone FROM data2flow_core.org_settings WHERE organization_id = :org),
                                        'Asia/Seoul')""")
                .param("org", organizationId).param("user", userId).query(String.class).single();
    }

    private static DeviceInfo device(ResultSet rs, int n) throws SQLException {
        return new DeviceInfo(rs.getLong("id"), rs.getString("name"), Pg.longOrNull(rs, "space_id"), rs.getString("status"),
                rs.getBoolean("is_virtual"), rs.getInt("interval_sec"), rs.getString("site_tz"));
    }

    private static LatestRow latest(ResultSet rs, int n) throws SQLException {
        return new LatestRow(rs.getLong("id"), rs.getString("name"), Pg.longOrNull(rs, "space_id"), rs.getBoolean("is_virtual"),
                rs.getString("connectivity"), Pg.instant(rs, "last_seen_at"), rs.getString("latest"));
    }

    private static LinkRow link(ResultSet rs, int n) throws SQLException {
        String eui = rs.getString("gateway_eui");
        return new LinkRow(Pg.instant(rs, "t"), eui == null || eui.isEmpty() ? null : eui, doubleOrNull(rs, "rssi"),
                doubleOrNull(rs, "snr"));
    }

    private static Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    /** 조회 대상 기기 */
    public record DeviceInfo(long id, String name, Long spaceId, String status, boolean virtual, int intervalSec, String siteTimezone) {
    }

    /** 측정 항목 정의 */
    public record MetricInfo(String key, String unit, String valueType, String aggDefault, String enumMap) {
    }

    /** 공간(경로 /1/4/9/) */
    public record SpaceInfo(long id, String path, String siteTimezone) {
    }

    /** 공간 집계 대상 기기 */
    public record SpaceDevice(long deviceId, Long spaceId) {
    }

    /** 현재값 한 행(latest는 device_state.latest JSON 문자열, 없으면 null) */
    public record LatestRow(long deviceId, String name, Long spaceId, boolean virtual, String connectivity, Instant lastSeenAt,
                            String latest) {
    }

    /** 통신 품질 한 행 */
    public record LinkRow(Instant t, String gatewayEui, Double rssi, Double snr) {
    }
}
