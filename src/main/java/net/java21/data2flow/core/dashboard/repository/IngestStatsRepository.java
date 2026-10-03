package net.java21.data2flow.core.dashboard.repository;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 수집 흐름 읽기(DSH-03.01·03.02). 소스별 분 통계 {@code source_stat_1m}(core, EVT-DSC-03로 쌓임)와 대표 연결 상태
 * {@code source_runtimes}, pipeline 소유 실패 보관함 {@code data2flow_pipeline.dlq_items}(STORE·PUBLISH 실패)를 읽기만 한다.
 * 범위 제한이 있는 사용자는 사이트({@code data_sources.site_id})가 범위 안인 소스만 본다.
 */
@Repository
public class IngestStatsRepository {

    private final JdbcClient jdbc;

    public IngestStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 기간 [from, to) 단계 합계 */
    public StatSums sumStats(long organizationId, SpaceScope scope, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT COALESCE(sum(st.received), 0) AS received, COALESCE(sum(st.accepted), 0) AS accepted,
                               COALESCE(sum(st.decode_errors), 0) AS decode_errors, COALESCE(sum(st.script_errors), 0) AS script_errors,
                               COALESCE(sum(st.rejected_unknown), 0) AS rejected_unknown, COALESCE(sum(st.invalid), 0) AS invalid,
                               COALESCE(sum(st.dup), 0) AS dup
                          FROM data2flow_core.source_stat_1m st
                          JOIN data2flow_core.data_sources ds ON ds.id = st.source_id AND ds.organization_id = st.organization_id
                         WHERE st.organization_id = :org AND st.minute >= :from AND st.minute < :to
                           AND (:all OR ds.site_id = ANY(CAST(:allowed AS bigint[])))""")
                .param("org", organizationId).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .param("all", scope.unrestricted()).param("allowed", Pg.bigintArray(scope.allowedSpaceIds()))
                .query((rs, n) -> new StatSums(rs.getLong("received"), rs.getLong("accepted"), rs.getLong("decode_errors"),
                        rs.getLong("script_errors"), rs.getLong("rejected_unknown"), rs.getLong("invalid"), rs.getLong("dup")))
                .single();
    }

    /** 기간 [from, to) 실패 보관함에 들어간 건수(단계별: DECODE·SCRIPT·STORE·PUBLISH) */
    public Map<String, Long> countDlqByStage(long organizationId, Instant from, Instant to) {
        Map<String, Long> result = new HashMap<>();
        jdbc.sql("""
                        SELECT stage, count(*) AS n FROM data2flow_pipeline.dlq_items
                         WHERE organization_id = :org AND created_at >= :from AND created_at < :to GROUP BY stage""")
                .param("org", organizationId).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query((ResultSet rs) -> {
                    result.put(rs.getString("stage"), rs.getLong("n"));
                });
        return result;
    }

    /** 보관되지 않은 소스와 대표 연결 상태(인스턴스별 상태 중 가장 좋은 것) */
    public List<SourceRow> findSources(long organizationId, SpaceScope scope) {
        return jdbc.sql("""
                        SELECT ds.id, ds.name, ds.lifecycle,
                               (SELECT r.state FROM data2flow_core.source_runtimes r
                                 WHERE r.source_id = ds.id AND r.organization_id = ds.organization_id
                                 ORDER BY CASE r.state WHEN 'CONNECTED' THEN 0 WHEN 'CONNECTING' THEN 1 WHEN 'ERROR' THEN 2
                                                       WHEN 'DISCONNECTED' THEN 3 ELSE 4 END
                                 LIMIT 1) AS state
                          FROM data2flow_core.data_sources ds
                         WHERE ds.organization_id = :org AND ds.lifecycle <> 'ARCHIVED' AND ds.archived_at IS NULL
                           AND (:all OR ds.site_id = ANY(CAST(:allowed AS bigint[])))
                         ORDER BY ds.name, ds.id""")
                .param("org", organizationId).param("all", scope.unrestricted())
                .param("allowed", Pg.bigintArray(scope.allowedSpaceIds()))
                .query((rs, n) -> new SourceRow(rs.getLong("id"), rs.getString("name"), rs.getString("lifecycle"), rs.getString("state")))
                .list();
    }

    /** 소스별 분 단위 수신 건수 [from, to) */
    public List<MinuteCount> findMinuteCounts(long organizationId, List<Long> sourceIds, Instant from, Instant to) {
        if (sourceIds.isEmpty()) {
            return List.of();
        }
        List<MinuteCount> result = new ArrayList<>();
        jdbc.sql("""
                        SELECT source_id, minute, received FROM data2flow_core.source_stat_1m
                         WHERE organization_id = :org AND source_id = ANY(CAST(:ids AS bigint[])) AND minute >= :from AND minute < :to""")
                .param("org", organizationId).param("ids", Pg.bigintArray(sourceIds))
                .param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query((ResultSet rs) -> {
                    result.add(new MinuteCount(rs.getLong("source_id"), Pg.instant(rs, "minute"), rs.getLong("received")));
                });
        return result;
    }

    /** 소스별 마지막으로 수신이 있었던 분(분 통계 기준, 분 정밀도) */
    public Map<Long, Instant> findLastActiveMinutes(long organizationId, List<Long> sourceIds, Instant since) {
        Map<Long, Instant> result = new HashMap<>();
        if (sourceIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        SELECT source_id, max(minute) AS minute FROM data2flow_core.source_stat_1m
                         WHERE organization_id = :org AND source_id = ANY(CAST(:ids AS bigint[])) AND minute >= :since AND received > 0
                         GROUP BY source_id""")
                .param("org", organizationId).param("ids", Pg.bigintArray(sourceIds)).param("since", Pg.ts(since))
                .query((ResultSet rs) -> {
                    result.put(rs.getLong("source_id"), Pg.instant(rs, "minute"));
                });
        return result;
    }

    public record StatSums(long received, long accepted, long decodeErrors, long scriptErrors, long rejectedUnknown, long invalid,
                           long dup) {
    }

    public record SourceRow(long id, String name, String lifecycle, String runtimeState) {
    }

    public record MinuteCount(long sourceId, Instant minute, long received) {
    }
}
