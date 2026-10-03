package net.java21.data2flow.core.passwordreset.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** 비밀번호 재설정 토큰(30분·1회용, IAM-02.04, BR-IAM-11) */
@Repository
public class PasswordResetTokenRepository {

    private final JdbcClient jdbc;

    public PasswordResetTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 10분 안에 만든 토큰 수(계정당 3회 한도) */
    public long countCreatedSince(long organizationId, long userId, Instant since) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.password_reset_tokens
                         WHERE organization_id = :org AND user_id = :user AND created_at > :since""")
                .param("org", organizationId).param("user", userId).param("since", Pg.ts(since)).query(Long.class).single();
    }

    /** 새 요청 시 이전 미사용 토큰 무효(만료 시각을 지금으로) */
    public void invalidateUnused(long organizationId, long userId, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.password_reset_tokens SET expires_at = LEAST(expires_at, :now)
                         WHERE organization_id = :org AND user_id = :user AND used_at IS NULL""")
                .param("now", Pg.ts(now)).param("org", organizationId).param("user", userId).update();
    }

    public void insert(long organizationId, long userId, String tokenHash, Instant expiresAt, String ip, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.password_reset_tokens (organization_id, user_id, token_hash, expires_at, requested_ip, created_at)
                        VALUES (:org, :user, :hash, :expires, CAST(:ip AS inet), :now)""")
                .param("org", organizationId).param("user", userId).param("hash", tokenHash).param("expires", Pg.ts(expiresAt))
                .param("ip", Pg.inetOrNull(ip)).param("now", Pg.ts(now)).update();
    }

    @OrganizationScopeExempt("공개 재설정 링크는 토큰 해시만 안다(design/auth.md §3.3)")
    public Optional<ResetToken> lockByTokenHash(String tokenHash) {
        return jdbc.sql("""
                        SELECT id, organization_id, user_id, expires_at, used_at FROM data2flow_core.password_reset_tokens
                         WHERE token_hash = :hash FOR UPDATE""")
                .param("hash", tokenHash)
                .query((rs, n) -> new ResetToken(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("user_id"),
                        Pg.instant(rs, "expires_at"), Pg.instant(rs, "used_at")))
                .optional();
    }

    public void markUsed(long id, long organizationId, Instant now) {
        jdbc.sql("UPDATE data2flow_core.password_reset_tokens SET used_at = :now WHERE id = :id AND organization_id = :org")
                .param("now", Pg.ts(now)).param("id", id).param("org", organizationId).update();
    }

    public void deleteAllForUser(long organizationId, long userId) {
        jdbc.sql("DELETE FROM data2flow_core.password_reset_tokens WHERE organization_id = :org AND user_id = :user")
                .param("org", organizationId).param("user", userId).update();
    }

    /** 재설정 토큰 한 행 */
    public record ResetToken(long id, long organizationId, long userId, Instant expiresAt, Instant usedAt) {

        public boolean usable(Instant now) {
            return usedAt == null && expiresAt.isAfter(now);
        }
    }
}
