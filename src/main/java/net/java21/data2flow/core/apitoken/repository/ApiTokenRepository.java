package net.java21.data2flow.core.apitoken.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 장기 토큰({@code data2flow_core.api_tokens}, IAM-05·IAM-04.07). 원문은 저장하지 않고 SHA-256 hex만 둔다(BR-IAM-18).
 * 상태: PENDING_APPROVAL → ACTIVE(ADMIN 승인) | REJECTED, ACTIVE → ROTATING(교체 유예) → REVOKED, ACTIVE → REVOKED | EXPIRED
 * (IAM domain-model §3.5).
 */
@Repository
public class ApiTokenRepository {

    /** 쓸 수 있는(또는 쓸 수 있게 될) 상태. 조직 한도(50개)와 사용자 비활성화 폐기 대상 */
    public static final List<String> LIVE_STATUSES = List.of("PENDING_APPROVAL", "ACTIVE", "ROTATING");

    private static final String COLUMNS = """
            t.id, t.organization_id, t.owner_type, t.owner_id, t.kind, t.name, t.token_prefix, t.scopes, t.space_scope, t.expires_at,
            t.rate_limit_per_min, t.status, t.last_used_at, host(t.last_used_ip) AS last_used_ip, t.rotated_from_id, t.grace_until,
            t.approved_by, t.created_by, t.created_at,
            CASE WHEN t.owner_type = 'USER' THEN u.name ELSE sa.name END AS owner_name,
            CASE WHEN t.owner_type = 'USER' THEN u.status ELSE sa.status END AS owner_status""";
    private static final String FROM = """
             FROM data2flow_core.api_tokens t
             LEFT JOIN data2flow_core.app_users u ON t.owner_type = 'USER' AND u.organization_id = t.organization_id AND u.id = t.owner_id
             LEFT JOIN data2flow_core.service_accounts sa ON t.owner_type = 'SERVICE_ACCOUNT' AND sa.organization_id = t.organization_id
                       AND sa.id = t.owner_id""";

    private final JdbcClient jdbc;

    public ApiTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 토큰 한 행(소유자 이름·상태 포함) */
    public record TokenRow(long id, long organizationId, String ownerType, long ownerId, String kind, String name, String tokenPrefix,
                           List<String> scopes, List<Long> spaceScope, Instant expiresAt, int rateLimitPerMin, String status,
                           Instant lastUsedAt, String lastUsedIp, Long rotatedFromId, Instant graceUntil, Long approvedBy, long createdBy,
                           Instant createdAt, String ownerName, String ownerStatus) {

        public boolean serviceAccount() {
            return "SERVICE_ACCOUNT".equals(ownerType);
        }
    }

    /** 새 토큰 */
    public record NewToken(long organizationId, String ownerType, long ownerId, String kind, String name, String tokenPrefix,
                           String tokenHash, List<String> scopes, List<Long> spaceScope, Instant expiresAt, int rateLimitPerMin,
                           String status, Long rotatedFromId, Long approvedBy, long createdBy, Instant createdAt) {
    }

