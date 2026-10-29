package net.java21.data2flow.core.energy.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 기준선 저장소(ENE-01.04, {@code energy_baselines}·{@code energy_baseline_versions}) */
@Repository
public class EnergyBaselineRepository {

    private static final String BASELINE = """
            SELECT b.id, b.scope_space_id, s.name AS scope_space_name, b.active_version_id, b.updated_at,
                   v.method AS a_method, v.r2 AS a_r2, v.train_from AS a_from, v.train_to AS a_to, v.analytics_model_id AS a_model
              FROM data2flow_core.energy_baselines b
              JOIN data2flow_core.spaces s ON s.id = b.scope_space_id
              LEFT JOIN data2flow_core.energy_baseline_versions v ON v.id = b.active_version_id
            """;

    private static final String VERSION = """
            SELECT v.id, v.organization_id, v.baseline_id, b.scope_space_id, v.method, v.train_from, v.train_to, v.params::text AS params, v.status,
                   v.r2, v.coefficients::text AS coefficients, v.analytics_model_id, v.warning, v.error_code, v.needed_days, v.created_by,
                   u.name AS created_by_name, v.created_at, v.updated_at
              FROM data2flow_core.energy_baseline_versions v
              JOIN data2flow_core.energy_baselines b ON b.id = v.baseline_id
              LEFT JOIN data2flow_core.app_users u ON u.id = v.created_by
            """;

    private final JdbcClient jdbc;

    public EnergyBaselineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 활성 버전 요약(없으면 method 등이 null) */
    public record BaselineRow(long id, long scopeSpaceId, String scopeSpaceName, Long activeVersionId, String activeMethod, BigDecimal activeR2,
                              LocalDate activeFrom, LocalDate activeTo, String activeModelKey, Instant updatedAt) {
    }

    public record VersionRow(long id, long organizationId, long baselineId, long scopeSpaceId, String method, LocalDate trainFrom, LocalDate trainTo,
                             String paramsJson, String status, BigDecimal r2, String coefficientsJson, String analyticsModelId, String warning,
                             String errorCode, Integer neededDays, long createdBy, String createdByName, Instant createdAt, Instant updatedAt) {
    }

    public List<BaselineRow> list(long orgId, Long scopeSpaceId) {
        return jdbc.sql(BASELINE + " WHERE b.organization_id = :org AND (CAST(:space AS bigint) IS NULL OR b.scope_space_id = :space) ORDER BY s.path, b.id")
                .param("org", orgId).param("space", scopeSpaceId).query(EnergyBaselineRepository::baseline).list();
    }

    public Optional<BaselineRow> find(long orgId, long baselineId) {
        return jdbc.sql(BASELINE + " WHERE b.organization_id = :org AND b.id = :id").param("org", orgId).param("id", baselineId)
                .query(EnergyBaselineRepository::baseline).optional();
    }

    public Optional<BaselineRow> forSpace(long orgId, long spaceId) {
        return jdbc.sql(BASELINE + " WHERE b.organization_id = :org AND b.scope_space_id = :space").param("org", orgId).param("space", spaceId)
                .query(EnergyBaselineRepository::baseline).optional();
    }

