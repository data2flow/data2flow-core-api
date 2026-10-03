package net.java21.data2flow.core.ingest.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 수집 모니터 집계(API-ING-01·02, API-OPS-02). 원천은 pipeline 소유 {@code raw_messages}·{@code dlq_items}(읽기 전용)와 core의
 * {@code data_sources}·{@code source_runtimes}·{@code source_stat_1m}이다. 지연은 received_at → processed_at(처리 완료) 밀리초다.
 */
@Repository
public class IngestMonitorRepository {

    private final JdbcClient jdbc;

    public IngestMonitorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 보관 소스(ARCHIVED 제외)와 연결 상태(인스턴스 중 CONNECTED가 하나라도 있으면 CONNECTED, 아니면 가장 최근 보고 상태) */
    public List<SourceRow> findSources(long organizationId) {
        return jdbc.sql("""
                        SELECT s.id, s.name, s.type, s.lifecycle,
                               (SELECT CASE WHEN bool_or(r.state = 'CONNECTED') THEN 'CONNECTED'
                                            ELSE (array_agg(r.state ORDER BY r.reported_at DESC))[1] END
                                  FROM data2flow_core.source_runtimes r WHERE r.source_id = s.id) AS connection,
                               (SELECT max(st.minute) FROM data2flow_core.source_stat_1m st WHERE st.source_id = s.id AND st.received > 0)
                                   AS last_stat_minute
                          FROM data2flow_core.data_sources s
                         WHERE s.organization_id = :org AND s.lifecycle <> 'ARCHIVED'
                         ORDER BY s.id""")
                .param("org", organizationId)
                .query((rs, n) -> new SourceRow(rs.getLong("id"), rs.getString("name"), rs.getString("type"), rs.getString("lifecycle"),
                        rs.getString("connection"), Pg.instant(rs, "last_stat_minute")))
                .list();
    }

    /** 기간 안 (소스, 처리 결과)별 건수와 소스별 마지막 수신 시각 */
    public List<SourceStatusCount> countBySourceAndStatus(long organizationId, Instant since, Instant until) {
        return jdbc.sql("""
                        SELECT source_id, status, count(*) AS n, max(received_at) AS last_at FROM data2flow_pipeline.raw_messages
                         WHERE organization_id = :org AND received_at >= :since AND received_at < :until
                         GROUP BY source_id, status""")
                .param("org", organizationId).param("since", Pg.ts(since)).param("until", Pg.ts(until))
                .query((rs, n) -> new SourceStatusCount(rs.getLong("source_id"), rs.getString("status"), rs.getLong("n"),
                        Pg.instant(rs, "last_at")))
                .list();
    }

    /** 수신 → 처리 완료 지연 p50·p95(밀리초). 처리된 행이 없으면 비어 있다 */
    public Optional<Latency> findLatency(long organizationId, Instant since, Instant until) {
        return jdbc.sql("""
                        SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY ms) AS p50, percentile_cont(0.95) WITHIN GROUP (ORDER BY ms) AS p95
                          FROM (SELECT EXTRACT(EPOCH FROM (processed_at - received_at)) * 1000 AS ms FROM data2flow_pipeline.raw_messages
                                 WHERE organization_id = :org AND received_at >= :since AND received_at < :until AND processed_at IS NOT NULL) x
                        HAVING count(*) > 0""")
                .param("org", organizationId).param("since", Pg.ts(since)).param("until", Pg.ts(until))
                .query((rs, n) -> new Latency(Math.round(rs.getDouble("p50")), Math.round(rs.getDouble("p95")))).optional();
    }

    /** 아직 처리되지 않은(RECEIVED) 원본 중 가장 오래된 수신 시각(1일 안) */
    public Optional<Instant> findOldestPending(long organizationId, Instant since) {
        return Optional.ofNullable(jdbc.sql("""
                        SELECT min(received_at) AS oldest FROM data2flow_pipeline.raw_messages
                         WHERE organization_id = :org AND status = 'RECEIVED' AND processed_at IS NULL AND received_at >= :since""")
                .param("org", organizationId).param("since", Pg.ts(since))
                .query((rs, n) -> Pg.instant(rs, "oldest")).single());
    }

