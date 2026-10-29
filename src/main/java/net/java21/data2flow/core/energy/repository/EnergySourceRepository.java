package net.java21.data2flow.core.energy.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 에너지 원천 저장소(ENE-01.01, {@code energy_sources})와 계량 후보 기기 조회 */
@Repository
public class EnergySourceRepository {

    private static final String SOURCE = """
            SELECT e.id, e.organization_id, e.space_id, s.name AS space_name, s.path AS space_path, e.kind, e.device_id, d.name AS device_name,
                   e.metric_key, e.cumulative, e.covers_descendants, e.rated_power_w, e.standby_power_w, e.enabled, e.version, e.updated_at
              FROM data2flow_core.energy_sources e
              JOIN data2flow_core.spaces s ON s.id = e.space_id
              JOIN data2flow_core.devices d ON d.id = e.device_id
            """;

    private final JdbcClient jdbc;

    public EnergySourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SourceRow(long id, long organizationId, long spaceId, String spaceName, String spacePath, String kind, long deviceId,
                            String deviceName, String metricKey, Boolean cumulative, boolean coversDescendants, Integer ratedPowerW,
                            Integer standbyPowerW, boolean enabled, int version, Instant updatedAt) {
    }

    public record Values(long spaceId, String kind, long deviceId, String metricKey, Boolean cumulative, boolean coversDescendants,
                         Integer ratedPowerW, Integer standbyPowerW, boolean enabled) {
    }

    /** 기기와 그 모델의 측정 항목 */
    public record DeviceRow(long id, String name, Long spaceId, List<String> metricKeys) {
    }

    public record CandidateRow(long deviceId, String deviceName, long spaceId, String spaceName, List<String> metricKeys) {
    }

    /** 일 집계용 원천: 켜진 원천과 그 사이트(공간 경로의 맨 위)·시간대(사이트, 없으면 조직), 공간 면적 */
    public record MeterRow(long id, long spaceId, Long siteId, String timezone, Double areaM2, String kind, long deviceId, String metricKey,
                           Boolean cumulative, boolean coversDescendants, Integer ratedPowerW, Integer standbyPowerW) {
    }

    public List<MeterRow> enabledMeters(long orgId) {
        return jdbc.sql("""
                        SELECT e.id, e.space_id, site.id AS site_id, coalesce(site.timezone, o.timezone, 'UTC') AS timezone, s.area_m2, e.kind,
                               e.device_id, e.metric_key, e.cumulative, e.covers_descendants, e.rated_power_w, e.standby_power_w
                          FROM data2flow_core.energy_sources e
                          JOIN data2flow_core.spaces s ON s.id = e.space_id
                          JOIN data2flow_core.organizations o ON o.id = e.organization_id
                          LEFT JOIN data2flow_core.spaces site ON site.id = CAST(split_part(s.path, '/', 2) AS bigint)
                         WHERE e.organization_id = :org AND e.enabled ORDER BY e.id""")
                .param("org", orgId)
                .query((rs, n) -> new MeterRow(rs.getLong("id"), rs.getLong("space_id"), Pg.longOrNull(rs, "site_id"), rs.getString("timezone"),
                        rs.getBigDecimal("area_m2") == null ? null : rs.getBigDecimal("area_m2").doubleValue(), rs.getString("kind"),
                        rs.getLong("device_id"), rs.getString("metric_key"), (Boolean) rs.getObject("cumulative"), rs.getBoolean("covers_descendants"),
                        (Integer) rs.getObject("rated_power_w"), (Integer) rs.getObject("standby_power_w")))
                .list();
    }

    /** 조직의 원천. {@code spaceId}가 있으면 그 공간 것만 */
    public List<SourceRow> list(long orgId, Long spaceId) {
        return jdbc.sql(SOURCE + " WHERE e.organization_id = :org AND (CAST(:space AS bigint) IS NULL OR e.space_id = :space) ORDER BY s.path, e.id")
                .param("org", orgId).param("space", spaceId).query(EnergySourceRepository::source).list();
    }

    public Optional<SourceRow> find(long orgId, long id) {
        return jdbc.sql(SOURCE + " WHERE e.organization_id = :org AND e.id = :id").param("org", orgId).param("id", id)
                .query(EnergySourceRepository::source).optional();
    }

    /** 같은 기기(측정이면 같은 측정 항목)를 쓰는 다른 원천 */
    public Optional<SourceRow> sameMeter(long orgId, String kind, long deviceId, String metricKey, Long exceptId) {
        return jdbc.sql(SOURCE + """
                         WHERE e.organization_id = :org AND e.kind = :kind AND e.device_id = :device
                           AND (e.kind = 'ESTIMATED' OR e.metric_key = :metric) AND e.id <> coalesce(CAST(:except AS bigint), -1)
                         LIMIT 1""")
                .param("org", orgId).param("kind", kind).param("device", deviceId).param("metric", metricKey).param("except", exceptId)
                .query(EnergySourceRepository::source).optional();
    }

