package net.java21.data2flow.core.energy.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** 가상 센서 설정({@code virtual_sensor_config}, ENE-02)과 입력 기기·공간 부피 조회 */
@Repository
public class VirtualSensorRepository {

    private static final String CONFIG = """
            SELECT space_id, kind, enabled, params::text AS params, output_metrics, last_status, last_missing, last_computed_at, version, updated_at
              FROM data2flow_core.virtual_sensor_config
            """;

    private final JdbcClient jdbc;

    public VirtualSensorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ConfigRow(long spaceId, String kind, boolean enabled, String paramsJson, List<String> outputMetrics, String lastStatus,
                            List<String> lastMissing, Instant lastComputedAt, int version, Instant updatedAt) {
    }

    /** 공간의 부피 재료와 사이트(없으면 조직) 시간대 */
    public record SpaceGeometry(long id, BigDecimal areaM2, BigDecimal ceilingHeightM, String timezone) {

        /** 면적 × 층고(㎥). 하나라도 없으면 null */
        public Double volumeM3() {
            return areaM2 == null || ceilingHeightM == null ? null : areaM2.multiply(ceilingHeightM).doubleValue();
        }
    }

    /** 켜진 설정 하나와 그 공간(설정 묶음용) */
    public record EnabledRow(ConfigRow config, SpaceGeometry space) {
    }

    public List<ConfigRow> list(long orgId, long spaceId) {
        return jdbc.sql(CONFIG + " WHERE organization_id = :org AND space_id = :space ORDER BY kind").param("org", orgId).param("space", spaceId)
                .query(VirtualSensorRepository::config).list();
    }

    public Optional<ConfigRow> find(long orgId, long spaceId, String kind) {
        return jdbc.sql(CONFIG + " WHERE organization_id = :org AND space_id = :space AND kind = :kind").param("org", orgId).param("space", spaceId)
                .param("kind", kind).query(VirtualSensorRepository::config).optional();
    }