    /** 범위 공간의 기준선 ID(없으면 만든다) */
    public long ensure(long orgId, long spaceId, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.energy_baselines (organization_id, scope_space_id, created_at, updated_at) VALUES (:org, :space, :now, :now)
                        ON CONFLICT (organization_id, scope_space_id) DO NOTHING""")
                .param("org", orgId).param("space", spaceId).param("now", Pg.ts(now)).update();
        return jdbc.sql("SELECT id FROM data2flow_core.energy_baselines WHERE organization_id = :org AND scope_space_id = :space FOR UPDATE")
                .param("org", orgId).param("space", spaceId).query(Long.class).single();
    }

    public long insertVersion(long orgId, long baselineId, String method, LocalDate from, LocalDate to, String paramsJson, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.energy_baseline_versions (organization_id, baseline_id, method, train_from, train_to, params, status,
                               created_by, created_at, updated_at)
                        VALUES (:org, :baseline, :method, :from, :to, CAST(:params AS jsonb), 'TRAINING', :by, :now, :now) RETURNING id""")
                .param("org", orgId).param("baseline", baselineId).param("method", method).param("from", from).param("to", to)
                .param("params", paramsJson).param("by", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public void setModelKey(long versionId, String key) {
        jdbc.sql("UPDATE data2flow_core.energy_baseline_versions SET analytics_model_id = :key WHERE id = :id").param("key", key).param("id", versionId)
                .update();
    }

    public List<VersionRow> versions(long orgId, long baselineId) {
        return jdbc.sql(VERSION + " WHERE v.organization_id = :org AND v.baseline_id = :baseline ORDER BY v.id DESC").param("org", orgId)
                .param("baseline", baselineId).query(EnergyBaselineRepository::version).list();
    }

    public Optional<VersionRow> version(long orgId, long versionId, boolean lock) {
        return jdbc.sql(VERSION + " WHERE v.organization_id = :org AND v.id = :id" + (lock ? " FOR UPDATE OF v" : "")).param("org", orgId)
                .param("id", versionId).query(EnergyBaselineRepository::version).optional();
    }

    /** EVT-ENE-02 READY: 학습 중인 버전만 바꾼다(늦게 온 중복 이벤트는 0) */
    public int trained(long orgId, long versionId, BigDecimal r2, String coefficientsJson, String modelKey, String warning, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.energy_baseline_versions SET status = 'READY', r2 = :r2, coefficients = CAST(:coef AS jsonb),
                               analytics_model_id = coalesce(:key, analytics_model_id), warning = :warning, error_code = NULL, needed_days = NULL,
                               updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'TRAINING'""")
                .param("r2", r2).param("coef", coefficientsJson).param("key", modelKey).param("warning", warning).param("now", Pg.ts(now))
                .param("org", orgId).param("id", versionId).update();
    }

    /** EVT-ENE-02 FAILED */
    public int failed(long orgId, long versionId, String errorCode, Integer neededDays, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.energy_baseline_versions SET status = 'FAILED', error_code = :error, needed_days = :needed, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'TRAINING'""")
                .param("error", errorCode).param("needed", neededDays).param("now", Pg.ts(now)).param("org", orgId).param("id", versionId).update();
    }

    /** READY → ACTIVE, 같은 기준선의 이전 ACTIVE → RETIRED, 기준선의 활성 버전을 바꾼다 */
    public void activate(long baselineId, long versionId, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.energy_baseline_versions SET status = 'RETIRED', updated_at = :now
                         WHERE baseline_id = :baseline AND status = 'ACTIVE' AND id <> :id""")
                .param("baseline", baselineId).param("id", versionId).param("now", Pg.ts(now)).update();
        jdbc.sql("UPDATE data2flow_core.energy_baseline_versions SET status = 'ACTIVE', updated_at = :now WHERE id = :id").param("id", versionId)
                .param("now", Pg.ts(now)).update();
        jdbc.sql("UPDATE data2flow_core.energy_baselines SET active_version_id = :id, updated_at = :now WHERE id = :baseline").param("id", versionId)
                .param("baseline", baselineId).param("now", Pg.ts(now)).update();
    }

    private static BaselineRow baseline(ResultSet rs, int n) throws SQLException {
        return new BaselineRow(rs.getLong("id"), rs.getLong("scope_space_id"), rs.getString("scope_space_name"), Pg.longOrNull(rs, "active_version_id"),
                rs.getString("a_method"), rs.getBigDecimal("a_r2"), rs.getObject("a_from", LocalDate.class), rs.getObject("a_to", LocalDate.class),
                rs.getString("a_model"), Pg.instant(rs, "updated_at"));
    }

    private static VersionRow version(ResultSet rs, int n) throws SQLException {
        int needed = rs.getInt("needed_days");
        return new VersionRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("baseline_id"), rs.getLong("scope_space_id"),
                rs.getString("method"), rs.getObject("train_from", LocalDate.class), rs.getObject("train_to", LocalDate.class), rs.getString("params"),
                rs.getString("status"), rs.getBigDecimal("r2"), rs.getString("coefficients"), rs.getString("analytics_model_id"), rs.getString("warning"),
                rs.getString("error_code"), rs.wasNull() ? null : needed, rs.getLong("created_by"), rs.getString("created_by_name"),
                Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"));
    }
}
