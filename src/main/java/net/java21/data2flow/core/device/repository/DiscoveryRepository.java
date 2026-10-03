package net.java21.data2flow.core.device.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * 기기 발견(ADR-031): 자동 등록 무시 목록({@code source_ignore_entries}, BR-DEV-07)과 소스별 시간당 자동 등록 한도
 * ({@code source_autoreg_quotas}, BR-ING-09).
 */
@Repository
public class DiscoveryRepository {

    private final JdbcClient jdbc;

    public DiscoveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsIgnoreEntry(long organizationId, long sourceId, String externalId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.source_ignore_entries
                                        WHERE organization_id = :org AND source_id = :source AND external_id = :ext)""")
                .param("org", organizationId).param("source", sourceId).param("ext", externalId).query(Boolean.class).single();
    }

    public void insertIgnoreEntry(long organizationId, long sourceId, String externalId, String reason, long createdBy, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.source_ignore_entries (source_id, external_id, organization_id, reason, created_by, created_at)
                        VALUES (:source, :ext, :org, :reason, :by, :now) ON CONFLICT (source_id, external_id) DO NOTHING""")
                .param("source", sourceId).param("ext", externalId).param("org", organizationId).param("reason", reason)
                .param("by", createdBy).param("now", Pg.ts(now)).update();
    }

    public int deleteIgnoreEntry(long organizationId, long sourceId, String externalId) {
        return jdbc.sql("DELETE FROM data2flow_core.source_ignore_entries WHERE organization_id = :org AND source_id = :source AND external_id = :ext")
                .param("org", organizationId).param("source", sourceId).param("ext", externalId).update();
    }

    /**
     * 소스의 한도 행을 잠근다(없으면 만든다). 같은 소스의 자동 등록이 이 잠금으로 한 줄로 서므로 한도를 정확히 센다.
     */
    public Quota lockQuota(long organizationId, long sourceId, Instant windowStart) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.source_autoreg_quotas (source_id, organization_id, window_started_at, updated_at)
                        VALUES (:source, :org, :window, :window) ON CONFLICT (source_id) DO NOTHING""")
                .param("source", sourceId).param("org", organizationId).param("window", Pg.ts(windowStart)).update();
        return jdbc.sql("""
                        SELECT window_started_at, used_count, rejected_count, blocked_at, released_at
                          FROM data2flow_core.source_autoreg_quotas WHERE organization_id = :org AND source_id = :source FOR UPDATE""")
                .param("org", organizationId).param("source", sourceId)
                .query((rs, n) -> new Quota(Pg.instant(rs, "window_started_at"), rs.getInt("used_count"), rs.getInt("rejected_count"),
                        Pg.instant(rs, "blocked_at"), Pg.instant(rs, "released_at")))
                .single();
    }

    public void updateQuota(long organizationId, long sourceId, Quota quota, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.source_autoreg_quotas
                           SET window_started_at = :window, used_count = :used, rejected_count = :rejected, blocked_at = :blocked,
                               updated_at = :now
                         WHERE organization_id = :org AND source_id = :source""")
                .param("window", Pg.ts(quota.windowStartedAt())).param("used", quota.usedCount()).param("rejected", quota.rejectedCount())
                .param("blocked", Pg.ts(quota.blockedAt())).param("now", Pg.ts(now)).param("org", organizationId).param("source", sourceId)
                .update();
    }

    /** 한도 해제(API-ING-16): 차단을 풀고 이번 시간 창을 새로 시작한다 */
    public void releaseQuota(long organizationId, long sourceId, Instant windowStart, long releasedBy, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.source_autoreg_quotas (source_id, organization_id, window_started_at, used_count,
                                                                          rejected_count, blocked_at, released_at, released_by, updated_at)
                        VALUES (:source, :org, :window, 0, 0, NULL, :now, :by, :now)
                        ON CONFLICT (source_id) DO UPDATE
                           SET window_started_at = EXCLUDED.window_started_at, used_count = 0, rejected_count = 0, blocked_at = NULL,
                               released_at = EXCLUDED.released_at, released_by = EXCLUDED.released_by, updated_at = EXCLUDED.updated_at""")
                .param("source", sourceId).param("org", organizationId).param("window", Pg.ts(windowStart)).param("now", Pg.ts(now))
                .param("by", releasedBy).update();
    }

    /** 소스의 시간당 한도 바꾸기(API-ING-16 newHourlyLimit). 소스 행 버전을 올린다 */
    public int updateSourceLimit(long organizationId, long sourceId, int limit, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.data_sources
                           SET autoreg_limit_per_hour = :limit, version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :source""")
                .param("limit", limit).param("by", updatedBy).param("now", Pg.ts(now)).param("org", organizationId)
                .param("source", sourceId).update();
    }

    public int findSourceVersion(long organizationId, long sourceId) {
        return jdbc.sql("SELECT version FROM data2flow_core.data_sources WHERE organization_id = :org AND id = :source")
                .param("org", organizationId).param("source", sourceId).query(Integer.class).single();
    }

    /** 한도 상태 */
    public record Quota(Instant windowStartedAt, int usedCount, int rejectedCount, Instant blockedAt, Instant releasedAt) {
    }
}
