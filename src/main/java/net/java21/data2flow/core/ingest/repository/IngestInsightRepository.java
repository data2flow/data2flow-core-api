package net.java21.data2flow.core.ingest.repository;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * M5 수집 관리 조회(pipeline 소유 표를 읽기만): 기간 재처리 작업 목록·상세({@code reprocess_jobs}, API-ING-14, ING-01.04),
 * 품질 추이·문제 유형 분포({@code data_quality_daily}, ING-06.02), 공백·완전성({@code data_gaps}, API-TSD-09, TSD-02.04).
 * 공간 범위가 제한된 사용자는 범위 안 기기만 본다(재처리 작업은 대상 기기가 모두 범위 안인 것만, IAM-04.06).
 */
@Repository
public class IngestInsightRepository {

    private static final String JOB_SELECT = """
            SELECT j.id, j.source_id, s.name AS source_name, j.device_ids, j.period_from, j.period_to, j.status, j.total, j.processed, j.failed,
                   j.skipped, j.requested_by, u.name AS requested_by_name, j.memo, j.error, j.only_failed, j.created_at, j.started_at,
                   j.finished_at
              FROM data2flow_pipeline.reprocess_jobs j
              LEFT JOIN data2flow_core.data_sources s ON s.id = j.source_id AND s.organization_id = j.organization_id
              LEFT JOIN data2flow_core.app_users u ON u.id = j.requested_by
            """;

    private static final String JOB_WHERE = """
             WHERE j.organization_id = :org AND (CAST(:status AS text) IS NULL OR j.status = :status)
               AND (CAST(:source AS bigint) IS NULL OR j.source_id = :source)
               AND (:unrestricted OR (cardinality(j.device_ids) > 0 AND NOT EXISTS (
                       SELECT 1 FROM unnest(j.device_ids) did LEFT JOIN data2flow_core.devices d ON d.id = did AND d.organization_id = j.organization_id
                        WHERE d.space_id IS NULL OR NOT d.space_id = ANY(CAST(:allowed AS bigint[])))))""";

    private final JdbcClient jdbc;