    /** 이 시각 이후 새 실패 메시지 수 */
    public long countDlqSince(long organizationId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM data2flow_pipeline.dlq_items WHERE organization_id = :org AND created_at >= :since")
                .param("org", organizationId).param("since", Pg.ts(since)).query(Long.class).single();
    }

    /** 구간별 (처리 결과)별 수신 건수. step은 PostgreSQL interval 문자열('1 minute') */
    public List<BucketCount> countByBucket(long organizationId, Long sourceId, Instant from, Instant to, String step) {
        return jdbc.sql("""
                        SELECT date_bin(CAST(:step AS interval), received_at, :origin) AS bucket, status, count(*) AS n
                          FROM data2flow_pipeline.raw_messages
                         WHERE organization_id = :org AND received_at >= :from AND received_at < :to
                           AND (:source = 0 OR source_id = :source)
                         GROUP BY 1, 2""")
                .param("step", step).param("origin", Pg.ts(from)).param("org", organizationId).param("from", Pg.ts(from))
                .param("to", Pg.ts(to)).param("source", sourceId == null ? 0L : sourceId)
                .query((rs, n) -> new BucketCount(Pg.instant(rs, "bucket"), rs.getString("status"), rs.getLong("n"))).list();
    }

    /** 구간별 지연 p50·p95와 처리 대기(RECEIVED) 건수 */
    public List<BucketLatency> findLatencyByBucket(long organizationId, Long sourceId, Instant from, Instant to, String step) {
        return jdbc.sql("""
                        SELECT date_bin(CAST(:step AS interval), received_at, :origin) AS bucket,
                               percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (processed_at - received_at)) * 1000)
                                   FILTER (WHERE processed_at IS NOT NULL) AS p50,
                               percentile_cont(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (processed_at - received_at)) * 1000)
                                   FILTER (WHERE processed_at IS NOT NULL) AS p95,
                               count(*) FILTER (WHERE status = 'RECEIVED') AS pending
                          FROM data2flow_pipeline.raw_messages
                         WHERE organization_id = :org AND received_at >= :from AND received_at < :to
                           AND (:source = 0 OR source_id = :source)
                         GROUP BY 1""")
                .param("step", step).param("origin", Pg.ts(from)).param("org", organizationId).param("from", Pg.ts(from))
                .param("to", Pg.ts(to)).param("source", sourceId == null ? 0L : sourceId)
                .query((rs, n) -> new BucketLatency(Pg.instant(rs, "bucket"), roundOrNull(rs, "p50"), roundOrNull(rs, "p95"),
                        rs.getLong("pending")))
                .list();
    }

    /** 조직의 소스인가(ARCHIVED 포함) */
    public boolean existsSource(long organizationId, long sourceId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.data_sources WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", sourceId).query(Long.class).single() > 0;
    }

    /** 조직 시간대(org_settings → organizations → Asia/Seoul) */
    public String findOrganizationTimezone(long organizationId) {
        return jdbc.sql("""
                        SELECT coalesce((SELECT timezone FROM data2flow_core.org_settings WHERE organization_id = :org),
                                        (SELECT timezone FROM data2flow_core.organizations WHERE id = :org), 'Asia/Seoul')""")
                .param("org", organizationId).query(String.class).single();
    }

    private static Long roundOrNull(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : Math.round(value);
    }

    /** 소스 */
    public record SourceRow(long id, String name, String type, String lifecycle, String connection, Instant lastStatMinute) {
    }

    /** (소스, 결과)별 건수 */
    public record SourceStatusCount(long sourceId, String status, long count, Instant lastAt) {
    }

    /** 지연(밀리초) */
    public record Latency(long p50, long p95) {
    }

    /** 구간별 결과 건수 */
    public record BucketCount(Instant bucket, String status, long count) {
    }

    /** 구간별 지연·대기 */
    public record BucketLatency(Instant bucket, Long p50, Long p95, long pending) {
    }
}