    /** 만들거나 고친다. 계산 결과(last_*)는 그대로 둔다 */
    public void upsert(long orgId, long spaceId, String kind, boolean enabled, String paramsJson, List<String> outputMetrics, long userId,
                       Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.virtual_sensor_config (space_id, kind, organization_id, enabled, params, output_metrics, updated_by,
                               created_at, updated_at)
                        VALUES (:space, :kind, :org, :enabled, CAST(:params AS jsonb), CAST(:metrics AS varchar(64)[]), :by, :now, :now)
                        ON CONFLICT (space_id, kind) DO UPDATE SET enabled = EXCLUDED.enabled, params = EXCLUDED.params,
                               output_metrics = EXCLUDED.output_metrics, version = virtual_sensor_config.version + 1, updated_by = EXCLUDED.updated_by,
                               updated_at = EXCLUDED.updated_at""")
                .param("space", spaceId).param("kind", kind).param("org", orgId).param("enabled", enabled).param("params", paramsJson)
                .param("metrics", Pg.textArray(outputMetrics)).param("by", userId).param("now", Pg.ts(now)).update();
    }

    /** EVT-ENE-03: 마지막 계산 결과(설정이 없으면 0) */
    public int recordComputed(long orgId, long spaceId, String kind, String status, List<String> missing, Instant at) {
        return jdbc.sql("""
                        UPDATE data2flow_core.virtual_sensor_config SET last_status = :status, last_missing = CAST(:missing AS varchar(64)[]),
                               last_computed_at = :at
                         WHERE organization_id = :org AND space_id = :space AND kind = :kind
                           AND (last_computed_at IS NULL OR last_computed_at <= :at)""")
                .param("status", status).param("missing", missing == null ? null : Pg.textArray(missing)).param("at", Pg.ts(at))
                .param("org", orgId).param("space", spaceId).param("kind", kind).update();
    }

    public Optional<SpaceGeometry> geometry(long orgId, long spaceId) {
        return jdbc.sql("""
                        SELECT s.id, s.area_m2, s.ceiling_height_m, coalesce(site.timezone, o.timezone, 'UTC') AS timezone
                          FROM data2flow_core.spaces s
                          JOIN data2flow_core.organizations o ON o.id = s.organization_id
                          LEFT JOIN data2flow_core.spaces site ON site.id = CAST(split_part(s.path, '/', 2) AS bigint)
                         WHERE s.organization_id = :org AND s.id = :id""")
                .param("org", orgId).param("id", spaceId).query(VirtualSensorRepository::geometry).optional();
    }

    /** 공간에 바로 놓인 ACTIVE 기기 중 모델에 {@code metricKey}가 있는 것(ID 순) */
    public List<Long> inputDevices(long orgId, long spaceId, String metricKey) {
        return jdbc.sql("""
                        SELECT d.id FROM data2flow_core.devices d
                          JOIN data2flow_core.model_metrics mm ON mm.model_id = d.model_id AND mm.metric_key = :metric
                         WHERE d.organization_id = :org AND d.space_id = :space AND d.status = 'ACTIVE'
                         ORDER BY d.id""")
                .param("org", orgId).param("space", spaceId).param("metric", metricKey).query(Long.class).list();
    }

    /** 조직의 켜진 설정(ACTIVE 공간만) */
    public List<EnabledRow> enabled(long orgId) {
        return jdbc.sql("""
                        SELECT c.space_id, c.kind, c.enabled, c.params::text AS params, c.output_metrics, c.last_status, c.last_missing,
                               c.last_computed_at, c.version, c.updated_at, s.id, s.area_m2, s.ceiling_height_m,
                               coalesce(site.timezone, o.timezone, 'UTC') AS timezone
                          FROM data2flow_core.virtual_sensor_config c
                          JOIN data2flow_core.spaces s ON s.id = c.space_id AND s.status = 'ACTIVE'
                          JOIN data2flow_core.organizations o ON o.id = c.organization_id
                          LEFT JOIN data2flow_core.spaces site ON site.id = CAST(split_part(s.path, '/', 2) AS bigint)
                         WHERE c.organization_id = :org AND c.enabled ORDER BY c.space_id, c.kind""")
                .param("org", orgId).query((rs, n) -> new EnabledRow(config(rs, n), geometry(rs, n))).list();
    }

    /** 켜진 가상 센서가 있는 조직. 배포 조직이 정해져 있으면 그 조직만(ADR-030) */
    @OrganizationScopeExempt("analytics 가상 센서 계산이 설정이 있는 모든 조직을 돈다(DeploymentOrganization.restriction()으로 좁힘)")
    public List<Long> organizations(OptionalLong restriction) {
        Long only = restriction.isPresent() ? restriction.getAsLong() : null;
        return jdbc.sql("""
                        SELECT DISTINCT c.organization_id FROM data2flow_core.virtual_sensor_config c
                          JOIN data2flow_core.organizations o ON o.id = c.organization_id AND o.status = 'ACTIVE'
                         WHERE c.enabled AND (CAST(:only AS bigint) IS NULL OR c.organization_id = :only) ORDER BY 1""")
                .param("only", only).query(Long.class).list();
    }

    static ConfigRow config(ResultSet rs, int n) throws SQLException {
        return new ConfigRow(rs.getLong("space_id"), rs.getString("kind"), rs.getBoolean("enabled"), rs.getString("params"),
                Pg.stringList(rs, "output_metrics"), rs.getString("last_status"), rs.getObject("last_missing") == null ? null : Pg.stringList(rs, "last_missing"),
                Pg.instant(rs, "last_computed_at"), rs.getInt("version"), Pg.instant(rs, "updated_at"));
    }

    static SpaceGeometry geometry(ResultSet rs, int n) throws SQLException {
        return new SpaceGeometry(rs.getLong("id"), rs.getBigDecimal("area_m2"), rs.getBigDecimal("ceiling_height_m"), rs.getString("timezone"));
    }
}