    public long insert(NewToken t) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.api_tokens (organization_id, owner_type, owner_id, kind, name, token_prefix, token_hash,
                               scopes, space_scope, expires_at, rate_limit_per_min, status, rotated_from_id, approved_by, created_by,
                               created_at, updated_at)
                        VALUES (:org, :ownerType, :ownerId, :kind, :name, :prefix, :hash, CAST(:scopes AS varchar(40)[]),
                                CAST(:spaces AS bigint[]), :expiresAt, :rate, :status, :rotatedFrom, :approvedBy, :createdBy, :now, :now)
                        RETURNING id""")
                .param("org", t.organizationId()).param("ownerType", t.ownerType()).param("ownerId", t.ownerId())
                .param("kind", t.kind()).param("name", t.name()).param("prefix", t.tokenPrefix()).param("hash", t.tokenHash())
                .param("scopes", Pg.textArray(t.scopes())).param("spaces", Pg.bigintArray(t.spaceScope()))
                .param("expiresAt", Pg.ts(t.expiresAt())).param("rate", t.rateLimitPerMin()).param("status", t.status())
                .param("rotatedFrom", t.rotatedFromId()).param("approvedBy", t.approvedBy()).param("createdBy", t.createdBy())
                .param("now", Pg.ts(t.createdAt()))
                .query(Long.class).single();
    }

    public Optional<TokenRow> find(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM + " WHERE t.organization_id = :org AND t.id = :id")
                .param("org", organizationId).param("id", id).query(ApiTokenRepository::row).optional();
    }

    /** introspection(API-IAM-46): 해시로 찾는다. 조직은 토큰이 정한다 */
    @OrganizationScopeExempt("장기 토큰 원문의 SHA-256이 조직과 소유자를 정한다(auth introspection, API-IAM-46)")
    public Optional<TokenRow> findByHash(String tokenHash) {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM + " WHERE t.token_hash = :hash")
                .param("hash", tokenHash).query(ApiTokenRepository::row).optional();
    }

    /** 목록. ownerUserId가 있으면 그 사용자 소유만(owner=me), status가 있으면 그 상태만 */
    public List<TokenRow> list(long organizationId, Long ownerUserId, String status, String kind, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM + where(ownerUserId, status, kind) + " ORDER BY t.id DESC LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("owner", ownerUserId).param("status", status).param("kind", kind)
                .param("limit", limit).param("offset", offset).query(ApiTokenRepository::row).list();
    }

    public long count(long organizationId, Long ownerUserId, String status, String kind) {
        return jdbc.sql("SELECT count(*) " + FROM + where(ownerUserId, status, kind))
                .param("org", organizationId).param("owner", ownerUserId).param("status", status).param("kind", kind)
                .query(Long.class).single();
    }

    private static String where(Long ownerUserId, String status, String kind) {
        StringBuilder sb = new StringBuilder(" WHERE t.organization_id = :org");
        if (ownerUserId != null) {
            sb.append(" AND t.owner_type = 'USER' AND t.owner_id = :owner");
        }
        if (status != null) {
            sb.append(" AND t.status = :status");
        }
        if (kind != null) {
            sb.append(" AND t.kind = :kind");
        }
        return sb.toString();
    }

    /** 조직의 살아 있는 토큰 수(한도 50, NFR-11.01) */
    public long countLive(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.api_tokens WHERE organization_id = :org AND status IN ('PENDING_APPROVAL','ACTIVE','ROTATING')")
                .param("org", organizationId).query(Long.class).single();
    }

    public boolean existsName(long organizationId, String ownerType, long ownerId, String name) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.api_tokens
                                        WHERE organization_id = :org AND owner_type = :type AND owner_id = :owner AND name = :name)""")
                .param("org", organizationId).param("type", ownerType).param("owner", ownerId).param("name", name)
                .query(Boolean.class).single();
    }

    /** 상태 바꾸기(from 상태일 때만). 바뀐 행 수 */
    public int updateStatus(long organizationId, long id, List<String> from, String to, Long approvedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.api_tokens SET status = :to, approved_by = COALESCE(:approvedBy, approved_by), updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = ANY(CAST(:from AS varchar[]))""")
                .param("to", to).param("approvedBy", approvedBy).param("now", Pg.ts(now)).param("org", organizationId).param("id", id)
                .param("from", Pg.textArray(from)).update();
    }

    /** 교체: 이전 토큰을 유예(ROTATING, grace_until) 또는 바로 폐기(유예 0시간) */
    public int markRotated(long organizationId, long id, Instant graceUntil, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.api_tokens
                           SET status = CASE WHEN CAST(:grace AS timestamptz) > CAST(:now AS timestamptz) THEN 'ROTATING' ELSE 'REVOKED' END,
                               grace_until = :grace, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'ACTIVE'""")
                .param("grace", Pg.ts(graceUntil)).param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    /** 이름 바꾸기(교체한 이전 토큰) */
    public int rename(long organizationId, long id, String name, Instant now) {
        return jdbc.sql("UPDATE data2flow_core.api_tokens SET name = :name, updated_at = :now WHERE organization_id = :org AND id = :id")
                .param("name", name).param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    /** 마지막 사용 시각·IP(1분에 한 번만 쓴다, IAM-05.03) */
    public void touch(long organizationId, long id, String ip, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.api_tokens SET last_used_at = :now, last_used_ip = COALESCE(CAST(:ip AS inet), last_used_ip)
                         WHERE organization_id = :org AND id = :id
                           AND (last_used_at IS NULL OR last_used_at < CAST(:now AS timestamptz) - interval '1 minute'
                                OR (CAST(:ip AS inet) IS NOT NULL AND last_used_ip IS DISTINCT FROM CAST(:ip AS inet)))""")
                .param("now", Pg.ts(now)).param("ip", ip).param("org", organizationId).param("id", id).update();
    }

    /** 서비스 계정의 살아 있는 토큰 ID(비활성화 시 폐기 알림용) */
    public List<Long> listLiveIdsOfServiceAccount(long organizationId, long serviceAccountId) {
        return jdbc.sql("""
                        SELECT id FROM data2flow_core.api_tokens
                         WHERE organization_id = :org AND owner_type = 'SERVICE_ACCOUNT' AND owner_id = :owner
                           AND status IN ('PENDING_APPROVAL','ACTIVE','ROTATING') ORDER BY id""")
                .param("org", organizationId).param("owner", serviceAccountId).query(Long.class).list();
    }

    /** 서비스 계정의 살아 있는 토큰 폐기(BR-IAM-35). 폐기한 수 */
    public int revokeAllOfServiceAccount(long organizationId, long serviceAccountId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.api_tokens SET status = 'REVOKED', updated_at = :now
                         WHERE organization_id = :org AND owner_type = 'SERVICE_ACCOUNT' AND owner_id = :owner
                           AND status IN ('PENDING_APPROVAL','ACTIVE','ROTATING')""")
                .param("now", Pg.ts(now)).param("org", organizationId).param("owner", serviceAccountId).update();
    }

    public long countLiveOfServiceAccount(long organizationId, long serviceAccountId) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.api_tokens
                         WHERE organization_id = :org AND owner_type = 'SERVICE_ACCOUNT' AND owner_id = :owner
                           AND status IN ('PENDING_APPROVAL','ACTIVE','ROTATING')""")
                .param("org", organizationId).param("owner", serviceAccountId).query(Long.class).single();
    }

    /** 사용자의 살아 있는 토큰 ID(비활성화·삭제 시 폐기 알림용) */
    public List<Long> listLiveIdsOfUser(long organizationId, long userId) {
        return jdbc.sql("""
                        SELECT id FROM data2flow_core.api_tokens
                         WHERE organization_id = :org AND owner_type = 'USER' AND owner_id = :owner
                           AND status IN ('PENDING_APPROVAL','ACTIVE','ROTATING') ORDER BY id""")
                .param("org", organizationId).param("owner", userId).query(Long.class).list();
    }

    /** 만료·유예 끝 정리(1분 작업): ACTIVE·PENDING → EXPIRED, ROTATING 유예 끝 → REVOKED. 바뀐 (조직, ID) */
    @OrganizationScopeExempt("모든 조직의 시각 기준 상태 정리(1분 작업). 판정은 시각만 본다")
    public List<long[]> expireDue(Instant now) {
        List<long[]> changed = new java.util.ArrayList<>(jdbc.sql("""
                        UPDATE data2flow_core.api_tokens SET status = 'EXPIRED', updated_at = :now
                         WHERE status IN ('PENDING_APPROVAL','ACTIVE') AND expires_at <= :now
                        RETURNING organization_id, id""")
                .param("now", Pg.ts(now)).query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}).list());
        changed.addAll(jdbc.sql("""
                        UPDATE data2flow_core.api_tokens SET status = 'REVOKED', updated_at = :now
                         WHERE status = 'ROTATING' AND (grace_until <= :now OR expires_at <= :now)
                        RETURNING organization_id, id""")
                .param("now", Pg.ts(now)).query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}).list());
        return changed;
    }

    static TokenRow row(ResultSet rs, int n) throws SQLException {
        return new TokenRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("owner_type"), rs.getLong("owner_id"),
                rs.getString("kind"), rs.getString("name"), rs.getString("token_prefix").strip(), Pg.stringList(rs, "scopes"),
                Pg.longList(rs, "space_scope"), Pg.instant(rs, "expires_at"), rs.getInt("rate_limit_per_min"), rs.getString("status"),
                Pg.instant(rs, "last_used_at"), rs.getString("last_used_ip"), Pg.longOrNull(rs, "rotated_from_id"),
                Pg.instant(rs, "grace_until"), Pg.longOrNull(rs, "approved_by"), rs.getLong("created_by"), Pg.instant(rs, "created_at"),
                rs.getString("owner_name"), rs.getString("owner_status"));
    }
}