    public IngestInsightRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<JobRow> findJobs(long organizationId, String status, Long sourceId, SpaceScope scope, int limit, long offset) {
        return jdbc.sql(JOB_SELECT + JOB_WHERE + " ORDER BY j.created_at DESC, j.id DESC LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("status", status).param("source", sourceId).param("unrestricted", scope.unrestricted())
                .param("allowed", Pg.bigintArray(scope.allowedSpaceIds())).param("limit", limit).param("offset", offset)
                .query(IngestInsightRepository::job).list();
    }

    public long countJobs(long organizationId, String status, Long sourceId, SpaceScope scope) {
        return jdbc.sql("SELECT count(*) FROM data2flow_pipeline.reprocess_jobs j " + JOB_WHERE)
                .param("org", organizationId).param("status", status).param("source", sourceId).param("unrestricted", scope.unrestricted())
                .param("allowed", Pg.bigintArray(scope.allowedSpaceIds())).query(Long.class).single();
    }

    public Optional<JobRow> findJob(long organizationId, long jobId, SpaceScope scope) {
        return jdbc.sql(JOB_SELECT + JOB_WHERE + " AND j.id = :id")
                .param("org", organizationId).param("status", null).param("source", null).param("unrestricted", scope.unrestricted())
                .param("allowed", Pg.bigintArray(scope.allowedSpaceIds())).param("id", jobId)
                .query(IngestInsightRepository::job).optional();
    }

    /**
     * 품질 일별 추이(대상 하나: 기기·공간 하위·모델). target 종류는 정해진 값만 SQL에 넣는다.
     */
    public List<TrendRow> findQualityTrend(long organizationId, String targetKind, long targetId, String spacePath, LocalDate from, LocalDate to,
                                           SpaceScope scope) {
        String cond = switch (targetKind) {
            case "SPACE" -> "sp.path LIKE :path || '%'";
            case "MODEL" -> "d.model_id = :target";
            default -> "d.id = :target";
        };
        return jdbc.sql("""
                        SELECT q.day, round(avg(q.score)) AS score, round(avg(q.completeness)) AS completeness, round(avg(q.timeliness)) AS timeliness,
                               round(avg(q.validity)) AS validity, round(avg(q.stability)) AS stability, count(*) AS devices
                          FROM data2flow_pipeline.data_quality_daily q
                          JOIN data2flow_core.devices d ON d.id = q.device_id AND d.organization_id = q.organization_id
                          LEFT JOIN data2flow_core.spaces sp ON sp.id = d.space_id
                         WHERE q.organization_id = :org AND q.day >= :from AND q.day <= :to AND %s
                           AND (:unrestricted OR d.space_id = ANY(CAST(:allowed AS bigint[])))
                         GROUP BY q.day ORDER BY q.day""".formatted(cond))
                .param("org", organizationId).param("from", from).param("to", to).param("target", targetId)
                .param("path", spacePath == null ? "" : spacePath).param("unrestricted", scope.unrestricted())
                .param("allowed", Pg.bigintArray(scope.allowedSpaceIds()))
                .query((rs, n) -> new TrendRow(rs.getObject("day", LocalDate.class), rs.getInt("score"), rs.getInt("completeness"),
                        rs.getInt("timeliness"), rs.getInt("validity"), rs.getInt("stability"), rs.getInt("devices"))).list();
    }

    /** 하루의 문제 유형 합계(공간 하위 조건 선택) */
    public Distribution findDistribution(long organizationId, LocalDate day, Instant dayStart, Instant dayEnd, String spacePath, SpaceScope scope) {
        return jdbc.sql("""
                        SELECT count(*) AS devices, coalesce(round(avg(q.score)), 0) AS avg_score,
                               coalesce(sum(q.expected_count), 0) AS expected, coalesce(sum(q.received_count), 0) AS received,
                               coalesce(sum(q.late_count), 0) AS late, coalesce(sum(q.out_of_range_count), 0) AS out_of_range,
                               coalesce(sum(q.suspect_count), 0) AS suspect,
                               coalesce(sum((SELECT count(*) FROM data2flow_pipeline.data_gaps g WHERE g.organization_id = q.organization_id
                                   AND g.device_id = q.device_id AND g.gap_start < :dayEnd AND g.gap_end > :dayStart)), 0) AS gaps
                          FROM data2flow_pipeline.data_quality_daily q
                          JOIN data2flow_core.devices d ON d.id = q.device_id AND d.organization_id = q.organization_id
                          LEFT JOIN data2flow_core.spaces sp ON sp.id = d.space_id
                         WHERE q.organization_id = :org AND q.day = :day AND (:path = '' OR sp.path LIKE :path || '%')
                           AND (:unrestricted OR d.space_id = ANY(CAST(:allowed AS bigint[])))""")
                .param("org", organizationId).param("day", day).param("dayStart", Pg.ts(dayStart)).param("dayEnd", Pg.ts(dayEnd))
                .param("path", spacePath == null ? "" : spacePath).param("unrestricted", scope.unrestricted())
                .param("allowed", Pg.bigintArray(scope.allowedSpaceIds()))
                .query((rs, n) -> new Distribution(rs.getLong("devices"), rs.getInt("avg_score"), rs.getLong("expected"), rs.getLong("received"),
                        rs.getLong("late"), rs.getLong("out_of_range"), rs.getLong("suspect"), rs.getLong("gaps"))).single();
    }

    /** 공백·완전성 대상 기기(기기 하나 또는 공간 하위, 활성·삭제 안 됨, 최대 500) */
    public List<GapDevice> findGapDevices(long organizationId, Long deviceId, String spacePath, SpaceScope scope) {
        return jdbc.sql("""
                        SELECT d.id, d.name, d.space_id, coalesce(d.expected_interval_sec, m.default_interval_sec, 600) AS interval_sec
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_core.device_models m ON m.id = d.model_id
                          LEFT JOIN data2flow_core.spaces sp ON sp.id = d.space_id
                         WHERE d.organization_id = :org AND d.status <> 'DELETED'
                           AND (CAST(:device AS bigint) IS NULL OR d.id = :device)
                           AND (:path = '' OR sp.path LIKE :path || '%')
                           AND (:unrestricted OR d.space_id = ANY(CAST(:allowed AS bigint[])))
                         ORDER BY d.id LIMIT 500""")
                .param("org", organizationId).param("device", deviceId).param("path", spacePath == null ? "" : spacePath)
                .param("unrestricted", scope.unrestricted()).param("allowed", Pg.bigintArray(scope.allowedSpaceIds()))
                .query((rs, n) -> new GapDevice(rs.getLong("id"), rs.getString("name"), Pg.longOrNull(rs, "space_id"), rs.getInt("interval_sec")))
                .list();
    }

    /** 기간에 걸치는 공백(기간 밖 부분은 잘라서) */
    public List<GapSpan> findGapSpans(long organizationId, List<Long> deviceIds, Instant from, Instant to) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT device_id, greatest(gap_start, CAST(:from AS timestamptz)) AS s, least(gap_end, CAST(:to AS timestamptz)) AS e,
                               gap_start, gap_end, expected_count
                          FROM data2flow_pipeline.data_gaps
                         WHERE organization_id = :org AND device_id = ANY(CAST(:ids AS bigint[])) AND gap_start < :to AND gap_end > :from
                         ORDER BY device_id, gap_start LIMIT 5000""")
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds)).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query((rs, n) -> new GapSpan(rs.getLong("device_id"), Pg.instant(rs, "s"), Pg.instant(rs, "e"), Pg.instant(rs, "gap_start"),
                        Pg.instant(rs, "gap_end"), rs.getInt("expected_count"))).list();
    }

    static JobRow job(ResultSet rs, int n) throws SQLException {
        return new JobRow(rs.getLong("id"), rs.getLong("source_id"), rs.getString("source_name"), Pg.longList(rs, "device_ids"),
                Pg.instant(rs, "period_from"), Pg.instant(rs, "period_to"), rs.getString("status"), rs.getLong("total"), rs.getLong("processed"),
                rs.getLong("failed"), rs.getLong("skipped"), rs.getLong("requested_by"), rs.getString("requested_by_name"), rs.getString("memo"),
                rs.getString("error"), rs.getBoolean("only_failed"), Pg.instant(rs, "created_at"), Pg.instant(rs, "started_at"),
                Pg.instant(rs, "finished_at"));
    }

    public record JobRow(long id, long sourceId, String sourceName, List<Long> deviceIds, Instant from, Instant to, String status, long total,
                         long processed, long failed, long skipped, long requestedBy, String requestedByName, String memo, String error,
                         boolean onlyFailed, Instant createdAt, Instant startedAt, Instant finishedAt) {
    }

    public record TrendRow(LocalDate day, int score, int completeness, int timeliness, int validity, int stability, int devices) {
    }

    public record Distribution(long devices, int averageScore, long expected, long received, long late, long outOfRange, long suspect,
                               long gaps) {
    }

    public record GapDevice(long id, String name, Long spaceId, int intervalSec) {
    }

    public record GapSpan(long deviceId, Instant from, Instant to, Instant gapStart, Instant gapEnd, int expectedCount) {
    }
}
