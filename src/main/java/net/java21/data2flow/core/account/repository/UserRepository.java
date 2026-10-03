package net.java21.data2flow.core.account.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.account.domain.AppUser;
import net.java21.data2flow.core.account.domain.UserStatus;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 회원 저장소({@code data2flow_core.app_users}). 조회는 조직 조건을 받는다(BR-IAM-01) */
@Repository
public class UserRepository {

    static final String COLUMNS = """
            u.id, u.organization_id, u.login_id, u.email, u.name, u.phone, u.locale, u.timezone, u.status, u.password_hash,
            u.password_changed_at, u.must_change_password, u.failed_login_count, u.locked_until, u.last_login_at,
            host(u.last_login_ip) AS last_login_ip, u.totp_secret_enc, u.totp_enabled, u.notification_pref::text AS notification_pref,
            u.anonymized_at, u.version, u.created_at, u.updated_at""";

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByIdAndOrganizationId(long id, long organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.app_users u WHERE u.id = :id AND u.organization_id = :org")
                .param("id", id).param("org", organizationId).query(UserRepository::map).optional();
    }

    /** 행 잠금 조회(상태 전이·실패 횟수 갱신의 경쟁을 막는다) */
    public Optional<AppUser> lockByIdAndOrganizationId(long id, long organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.app_users u WHERE u.id = :id AND u.organization_id = :org FOR UPDATE")
                .param("id", id).param("org", organizationId).query(UserRepository::map).optional();
    }

    /** 내부 API(MFA 확인 API-IAM-61b)는 사용자 ID만 받는다 */
    @OrganizationScopeExempt("auth 내부 API가 사용자 ID로만 부른다(API-IAM-61b). 결과의 조직으로 이후 처리를 묶는다")
    public Optional<AppUser> lockForInternal(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.app_users u WHERE u.id = :id FOR UPDATE")
                .param("id", id).query(UserRepository::map).optional();
    }

