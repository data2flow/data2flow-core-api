package net.java21.data2flow.core.dashboard.repository;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.dashboard.domain.Comfort;
import net.java21.data2flow.core.dashboard.domain.DeviceSnapshot;
import net.java21.data2flow.core.dashboard.domain.DeviceSnapshot.MetricValue;
import net.java21.data2flow.core.dashboard.domain.SpaceTree;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * 홈·공간 보기 읽기(DSH-01·02). core 표(spaces·space_targets·devices·device_models·metrics·data_sources·source_runtimes·floorplans)와
 * pipeline 소유 {@code data2flow_pipeline.device_state}를 <b>읽기만</b> 한다(conventions §6). 공간 범위(IAM-04.06)는 SQL에서 건다.
 */
@Repository
public class DashboardRepository {

    private static final String DEVICE_COLUMNS = """
            d.id, d.name, d.space_id, d.model_id, m.name AS model_name, d.status, d.is_virtual,
            COALESCE(s.connectivity, 'UNKNOWN') AS connectivity, s.last_seen_at, s.battery, s.rssi,
            COALESCE(s.latest, '{}'::jsonb)::text AS latest""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DashboardRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 조직의 ACTIVE 공간 전체(트리 계산용) */
    public List<SpaceTree.Node> findSpaces(long organizationId) {
        return jdbc.sql("""
                        SELECT id, parent_id, name, type, path, sort_order FROM data2flow_core.spaces
                         WHERE organization_id = :org AND status = 'ACTIVE'""")
                .param("org", organizationId)
                .query((rs, n) -> new SpaceTree.Node(rs.getLong("id"), Pg.longOrNull(rs, "parent_id"), rs.getString("name"),
                        rs.getString("type"), rs.getString("path"), rs.getInt("sort_order")))
                .list();
    }

    /** 공간별 직접 지정한 목표 환경(DEV-01.04) */
    public Map<Long, List<Comfort.Target>> findTargets(long organizationId) {
        Map<Long, List<Comfort.Target>> result = new HashMap<>();
        jdbc.sql("""
                        SELECT space_id, metric_key, min_value, max_value FROM data2flow_core.space_targets
                         WHERE organization_id = :org ORDER BY space_id, metric_key""")
                .param("org", organizationId)
                .query((ResultSet rs) -> {
                    double min = rs.getDouble("min_value");
                    Double minValue = rs.wasNull() ? null : min;
                    double max = rs.getDouble("max_value");
                    Double maxValue = rs.wasNull() ? null : max;
                    if (minValue != null || maxValue != null) {
                        result.computeIfAbsent(rs.getLong("space_id"), k -> new ArrayList<>())
                                .add(new Comfort.Target(rs.getString("metric_key"), minValue, maxValue));
                    }
                });
        return result;
    }

    /** 측정 항목 단위(key → unit) */
    public Map<String, String> findMetricUnits(long organizationId) {
        Map<String, String> units = new HashMap<>();
        jdbc.sql("SELECT key, unit FROM data2flow_core.metrics WHERE organization_id = :org AND unit IS NOT NULL")
                .param("org", organizationId)
                .query((ResultSet rs) -> {
                    units.put(rs.getString("key"), rs.getString("unit"));
                });
        return units;
    }

    /** 공간에 배치된 ACTIVE 기기와 현재 상태(공간 범위 안) */
    public List<DeviceSnapshot> findPlacedActiveDevices(long organizationId, SpaceScope scope, Map<String, String> units) {
        return jdbc.sql("SELECT " + DEVICE_COLUMNS + """
                         FROM data2flow_core.devices d
                         LEFT JOIN data2flow_core.device_models m ON m.id = d.model_id
                         LEFT JOIN data2flow_pipeline.device_state s ON s.device_id = d.id AND s.organization_id = d.organization_id
                        WHERE d.organization_id = :org AND d.status = 'ACTIVE' AND d.space_id IS NOT NULL
                          AND (:all OR d.space_id = ANY(CAST(:allowed AS bigint[])))
                        ORDER BY d.id""")
                .param("org", organizationId).param("all", scope.unrestricted())
                .param("allowed", Pg.bigintArray(scope.allowedSpaceIds()))
                .query((rs, n) -> device(rs, units)).list();
    }

    /** 한 공간에 직접 배치된 기기(ACTIVE·INACTIVE, API-DSH-02 overview) */
    public List<DeviceSnapshot> findDevicesInSpace(long organizationId, long spaceId, Map<String, String> units) {
        return jdbc.sql("SELECT " + DEVICE_COLUMNS + """
                         FROM data2flow_core.devices d
                         LEFT JOIN data2flow_core.device_models m ON m.id = d.model_id
                         LEFT JOIN data2flow_pipeline.device_state s ON s.device_id = d.id AND s.organization_id = d.organization_id
                        WHERE d.organization_id = :org AND d.space_id = :space AND d.status IN ('ACTIVE', 'INACTIVE')
                        ORDER BY d.name, d.id""")
                .param("org", organizationId).param("space", spaceId)
                .query((rs, n) -> device(rs, units)).list();
    }

    /** 오프라인 기기 수(ACTIVE, 공간 범위 안). 공간 미배치 기기는 범위 제한이 있으면 세지 않는다 */
    public long countOfflineDevices(long organizationId, SpaceScope scope) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.devices d
                          JOIN data2flow_pipeline.device_state s ON s.device_id = d.id AND s.organization_id = d.organization_id
                         WHERE d.organization_id = :org AND d.status = 'ACTIVE' AND s.connectivity = 'OFFLINE'
                           AND (:all OR d.space_id = ANY(CAST(:allowed AS bigint[])))""")
                .param("org", organizationId).param("all", scope.unrestricted())
                .param("allowed", Pg.bigintArray(scope.allowedSpaceIds()))
                .query(Long.class).single();
    }

    /** 승인 대기 기기 수(DEV-02.01). 공간이 없는 대기 기기는 조직 단위 자원이라 범위와 관계없이 센다(SpaceScope.includes(null)) */
    public long countPendingDevices(long organizationId, SpaceScope scope) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.devices d
                         WHERE d.organization_id = :org AND d.status = 'PENDING'
                           AND (:all OR d.space_id IS NULL OR d.space_id = ANY(CAST(:allowed AS bigint[])))""")
                .param("org", organizationId).param("all", scope.unrestricted())
                .param("allowed", Pg.bigintArray(scope.allowedSpaceIds()))
                .query(Long.class).single();
    }

    /** 소스 연결 현황(보관되지 않은 소스, 범위 제한이 있으면 site_id가 범위 안인 소스만) */
    public SourceCounts countSources(long organizationId, SpaceScope scope) {
        return jdbc.sql("""
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE EXISTS (SELECT 1 FROM data2flow_core.source_runtimes r
                                                                WHERE r.source_id = ds.id AND r.organization_id = ds.organization_id
                                                                  AND r.state = 'CONNECTED')) AS connected
                          FROM data2flow_core.data_sources ds
                         WHERE ds.organization_id = :org AND ds.lifecycle <> 'ARCHIVED' AND ds.archived_at IS NULL
                           AND (:all OR ds.site_id = ANY(CAST(:allowed AS bigint[])))""")
                .param("org", organizationId).param("all", scope.unrestricted())
                .param("allowed", Pg.bigintArray(scope.allowedSpaceIds()))
                .query((rs, n) -> new SourceCounts(rs.getLong("connected"), rs.getLong("total"))).single();
    }

    /** 기간 [from, to)의 수신 건수 합(source_stat_1m.received, 범위 안 소스) */
    public long sumReceived(long organizationId, SpaceScope scope, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT COALESCE(sum(st.received), 0) FROM data2flow_core.source_stat_1m st
                          JOIN data2flow_core.data_sources ds ON ds.id = st.source_id AND ds.organization_id = st.organization_id
                         WHERE st.organization_id = :org AND st.minute >= :from AND st.minute < :to
                           AND (:all OR ds.site_id = ANY(CAST(:allowed AS bigint[])))""")
                .param("org", organizationId).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .param("all", scope.unrestricted()).param("allowed", Pg.bigintArray(scope.allowedSpaceIds()))
                .query(Long.class).single();
    }

    /** 평면도가 있는가 */
    public boolean existsFloorplan(long organizationId, long spaceId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.floorplans WHERE organization_id = :org AND space_id = :space)")
                .param("org", organizationId).param("space", spaceId).query(Boolean.class).single();
    }

    public record SourceCounts(long connected, long total) {
    }

    private DeviceSnapshot device(ResultSet rs, Map<String, String> units) throws SQLException {
        double battery = rs.getDouble("battery");
        Double batteryValue = rs.wasNull() ? null : battery;
        double rssi = rs.getDouble("rssi");
        Double rssiValue = rs.wasNull() ? null : rssi;
        return new DeviceSnapshot(rs.getLong("id"), rs.getString("name"), Pg.longOrNull(rs, "space_id"),
                Pg.longOrNull(rs, "model_id"), rs.getString("model_name"), rs.getString("status"), rs.getBoolean("is_virtual"),
                rs.getString("connectivity"), Pg.instant(rs, "last_seen_at"), batteryValue, rssiValue,
                metrics(rs.getString("latest"), units));
    }

    /** {@code device_state.latest} = {@code {metricKey: {v, t, q, unit}}}(design/erd/pipeline.md). 숫자가 아닌 값은 건너뛴다 */
    List<MetricValue> metrics(String latest, Map<String, String> units) {
        List<MetricValue> result = new ArrayList<>();
        if (latest == null || latest.isBlank()) {
            return result;
        }
        JsonNode root = json.readTree(latest);
        for (Entry<String, JsonNode> e : root.properties()) {
            JsonNode node = e.getValue();
            JsonNode v = node.get("v");
            if (v == null || !v.isNumber()) {
                continue;
            }
            String unit = node.hasNonNull("unit") ? node.get("unit").asString() : units.get(e.getKey());
            Integer quality = node.hasNonNull("q") ? node.get("q").asInt() : null;
            Instant at = null;
            if (node.hasNonNull("t")) {
                try {
                    at = Instant.parse(node.get("t").asString());
                } catch (java.time.format.DateTimeParseException ignored) {
                    at = null;
                }
            }
            result.add(new MetricValue(e.getKey(), v.asDouble(), unit, quality, at));
        }
        result.sort(Comparator.comparing(MetricValue::key));
        return result;
    }
}
