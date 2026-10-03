package net.java21.data2flow.core.session.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.session.domain.RefreshToken;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Refresh 계보 저장소. 내부 API(auth → core)는 토큰 ID(jti)·세션 ID(sid)로만 찾으므로 조직 조건 없이 조회하는 메서드는
 * {@link OrganizationScopeExempt}로 표시한다. 사용자 화면용 조회는 조직·사용자 조건을 받는다.
 */
@Repository
public class RefreshTokenRepository {

    private static final String COLUMNS = """
            jti, session_id, organization_id, user_id, token_hash, issued_at, last_used_at, expires_at, absolute_expires_at,
            rotated_at, revoked_at, revoke_reason, host(ip) AS ip, user_agent""";

    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @OrganizationScopeExempt("auth 내부 API가 토큰 ID(jti)로 찾는다(API-IAM-35·37)")
    public Optional<RefreshToken> lockByJti(UUID jti) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.refresh_tokens WHERE jti = :jti FOR UPDATE")
                .param("jti", jti).query(RefreshTokenRepository::map).optional();
    }

    @OrganizationScopeExempt("auth 내부 API가 토큰 ID(jti)로 찾는다(API-IAM-36 재시도 멱등)")
    public Optional<RefreshToken> findByJti(UUID jti) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.refresh_tokens WHERE jti = :jti")
                .param("jti", jti).query(RefreshTokenRepository::map).optional();
    }

    /** 계보의 최신 유효 토큰(GRACE 판정) */
    @OrganizationScopeExempt("auth 내부 API: 세션 ID로 계보를 찾는다")
    public Optional<RefreshToken> findLatestActiveInSession(UUID sessionId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM data2flow_core.refresh_tokens
                        WHERE session_id = :sid AND revoked_at IS NULL AND rotated_at IS NULL
                        ORDER BY issued_at DESC LIMIT 1""")
                .param("sid", sessionId).query(RefreshTokenRepository::map).optional();
    }

    public void insert(RefreshToken t) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.refresh_tokens (jti, session_id, organization_id, user_id, token_hash, issued_at,
                            last_used_at, expires_at, absolute_expires_at, ip, user_agent)
                        VALUES (:jti, :sid, :org, :user, :hash, :issued, :issued, :expires, :absolute, CAST(:ip AS inet), :ua)""")
                .param("jti", t.jti()).param("sid", t.sessionId()).param("org", t.organizationId()).param("user", t.userId())
                .param("hash", t.tokenHash()).param("issued", Pg.ts(t.issuedAt())).param("expires", Pg.ts(t.expiresAt()))
                .param("absolute", Pg.ts(t.absoluteExpiresAt())).param("ip", Pg.inetOrNull(t.ip())).param("ua", t.userAgent())
                .update();
    }

    @OrganizationScopeExempt("auth 내부 API: 잠근 토큰을 jti로 갱신")
    public void markRotated(UUID jti, Instant now) {
        jdbc.sql("UPDATE data2flow_core.refresh_tokens SET rotated_at = :now, last_used_at = :now WHERE jti = :jti")
                .param("now", Pg.ts(now)).param("jti", jti).update();
    }

    @OrganizationScopeExempt("auth 내부 API: 잠근 토큰을 jti로 갱신")
    public void touch(UUID jti, Instant now) {
        jdbc.sql("UPDATE data2flow_core.refresh_tokens SET last_used_at = :now WHERE jti = :jti")
                .param("now", Pg.ts(now)).param("jti", jti).update();
    }

    /** jti 하나 폐기(멱등). 바뀐 행 수 */
    @OrganizationScopeExempt("auth 내부 API: 로그아웃(API-IAM-37)")
    public int revokeJti(UUID jti, String reason, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.refresh_tokens SET revoked_at = :now, revoke_reason = :reason
                         WHERE jti = :jti AND revoked_at IS NULL""")
                .param("now", Pg.ts(now)).param("reason", reason).param("jti", jti).update();
    }

    /** sid 계보 전체 폐기(멱등). 폐기한 토큰 수 */
    @OrganizationScopeExempt("auth 내부 API·재사용 탐지: 세션 ID로 계보 전체를 폐기한다")
    public int revokeSession(UUID sessionId, String reason, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.refresh_tokens SET revoked_at = :now, revoke_reason = :reason
                         WHERE session_id = :sid AND revoked_at IS NULL""")
                .param("now", Pg.ts(now)).param("reason", reason).param("sid", sessionId).update();
    }

    /** 사용자의 모든(또는 하나를 뺀) 세션 폐기. 폐기한 sid 목록 */
    public List<UUID> revokeAllForUser(long organizationId, long userId, UUID exceptSessionId, String reason, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.refresh_tokens SET revoked_at = :now, revoke_reason = :reason
                         WHERE organization_id = :org AND user_id = :user AND revoked_at IS NULL
                           AND (CAST(:except AS uuid) IS NULL OR session_id <> CAST(:except AS uuid))
                        RETURNING session_id""")
                .param("now", Pg.ts(now)).param("reason", reason).param("org", organizationId).param("user", userId)
                .param("except", exceptSessionId)
                .query(UUID.class).list().stream().distinct().toList();
    }

    /** 활성 로그인 목록(IAM-03.02, IAM-07.08): 계열(sid)당 한 줄 */
    public List<SessionRow> listActiveSessions(long organizationId, long userId, Instant now) {
        return jdbc.sql("""
                        SELECT session_id, min(issued_at) AS first_login_at, max(last_used_at) AS last_used_at,
                               (array_agg(host(ip) ORDER BY issued_at DESC))[1] AS ip,
                               (array_agg(user_agent ORDER BY issued_at DESC))[1] AS user_agent
                          FROM data2flow_core.refresh_tokens
                         WHERE organization_id = :org AND user_id = :user
                         GROUP BY session_id
                        HAVING bool_and(revoked_at IS NULL)
                           AND bool_or(rotated_at IS NULL AND expires_at > :now AND absolute_expires_at > :now)
                         ORDER BY max(last_used_at) DESC""")
                .param("org", organizationId).param("user", userId).param("now", Pg.ts(now))
                .query((rs, n) -> new SessionRow(rs.getObject("session_id", UUID.class), Pg.instant(rs, "first_login_at"),
                        Pg.instant(rs, "last_used_at"), rs.getString("ip"), rs.getString("user_agent")))
                .list();
    }

    public long countActiveSessions(long organizationId, long userId, Instant now) {
        return listActiveSessions(organizationId, userId, now).size();
    }

    /** 사용자의 세션인가(개별 종료 전 소유 확인) */
    public boolean existsSessionOfUser(long organizationId, long userId, UUID sessionId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.refresh_tokens
                         WHERE organization_id = :org AND user_id = :user AND session_id = :sid)""")
                .param("org", organizationId).param("user", userId).param("sid", sessionId).query(Boolean.class).single();
    }

    /** 최근 폐기 목록(API-IAM-39a, ADR-022 Redis 재적재) */
    @OrganizationScopeExempt("auth 재적재: 모든 조직의 최근 폐기 sid·jti")
    public List<RefreshToken> findRevokedSince(Instant since, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.refresh_tokens WHERE revoked_at >= :since ORDER BY revoked_at LIMIT :limit")
                .param("since", Pg.ts(since)).param("limit", limit).query(RefreshTokenRepository::map).list();
    }

    private static RefreshToken map(ResultSet rs, int n) throws SQLException {
        return new RefreshToken(rs.getObject("jti", UUID.class), rs.getObject("session_id", UUID.class),
                rs.getLong("organization_id"), rs.getLong("user_id"), rs.getString("token_hash"), Pg.instant(rs, "issued_at"),
                Pg.instant(rs, "last_used_at"), Pg.instant(rs, "expires_at"), Pg.instant(rs, "absolute_expires_at"),
                Pg.instant(rs, "rotated_at"), Pg.instant(rs, "revoked_at"), rs.getString("revoke_reason"), rs.getString("ip"),
                rs.getString("user_agent"));
    }

    /** 활성 로그인 한 줄 */
    public record SessionRow(UUID sessionId, Instant firstLoginAt, Instant lastUsedAt, String ip, String userAgent) {
    }
}
