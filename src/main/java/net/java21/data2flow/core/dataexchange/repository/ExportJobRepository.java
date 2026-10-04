package net.java21.data2flow.core.dataexchange.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 내보내기 작업({@code export_jobs}, TSD-04.01·04.03, BR-TSD-14) */
@Repository
public class ExportJobRepository {

    private static final String SELECT = """
            SELECT id, organization_id, requested_by, schedule_id, query, plan, format, status, rows_count, bytes, estimated_rows, object_key,
                   dictionary_version, expires_at, error, started_at, finished_at, created_at
              FROM data2flow_core.export_jobs""";

    private final JdbcClient jdbc;

    public ExportJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long organizationId, long requestedBy, Long scheduleId, String queryJson, String planJson, String format,
                       long estimatedRows, Integer dictionaryVersion, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.export_jobs (organization_id, requested_by, schedule_id, query, plan, format, status,
                               estimated_rows, dictionary_version, created_at, updated_at)
                        VALUES (:org, :by, :schedule, CAST(:query AS jsonb), CAST(:plan AS jsonb), :format, 'QUEUED', :est, :dict, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("by", requestedBy).param("schedule", scheduleId).param("query", queryJson)
                .param("plan", planJson).param("format", format).param("est", estimatedRows).param("dict", dictionaryVersion)
                .param("now", Pg.ts(now)).query(Long.class).single();
    }

    public Optional<ExportJobRow> findById(long organizationId, long id) {
        return jdbc.sql(SELECT + " WHERE organization_id = :org AND id = :id").param("org", organizationId).param("id", id)
                .query(ExportJobRepository::map).optional();
    }

    /** 목록(요청자 지정이면 그 사람 것만) — 최근 순 */
    public List<ExportJobRow> list(long organizationId, Long requestedBy, int limit, long offset) {
        return jdbc.sql(SELECT + " WHERE organization_id = :org AND (CAST(:by AS bigint) IS NULL OR requested_by = :by)"
                        + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("by", requestedBy).param("limit", limit).param("offset", offset)
                .query(ExportJobRepository::map).list();
    }

    public long count(long organizationId, Long requestedBy) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.export_jobs WHERE organization_id = :org"
                        + " AND (CAST(:by AS bigint) IS NULL OR requested_by = :by)")
                .param("org", organizationId).param("by", requestedBy).query(Long.class).single();
    }

    /** 사용자의 진행 중 수동 작업 수(동시 비동기 3개 한도, UC-TSD-04) */
    public long countActive(long organizationId, long requestedBy) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.export_jobs
                         WHERE organization_id = :org AND requested_by = :by AND schedule_id IS NULL AND status IN ('QUEUED', 'RUNNING')""")
                .param("org", organizationId).param("by", requestedBy).query(Long.class).single();
    }

    /** QUEUED → RUNNING으로 하나 잡는다(여러 파드 중 하나만, SKIP LOCKED). 없으면 빈 값 */
    @OrganizationScopeExempt("비동기 실행기: 모든 조직의 대기 작업을 순서대로 처리")
    public Optional<ExportJobRow> claimNextQueued(Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.export_jobs SET status = 'RUNNING', started_at = :now, updated_at = :now
                         WHERE id = (SELECT id FROM data2flow_core.export_jobs WHERE status = 'QUEUED'
                                      ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
                        RETURNING id, organization_id, requested_by, schedule_id, query, plan, format, status, rows_count, bytes,
                                  estimated_rows, object_key, dictionary_version, expires_at, error, started_at, finished_at, created_at""")
                .param("now", Pg.ts(now)).query(ExportJobRepository::map).optional();
    }

    /** 지정한 작업을 QUEUED → RUNNING으로(동기 시도). 이미 누가 잡았으면 false */
    public boolean updateClaim(long organizationId, long id, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.export_jobs SET status = 'RUNNING', started_at = :now, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'QUEUED'""")
                .param("org", organizationId).param("id", id).param("now", Pg.ts(now)).update() == 1;
    }

    public boolean updateSucceeded(long organizationId, long id, long rows, long bytes, String objectKey, Instant expiresAt, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.export_jobs SET status = 'SUCCEEDED', rows_count = :rows, bytes = :bytes, object_key = :key,
                               expires_at = :expires, finished_at = :now, updated_at = :now, error = NULL
                         WHERE organization_id = :org AND id = :id AND status = 'RUNNING'""")
                .param("org", organizationId).param("id", id).param("rows", rows).param("bytes", bytes).param("key", objectKey)
                .param("expires", Pg.ts(expiresAt)).param("now", Pg.ts(now)).update() == 1;
    }

    public boolean updateFailed(long organizationId, long id, String error, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.export_jobs SET status = 'FAILED', error = :error, finished_at = :now, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status IN ('QUEUED', 'RUNNING')""")
                .param("org", organizationId).param("id", id).param("error", truncate(error)).param("now", Pg.ts(now)).update() == 1;
    }

    public boolean updateCancelled(long organizationId, long id, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.export_jobs SET status = 'CANCELLED', finished_at = :now, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status IN ('QUEUED', 'RUNNING')""")
                .param("org", organizationId).param("id", id).param("now", Pg.ts(now)).update() == 1;
    }

    public String findStatus(long organizationId, long id) {
        return jdbc.sql("SELECT status FROM data2flow_core.export_jobs WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).query(String.class).optional().orElse(null);
    }

    /** 7일 지난 결과를 EXPIRED로 바꾸고 그 ID를 돌려준다(파일 조각 삭제용) */
    @OrganizationScopeExempt("시스템 정리 작업: 모든 조직의 만료 결과")
    public List<Long> expireFinished(Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.export_jobs SET status = 'EXPIRED', updated_at = :now
                         WHERE status = 'SUCCEEDED' AND expires_at <= :now RETURNING id""")
                .param("now", Pg.ts(now)).query(Long.class).list();
    }

    /** 맡은 파드가 죽어 오래 RUNNING인 작업을 다시 대기로(1시간) */
    @OrganizationScopeExempt("시스템 정리 작업: 멈춘 작업 되살리기")
    public int requeueStale(Instant before, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.export_jobs SET status = 'QUEUED', updated_at = :now
                         WHERE status = 'RUNNING' AND started_at < :before""")
                .param("before", Pg.ts(before)).param("now", Pg.ts(now)).update();
    }

    static String truncate(String s) {
        return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }

    static ExportJobRow map(ResultSet rs, int n) throws SQLException {
        return new ExportJobRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("requested_by"), Pg.longOrNull(rs, "schedule_id"),
                rs.getString("query"), rs.getString("plan"), rs.getString("format"), rs.getString("status"), Pg.longOrNull(rs, "rows_count"),
                Pg.longOrNull(rs, "bytes"), Pg.longOrNull(rs, "estimated_rows"), rs.getString("object_key"),
                (Integer) rs.getObject("dictionary_version"), Pg.instant(rs, "expires_at"), rs.getString("error"), Pg.instant(rs, "started_at"),
                Pg.instant(rs, "finished_at"), Pg.instant(rs, "created_at"));
    }

    public record ExportJobRow(long id, long organizationId, long requestedBy, Long scheduleId, String queryJson, String planJson,
                               String format, String status, Long rows, Long bytes, Long estimatedRows, String objectKey,
                               Integer dictionaryVersion, Instant expiresAt, String error, Instant startedAt, Instant finishedAt,
                               Instant createdAt) {
    }
}
