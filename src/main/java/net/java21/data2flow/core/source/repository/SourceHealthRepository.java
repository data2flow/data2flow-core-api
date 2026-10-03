package net.java21.data2flow.core.source.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.source.domain.SourceModels.RuntimeRow;
import net.java21.data2flow.core.source.domain.SourceModels.StatBucket;
import net.java21.data2flow.core.source.domain.SourceModels.StateRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 소스 연결 상태와 지표: {@code source_runtimes}(ingress 인스턴스별 보고), {@code source_states}(대표 상태·무수신 판정),
 * {@code source_stat_1m}(1분 지표, 7일 보관). DSC-02.01·02.03, EVT-DSC-02·03·04·05.
 */
@Repository
public class SourceHealthRepository {

    /** 지표 이름(EVT-DSC-03 counters 키) → source_stat_1m 열. snake_case 키도 받는다 */
    public static final Map<String, String> COUNTER_COLUMNS = Map.ofEntries(
            Map.entry("received", "received"), Map.entry("accepted", "accepted"),
            Map.entry("decodeErrors", "decode_errors"), Map.entry("decode_errors", "decode_errors"),
            Map.entry("scriptErrors", "script_errors"), Map.entry("script_errors", "script_errors"),
            Map.entry("rejectedUnknown", "rejected_unknown"), Map.entry("rejected_unknown", "rejected_unknown"),
            Map.entry("invalid", "invalid"), Map.entry("dup", "dup"), Map.entry("bytes", "bytes"),
            Map.entry("reconnects", "reconnects"));

    private final JdbcClient jdbc;

    public SourceHealthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- source_runtimes ----

