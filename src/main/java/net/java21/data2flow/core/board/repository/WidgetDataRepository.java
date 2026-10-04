package net.java21.data2flow.core.board.repository;

import net.java21.data2flow.core.board.domain.WidgetResolution;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 위젯 데이터 조회(API-DSH-09, DSH-04.06). 시계열은 pipeline 소유 자체 집계 표({@code telemetry_1m·1h·1d}, ADR-019)를 읽기만 하고,
 * 5분 단위는 1분 집계를 {@code date_bin}으로 다시 묶는다(평균 = 합 ÷ 표본 수). 표 이름·열 이름은 정해진 값만 SQL에 넣는다.
 * 모든 조회에 organization_id 조건을 건다(BR-IAM-01).
 */
@Repository
public class WidgetDataRepository {

    private static final String P = "data2flow_pipeline.";
    private static final Map<String, String> REBUCKET = Map.of(
            "avg", "sum(sum) / nullif(sum(count), 0)", "min", "min(min)", "max", "max(max)", "sum", "sum(sum)",
            "last", "(array_agg(last ORDER BY bucket DESC))[1]", "count", "sum(count)");
    private static final Map<String, String> COLUMN = Map.of(
            "avg", "avg", "min", "min", "max", "max", "sum", "sum", "last", "last", "count", "count");

    private final JdbcClient jdbc;

