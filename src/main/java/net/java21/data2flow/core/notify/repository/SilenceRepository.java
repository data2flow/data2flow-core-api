package net.java21.data2flow.core.notify.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 무음({@code data2flow_core.silences}, RUL-02.07). 대상 이름과 SPACE 대상의 경로를 붙여 읽는다 */
@Repository
public class SilenceRepository {

    static final String SELECT = """
            SELECT si.id, si.organization_id, si.kind, si.target_type, si.target_id, si.starts_at, si.ends_at, si.recurrence::text AS recurrence,
                   si.reason, si.created_by, u.name AS created_by_name, si.created_at,
                   CASE si.target_type WHEN 'SPACE' THEN sp.name WHEN 'DEVICE' THEN d.name WHEN 'RULE' THEN r.name
                        WHEN 'ALARM' THEN al.title END AS target_name,
                   sp.path AS target_space_path
              FROM data2flow_core.silences si
              LEFT JOIN data2flow_core.app_users u ON u.id = si.created_by AND u.organization_id = si.organization_id
              LEFT JOIN data2flow_core.spaces sp ON si.target_type = 'SPACE' AND sp.id = si.target_id AND sp.organization_id = si.organization_id
              LEFT JOIN data2flow_core.devices d ON si.target_type = 'DEVICE' AND d.id = si.target_id AND d.organization_id = si.organization_id
              LEFT JOIN data2flow_core.rules r ON si.target_type = 'RULE' AND r.id = si.target_id AND r.organization_id = si.organization_id
              LEFT JOIN data2flow_core.alarms al ON si.target_type = 'ALARM' AND al.id = si.target_id AND al.organization_id = si.organization_id""";

    private final JdbcClient jdbc;

    public SilenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SilenceRow(long id, long organizationId, String kind, String targetType, long targetId, Instant startsAt, Instant endsAt,
                             String recurrence, String reason, long createdBy, String createdByName, Instant createdAt, String targetName,
                             String targetSpacePath) {
    }

    public long insert(long organizationId, String kind, String targetType, long targetId, Instant startsAt, Instant endsAt,
                       String recurrenceJson, String reason, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.silences (organization_id, kind, target_type, target_id, starts_at, ends_at, recurrence, reason,
                               created_by, created_at)
                        VALUES (:org, :kind, :type, :target, :starts, :ends, CAST(:recurrence AS jsonb), :reason, :user, :now) RETURNING id""")
                .param("org", organizationId).param("kind", kind).param("type", targetType).param("target", targetId)
                .param("starts", Pg.ts(startsAt)).param("ends", Pg.ts(endsAt)).param("recurrence", recurrenceJson).param("reason", reason)
                .param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public Optional<SilenceRow> findById(long organizationId, long id) {
        return jdbc.sql(SELECT + " WHERE si.organization_id = :org AND si.id = :id").param("org", organizationId).param("id", id)
                .query(SilenceRepository::map).optional();
    }

    /** 끝나지 않은 무음(반복 무음 포함). 끝난 일회 무음은 뺀다 */
    public List<SilenceRow> listCurrent(long organizationId, Instant now) {
        return jdbc.sql(SELECT + " WHERE si.organization_id = :org AND (si.kind = 'RECURRING' OR si.ends_at > :now) ORDER BY si.id")
                .param("org", organizationId).param("now", Pg.ts(now)).query(SilenceRepository::map).list();
    }

    public List<SilenceRow> list(long organizationId, boolean includeEnded, Instant now, int limit, long offset) {
        return jdbc.sql(SELECT + " WHERE si.organization_id = :org AND (:all OR si.kind = 'RECURRING' OR si.ends_at > :now)"
                        + " ORDER BY si.created_at DESC, si.id DESC LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("all", includeEnded).param("now", Pg.ts(now)).param("limit", limit)
                .param("offset", offset).query(SilenceRepository::map).list();
    }

    public long count(long organizationId, boolean includeEnded, Instant now) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.silences si WHERE si.organization_id = :org"
                        + " AND (:all OR si.kind = 'RECURRING' OR si.ends_at > :now)")
                .param("org", organizationId).param("all", includeEnded).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.silences WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    static SilenceRow map(ResultSet rs, int n) throws SQLException {
        return new SilenceRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("kind"), rs.getString("target_type"),
                rs.getLong("target_id"), Pg.instant(rs, "starts_at"), Pg.instant(rs, "ends_at"), rs.getString("recurrence"),
                rs.getString("reason"), rs.getLong("created_by"), rs.getString("created_by_name"), Pg.instant(rs, "created_at"),
                rs.getString("target_name"), rs.getString("target_space_path"));
    }
}
