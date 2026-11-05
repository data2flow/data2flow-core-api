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

    /** 공간의 부피 재료·정원과 사이트(없으면 조직) 시간대 */
    public record SpaceGeometry(long id, String name, BigDecimal areaM2, BigDecimal ceilingHeightM, Integer capacity, String timezone) {

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
                        SELECT s.id, s.name, s.area_m2, s.ceiling_height_m, s.capacity, coalesce(site.timezone, o.timezone, 'UTC') AS timezone
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

    /** 가상 센서 계산값 시스템 소스 코드(조직마다 하나, type DERIVED) */
    public static final String DERIVED_SOURCE = "virtual-sensors";

    /** 공간 가상 기기 외부 ID */
    static String virtualExternalId(long spaceId) {
        return "space-" + spaceId;
    }

    /** 공간 가상 기기(없으면 빈 값) */
    public Optional<Long> findVirtualDevice(long orgId, long spaceId) {
        return jdbc.sql("""
                        SELECT d.id FROM data2flow_core.devices d
                          JOIN data2flow_core.data_sources s ON s.id = d.source_id AND s.type = 'DERIVED' AND s.code = :code
                         WHERE d.organization_id = :org AND d.external_id = :ext AND d.status <> 'DELETED'""")
                .param("code", DERIVED_SOURCE).param("org", orgId).param("ext", virtualExternalId(spaceId)).query(Long.class).optional();
    }

    /**
     * 공간 가상 기기를 만든다(있으면 그대로). 시스템 소스(type DERIVED, ACTIVE)도 없으면 만든다. 기기는 SENSOR·ACTIVE·실제 데이터(SIM 아님)이고
     * 모델이 없어 다른 가상 센서의 입력이 되지 않는다. 보고 주기는 1시간(계산이 멈추면 오프라인으로 보인다)
     */
    public long ensureVirtualDevice(long orgId, long spaceId, String spaceName, long userId, Instant now) {
        long source = jdbc.sql("""
                        INSERT INTO data2flow_core.data_sources (organization_id, code, name, type, lifecycle, connection, decoder_key,
                               unknown_device_policy, no_data_alarm_after_sec, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :code, '가상 센서 계산값', 'DERIVED', 'ACTIVE', '{}'::jsonb, 'none', 'REJECT', 86400, :user, :user, :now, :now)
                        ON CONFLICT (organization_id, code) DO UPDATE SET updated_at = data2flow_core.data_sources.updated_at
                        RETURNING id""")
                .param("org", orgId).param("code", DERIVED_SOURCE).param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
        String name = ((spaceName == null ? "공간 " + spaceId : spaceName) + " 가상 센서");
        long id = jdbc.sql("""
                        INSERT INTO data2flow_core.devices (organization_id, source_id, external_id, name, kind, space_id, status, is_virtual,
                               expected_interval_sec, approved_at, approved_by, source_meta, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :source, :ext, :name, 'SENSOR', :space, 'ACTIVE', false, 3600, :now, :user,
                                '{"origin":"VIRTUAL_SENSOR"}'::jsonb, :user, :user, :now, :now)
                        ON CONFLICT (source_id, external_id) DO UPDATE SET updated_at = data2flow_core.devices.updated_at
                        RETURNING id""")
                .param("org", orgId).param("source", source).param("ext", virtualExternalId(spaceId))
                .param("name", name.length() > 100 ? name.substring(0, 100) : name).param("space", spaceId).param("user", userId)
                .param("now", Pg.ts(now)).query(Long.class).single();
        jdbc.sql("UPDATE data2flow_core.devices SET logical_device_id = id WHERE organization_id = :org AND id = :id AND logical_device_id IS NULL")
                .param("org", orgId).param("id", id).update();
        return id;
    }

    /** 계산값 한 점(pipeline telemetry) */
    public record DerivedPoint(Instant time, double value) {
    }

    /** 가상 기기의 측정값(시각 순). pipeline 소유 telemetry를 읽기만 한다 */
    public List<DerivedPoint> derived(long orgId, long deviceId, String metricKey, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT time, value FROM data2flow_pipeline.telemetry
                         WHERE organization_id = :org AND device_id = :device AND metric_key = :metric AND time >= :from AND time < :to
                         ORDER BY time""")
                .param("org", orgId).param("device", deviceId).param("metric", metricKey).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query((rs, n) -> new DerivedPoint(Pg.instant(rs, "time"), rs.getDouble("value"))).list();
    }

    /** 가상 기기의 마지막 측정값 */
    public Optional<DerivedPoint> latestDerived(long orgId, long deviceId, String metricKey) {
        return jdbc.sql("""
                        SELECT time, value FROM data2flow_pipeline.telemetry
                         WHERE organization_id = :org AND device_id = :device AND metric_key = :metric ORDER BY time DESC LIMIT 1""")
                .param("org", orgId).param("device", deviceId).param("metric", metricKey)
                .query((rs, n) -> new DerivedPoint(Pg.instant(rs, "time"), rs.getDouble("value"))).optional();
    }

    /** 조직의 켜진 설정(ACTIVE 공간만) */
    public List<EnabledRow> enabled(long orgId) {
        return jdbc.sql("""
                        SELECT c.space_id, c.kind, c.enabled, c.params::text AS params, c.output_metrics, c.last_status, c.last_missing,
                               c.last_computed_at, c.version, c.updated_at, s.id, s.name, s.area_m2, s.ceiling_height_m, s.capacity,
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
        return new SpaceGeometry(rs.getLong("id"), rs.getString("name"), rs.getBigDecimal("area_m2"), rs.getBigDecimal("ceiling_height_m"),
                (Integer) rs.getObject("capacity", Integer.class), rs.getString("timezone"));
    }
}