    /**
     * 로그인 아이디로 찾는다(API-IAM-30). 로그인 시점에는 조직을 모른다(v1 단일 조직). 여러 조직에 같은 아이디가 있으면
     * 판정할 수 없으므로 호출 쪽이 실패로 처리한다.
     */
    @OrganizationScopeExempt("로그인 시점에는 조직을 모른다(v1 단일 조직, ADR-004)")
    public List<AppUser> lockByLoginId(String loginIdLower) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.app_users u WHERE lower(u.login_id) = :login AND u.status <> 'DELETED' FOR UPDATE")
                .param("login", loginIdLower).query(UserRepository::map).list();
    }

    /** 배포 조직이 정해졌을 때(ADR-030 staging 전용 조직) 그 조직 안에서만 로그인 아이디로 찾는다 */
    public List<AppUser> lockByLoginId(long organizationId, String loginIdLower) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.app_users u WHERE u.organization_id = :org AND lower(u.login_id) = :login AND u.status <> 'DELETED' FOR UPDATE")
                .param("org", organizationId).param("login", loginIdLower).query(UserRepository::map).list();
    }

    /** 로그인 아이디 또는 이메일(API-IAM-13 재설정 요청) */
    public Optional<AppUser> findByLoginIdOrEmail(long organizationId, String value) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM data2flow_core.app_users u
                        WHERE u.organization_id = :org AND (lower(u.login_id) = lower(:v) OR lower(u.email) = lower(:v))
                          AND u.status <> 'DELETED' LIMIT 1""")
                .param("org", organizationId).param("v", value).query(UserRepository::map).optional();
    }

    public Optional<AppUser> findByEmail(long organizationId, String email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.app_users u WHERE u.organization_id = :org AND lower(u.email) = lower(:email)")
                .param("org", organizationId).param("email", email).query(UserRepository::map).optional();
    }

    public boolean existsLoginId(long organizationId, String loginId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.app_users WHERE organization_id = :org AND lower(login_id) = lower(:login))")
                .param("org", organizationId).param("login", loginId).query(Boolean.class).single();
    }

    public long insert(NewUser u) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.app_users (organization_id, login_id, email, name, phone, locale, timezone, status,
                            password_hash, password_changed_at, must_change_password, created_at, updated_at)
                        VALUES (:org, :login, :email, :name, :phone, :locale, :tz, :status, :hash, :changedAt, :mustChange, :now, :now)
                        RETURNING id""")
                .param("org", u.organizationId()).param("login", u.loginId()).param("email", u.email()).param("name", u.name())
                .param("phone", u.phone()).param("locale", u.locale()).param("tz", u.timezone()).param("status", u.status().name())
                .param("hash", u.passwordHash()).param("changedAt", u.passwordHash() == null ? null : Pg.ts(u.now()))
                .param("mustChange", u.mustChangePassword()).param("now", Pg.ts(u.now()))
                .query(Long.class).single();
    }

    /** 상태만 바꾼다(실패 횟수·잠금 해제 포함). version을 올린다 */
    public void updateStatus(long id, long organizationId, UserStatus status, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.app_users
                           SET status = :status, failed_login_count = CASE WHEN CAST(:status AS varchar) IN ('ACTIVE', 'DISABLED') THEN 0 ELSE failed_login_count END,
                               locked_until = CASE WHEN CAST(:status AS varchar) = 'LOCKED' THEN locked_until ELSE NULL END,
                               version = version + 1, updated_at = :now
                         WHERE id = :id AND organization_id = :org""")
                .param("status", status.name()).param("now", Pg.ts(now)).param("id", id).param("org", organizationId).update();
    }

    /** 초대 수락·가입 승인: 아이디·비밀번호를 정하고 ACTIVE로 */
    public void activate(long id, long organizationId, String loginId, String passwordHash, boolean mustChange, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.app_users
                           SET status = 'ACTIVE', login_id = COALESCE(CAST(:login AS varchar), login_id),
                               password_hash = COALESCE(CAST(:hash AS varchar), password_hash),
                               password_changed_at = CASE WHEN CAST(:hash AS varchar) IS NULL THEN password_changed_at ELSE :now END,
                               must_change_password = :mustChange, failed_login_count = 0, locked_until = NULL,
                               version = version + 1, updated_at = :now
                         WHERE id = :id AND organization_id = :org""")
                .param("login", loginId).param("hash", passwordHash).param("mustChange", mustChange).param("now", Pg.ts(now))
                .param("id", id).param("org", organizationId).update();
    }

    public void updatePassword(long id, long organizationId, String passwordHash, boolean mustChange, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.app_users
                           SET password_hash = :hash, password_changed_at = :now, must_change_password = :mustChange,
                               version = version + 1, updated_at = :now
                         WHERE id = :id AND organization_id = :org""")
                .param("hash", passwordHash).param("mustChange", mustChange).param("now", Pg.ts(now))
                .param("id", id).param("org", organizationId).update();
    }

    /** 해시 매개변수가 바뀌어 다시 해시한 값만 바꾼다(비밀번호 변경 아님) */
    public void updatePasswordHashOnly(long id, long organizationId, String passwordHash) {
        jdbc.sql("UPDATE data2flow_core.app_users SET password_hash = :hash WHERE id = :id AND organization_id = :org")
                .param("hash", passwordHash).param("id", id).param("org", organizationId).update();
    }

    /** 로그인 실패 1회(BR-IAM-05). lockUntil이 있으면 LOCKED로 */
    public void updateLoginFailure(long id, long organizationId, int failedCount, Instant lockUntil, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.app_users
                           SET failed_login_count = :count,
                               status = CASE WHEN CAST(:lockUntil AS timestamptz) IS NOT NULL THEN 'LOCKED' ELSE status END,
                               locked_until = COALESCE(CAST(:lockUntil AS timestamptz), locked_until), updated_at = :now
                         WHERE id = :id AND organization_id = :org""")
                .param("count", Math.min(failedCount, 10)).param("lockUntil", Pg.ts(lockUntil)).param("now", Pg.ts(now))
                .param("id", id).param("org", organizationId).update();
    }

    public void updateLoginSuccess(long id, long organizationId, String ip, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.app_users
                           SET failed_login_count = 0, last_login_at = :now, last_login_ip = CAST(:ip AS inet), updated_at = :now
                         WHERE id = :id AND organization_id = :org""")
                .param("now", Pg.ts(now)).param("ip", Pg.inetOrNull(ip)).param("id", id).param("org", organizationId).update();
    }

    /** MFA 성공: 실패 횟수만 0으로 */
    public void resetFailures(long id, long organizationId) {
        jdbc.sql("UPDATE data2flow_core.app_users SET failed_login_count = 0 WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId).update();
    }

    /** 프로필(API-IAM-15). version이 baseVersion일 때만 바꾼다 */
    public int updateProfile(long id, long organizationId, int baseVersion, String name, String phone, String locale,
                             String timezone, String notificationPrefJson, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.app_users
                           SET name = :name, phone = :phone, locale = :locale, timezone = :tz,
                               notification_pref = CAST(:pref AS jsonb), version = version + 1, updated_at = :now
                         WHERE id = :id AND organization_id = :org AND version = :base""")
                .param("name", name).param("phone", phone).param("locale", locale).param("tz", timezone)
                .param("pref", notificationPrefJson).param("now", Pg.ts(now))
                .param("id", id).param("org", organizationId).param("base", baseVersion).update();
    }

    /** 역할 변경 등 사용자 단위 낙관적 잠금 증가 */
    public int updateVersion(long id, long organizationId, int baseVersion, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.app_users SET version = version + 1, updated_at = :now
                         WHERE id = :id AND organization_id = :org AND version = :base""")
                .param("now", Pg.ts(now)).param("id", id).param("org", organizationId).param("base", baseVersion).update();
    }

    public void updateTotp(long id, long organizationId, byte[] secretEnc, boolean enabled, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.app_users SET totp_secret_enc = :secret, totp_enabled = :enabled, version = version + 1, updated_at = :now
                         WHERE id = :id AND organization_id = :org""")
                .param("secret", secretEnc).param("enabled", enabled).param("now", Pg.ts(now))
                .param("id", id).param("org", organizationId).update();
    }

    /** 삭제 = 익명화(IAM-01.10, NFR-12.01). 행은 남겨 감사 로그의 행위자 참조를 유지한다 */
    public void anonymize(long id, long organizationId, Instant now) {
        String tag = "deleted-" + id;
        jdbc.sql("""
                        UPDATE data2flow_core.app_users
                           SET status = 'DELETED', login_id = :tag, email = :email, name = :tag, phone = NULL, password_hash = NULL,
                               must_change_password = false, failed_login_count = 0, locked_until = NULL, last_login_ip = NULL,
                               totp_secret_enc = NULL, totp_enabled = false, notification_pref = '{}'::jsonb,
                               anonymized_at = :now, version = version + 1, updated_at = :now
                         WHERE id = :id AND organization_id = :org""")
                .param("tag", tag).param("email", tag + "@deleted.invalid").param("now", Pg.ts(now))
                .param("id", id).param("org", organizationId).update();
    }

    /** 가입 신청 거절·만료, 초대 취소·만료: 승인 전 계정 행 삭제(§3.1) */
    public void deleteUnactivated(long id, long organizationId) {
        jdbc.sql("DELETE FROM data2flow_core.app_users WHERE id = :id AND organization_id = :org AND status IN ('INVITED', 'PENDING_APPROVAL')")
                .param("id", id).param("org", organizationId).update();
    }

    /** ACTIVE 상태의 ADMIN 수(BR-IAM-07) */
    public long countActiveAdmins(long organizationId) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.app_users u JOIN data2flow_core.user_roles r ON r.user_id = u.id
                         WHERE u.organization_id = :org AND u.status = 'ACTIVE' AND r.role = 'ADMIN'""")
                .param("org", organizationId).query(Long.class).single();
    }

    /** 삭제되지 않은 ADMIN이 있는가(부트스트랩 멱등, AT-IAM-01.4) */
    public boolean existsAdmin(long organizationId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.app_users u JOIN data2flow_core.user_roles r ON r.user_id = u.id
                         WHERE u.organization_id = :org AND u.status <> 'DELETED' AND r.role = 'ADMIN')""")
                .param("org", organizationId).query(Boolean.class).single();
    }

    /** 회원 목록(API-IAM-31) */
    public List<UserListRow> search(UserSearch s, String orderBy, int limit, long offset) {
        Map<String, Object> params = new HashMap<>();
        String where = where(s, params);
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql("""
                        SELECT u.id, u.name, u.login_id, u.email, u.status, u.totp_enabled, u.last_login_at, u.created_at,
                               r.role, r.custom_role_id, r.space_scope
                          FROM data2flow_core.app_users u LEFT JOIN data2flow_core.user_roles r ON r.user_id = u.id
                        """ + where + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, n) -> new UserListRow(rs.getLong("id"), rs.getString("name"), rs.getString("login_id"),
                        rs.getString("email"), rs.getString("status"), rs.getBoolean("totp_enabled"), Pg.instant(rs, "last_login_at"),
                        rs.getString("role"), Pg.longOrNull(rs, "custom_role_id"), Pg.longList(rs, "space_scope")))
                .list();
    }

    public long count(UserSearch s) {
        Map<String, Object> params = new HashMap<>();
        String where = where(s, params);
        return jdbc.sql("SELECT count(*) FROM data2flow_core.app_users u LEFT JOIN data2flow_core.user_roles r ON r.user_id = u.id " + where)
                .params(params).query(Long.class).single();
    }

    private static String where(UserSearch s, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE u.organization_id = :org");
        params.put("org", s.organizationId());
        if (s.keyword() != null) {
            sql.append(" AND (u.name ILIKE :kw OR u.login_id ILIKE :kw OR u.email ILIKE :kw)");
            params.put("kw", "%" + s.keyword().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (s.role() != null) {
            sql.append(" AND r.role = :role");
            params.put("role", s.role());
        }
        if (s.status() != null) {
            sql.append(" AND u.status = :status");
            params.put("status", s.status());
        }
        if (s.spaceId() != null) {
            // 그 공간을 볼 수 있는 회원: 전체 범위이거나, 범위의 어느 공간이 그 공간의 조상(자기 포함)이다(IAM-01.07, BR-IAM-16)
            sql.append(" ").append("""
                     AND (r.space_scope = '{}' OR EXISTS (
                            SELECT 1 FROM data2flow_core.spaces sp, unnest(r.space_scope) AS a(space_id)
                             WHERE sp.organization_id = u.organization_id AND sp.id = :spaceId
                               AND sp.path LIKE '%/' || a.space_id || '/%'))""");
            params.put("spaceId", s.spaceId());
        }
        return sql.toString();
    }

    /** 요청마다 보는 계정 관문 상태(BR-IAM-06 비밀번호 변경 강제, BR-IAM-25 2단계 인증 필수) */
    public Optional<GateState> findGateState(long organizationId, long userId) {
        return jdbc.sql("""
                        SELECT u.must_change_password, u.totp_enabled, r.role,
                               COALESCE(p.mfa_required_roles, '{}') AS mfa_required_roles
                          FROM data2flow_core.app_users u
                          LEFT JOIN data2flow_core.user_roles r ON r.user_id = u.id
                          LEFT JOIN data2flow_core.org_security_policies p ON p.organization_id = u.organization_id
                         WHERE u.id = :id AND u.organization_id = :org""")
                .param("id", userId).param("org", organizationId)
                .query((rs, n) -> new GateState(rs.getBoolean("must_change_password"), rs.getBoolean("totp_enabled"),
                        rs.getString("role"), Pg.stringList(rs, "mfa_required_roles")))
                .optional();
    }

    static AppUser map(ResultSet rs, int n) throws SQLException {
        return new AppUser(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("login_id"), rs.getString("email"),
                rs.getString("name"), rs.getString("phone"), rs.getString("locale"), rs.getString("timezone"),
                UserStatus.valueOf(rs.getString("status")), rs.getString("password_hash"), Pg.instant(rs, "password_changed_at"),
                rs.getBoolean("must_change_password"), rs.getInt("failed_login_count"), Pg.instant(rs, "locked_until"),
                Pg.instant(rs, "last_login_at"), rs.getString("last_login_ip"), rs.getBytes("totp_secret_enc"),
                rs.getBoolean("totp_enabled"), rs.getString("notification_pref"), Pg.instant(rs, "anonymized_at"),
                rs.getInt("version"), Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"));
    }

    /** 계정 관문 상태 */
    public record GateState(boolean mustChangePassword, boolean totpEnabled, String role, List<String> mfaRequiredRoles) {

        /** 정책상 2단계 인증이 필요한데 아직 설정하지 않았는가 */
        public boolean mfaSetupRequired() {
            return !totpEnabled && role != null && mfaRequiredRoles.contains(role);
        }
    }

    /** 새 사용자 행 */
    public record NewUser(long organizationId, String loginId, String email, String name, String phone, String locale,
                          String timezone, UserStatus status, String passwordHash, boolean mustChangePassword, Instant now) {
    }

    /** 목록 검색 조건 */
    public record UserSearch(long organizationId, String keyword, String role, String status, Long spaceId) {
    }

    /** 목록 한 행 */
    public record UserListRow(long id, String name, String loginId, String email, String status, boolean mfaEnabled,
                              Instant lastLoginAt, String role, Long customRoleId, List<Long> spaceScope) {
    }
}
