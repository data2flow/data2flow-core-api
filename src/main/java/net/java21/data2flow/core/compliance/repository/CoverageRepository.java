package net.java21.data2flow.core.compliance.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 커버리지 점검(CMP-03) 읽기: 공간 면적·용도, 기기 수신 주기, 기간 수신 통계 */
@Repository
public class CoverageRepository {

    private final JdbcClient jdbc;

    public CoverageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record AreaSpace(long id, String name, String path, String type, String usage, BigDecimal areaM2) {
    }

    /** 기기 수신 주기(모델 기본 주기)와 등록 시각 */
    public record DeviceInfo(long id, int intervalSec, Instant createdAt) {
    }

    /** 기기 하나의 기간 수신 수(항목 중 가장 많이 받은 항목 기준) */
    public record Samples(long good, long all) {
    }

    public Optional<AreaSpace> space(long orgId, long id) {
        return jdbc.sql(SPACE + " WHERE organization_id = :org AND id = :id AND status = 'ACTIVE'").param("org", orgId).param("id", id)
                .query(CoverageRepository::areaSpace).optional();
    }

    /** 경로 아래 공간 전부(ACTIVE, 자기 포함) */
    public List<AreaSpace> subtree(long orgId, String path) {
        return jdbc.sql(SPACE + " WHERE organization_id = :org AND status = 'ACTIVE' AND path LIKE :path || '%' ORDER BY path")
                .param("org", orgId).param("path", path).query(CoverageRepository::areaSpace).list();
    }

    public Map<Long, DeviceInfo> devices(long orgId, Collection<Long> ids) {
        Map<Long, DeviceInfo> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT d.id, coalesce(m.default_interval_sec, 600) AS interval_sec, d.created_at
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_core.device_models m ON m.id = d.model_id
                         WHERE d.organization_id = :org AND d.id = ANY(CAST(:ids AS bigint[]))""")
                .param("org", orgId).param("ids", Pg.bigintArray(ids))
                .query((rs, n) -> out.put(rs.getLong("id"), new DeviceInfo(rs.getLong("id"), rs.getInt("interval_sec"), Pg.instant(rs, "created_at"))))
                .list();
        return out;
    }

    /** 기기별 [from, to) 수신 수. 항목마다 따로 세고 가장 많이 받은 항목을 쓴다 */
    public Map<Long, Samples> samples(long orgId, Collection<Long> deviceIds, Collection<String> metricKeys, Instant from, Instant to) {
        Map<Long, Samples> out = new HashMap<>();
        if (deviceIds.isEmpty() || metricKeys.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT device_id, max(good) AS good, max(all_count) AS all_count
                          FROM (SELECT device_id, metric_key, sum(count) AS good, sum(count_all) AS all_count
                                  FROM data2flow_pipeline.telemetry_1m
                                 WHERE organization_id = :org AND device_id = ANY(CAST(:ids AS bigint[])) AND NOT is_virtual
                                   AND metric_key = ANY(CAST(:keys AS text[])) AND bucket >= :from AND bucket < :to
                                 GROUP BY device_id, metric_key) m
                         GROUP BY device_id""")
                .param("org", orgId).param("ids", Pg.bigintArray(deviceIds)).param("keys", Pg.textArray(metricKeys))
                .param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query((rs, n) -> out.put(rs.getLong("device_id"), new Samples(rs.getLong("good"), rs.getLong("all_count")))).list();
        return out;
    }

    private static final String SPACE = "SELECT id, name, path, type, usage, area_m2 FROM data2flow_core.spaces";

    static AreaSpace areaSpace(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new AreaSpace(rs.getLong("id"), rs.getString("name"), rs.getString("path"), rs.getString("type"), rs.getString("usage"),
                rs.getBigDecimal("area_m2"));
    }
}