    public WidgetDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 대상 기기(삭제 제외)의 이름·공간 */
    public List<DeviceRef> findDevices(long organizationId, Collection<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT d.id, d.name, d.space_id, d.is_virtual, d.external_id FROM data2flow_core.devices d
                         WHERE d.organization_id = :org AND d.id = ANY(CAST(:ids AS bigint[])) AND d.status <> 'DELETED' ORDER BY d.id""")
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds))
                .query((rs, n) -> new DeviceRef(rs.getLong("id"), rs.getString("name"), Pg.longOrNull(rs, "space_id"),
                        rs.getBoolean("is_virtual"), rs.getString("external_id"))).list();
    }

    /** 외부 ID로 기기 찾기(대시보드 가져오기 매핑, DSH-04.08). 여러 소스에 같은 외부 ID가 있으면 가장 작은 ID */
    public Optional<Long> findDeviceIdByExternalId(long organizationId, String externalId) {
        return jdbc.sql("""
                        SELECT id FROM data2flow_core.devices
                         WHERE organization_id = :org AND lower(external_id) = lower(:ext) AND status <> 'DELETED' ORDER BY id LIMIT 1""")
                .param("org", organizationId).param("ext", externalId).query(Long.class).optional();
    }

    /** 이름으로 공간 찾기(대시보드 가져오기 매핑). 같은 이름이 여럿이면 가장 얕은 것 */
    public Optional<Long> findSpaceIdByName(long organizationId, String name) {
        return jdbc.sql("""
                        SELECT id FROM data2flow_core.spaces WHERE organization_id = :org AND status = 'ACTIVE' AND lower(name) = lower(:name)
                         ORDER BY depth, id LIMIT 1""")
                .param("org", organizationId).param("name", name).query(Long.class).optional();
    }

    /** 공간(ACTIVE) */
    public Optional<SpaceRef> findSpace(long organizationId, long spaceId) {
        return jdbc.sql("SELECT id, name, path, type FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id AND status = 'ACTIVE'")
                .param("org", organizationId).param("id", spaceId)
                .query((rs, n) -> new SpaceRef(rs.getLong("id"), rs.getString("name"), rs.getString("path"), rs.getString("type"))).optional();
    }

    /** 공간(하위 포함)의 활성 기기. 가상 기기 포함 */
    public List<DeviceRef> findSpaceDevices(long organizationId, String spacePath, int limit) {
        return jdbc.sql("""
                        SELECT d.id, d.name, d.space_id, d.is_virtual, d.external_id FROM data2flow_core.devices d
                          JOIN data2flow_core.spaces s ON s.id = d.space_id AND s.organization_id = d.organization_id
                         WHERE d.organization_id = :org AND d.status = 'ACTIVE' AND s.path LIKE :path || '%'
                         ORDER BY d.id LIMIT :limit""")
                .param("org", organizationId).param("path", spacePath).param("limit", limit)
                .query((rs, n) -> new DeviceRef(rs.getLong("id"), rs.getString("name"), Pg.longOrNull(rs, "space_id"),
                        rs.getBoolean("is_virtual"), rs.getString("external_id"))).list();
    }

    /** 측정 항목 단위·기본 집계 */
    public Optional<MetricRef> findMetric(long organizationId, String key) {
        return jdbc.sql("SELECT key, display_name AS name, unit, lower(agg_default) AS agg_default FROM data2flow_core.metrics WHERE organization_id = :org AND key = :key")
                .param("org", organizationId).param("key", key)
                .query((rs, n) -> new MetricRef(rs.getString("key"), rs.getString("name"), rs.getString("unit"), rs.getString("agg_default")))
                .optional();
    }

    /** 원본 점 수(limit까지만) */
    public long countRaw(long organizationId, Collection<Long> deviceIds, String metric, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT count(*) FROM (SELECT 1 FROM data2flow_pipeline.telemetry
                         WHERE organization_id = :org AND device_id = ANY(CAST(:ids AS bigint[])) AND metric_key = :metric
                           AND time >= :from AND time < :to LIMIT :limit) x""")
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds)).param("metric", metric)
                .param("from", Pg.ts(from)).param("to", Pg.ts(to)).param("limit", limit).query(Long.class).single();
    }

    /**
     * 시계열 점. 기기가 여럿이면(공간 집계) 같은 구간의 기기 값을 평균한다. 세 번째 값은 원본이면 품질, 집계면 표본 수.
     * 원본은 품질 0·4(정상·시각 보정)와 1·3(범위 초과·의심, 화면에서 모양을 달리 그림)을 준다.
     */
    public List<Point> findSeries(long organizationId, WidgetResolution level, Collection<Long> deviceIds, String metric, Instant from,
                                  Instant to, String agg) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        boolean single = deviceIds.size() == 1;
        if (level == WidgetResolution.RAW) {
            String sql = single
                    ? """
                      SELECT time AS t, value AS v, quality AS q FROM data2flow_pipeline.telemetry
                       WHERE organization_id = :org AND device_id = ANY(CAST(:ids AS bigint[])) AND metric_key = :metric
                         AND time >= :from AND time < :to AND quality IN (0, 1, 3, 4) ORDER BY time LIMIT 2001"""
                    : """
                      SELECT date_bin(interval '1 minute', time, TIMESTAMPTZ '2000-01-01 00:00:00+00') AS t, avg(value) AS v, count(*) AS q
                        FROM data2flow_pipeline.telemetry
                       WHERE organization_id = :org AND device_id = ANY(CAST(:ids AS bigint[])) AND metric_key = :metric
                         AND time >= :from AND time < :to AND quality IN (0, 4) GROUP BY 1 ORDER BY 1 LIMIT 2001""";
            return run(sql, organizationId, deviceIds, metric, from, to);
        }
        String a = COLUMN.containsKey(agg) ? agg : "avg";
        String sql;
        if (level.rebucket()) {
            sql = """
                  SELECT t, avg(v) AS v, sum(n) AS q FROM (
                    SELECT device_id, date_bin(interval '5 minutes', bucket, TIMESTAMPTZ '2000-01-01 00:00:00+00') AS t, %s AS v, sum(count) AS n
                      FROM %s WHERE organization_id = :org AND device_id = ANY(CAST(:ids AS bigint[])) AND metric_key = :metric
                       AND bucket >= :from AND bucket < :to GROUP BY 1, 2) x
                   GROUP BY t ORDER BY t LIMIT 2001""".formatted(REBUCKET.get(a), P + level.table());
        } else {
            sql = """
                  SELECT bucket AS t, avg(%s) AS v, sum(count) AS q FROM %s
                   WHERE organization_id = :org AND device_id = ANY(CAST(:ids AS bigint[])) AND metric_key = :metric
                     AND bucket >= :from AND bucket < :to GROUP BY bucket ORDER BY bucket LIMIT 2001""".formatted(COLUMN.get(a), P + level.table());
        }
        return run(sql, organizationId, deviceIds, metric, from, to);
    }

    private List<Point> run(String sql, long organizationId, Collection<Long> deviceIds, String metric, Instant from, Instant to) {
        return jdbc.sql(sql).param("org", organizationId).param("ids", Pg.bigintArray(deviceIds)).param("metric", metric)
                .param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query((rs, n) -> new Point(Pg.instant(rs, "t"), doubleOrNull(rs, "v"), rs.getLong("q"))).list();
    }

    /** 기간 요약(최소·최대·평균·마지막 시각). 2일 넘으면 1시간 집계, 아니면 1분 집계 */
    public Summary findSummary(long organizationId, Collection<Long> deviceIds, String metric, Instant from, Instant to, boolean hourly) {
        if (deviceIds.isEmpty()) {
            return new Summary(null, null, null, null);
        }
        String table = P + (hourly ? "telemetry_1h" : "telemetry_1m");
        return jdbc.sql("""
                        SELECT min(min) AS mn, max(max) AS mx, sum(sum) / nullif(sum(count), 0) AS av, max(last_time) AS lt FROM %s
                         WHERE organization_id = :org AND device_id = ANY(CAST(:ids AS bigint[])) AND metric_key = :metric
                           AND bucket >= :from AND bucket < :to""".formatted(table))
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds)).param("metric", metric)
                .param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query((rs, n) -> new Summary(doubleOrNull(rs, "mn"), doubleOrNull(rs, "mx"), doubleOrNull(rs, "av"), Pg.instant(rs, "lt")))
                .single();
    }

    /** 현재 상태(device_state). 없는 기기는 빠진다 */
    public List<StateRow> findStates(long organizationId, Collection<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT ds.device_id, ds.connectivity, ds.last_seen_at, ds.battery::float8 AS battery, ds.rssi::float8 AS rssi,
                               ds.latest::text AS latest,
                               (SELECT count(*) FROM data2flow_core.alarms a WHERE a.organization_id = ds.organization_id
                                   AND a.device_id = ds.device_id AND a.status IN ('ACTIVE', 'ACKNOWLEDGED')) AS alarms
                          FROM data2flow_pipeline.device_state ds
                         WHERE ds.organization_id = :org AND ds.device_id = ANY(CAST(:ids AS bigint[]))""")
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds))
                .query((rs, n) -> new StateRow(rs.getLong("device_id"), rs.getString("connectivity"), Pg.instant(rs, "last_seen_at"),
                        doubleOrNull(rs, "battery"), doubleOrNull(rs, "rssi"), rs.getString("latest"), rs.getLong("alarms"))).list();
    }

    /** 히트맵: 1시간 집계를 (날짜, 시) 칸으로. 날짜·시는 tz 기준 */
    public List<HeatCell> findHeatmap(long organizationId, Collection<Long> deviceIds, String metric, Instant from, Instant to, String tz,
                                      boolean max) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT to_char(bucket AT TIME ZONE :tz, 'YYYY-MM-DD') AS day, extract(hour FROM bucket AT TIME ZONE :tz)::int AS hour,
                               %s AS v
                          FROM data2flow_pipeline.telemetry_1h
                         WHERE organization_id = :org AND device_id = ANY(CAST(:ids AS bigint[])) AND metric_key = :metric
                           AND bucket >= :from AND bucket < :to
                         GROUP BY 1, 2 ORDER BY 1, 2""".formatted(max ? "max(max)" : "sum(sum) / nullif(sum(count), 0)"))
                .param("tz", tz).param("org", organizationId).param("ids", Pg.bigintArray(deviceIds)).param("metric", metric)
                .param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query((rs, n) -> new HeatCell(rs.getString("day"), rs.getInt("hour"), doubleOrNull(rs, "v"))).list();
    }

    /**
     * 열린 알람(최근 발생 순). spacePaths가 있으면 그 공간(하위 포함)·기기만, allowedSpaceIds가 있으면 범위 안 공간만.
     */
    public List<AlarmItem> findAlarms(long organizationId, List<String> spacePaths, Collection<Long> deviceIds, Collection<Long> allowedSpaceIds,
                                      Collection<String> severities, Collection<String> states, int limit) {
        return jdbc.sql("""
                        SELECT a.id, a.severity, a.title, a.status, a.last_raised_at FROM data2flow_core.alarms a
                          LEFT JOIN data2flow_core.spaces s ON s.id = a.space_id AND s.organization_id = a.organization_id
                         WHERE a.organization_id = :org AND a.status = ANY(CAST(:states AS varchar[]))
                           AND a.severity = ANY(CAST(:severities AS varchar[]))
                           AND (CAST(:allowed AS bigint[]) IS NULL OR a.space_id = ANY(CAST(:allowed AS bigint[])))
                           AND ((cardinality(CAST(:paths AS text[])) = 0 AND cardinality(CAST(:devices AS bigint[])) = 0)
                                OR a.device_id = ANY(CAST(:devices AS bigint[]))
                                OR EXISTS (SELECT 1 FROM unnest(CAST(:paths AS text[])) p WHERE s.path LIKE p || '%'))
                         ORDER BY a.last_raised_at DESC, a.id DESC LIMIT :limit""")
                .param("org", organizationId).param("states", Pg.textArray(states)).param("severities", Pg.textArray(severities))
                .param("allowed", allowedSpaceIds == null ? null : Pg.bigintArray(allowedSpaceIds)).param("paths", Pg.textArray(spacePaths))
                .param("devices", Pg.bigintArray(deviceIds)).param("limit", limit)
                .query((rs, n) -> new AlarmItem(rs.getLong("id"), rs.getString("severity"), rs.getString("title"), rs.getString("status"),
                        Pg.instant(rs, "last_raised_at"))).list();
    }

    /** 공간 평면도와 마커(평면도 위젯) */
    public Optional<FloorplanRef> findFloorplan(long organizationId, long spaceId) {
        return jdbc.sql("SELECT id, width_px, height_px, version FROM data2flow_core.floorplans WHERE organization_id = :org AND space_id = :space")
                .param("org", organizationId).param("space", spaceId)
                .query((rs, n) -> new FloorplanRef(rs.getLong("id"), rs.getInt("width_px"), rs.getInt("height_px"), rs.getInt("version")))
                .optional();
    }

    public List<Marker> findMarkers(long organizationId, long floorplanId) {
        return jdbc.sql("""
                        SELECT m.device_id, m.x::float8 AS x, m.y::float8 AS y FROM data2flow_core.floorplan_markers m
                          JOIN data2flow_core.devices d ON d.id = m.device_id AND d.status <> 'DELETED'
                         WHERE m.organization_id = :org AND m.floorplan_id = :fp ORDER BY m.device_id""")
                .param("org", organizationId).param("fp", floorplanId)
                .query((rs, n) -> new Marker(rs.getLong("device_id"), rs.getDouble("x"), rs.getDouble("y"))).list();
    }

    private static Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    public record DeviceRef(long id, String name, Long spaceId, boolean virtual, String externalId) {
    }

    public record SpaceRef(long id, String name, String path, String type) {
    }

    public record MetricRef(String key, String name, String unit, String aggDefault) {
    }

    public record Point(Instant t, Double v, long q) {
    }

    public record Summary(Double min, Double max, Double avg, Instant lastTime) {
    }

    public record StateRow(long deviceId, String connectivity, Instant lastSeenAt, Double battery, Double rssi, String latest, long alarms) {
    }

    public record HeatCell(String day, int hour, Double value) {
    }

    public record AlarmItem(long id, String severity, String title, String status, Instant at) {
    }

    public record FloorplanRef(long id, int width, int height, int version) {
    }

    public record Marker(long deviceId, double x, double y) {
    }
}
