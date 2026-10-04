package net.java21.data2flow.core.maintenance.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 유지보수 구간({@code data2flow_core.maintenance_windows}, OPS-05, BR-OPS-10·11) */
@Repository
public class MaintenanceRepository {

    static final String SELECT = """
            SELECT w.id, w.organization_id, w.target_type, w.target_id, w.starts_at, w.ends_at, w.pause_automation, w.exclude_from_analytics,
                   w.reason, w.status, w.version, w.created_by, w.created_at,
                   CASE w.target_type WHEN 'SPACE' THEN s.name ELSE d.name END AS target_name,
                   CASE w.target_type WHEN 'SPACE' THEN s.path ELSE ds.path END AS target_path,
                   CASE w.target_type WHEN 'SPACE' THEN s.id ELSE d.space_id END AS target_space_id
              FROM data2flow_core.maintenance_windows w
              LEFT JOIN data2flow_core.spaces s ON w.target_type = 'SPACE' AND s.id = w.target_id AND s.organization_id = w.organization_id
              LEFT JOIN data2flow_core.devices d ON w.target_type = 'DEVICE' AND d.id = w.target_id AND d.organization_id = w.organization_id
              LEFT JOIN data2flow_core.spaces ds ON ds.id = d.space_id AND ds.organization_id = w.organization_id""";

    private final JdbcClient jdbc;

    public MaintenanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record WindowRow(long id, long organizationId, String targetType, long targetId, Instant startsAt, Instant endsAt,
                            boolean pauseAutomation, boolean excludeFromAnalytics, String reason, String status, int version, long createdBy,
                            Instant createdAt, String targetName, String targetPath, Long targetSpaceId) {
    }

    public Optional<WindowRow> findById(long organizationId, long id) {
        return jdbc.sql(SELECT + " WHERE w.organization_id = :org AND w.id = :id").param("org", organizationId).param("id", id)
                .query(MaintenanceRepository::map).optional();
    }

    public Optional<WindowRow> lockById(long organizationId, long id) {
        jdbc.sql("SELECT id FROM data2flow_core.maintenance_windows WHERE organization_id = :org AND id = :id FOR UPDATE")
                .param("org", organizationId).param("id", id).query(Long.class).optional();
        return findById(organizationId, id);
    }

    /** 같은 대상의 겹치는 열린 구간(SCHEDULED·ACTIVE). 끝이 없으면 무한 */
    public boolean existsOverlap(long organizationId, String targetType, long targetId, Instant startsAt, Instant endsAt) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.maintenance_windows WHERE organization_id = :org AND target_type = :type
                                         AND target_id = :target AND status IN ('SCHEDULED','ACTIVE')
                                         AND starts_at < coalesce(CAST(:ends AS timestamptz), 'infinity'::timestamptz)
                                         AND coalesce(ends_at, 'infinity'::timestamptz) > :starts)""")
                .param("org", organizationId).param("type", targetType).param("target", targetId).param("starts", Pg.ts(startsAt))
                .param("ends", Pg.ts(endsAt)).query(Boolean.class).single();
    }

    public long insert(long organizationId, String targetType, long targetId, Instant startsAt, Instant endsAt, boolean pause, boolean exclude,
                       String reason, String status, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.maintenance_windows (organization_id, target_type, target_id, starts_at, ends_at, pause_automation,
                               exclude_from_analytics, reason, status, version, created_by, created_at, updated_at)
                        VALUES (:org, :type, :target, :starts, :ends, :pause, :exclude, :reason, :status, 1, :user, :now, :now) RETURNING id""")
                .param("org", organizationId).param("type", targetType).param("target", targetId).param("starts", Pg.ts(startsAt))
                .param("ends", Pg.ts(endsAt)).param("pause", pause).param("exclude", exclude).param("reason", reason).param("status", status)
                .param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public void updateStatus(long organizationId, long id, String status, Instant endsAt, Long endedBy, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.maintenance_windows SET status = :status, ends_at = coalesce(CAST(:ends AS timestamptz), ends_at),
                               ended_by = coalesce(CAST(:by AS bigint), ended_by), version = version + 1, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("status", status).param("ends", Pg.ts(endsAt)).param("by", endedBy).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", id).update();
    }

    public List<WindowRow> list(long organizationId, Collection<String> statuses, Long targetId, int limit, long offset) {
        return jdbc.sql(SELECT + " WHERE w.organization_id = :org AND (CAST(:statuses AS text[]) IS NULL OR w.status = ANY(CAST(:statuses AS text[])))"
                        + " AND (CAST(:target AS bigint) IS NULL OR w.target_id = :target) ORDER BY w.starts_at DESC, w.id DESC LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("statuses", statuses == null ? null : Pg.textArray(statuses)).param("target", targetId)
                .param("limit", limit).param("offset", offset).query(MaintenanceRepository::map).list();
    }

    public long count(long organizationId, Collection<String> statuses, Long targetId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.maintenance_windows w WHERE w.organization_id = :org"
                        + " AND (CAST(:statuses AS text[]) IS NULL OR w.status = ANY(CAST(:statuses AS text[])))"
                        + " AND (CAST(:target AS bigint) IS NULL OR w.target_id = :target)")
                .param("org", organizationId).param("statuses", statuses == null ? null : Pg.textArray(statuses)).param("target", targetId)
                .query(Long.class).single();
    }

    /** 시작할 때가 된 SCHEDULED, 끝날 때가 된 ACTIVE */
    public List<WindowRow> listDue(long organizationId, Instant now) {
        return jdbc.sql(SELECT + " WHERE w.organization_id = :org AND ((w.status = 'SCHEDULED' AND w.starts_at <= :now)"
                        + " OR (w.status IN ('SCHEDULED','ACTIVE') AND w.ends_at IS NOT NULL AND w.ends_at <= :now)) ORDER BY w.starts_at, w.id")
                .param("org", organizationId).param("now", Pg.ts(now)).query(MaintenanceRepository::map).list();
    }

    /** 하위 공간 ID(대상이 공간일 때, 자기 제외) */
    public List<Long> listDescendants(long organizationId, String path) {
        return jdbc.sql("SELECT id FROM data2flow_core.spaces WHERE organization_id = :org AND path LIKE :path || '%' AND path <> :path ORDER BY id")
                .param("org", organizationId).param("path", path).query(Long.class).list();
    }

    public Optional<Long> findTargetSpace(long organizationId, String targetType, long targetId) {
        String sql = "SPACE".equals(targetType)
                ? "SELECT id FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id"
                : "SELECT coalesce(space_id, 0) FROM data2flow_core.devices WHERE organization_id = :org AND id = :id AND status <> 'DELETED'";
        return jdbc.sql(sql).param("org", organizationId).param("id", targetId).query(Long.class).optional();
    }

    static WindowRow map(ResultSet rs, int n) throws SQLException {
        return new WindowRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("target_type"), rs.getLong("target_id"),
                Pg.instant(rs, "starts_at"), Pg.instant(rs, "ends_at"), rs.getBoolean("pause_automation"),
                rs.getBoolean("exclude_from_analytics"), rs.getString("reason"), rs.getString("status"), rs.getInt("version"),
                rs.getLong("created_by"), Pg.instant(rs, "created_at"), rs.getString("target_name"), rs.getString("target_path"),
                Pg.longOrNull(rs, "target_space_id"));
    }
}
