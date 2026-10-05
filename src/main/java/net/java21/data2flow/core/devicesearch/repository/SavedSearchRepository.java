package net.java21.data2flow.core.devicesearch.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 저장된 기기 검색({@code saved_searches}, DEV-13.03, API-DEV-134). 본인 것과 공유된 것이 보인다 */
@Repository
public class SavedSearchRepository {

    private static final String SELECT = """
            SELECT s.id, s.organization_id, s.owner_id, u.name AS owner_name, s.name, s.query, s.ast::text AS ast, s.shared, s.updated_at
              FROM data2flow_core.saved_searches s
              LEFT JOIN data2flow_core.app_users u ON u.id = s.owner_id AND u.organization_id = s.organization_id""";

    private final JdbcClient jdbc;

    public SavedSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<SavedSearch> listVisible(long organizationId, long userId, int limit, long offset) {
        return jdbc.sql(SELECT + " WHERE s.organization_id = :org AND (s.owner_id = :user OR s.shared) ORDER BY s.name, s.id LIMIT :l OFFSET :o")
                .param("org", organizationId).param("user", userId).param("l", limit).param("o", offset).query(SavedSearchRepository::map).list();
    }

    public long countVisible(long organizationId, long userId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.saved_searches s WHERE s.organization_id = :org AND (s.owner_id = :user OR s.shared)")
                .param("org", organizationId).param("user", userId).query(Long.class).single();
    }

    public Optional<SavedSearch> find(long organizationId, long id) {
        return jdbc.sql(SELECT + " WHERE s.organization_id = :org AND s.id = :id")
                .param("org", organizationId).param("id", id).query(SavedSearchRepository::map).optional();
    }

    public boolean existsName(long organizationId, long ownerId, String name, Long exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.saved_searches WHERE organization_id = :org AND owner_id = :owner AND name = :name
                                         AND (CAST(:except AS bigint) IS NULL OR id <> :except))""")
                .param("org", organizationId).param("owner", ownerId).param("name", name).param("except", exceptId)
                .query(Boolean.class).single();
    }

    public long insert(long organizationId, long ownerId, String name, String query, String astJson, boolean shared, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.saved_searches (organization_id, owner_id, name, query, ast, shared, created_at, updated_at)
                        VALUES (:org, :owner, :name, :query, CAST(:ast AS jsonb), :shared, :now, :now) RETURNING id""")
                .param("org", organizationId).param("owner", ownerId).param("name", name).param("query", query).param("ast", astJson)
                .param("shared", shared).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public int update(long organizationId, long id, String name, String query, String astJson, boolean shared, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.saved_searches SET name = :name, query = :query, ast = CAST(:ast AS jsonb), shared = :shared, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("name", name).param("query", query).param("ast", astJson).param("shared", shared).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", id).update();
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.saved_searches WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    static SavedSearch map(ResultSet rs, int n) throws SQLException {
        return new SavedSearch(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("owner_id"), rs.getString("owner_name"),
                rs.getString("name"), rs.getString("query"), rs.getString("ast"), rs.getBoolean("shared"), Pg.instant(rs, "updated_at"));
    }

    public record SavedSearch(long id, long organizationId, long ownerId, String ownerName, String name, String query, String astJson,
                              boolean shared, Instant updatedAt) {
    }
}