    /** 인스턴스 보고 저장. 늦게 도착한 오래된 보고는 새 보고를 덮지 않는다(재전달·순서 뒤바뀜) */
    public void upsertRuntime(long organizationId, RuntimeRow r) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.source_runtimes (source_id, instance_id, organization_id, state, error_kind, error_message,
                            client_id, connected_since, reconnects_24h, reported_at)
                        VALUES (:source, :instance, :org, :state, :kind, :message, :client, :since, :reconnects, :at)
                        ON CONFLICT (source_id, instance_id) DO UPDATE
                           SET state = EXCLUDED.state, error_kind = EXCLUDED.error_kind, error_message = EXCLUDED.error_message,
                               client_id = EXCLUDED.client_id, connected_since = EXCLUDED.connected_since,
                               reconnects_24h = EXCLUDED.reconnects_24h, reported_at = EXCLUDED.reported_at
                         WHERE data2flow_core.source_runtimes.reported_at <= EXCLUDED.reported_at""")
                .param("source", r.sourceId()).param("instance", r.instanceId()).param("org", organizationId).param("state", r.state())
                .param("kind", r.errorKind()).param("message", r.errorMessage()).param("client", r.clientId())
                .param("since", Pg.ts(r.connectedSince())).param("reconnects", r.reconnects24h()).param("at", Pg.ts(r.reportedAt()))
                .update();
    }

    public List<RuntimeRow> findRuntimes(long organizationId, long sourceId) {
        return jdbc.sql("SELECT * FROM data2flow_core.source_runtimes WHERE organization_id = :org AND source_id = :id ORDER BY instance_id")
                .param("org", organizationId).param("id", sourceId).query(SourceHealthRepository::runtime).list();
    }

    public Map<Long, List<RuntimeRow>> findRuntimesOf(long organizationId, Collection<Long> sourceIds) {
        Map<Long, List<RuntimeRow>> result = new LinkedHashMap<>();
        if (sourceIds.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT * FROM data2flow_core.source_runtimes WHERE organization_id = :org AND source_id = ANY(CAST(:ids AS bigint[])) ORDER BY instance_id")
                .param("org", organizationId).param("ids", Pg.bigintArray(sourceIds))
                .query(SourceHealthRepository::runtime).list()
                .forEach(r -> result.computeIfAbsent(r.sourceId(), k -> new ArrayList<>()).add(r));
        return result;
    }

    /** 오래된(24시간 넘게 보고 없는) 인스턴스 행 정리 — 파드 이름이 바뀌며 쌓이는 행 */
    @OrganizationScopeExempt("모든 조직을 도는 정리 작업(배포 조직으로 좁힘)")
    public int deleteRuntimesBefore(Instant cutoff, OptionalLong restriction) {
        Long org = restriction.isPresent() ? restriction.getAsLong() : null;
        return jdbc.sql("""
                        DELETE FROM data2flow_core.source_runtimes
                         WHERE reported_at < :cutoff AND (CAST(:org AS bigint) IS NULL OR organization_id = :org)""")
                .param("cutoff", Pg.ts(cutoff)).param("org", org).update();
    }

    // ---- source_states ----

    /** 상태 행을 만들고(없으면) 잠근다. 같은 소스 보고를 동시에 처리해도 대표 상태 계산이 섞이지 않게 */
    public StateRow lockState(long organizationId, long sourceId) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.source_states (source_id, organization_id) VALUES (:id, :org)
                        ON CONFLICT (source_id) DO NOTHING""")
                .param("id", sourceId).param("org", organizationId).update();
        return jdbc.sql("SELECT * FROM data2flow_core.source_states WHERE organization_id = :org AND source_id = :id FOR UPDATE")
                .param("org", organizationId).param("id", sourceId).query(SourceHealthRepository::state).single();
    }

    public Optional<StateRow> findState(long organizationId, long sourceId) {
        return jdbc.sql("SELECT * FROM data2flow_core.source_states WHERE organization_id = :org AND source_id = :id")
                .param("org", organizationId).param("id", sourceId).query(SourceHealthRepository::state).optional();
    }

    public Map<Long, StateRow> findStatesOf(long organizationId, Collection<Long> sourceIds) {
        Map<Long, StateRow> result = new LinkedHashMap<>();
        if (sourceIds.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT * FROM data2flow_core.source_states WHERE organization_id = :org AND source_id = ANY(CAST(:ids AS bigint[]))")
                .param("org", organizationId).param("ids", Pg.bigintArray(sourceIds))
                .query(SourceHealthRepository::state).list().forEach(s -> result.put(s.sourceId(), s));
        return result;
    }

    public void updateConnectionState(long organizationId, long sourceId, String state, String errorKind, Instant changedAt, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.source_states
                           SET connection_state = :state, error_kind = :kind, state_changed_at = :changed, updated_at = :now
                         WHERE organization_id = :org AND source_id = :id""")
                .param("state", state).param("kind", errorKind).param("changed", Pg.ts(changedAt)).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", sourceId).update();
    }

    public void updateActivatedAt(long organizationId, long sourceId, Instant activatedAt, Instant now) {
        jdbc.sql("UPDATE data2flow_core.source_states SET activated_at = :at, updated_at = :now WHERE organization_id = :org AND source_id = :id")
                .param("at", Pg.ts(activatedAt)).param("now", Pg.ts(now)).param("org", organizationId).param("id", sourceId).update();
    }

    /** 마지막 수신 분을 앞으로만 옮긴다 */
    public void updateLastReceived(long organizationId, long sourceId, Instant minute, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.source_states
                           SET last_received_at = greatest(coalesce(last_received_at, :minute), :minute), updated_at = :now
                         WHERE organization_id = :org AND source_id = :id""")
                .param("minute", Pg.ts(minute)).param("now", Pg.ts(now)).param("org", organizationId).param("id", sourceId).update();
    }

    public void updateNoData(long organizationId, long sourceId, boolean noData, Instant now) {
        jdbc.sql("UPDATE data2flow_core.source_states SET no_data = :flag, updated_at = :now WHERE organization_id = :org AND source_id = :id")
                .param("flag", noData).param("now", Pg.ts(now)).param("org", organizationId).param("id", sourceId).update();
    }

    /** 점검 대상: ACTIVE 소스와 상태(1분 주기 점검, 배포 조직으로 좁힘). (조직 ID, 소스 ID, 무수신 기준 초) */
    @OrganizationScopeExempt("모든 조직의 ACTIVE 소스를 도는 1분 점검(배포 조직으로 좁힘)")
    public List<ActiveSource> listActiveSources(OptionalLong restriction) {
        Long org = restriction.isPresent() ? restriction.getAsLong() : null;
        return jdbc.sql("""
                        SELECT id, organization_id, no_data_alarm_after_sec FROM data2flow_core.data_sources
                         WHERE lifecycle = 'ACTIVE' AND (CAST(:org AS bigint) IS NULL OR organization_id = :org) ORDER BY id""")
                .param("org", org)
                .query((rs, n) -> new ActiveSource(rs.getLong("organization_id"), rs.getLong("id"), rs.getInt("no_data_alarm_after_sec")))
                .list();
    }

    public record ActiveSource(long organizationId, long sourceId, int noDataAlarmAfterSec) {
    }

    // ---- source_stat_1m ----

    /**
     * 1분 지표 합산(EVT-DSC-03). ingress·pipeline이 같은 분의 다른 열을 보내므로 더한다. 같은 메시지 두 번은 소비자의
     * messageId 중복 제거가 막는다.
     */
    public void addStats(long organizationId, long sourceId, Instant minute, Map<String, Long> columnValues) {
        if (columnValues.isEmpty()) {
            return;
        }
        List<String> cols = new ArrayList<>(columnValues.keySet());
        String insertCols = String.join(", ", cols);
        String insertVals = String.join(", ", cols.stream().map(c -> ":" + c).toList());
        String updates = String.join(", ", cols.stream().map(c -> c + " = data2flow_core.source_stat_1m." + c + " + EXCLUDED." + c).toList());
        var spec = jdbc.sql("INSERT INTO data2flow_core.source_stat_1m (source_id, minute, organization_id, " + insertCols + ") "
                        + "VALUES (:source, :minute, :org, " + insertVals + ") "
                        + "ON CONFLICT (source_id, minute) DO UPDATE SET " + updates)
                .param("source", sourceId).param("minute", Pg.ts(minute)).param("org", organizationId);
        for (Map.Entry<String, Long> e : columnValues.entrySet()) {
            spec = "bytes".equals(e.getKey()) ? spec.param(e.getKey(), e.getValue()) : spec.param(e.getKey(), (int) Math.min(Integer.MAX_VALUE, e.getValue()));
        }
        spec.update();
    }

    /**
     * 구간별 지표(API-DSC-09). 빈 구간은 0으로 채운다. bucket은 1·5·60분.
     */
    public List<StatBucket> findStats(long organizationId, long sourceId, Instant from, Instant to, Duration bucket) {
        long minutes = bucket.toMinutes();
        return jdbc.sql("""
                        WITH b AS (
                            SELECT generate_series(date_bin(CAST(:step AS interval), CAST(:from AS timestamptz), TIMESTAMPTZ '2000-01-01 00:00:00+00'),
                                                   CAST(:to AS timestamptz) - interval '1 microsecond', CAST(:step AS interval)) AS t)
                        SELECT b.t,
                               coalesce(sum(s.received), 0) AS received, coalesce(sum(s.accepted), 0) AS accepted,
                               coalesce(sum(s.decode_errors), 0) AS decode_errors, coalesce(sum(s.script_errors), 0) AS script_errors,
                               coalesce(sum(s.rejected_unknown), 0) AS rejected_unknown, coalesce(sum(s.invalid), 0) AS invalid,
                               coalesce(sum(s.dup), 0) AS dup, coalesce(sum(s.bytes), 0) AS bytes, coalesce(sum(s.reconnects), 0) AS reconnects
                          FROM b
                          LEFT JOIN data2flow_core.source_stat_1m s
                            ON s.organization_id = :org AND s.source_id = :id
                           AND s.minute >= b.t AND s.minute < b.t + CAST(:step AS interval)
                           AND s.minute >= :from AND s.minute < :to
                         GROUP BY b.t ORDER BY b.t""")
                .param("step", minutes + " minutes").param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .param("org", organizationId).param("id", sourceId)
                .query((rs, n) -> new StatBucket(Pg.instant(rs, "t"), rs.getLong("received"), rs.getLong("accepted"),
                        rs.getLong("decode_errors"), rs.getLong("script_errors"), rs.getLong("rejected_unknown"), rs.getLong("invalid"),
                        rs.getLong("dup"), rs.getLong("bytes"), rs.getLong("reconnects"))).list();
    }

    /** 소스들의 최근 1시간 분별 수신·디코딩 실패(목록 요약). (소스 ID → (분 → [received, decodeErrors])) */
    public Map<Long, Map<Instant, long[]>> findRecentMinutes(long organizationId, Collection<Long> sourceIds, Instant from) {
        Map<Long, Map<Instant, long[]>> result = new LinkedHashMap<>();
        if (sourceIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        SELECT source_id, minute, received, decode_errors FROM data2flow_core.source_stat_1m
                         WHERE organization_id = :org AND source_id = ANY(CAST(:ids AS bigint[])) AND minute >= :from""")
                .param("org", organizationId).param("ids", Pg.bigintArray(sourceIds)).param("from", Pg.ts(from))
                .query((rs, n) -> result.computeIfAbsent(rs.getLong("source_id"), k -> new LinkedHashMap<>())
                        .put(Pg.instant(rs, "minute"), new long[]{rs.getLong("received"), rs.getLong("decode_errors")}))
                .list();
        return result;
    }

    /** 최근 7일 일별 수신량(API-DSC-11 volume7d). UTC 날짜 */
    public List<Map<String, Object>> findDailyVolume(long organizationId, long sourceId, Instant from) {
        return jdbc.sql("""
                        SELECT to_char(date_trunc('day', minute AT TIME ZONE 'UTC'), 'YYYY-MM-DD') AS day, sum(received) AS count
                          FROM data2flow_core.source_stat_1m
                         WHERE organization_id = :org AND source_id = :id AND minute >= :from
                         GROUP BY 1 ORDER BY 1 DESC""")
                .param("org", organizationId).param("id", sourceId).param("from", Pg.ts(from))
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("day", rs.getString("day"));
                    m.put("count", rs.getLong("count"));
                    return m;
                }).list();
    }

    /** 7일 보관(DSC domain-model §2.6). 배포 조직으로 좁힌다 */
    @OrganizationScopeExempt("모든 조직을 도는 보관 기간 정리(배포 조직으로 좁힘)")
    public int deleteStatsBefore(Instant cutoff, OptionalLong restriction) {
        Long org = restriction.isPresent() ? restriction.getAsLong() : null;
        return jdbc.sql("""
                        DELETE FROM data2flow_core.source_stat_1m
                         WHERE minute < :cutoff AND (CAST(:org AS bigint) IS NULL OR organization_id = :org)""")
                .param("cutoff", Pg.ts(cutoff)).param("org", org).update();
    }

    private static RuntimeRow runtime(ResultSet rs, int n) throws SQLException {
        return new RuntimeRow(rs.getLong("source_id"), rs.getString("instance_id"), rs.getString("state"), rs.getString("error_kind"),
                rs.getString("error_message"), rs.getString("client_id"), Pg.instant(rs, "connected_since"), rs.getInt("reconnects_24h"),
                Pg.instant(rs, "reported_at"));
    }

    private static StateRow state(ResultSet rs, int n) throws SQLException {
        return new StateRow(rs.getLong("source_id"), rs.getLong("organization_id"), rs.getString("connection_state"),
                rs.getString("error_kind"), Pg.instant(rs, "state_changed_at"), Pg.instant(rs, "last_received_at"),
                Pg.instant(rs, "activated_at"), rs.getBoolean("no_data"));
    }
}
