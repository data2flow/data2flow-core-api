package net.java21.data2flow.core.signup.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 가입 신청({@code data2flow_core.signup_requests}, IAM-01.08, ADR-032) */
@Repository
public class SignupRequestRepository {

    private static final String COLUMNS = """
            id, organization_id, email, name, login_id, password_hash, message, email_verified_at, status, user_id, decided_by,
            decided_at, reject_reason, host(request_ip) AS request_ip, created_at""";

    private final JdbcClient jdbc;

    public SignupRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long organizationId, String email, String name, String loginId, String passwordHash, String message,
                       String verifyTokenHash, String ip, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.signup_requests (organization_id, email, name, login_id, password_hash, message,
                            email_verify_token_hash, request_ip, created_at, updated_at)
                        VALUES (:org, :email, :name, :login, :hash, :message, :token, CAST(:ip AS inet), :now, :now) RETURNING id""")
                .param("org", organizationId).param("email", email).param("name", name).param("login", loginId)
                .param("hash", passwordHash).param("message", message).param("token", verifyTokenHash)
                .param("ip", Pg.inetOrNull(ip)).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    /** 진행 중(확인 대기·승인 대기)인 신청이 이 아이디·이메일을 쓰는가 */
    public boolean existsOpenLoginId(long organizationId, String loginId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.signup_requests WHERE organization_id = :org
                           AND lower(login_id) = lower(:login) AND status IN ('PENDING_VERIFICATION', 'PENDING_APPROVAL'))""")
                .param("org", organizationId).param("login", loginId).query(Boolean.class).single();
    }

    public boolean existsOpenEmail(long organizationId, String email) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.signup_requests WHERE organization_id = :org
                           AND lower(email) = lower(:email) AND status IN ('PENDING_VERIFICATION', 'PENDING_APPROVAL'))""")
                .param("org", organizationId).param("email", email).query(Boolean.class).single();
    }

    /** IP당 최근 1시간 신청 시각(BR-IAM-28 한도). 오래된 순 */
    public List<Instant> listCreatedSinceByIp(long organizationId, String ip, Instant since) {
        return jdbc.sql("""
                        SELECT created_at FROM data2flow_core.signup_requests
                         WHERE organization_id = :org AND request_ip = CAST(:ip AS inet) AND created_at > :since ORDER BY created_at""")
                .param("org", organizationId).param("ip", ip).param("since", Pg.ts(since))
                .query((rs, n) -> Pg.instant(rs, "created_at")).list();
    }

    @OrganizationScopeExempt("공개 확인 링크는 토큰 해시만 안다")
    public Optional<SignupRequest> lockByVerifyTokenHash(String tokenHash) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.signup_requests WHERE email_verify_token_hash = :hash FOR UPDATE")
                .param("hash", tokenHash).query(SignupRequestRepository::map).optional();
    }

    public Optional<SignupRequest> lockByIdAndOrganizationId(long id, long organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.signup_requests WHERE id = :id AND organization_id = :org FOR UPDATE")
                .param("id", id).param("org", organizationId).query(SignupRequestRepository::map).optional();
    }

    /** 이메일 확인 완료 → 승인 대기. 토큰은 지운다(1회용) */
    public void markVerified(long id, long organizationId, long userId, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.signup_requests SET status = 'PENDING_APPROVAL', email_verified_at = :now,
                            email_verify_token_hash = NULL, user_id = :user, updated_at = :now
                         WHERE id = :id AND organization_id = :org""")
                .param("now", Pg.ts(now)).param("user", userId).param("id", id).param("org", organizationId).update();
    }

    public void markDecided(long id, long organizationId, String status, long decidedBy, String rejectReason, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.signup_requests SET status = :status, decided_by = :by, decided_at = :now,
                            reject_reason = :reason, updated_at = :now
                         WHERE id = :id AND organization_id = :org""")
                .param("status", status).param("by", decidedBy).param("now", Pg.ts(now)).param("reason", rejectReason)
                .param("id", id).param("org", organizationId).update();
    }

    /** 관리자 목록: 이메일 확인 전 신청은 보이지 않는다(AT-IAM-16.3) */
    public List<SignupRequest> list(long organizationId, String status, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM data2flow_core.signup_requests
                        WHERE organization_id = :org AND status <> 'PENDING_VERIFICATION' AND status = :status
                        ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset""")
                .param("org", organizationId).param("status", status).param("limit", limit).param("offset", offset)
                .query(SignupRequestRepository::map).list();
    }

    public long count(long organizationId, String status) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.signup_requests
                         WHERE organization_id = :org AND status <> 'PENDING_VERIFICATION' AND status = :status""")
                .param("org", organizationId).param("status", status).query(Long.class).single();
    }

    /** 만료 정리 대상: 확인 24시간·승인 대기 14일 초과(§3.4) */
    @OrganizationScopeExempt("모든 조직의 만료 정리 작업")
    public List<SignupRequest> lockExpired(Instant verifyCutoff, Instant approvalCutoff) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM data2flow_core.signup_requests
                        WHERE (status = 'PENDING_VERIFICATION' AND created_at <= :verify)
                           OR (status = 'PENDING_APPROVAL' AND email_verified_at <= :approval)
                        FOR UPDATE SKIP LOCKED""")
                .param("verify", Pg.ts(verifyCutoff)).param("approval", Pg.ts(approvalCutoff))
                .query(SignupRequestRepository::map).list();
    }

    public void markExpired(long id, long organizationId, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.signup_requests SET status = 'EXPIRED', email_verify_token_hash = NULL, updated_at = :now
                         WHERE id = :id AND organization_id = :org""")
                .param("now", Pg.ts(now)).param("id", id).param("org", organizationId).update();
    }

    private static SignupRequest map(ResultSet rs, int n) throws SQLException {
        return new SignupRequest(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("email"), rs.getString("name"),
                rs.getString("login_id"), rs.getString("password_hash"), rs.getString("message"),
                Pg.instant(rs, "email_verified_at"), rs.getString("status"), Pg.longOrNull(rs, "user_id"),
                Pg.longOrNull(rs, "decided_by"), Pg.instant(rs, "decided_at"), rs.getString("reject_reason"),
                rs.getString("request_ip"), Pg.instant(rs, "created_at"));
    }

    /** 가입 신청 한 행 */
    public record SignupRequest(long id, long organizationId, String email, String name, String loginId, String passwordHash,
                                String message, Instant emailVerifiedAt, String status, Long userId, Long decidedBy,
                                Instant decidedAt, String rejectReason, String requestIp, Instant createdAt) {
    }
}
