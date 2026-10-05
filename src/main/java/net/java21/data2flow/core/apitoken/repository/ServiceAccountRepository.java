package net.java21.data2flow.core.apitoken.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 서비스 계정({@code data2flow_core.service_accounts}, IAM-05.01·BR-IAM-35). 로그인 아이디가 없어 웹 로그인은 할 수 없다 */
@Repository
public class ServiceAccountRepository {

    private static final String SELECT = """
            SELECT sa.id, sa.organization_id, sa.name, sa.description, sa.status, sa.version, sa.created_by, sa.created_at,
                   (SELECT count(*) FROM data2flow_core.api_tokens t
                     WHERE t.organization_id = sa.organization_id AND t.owner_type = 'SERVICE_ACCOUNT' AND t.owner_id = sa.id
                       AND t.status IN ('PENDING_APPROVAL','ACTIVE','ROTATING')) AS token_count
              FROM data2flow_core.service_accounts sa""";

    private final JdbcClient jdbc;

    public ServiceAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record AccountRow(long id, long organizationId, String name, String description, String status, int version, long createdBy,
                             Instant createdAt, long tokenCount) {
    }

    public long insert(long organizationId, String name, String description, long createdBy, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.service_accounts (organization_id, name, description, created_by, created_at, updated_at)
                        VALUES (:org, :name, :description, :by, :now, :now) RETURNING id""")
                .param("org", organizationId).param("name", name).param("description", description).param("by", createdBy)
                .param("now", Pg.ts(now)).query(Long.class).single();
    }

    public Optional<AccountRow> find(long organizationId, long id) {
        return jdbc.sql(SELECT + " WHERE sa.organization_id = :org AND sa.id = :id")
                .param("org", organizationId).param("id", id).query(ServiceAccountRepository::row).optional();
    }

    public boolean existsName(long organizationId, String name) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.service_accounts WHERE organization_id = :org AND lower(name) = lower(:name))")
                .param("org", organizationId).param("name", name).query(Boolean.class).single();
    }

    public List<AccountRow> list(long organizationId, int limit, long offset) {
        return jdbc.sql(SELECT + " WHERE sa.organization_id = :org ORDER BY sa.id DESC LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("limit", limit).param("offset", offset).query(ServiceAccountRepository::row).list();
    }

    public long count(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.service_accounts WHERE organization_id = :org")
                .param("org", organizationId).query(Long.class).single();
    }

    /** ACTIVE → DISABLED. 바뀐 행 수(이미 DISABLED면 0) */
    public int disable(long organizationId, long id, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.service_accounts SET status = 'DISABLED', version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'ACTIVE'""")
                .param("by", userId).param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    static AccountRow row(ResultSet rs, int n) throws SQLException {
        return new AccountRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("description"),
                rs.getString("status"), rs.getInt("version"), rs.getLong("created_by"), Pg.instant(rs, "created_at"),
                rs.getLong("token_count"));
    }
}
