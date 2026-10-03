package net.java21.data2flow.core.role.repository;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.role.domain.RoleModels.CustomRole;
import net.java21.data2flow.core.role.domain.RoleModels.GrantSource;
import net.java21.data2flow.core.role.domain.RoleModels.UserRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 역할 저장소({@code user_roles}, {@code custom_roles}). v1은 사용자당 역할 1개(uq_user_roles_user_id) */
@Repository
public class RoleRepository {

    private final JdbcClient jdbc;

    public RoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 권한 판정 원천(BR-IAM-13: 요청마다 DB에서 판정) */
    public Optional<GrantSource> findGrantSource(long organizationId, long userId) {
        return jdbc.sql("""
                        SELECT u.status, r.role, r.space_scope, c.permissions
                          FROM data2flow_core.app_users u
                          LEFT JOIN data2flow_core.user_roles r ON r.user_id = u.id
                          LEFT JOIN data2flow_core.custom_roles c ON c.id = r.custom_role_id
                         WHERE u.id = :user AND u.organization_id = :org""")
                .param("user", userId).param("org", organizationId)
                .query((rs, n) -> new GrantSource(rs.getString("status"), rs.getString("role"),
                        rs.getString("role") == null ? List.of() : Pg.longList(rs, "space_scope"),
                        rs.getArray("permissions") == null ? null : Pg.stringList(rs, "permissions")))
                .optional();
    }

    public Optional<UserRole> findUserRole(long organizationId, long userId) {
        return jdbc.sql("""
                        SELECT organization_id, user_id, role, custom_role_id, space_scope, granted_by, granted_at
                          FROM data2flow_core.user_roles WHERE user_id = :user AND organization_id = :org""")
                .param("user", userId).param("org", organizationId).query(RoleRepository::mapUserRole).optional();
    }

    /** 역할 지정(있으면 바꾼다) */
    public void upsertUserRole(long organizationId, long userId, String role, Long customRoleId, List<Long> spaceScope,
                               long grantedBy, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.user_roles (organization_id, user_id, role, custom_role_id, space_scope, granted_by, granted_at)
                        VALUES (:org, :user, :role, :custom, CAST(:scope AS bigint[]), :by, :now)
                        ON CONFLICT (user_id) DO UPDATE SET role = EXCLUDED.role, custom_role_id = EXCLUDED.custom_role_id,
                            space_scope = EXCLUDED.space_scope, granted_by = EXCLUDED.granted_by, granted_at = EXCLUDED.granted_at""")
                .param("org", organizationId).param("user", userId).param("role", role).param("custom", customRoleId)
                .param("scope", Pg.bigintArray(spaceScope)).param("by", grantedBy).param("now", Pg.ts(now))
                .update();
    }

    public void deleteUserRole(long organizationId, long userId) {
        jdbc.sql("DELETE FROM data2flow_core.user_roles WHERE user_id = :user AND organization_id = :org")
                .param("user", userId).param("org", organizationId).update();
    }

    // ---- 사용자 정의 역할(IAM-04.03)

    public List<CustomRole> listCustomRoles(long organizationId, int limit, long offset) {
        return jdbc.sql("""
                        SELECT id, organization_id, name, description, permissions, based_on, version, updated_at
                          FROM data2flow_core.custom_roles WHERE organization_id = :org ORDER BY name LIMIT :limit OFFSET :offset""")
                .param("org", organizationId).param("limit", limit).param("offset", offset)
                .query(RoleRepository::mapCustomRole).list();
    }

    public long countCustomRoles(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.custom_roles WHERE organization_id = :org")
                .param("org", organizationId).query(Long.class).single();
    }

    public Optional<CustomRole> findCustomRole(long organizationId, long id) {
        return jdbc.sql("""
                        SELECT id, organization_id, name, description, permissions, based_on, version, updated_at
                          FROM data2flow_core.custom_roles WHERE id = :id AND organization_id = :org""")
                .param("id", id).param("org", organizationId).query(RoleRepository::mapCustomRole).optional();
    }

    public boolean existsCustomRoleName(long organizationId, String name, Long exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.custom_roles
                         WHERE organization_id = :org AND name = :name AND (CAST(:except AS bigint) IS NULL OR id <> :except))""")
                .param("org", organizationId).param("name", name).param("except", exceptId).query(Boolean.class).single();
    }

    public long insertCustomRole(long organizationId, String name, String description, List<String> permissions, String basedOn,
                                 long createdBy, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.custom_roles (organization_id, name, description, permissions, based_on, created_by,
                            created_at, updated_at)
                        VALUES (:org, :name, :desc, CAST(:perms AS varchar(40)[]), :basedOn, :by, :now, :now) RETURNING id""")
                .param("org", organizationId).param("name", name).param("desc", description)
                .param("perms", Pg.textArray(permissions)).param("basedOn", basedOn).param("by", createdBy).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    public int updateCustomRole(long organizationId, long id, int baseVersion, String name, String description,
                                List<String> permissions, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.custom_roles
                           SET name = :name, description = :desc, permissions = CAST(:perms AS varchar(40)[]), updated_by = :by,
                               version = version + 1, updated_at = :now
                         WHERE id = :id AND organization_id = :org AND version = :base""")
                .param("name", name).param("desc", description).param("perms", Pg.textArray(permissions))
                .param("by", updatedBy).param("now", Pg.ts(now)).param("id", id).param("org", organizationId)
                .param("base", baseVersion).update();
    }

    public int deleteCustomRole(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.custom_roles WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId).update();
    }

    public long countUsersWithCustomRole(long organizationId, long customRoleId) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.user_roles r JOIN data2flow_core.app_users u ON u.id = r.user_id
                         WHERE r.organization_id = :org AND r.custom_role_id = :id AND u.status <> 'DELETED'""")
                .param("org", organizationId).param("id", customRoleId).query(Long.class).single();
    }

    /** 이 역할을 쓰는 사용자 ID(권한 변경 이벤트용) */
    public List<Long> listUserIdsWithCustomRole(long organizationId, long customRoleId) {
        return jdbc.sql("SELECT user_id FROM data2flow_core.user_roles WHERE organization_id = :org AND custom_role_id = :id")
                .param("org", organizationId).param("id", customRoleId).query(Long.class).list();
    }

    private static UserRole mapUserRole(ResultSet rs, int n) throws SQLException {
        return new UserRole(rs.getLong("organization_id"), rs.getLong("user_id"), rs.getString("role"),
                Pg.longOrNull(rs, "custom_role_id"), Pg.longList(rs, "space_scope"), rs.getLong("granted_by"),
                Pg.instant(rs, "granted_at"));
    }

    private static CustomRole mapCustomRole(ResultSet rs, int n) throws SQLException {
        return new CustomRole(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("description"),
                Pg.stringList(rs, "permissions"), rs.getString("based_on"), rs.getInt("version"), Pg.instant(rs, "updated_at"));
    }
}