    /** 하위 합계를 포함하는 켜진 측정 원천(상위 계량기) */
    public List<SourceRow> coveringMeters(long orgId, Long exceptId) {
        return jdbc.sql(SOURCE + """
                         WHERE e.organization_id = :org AND e.kind = 'METERED' AND e.covers_descendants AND e.enabled
                           AND e.id <> coalesce(CAST(:except AS bigint), -1)""")
                .param("org", orgId).param("except", exceptId).query(EnergySourceRepository::source).list();
    }

    public Optional<DeviceRow> device(long orgId, long id) {
        return jdbc.sql("""
                        SELECT d.id, d.name, d.space_id,
                               coalesce(array_agg(mm.metric_key ORDER BY mm.metric_key) FILTER (WHERE mm.metric_key IS NOT NULL), '{}') AS metric_keys
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_core.model_metrics mm ON mm.model_id = d.model_id
                         WHERE d.organization_id = :org AND d.id = :id AND d.status <> 'DELETED'
                         GROUP BY d.id""")
                .param("org", orgId).param("id", id)
                .query((rs, n) -> new DeviceRow(rs.getLong("id"), rs.getString("name"), Pg.longOrNull(rs, "space_id"), Pg.stringList(rs, "metric_keys")))
                .optional();
    }

    /** 공간(하위 포함)에 놓인 기기 중 모델에 {@code energy}·{@code power}가 있는 것 */
    public List<CandidateRow> candidates(long orgId, String path) {
        return jdbc.sql("""
                        SELECT d.id AS device_id, d.name AS device_name, s.id AS space_id, s.name AS space_name,
                               array_agg(mm.metric_key ORDER BY mm.metric_key) AS metric_keys
                          FROM data2flow_core.devices d
                          JOIN data2flow_core.spaces s ON s.id = d.space_id AND s.status = 'ACTIVE'
                          JOIN data2flow_core.model_metrics mm ON mm.model_id = d.model_id AND mm.metric_key IN ('energy', 'power')
                         WHERE d.organization_id = :org AND d.status = 'ACTIVE' AND s.path LIKE :path
                         GROUP BY d.id, d.name, s.id, s.name, s.path
                         ORDER BY s.path, d.name, d.id
                         LIMIT 500""")
                .param("org", orgId).param("path", path + "%")
                .query((rs, n) -> new CandidateRow(rs.getLong("device_id"), rs.getString("device_name"), rs.getLong("space_id"),
                        rs.getString("space_name"), Pg.stringList(rs, "metric_keys")))
                .list();
    }

    /** 측정 원천의 마지막 수신 시각(최근 24시간 분 집계에서) */
    public Optional<Instant> lastValueAt(long deviceId, String metricKey, Instant now) {
        return jdbc.sql("""
                        SELECT bucket FROM data2flow_pipeline.telemetry_1m
                         WHERE device_id = :device AND metric_key = :metric AND bucket >= :since
                         ORDER BY bucket DESC LIMIT 1""")
                .param("device", deviceId).param("metric", metricKey).param("since", Pg.ts(now.minusSeconds(86_400)))
                .query((rs, n) -> Pg.instant(rs, "bucket")).optional();
    }

    public long insert(long orgId, Values v, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.energy_sources (organization_id, space_id, kind, device_id, metric_key, cumulative, covers_descendants,
                               rated_power_w, standby_power_w, enabled, created_by, created_at, updated_at)
                        VALUES (:org, :space, :kind, :device, :metric, :cumulative, :covers, :rated, :standby, :enabled, :by, :now, :now)
                        RETURNING id""")
                .params(params(v)).param("org", orgId).param("by", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    /** 버전이 맞을 때만 고친다(고친 행 수) */
    public int update(long orgId, long id, int baseVersion, Values v, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.energy_sources SET space_id = :space, metric_key = :metric, cumulative = :cumulative,
                               covers_descendants = :covers, rated_power_w = :rated, standby_power_w = :standby, enabled = :enabled,
                               version = version + 1, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :version""")
                .params(params(v)).param("org", orgId).param("id", id).param("version", baseVersion).param("now", Pg.ts(now)).update();
    }

    public int delete(long orgId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.energy_sources WHERE organization_id = :org AND id = :id").param("org", orgId).param("id", id)
                .update();
    }

    private static Map<String, Object> params(Values v) {
        Map<String, Object> m = new HashMap<>();
        m.put("space", v.spaceId());
        m.put("kind", v.kind());
        m.put("device", v.deviceId());
        m.put("metric", v.metricKey());
        m.put("cumulative", v.cumulative());
        m.put("covers", v.coversDescendants());
        m.put("rated", v.ratedPowerW());
        m.put("standby", v.standbyPowerW());
        m.put("enabled", v.enabled());
        return m;
    }

    static SourceRow source(ResultSet rs, int n) throws SQLException {
        Boolean cumulative = rs.getObject("cumulative", Boolean.class);
        return new SourceRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("space_id"), rs.getString("space_name"),
                rs.getString("space_path"), rs.getString("kind"), rs.getLong("device_id"), rs.getString("device_name"), rs.getString("metric_key"),
                cumulative, rs.getBoolean("covers_descendants"), rs.getObject("rated_power_w", Integer.class),
                rs.getObject("standby_power_w", Integer.class), rs.getBoolean("enabled"), rs.getInt("version"), Pg.instant(rs, "updated_at"));
    }
}
