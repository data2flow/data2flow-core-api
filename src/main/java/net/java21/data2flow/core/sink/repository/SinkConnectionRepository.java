package net.java21.data2flow.core.sink.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Sink 연결({@code data2flow_core.sink_connections}, FLW-04.01). 비밀값은 암호문으로만 */
@Repository
public class SinkConnectionRepository {

    static final String COLUMNS = """
            c.id, c.organization_id, c.name, c.type, c.config::text AS config, c.secret_enc, c.status, c.last_error, c.version, c.created_at,
            c.updated_at,
            (SELECT count(*) FROM data2flow_core.flows f JOIN data2flow_core.flow_versions v ON v.flow_id = f.id AND v.organization_id = f.organization_id
                    AND v.version_no IN (f.active_version, f.draft_version)
              WHERE f.organization_id = c.organization_id AND f.status <> 'DELETED'
                AND jsonb_path_exists(v.definition, '$.nodes[*] ? (@.type == "sink.database").config.connectionId ? (@ == $id || @ == $sid)',
                                      jsonb_build_object('id', c.id, 'sid', c.id::text))) AS used_flow_count""";

    private final JdbcClient jdbc;

    public SinkConnectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SinkRow(long id, long organizationId, String name, String type, String config, byte[] secretEnc, String status,
                          String lastError, int version, Instant createdAt, Instant updatedAt, long usedFlowCount) {
    }

    public List<SinkRow> list(long organizationId, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.sink_connections c WHERE c.organization_id = :org ORDER BY c.name, c.id"
                        + " LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("limit", limit).param("offset", offset).query(SinkConnectionRepository::map).list();
    }

    public long count(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.sink_connections WHERE organization_id = :org").param("org", organizationId)
                .query(Long.class).single();
    }

    public Optional<SinkRow> findById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.sink_connections c WHERE c.organization_id = :org AND c.id = :id")
                .param("org", organizationId).param("id", id).query(SinkConnectionRepository::map).optional();
    }

    @OrganizationScopeExempt("연결 ID는 전역 고유이고 응답에 organizationId를 담는다(API-FLW-85 action 내부 호출)")
    public Optional<SinkRow> findAnyOrganization(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.sink_connections c WHERE c.id = :id").param("id", id)
                .query(SinkConnectionRepository::map).optional();
    }

    public boolean existsName(long organizationId, String name, Long exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.sink_connections WHERE organization_id = :org AND name = :name
                                         AND (CAST(:except AS bigint) IS NULL OR id <> :except))""")
                .param("org", organizationId).param("name", name).param("except", exceptId).query(Boolean.class).single();
    }

    /** 새 연결(비밀값은 저장 뒤 ID 문맥으로 암호화해 다시 쓴다) */
    public long insert(long organizationId, String name, String type, String config, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.sink_connections (organization_id, name, type, config, secret_enc, status, version, created_by,
                               updated_by, created_at, updated_at)
                        VALUES (:org, :name, :type, CAST(:config AS jsonb), '\\x'::bytea, 'UNTESTED', 1, :user, :user, :now, :now) RETURNING id""")
                .param("org", organizationId).param("name", name).param("type", type).param("config", config).param("user", userId)
                .param("now", Pg.ts(now)).query(Long.class).single();
    }

    public int update(long organizationId, long id, int baseVersion, String name, String config, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.sink_connections SET name = :name, config = CAST(:config AS jsonb), status = 'UNTESTED',
                               version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .param("name", name).param("config", config).param("user", userId).param("now", Pg.ts(now)).param("org", organizationId)
                .param("id", id).param("base", baseVersion).update();
    }

    public void updateSecret(long organizationId, long id, byte[] secretEnc) {
        jdbc.sql("UPDATE data2flow_core.sink_connections SET secret_enc = :secret WHERE organization_id = :org AND id = :id")
                .param("secret", secretEnc).param("org", organizationId).param("id", id).update();
    }

    public void updateStatus(long organizationId, long id, String status, String lastError) {
        jdbc.sql("UPDATE data2flow_core.sink_connections SET status = :status, last_error = :error WHERE organization_id = :org AND id = :id")
                .param("status", status).param("error", lastError).param("org", organizationId).param("id", id).update();
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.sink_connections WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    static SinkRow map(ResultSet rs, int n) throws SQLException {
        return new SinkRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("type"), rs.getString("config"),
                rs.getBytes("secret_enc"), rs.getString("status"), rs.getString("last_error"), rs.getInt("version"), Pg.instant(rs, "created_at"),
                Pg.instant(rs, "updated_at"), rs.getLong("used_flow_count"));
    }
}
