package net.java21.data2flow.core.invitation.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 초대({@code data2flow_core.invitations}, IAM-01.03). 토큰은 SHA-256 해시로만 찾는다 */
@Repository
public class InvitationRepository {

    private static final String COLUMNS = """
            id, organization_id, user_id, email, role, custom_role_id, space_scope, status, expires_at, sent_count, invited_by,
            accepted_at, canceled_at, created_at""";

    private final JdbcClient jdbc;

    public InvitationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long organizationId, long userId, String email, String role, Long customRoleId, List<Long> spaceScope,
                       String tokenHash, Instant expiresAt, long invitedBy, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.invitations (organization_id, user_id, email, role, custom_role_id, space_scope,
                            token_hash, expires_at, invited_by, created_at, updated_at)
                        VALUES (:org, :user, :email, :role, :custom, CAST(:scope AS bigint[]), :hash, :expires, :by, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("user", userId).param("email", email).param("role", role)
                .param("custom", customRoleId).param("scope", Pg.bigintArray(spaceScope)).param("hash", tokenHash)
                .param("expires", Pg.ts(expiresAt)).param("by", invitedBy).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    /** 공개 초대 링크: 토큰 해시로 찾는다(조직은 결과로 정해진다) */
    @OrganizationScopeExempt("공개 초대 링크는 토큰 해시만 안다(design/auth.md §3.3)")
    public Optional<Invitation> lockByTokenHash(String tokenHash) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.invitations WHERE token_hash = :hash FOR UPDATE")
                .param("hash", tokenHash).query(InvitationRepository::map).optional();
    }

    public Optional<Invitation> lockByIdAndOrganizationId(long id, long organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.invitations WHERE id = :id AND organization_id = :org FOR UPDATE")
                .param("id", id).param("org", organizationId).query(InvitationRepository::map).optional();
    }

    /** 사용자에게 지금 쓸 수 있는(PENDING, 만료 전) 초대가 있는가(BR-IAM-10) */
    public boolean existsValidPending(long organizationId, long userId, Instant now) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.invitations
                         WHERE organization_id = :org AND user_id = :user AND status = 'PENDING' AND expires_at > :now)""")
                .param("org", organizationId).param("user", userId).param("now", Pg.ts(now)).query(Boolean.class).single();
    }

    /** 재발송(BR-IAM-09): 새 토큰, 새 만료, 횟수 +1 */
    public void updateForResend(long id, long organizationId, String tokenHash, Instant expiresAt, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.invitations SET token_hash = :hash, expires_at = :expires, sent_count = sent_count + 1,
                            updated_at = :now WHERE id = :id AND organization_id = :org""")
                .param("hash", tokenHash).param("expires", Pg.ts(expiresAt)).param("now", Pg.ts(now))
                .param("id", id).param("org", organizationId).update();
    }

    public void updateStatus(long id, long organizationId, String status, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.invitations SET status = :status, updated_at = :now,
                            accepted_at = CASE WHEN CAST(:status AS varchar) = 'ACCEPTED' THEN :now ELSE accepted_at END,
                            canceled_at = CASE WHEN CAST(:status AS varchar) = 'CANCELED' THEN :now ELSE canceled_at END
                         WHERE id = :id AND organization_id = :org""")
                .param("status", status).param("now", Pg.ts(now)).param("id", id).param("org", organizationId).update();
    }

    public List<Invitation> list(long organizationId, String status, Instant now, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.invitations WHERE organization_id = :org" + statusFilter(status)
                        + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("status", status).param("now", Pg.ts(now))
                .param("limit", limit).param("offset", offset).query(InvitationRepository::map).list();
    }

    public long count(long organizationId, String status, Instant now) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.invitations WHERE organization_id = :org" + statusFilter(status))
                .param("org", organizationId).param("status", status).param("now", Pg.ts(now)).query(Long.class).single();
    }

    /** 72시간 지난 PENDING을 EXPIRED로(매시간 정리, domain-model §3.2) */
    @OrganizationScopeExempt("모든 조직의 만료 정리 작업")
    public int expirePending(Instant now) {
        return jdbc.sql("UPDATE data2flow_core.invitations SET status = 'EXPIRED', updated_at = :now WHERE status = 'PENDING' AND expires_at <= :now")
                .param("now", Pg.ts(now)).update();
    }

    /** 상태 필터. 만료 시각이 지난 PENDING은 EXPIRED로 본다 */
    private static String statusFilter(String status) {
        if (status == null) {
            return " AND CAST(:status AS varchar) IS NULL AND CAST(:now AS timestamptz) IS NOT NULL";
        }
        return switch (status) {
            case "PENDING" -> " AND status = :status AND expires_at > :now";
            case "EXPIRED" -> " AND (status = :status OR (status = 'PENDING' AND expires_at <= :now))";
            default -> " AND status = :status AND CAST(:now AS timestamptz) IS NOT NULL";
        };
    }

    private static Invitation map(ResultSet rs, int n) throws SQLException {
        return new Invitation(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("user_id"), rs.getString("email"),
                rs.getString("role"), Pg.longOrNull(rs, "custom_role_id"), Pg.longList(rs, "space_scope"), rs.getString("status"),
                Pg.instant(rs, "expires_at"), rs.getInt("sent_count"), rs.getLong("invited_by"), Pg.instant(rs, "accepted_at"),
                Pg.instant(rs, "canceled_at"), Pg.instant(rs, "created_at"));
    }

    /** 초대 한 행 */
    public record Invitation(long id, long organizationId, long userId, String email, String role, Long customRoleId,
                             List<Long> spaceScope, String status, Instant expiresAt, int sentCount, long invitedBy,
                             Instant acceptedAt, Instant canceledAt, Instant createdAt) {

        /** 지금 쓸 수 있는가(PENDING이고 만료 전) */
        public boolean usable(Instant now) {
            return "PENDING".equals(status) && expiresAt.isAfter(now);
        }

        /** 화면 표시 상태(만료 시각이 지난 PENDING은 EXPIRED) */
        public String effectiveStatus(Instant now) {
            return "PENDING".equals(status) && !expiresAt.isAfter(now) ? "EXPIRED" : status;
        }
    }
}
