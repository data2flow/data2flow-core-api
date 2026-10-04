package net.java21.data2flow.core.control.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 인터락(interlock_rules, ACT-02.08·06.02)·비상 정지(emergency_stops, ACT-06.03)·측정값 조회(API-ACT-45) */
@Repository
public class SafetyRepository {

    private final JdbcClient jdbc;

    public SafetyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 인터락

    public record InterlockRow(long id, long organizationId, String name, Long spaceId, String spaceName, boolean includeChildren,
                               boolean enabled, String condition, String forbid, String message, int version, Instant updatedAt) {
    }

    static final String INTERLOCK = """
            SELECT i.id, i.organization_id, i.name, i.space_id, s.name AS space_name, i.include_children, i.enabled, i.condition::text AS condition,
                   i.forbid::text AS forbid, i.message, i.version, i.updated_at
              FROM data2flow_core.interlock_rules i
              LEFT JOIN data2flow_core.spaces s ON s.id = i.space_id AND s.organization_id = i.organization_id""";

    public List<InterlockRow> listInterlocks(long organizationId) {
        return jdbc.sql(INTERLOCK + " WHERE i.organization_id = :org ORDER BY i.name, i.id").param("org", organizationId)
                .query(SafetyRepository::interlock).list();
    }

    public Optional<InterlockRow> findInterlock(long organizationId, long id) {
        return jdbc.sql(INTERLOCK + " WHERE i.organization_id = :org AND i.id = :id").param("org", organizationId).param("id", id)
                .query(SafetyRepository::interlock).optional();
    }

    public boolean existsInterlockName(long organizationId, String name, Long exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.interlock_rules WHERE organization_id = :org AND name = :name
                                         AND (CAST(:except AS bigint) IS NULL OR id <> :except))""")
                .param("org", organizationId).param("name", name).param("except", exceptId).query(Boolean.class).single();
    }

    public long insertInterlock(long organizationId, String name, Long spaceId, boolean includeChildren, boolean enabled, String condition,
                                String forbid, String message, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.interlock_rules (organization_id, name, space_id, include_children, enabled, condition, forbid,
                               message, version, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :name, :space, :children, :enabled, CAST(:condition AS jsonb), CAST(:forbid AS jsonb), :message, 1, :user,
                                :user, :now, :now) RETURNING id""")
                .param("org", organizationId).param("name", name).param("space", spaceId).param("children", includeChildren)
                .param("enabled", enabled).param("condition", condition).param("forbid", forbid).param("message", message)
                .param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public int updateInterlock(long organizationId, long id, Integer baseVersion, String name, Long spaceId, boolean includeChildren,
                               boolean enabled, String condition, String forbid, String message, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.interlock_rules SET name = :name, space_id = :space, include_children = :children, enabled = :enabled,
                               condition = CAST(:condition AS jsonb), forbid = CAST(:forbid AS jsonb), message = :message, version = version + 1,
                               updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND (CAST(:base AS integer) IS NULL OR version = :base)""")
                .param("name", name).param("space", spaceId).param("children", includeChildren).param("enabled", enabled)
                .param("condition", condition).param("forbid", forbid).param("message", message).param("user", userId)
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", id).param("base", baseVersion).update();
    }

    public int deleteInterlock(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.interlock_rules WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    /** API-ACT-44: 켜져 있고 기기 공간을 덮는 인터락(공간 없음 = 조직 전체, 하위 포함 여부 반영) */
    public List<InterlockRow> listInterlocksForDevice(long organizationId, long deviceId) {
        return jdbc.sql(INTERLOCK + " " + """
                         WHERE i.organization_id = :org AND i.enabled
                           AND (i.space_id IS NULL OR EXISTS (
                                SELECT 1 FROM data2flow_core.devices d JOIN data2flow_core.spaces ds ON ds.id = d.space_id
                                       AND ds.organization_id = d.organization_id
                                 WHERE d.organization_id = i.organization_id AND d.id = :device
                                   AND (ds.id = i.space_id OR (i.include_children AND ds.path LIKE s.path || '%'))))
                         ORDER BY i.id""")
                .param("org", organizationId).param("device", deviceId).query(SafetyRepository::interlock).list();
    }

    static InterlockRow interlock(ResultSet rs, int n) throws SQLException {
        return new InterlockRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), Pg.longOrNull(rs, "space_id"),
                rs.getString("space_name"), rs.getBoolean("include_children"), rs.getBoolean("enabled"), rs.getString("condition"),
                rs.getString("forbid"), rs.getString("message"), rs.getInt("version"), Pg.instant(rs, "updated_at"));
    }

    // ------------------------------------------------------------------ 비상 정지

    public record StopRow(long id, long organizationId, String scope, String reason, long startedBy, Instant startedAt, Long releasedBy,
                          Instant releasedAt, String releaseNote, String startedByName, String releasedByName) {
    }

    static final String STOP = """
            SELECT id, organization_id, scope::text AS scope, reason, started_by, started_at, released_by, released_at, release_note,
                   (SELECT u.name FROM data2flow_core.app_users u WHERE u.id = e.started_by AND u.organization_id = e.organization_id)
                       AS started_by_name,
                   (SELECT u.name FROM data2flow_core.app_users u WHERE u.id = e.released_by AND u.organization_id = e.organization_id)
                       AS released_by_name
              FROM data2flow_core.emergency_stops e""";

    public List<StopRow> listStops(long organizationId, boolean activeOnly, int limit) {
        return jdbc.sql(STOP + " WHERE organization_id = :org AND (NOT :active OR released_at IS NULL) ORDER BY started_at DESC, id DESC LIMIT :limit")
                .param("org", organizationId).param("active", activeOnly).param("limit", limit).query(SafetyRepository::stop).list();
    }

    @OrganizationScopeExempt("배포 조직 목록으로 거른다(API-ACT-46: 배포 조직 전체의 진행 중 비상 정지)")
    public List<StopRow> listActiveStops(Collection<Long> organizationIds) {
        return jdbc.sql(STOP + " WHERE released_at IS NULL AND organization_id = ANY(CAST(:orgs AS bigint[])) ORDER BY id")
                .param("orgs", Pg.bigintArray(organizationIds)).query(SafetyRepository::stop).list();
    }

    public Optional<StopRow> lockStop(long organizationId, long id) {
        return jdbc.sql(STOP + " WHERE organization_id = :org AND id = :id FOR UPDATE").param("org", organizationId).param("id", id)
                .query(SafetyRepository::stop).optional();
    }

    /** 같은 조직의 비상 정지 시작 직렬화(같은 범위 중복 409) */
    public void lockOrganization(long organizationId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", 0x6432_6665_7374_0000L + organizationId).query(Object.class).list();
    }

    public long insertStop(long organizationId, String scope, String reason, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.emergency_stops (organization_id, scope, reason, started_by, started_at)
                        VALUES (:org, CAST(:scope AS jsonb), :reason, :user, :now) RETURNING id""")
                .param("org", organizationId).param("scope", scope).param("reason", reason).param("user", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    public void updateReleased(long organizationId, long id, long userId, String note, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.emergency_stops SET released_by = :user, released_at = :now, release_note = :note
                         WHERE organization_id = :org AND id = :id""")
                .param("user", userId).param("now", Pg.ts(now)).param("note", note).param("org", organizationId).param("id", id).update();
    }

    static StopRow stop(ResultSet rs, int n) throws SQLException {
        return new StopRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("scope"), rs.getString("reason"),
                rs.getLong("started_by"), Pg.instant(rs, "started_at"), Pg.longOrNull(rs, "released_by"), Pg.instant(rs, "released_at"),
                rs.getString("release_note"), rs.getString("started_by_name"), rs.getString("released_by_name"));
    }

    // ------------------------------------------------------------------ 측정값(API-ACT-45)

    public record MetricValueRow(Double value, Instant measuredAt, Integer reportIntervalSec) {
    }

    /** 기기의 at 이전 마지막 측정값(pipeline telemetry 읽기) */
    @OrganizationScopeExempt("기기 ID는 전역 고유이고 기기 행의 조직과 같은 조직의 측정값만 읽는다(action 내부 호출, ADR-021)")
    public Optional<MetricValueRow> findDeviceValue(long deviceId, String metric, Instant at) {
        return jdbc.sql("""
                        SELECT t.value, t.time, coalesce(d.expected_interval_sec, m.default_interval_sec) AS interval_sec
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_core.device_models m ON m.id = d.model_id AND m.organization_id = d.organization_id
                          JOIN LATERAL (SELECT value, time FROM data2flow_pipeline.telemetry
                                         WHERE device_id = d.id AND organization_id = d.organization_id AND metric_key = :metric AND time <= :at
                                         ORDER BY time DESC LIMIT 1) t ON true
                         WHERE d.id = :device""")
                .param("device", deviceId).param("metric", metric).param("at", Pg.ts(at))
                .query((rs, n) -> new MetricValueRow(rs.getDouble("value"), Pg.instant(rs, "time"), (Integer) rs.getObject("interval_sec")))
                .optional();
    }

    /** 공간(측정 기기 = 공간에 놓인 기기와 MEASURES 관계 기기)의 at 이전 마지막 값 평균(최근 24시간 안) */
    @OrganizationScopeExempt("공간 ID는 전역 고유이고 공간 행의 조직과 같은 조직의 기기만 쓴다(action 내부 호출, ADR-021)")
    public Optional<MetricValueRow> findSpaceAverage(long spaceId, String metric, Instant at) {
        List<MetricValueRow> rows = jdbc.sql("""
                        SELECT avg(t.value) AS value, max(t.time) AS time, max(coalesce(d.expected_interval_sec, m.default_interval_sec)) AS interval_sec
                          FROM data2flow_core.spaces s
                          JOIN data2flow_core.devices d ON d.organization_id = s.organization_id AND d.status = 'ACTIVE'
                               AND (d.space_id = s.id OR EXISTS (SELECT 1 FROM data2flow_core.device_space_relations r
                                     WHERE r.organization_id = d.organization_id AND r.device_id = d.id AND r.space_id = s.id AND r.relation = 'MEASURES'))
                          LEFT JOIN data2flow_core.device_models m ON m.id = d.model_id AND m.organization_id = d.organization_id
                          JOIN LATERAL (SELECT value, time FROM data2flow_pipeline.telemetry
                                         WHERE device_id = d.id AND organization_id = d.organization_id AND metric_key = :metric
                                           AND time <= :at AND time > :since ORDER BY time DESC LIMIT 1) t ON true
                         WHERE s.id = :space""")
                .param("space", spaceId).param("metric", metric).param("at", Pg.ts(at)).param("since", Pg.ts(at.minusSeconds(86400)))
                .query((rs, n) -> {
                    double v = rs.getDouble("value");
                    return rs.wasNull() ? null : new MetricValueRow(v, Pg.instant(rs, "time"), (Integer) rs.getObject("interval_sec"));
                }).list();
        return rows.isEmpty() || rows.getFirst() == null ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** 공간 경로(범위 판정) */
    public Optional<String> findSpacePath(long organizationId, long spaceId) {
        return jdbc.sql("SELECT path FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", spaceId).query(String.class).optional();
    }

    public Optional<Long> findDeviceSpace(long organizationId, long deviceId) {
        return jdbc.sql("SELECT coalesce(space_id, 0) FROM data2flow_core.devices WHERE organization_id = :org AND id = :id AND status <> 'DELETED'")
                .param("org", organizationId).param("id", deviceId).query(Long.class).optional();
    }
}
