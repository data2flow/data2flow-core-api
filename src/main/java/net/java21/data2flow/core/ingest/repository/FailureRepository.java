package net.java21.data2flow.core.ingest.repository;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 실패 메시지(DLQ)·재처리 작업 조회(API-ING-07~12, ING-07.03). {@code dlq_items}·{@code raw_messages}·{@code reprocess_jobs}는 pipeline
 * 소유라 읽기만 한다. 실패 항목의 소스·기기는 원본 행에서 가져온다(dlq_items에 없음). 공간 범위가 제한된 사용자는 기기가 범위 안인 항목만 본다.
 */
@Repository
public class FailureRepository {

    private static final String FROM = """
             FROM data2flow_pipeline.dlq_items q
             LEFT JOIN data2flow_pipeline.raw_messages r ON r.id = q.raw_message_id AND r.received_at = q.raw_received_at
                                                        AND r.organization_id = q.organization_id
             LEFT JOIN data2flow_core.devices d ON d.id = r.device_id AND d.organization_id = q.organization_id
             LEFT JOIN data2flow_core.data_sources s ON s.id = r.source_id AND s.organization_id = q.organization_id
            """;
    private static final String FILTER = """
             WHERE q.organization_id = :org AND q.status = :status AND q.created_at >= :from AND q.created_at < :to
               AND (:unrestricted OR d.space_id = ANY(CAST(:allowed AS bigint[])))
            """;
    private static final String NARROW = """
               AND (:stage = '' OR q.stage = :stage) AND (:code = '' OR q.error_code = :code)
            """;

    private final JdbcClient jdbc;

    public FailureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 목록 조건(stage·errorCode는 빈 문자열이면 전체) */
    public record FailureFilter(long organizationId, String status, Instant from, Instant to, String stage, String errorCode, SpaceScope scope) {
    }

    /** 오류 코드별 묶음 */
    public List<GroupRow> findGroups(FailureFilter filter) {
        return bind(jdbc.sql("""
                        SELECT q.error_code, count(*) AS n, min(q.created_at) AS first_at, max(q.created_at) AS last_at,
                               (array_agg(q.error_message ORDER BY q.created_at DESC))[1] AS sample
                        """ + FROM + FILTER + NARROW + " GROUP BY q.error_code ORDER BY n DESC, q.error_code"), filter)
                .query((rs, n) -> new GroupRow(rs.getString("error_code"), rs.getLong("n"), Pg.instant(rs, "first_at"), Pg.instant(rs, "last_at"),
                        rs.getString("sample")))
                .list();
    }

    /** 단계별 건수(단계·오류 코드 조건 없이, 화면 탭 숫자) */
    public Map<String, Long> countByStage(FailureFilter filter) {
        Map<String, Long> counts = new LinkedHashMap<>();
        bind(jdbc.sql("SELECT q.stage, count(*) AS n " + FROM + FILTER + " GROUP BY q.stage"), filter)
                .query((rs, n) -> Map.entry(rs.getString("stage"), rs.getLong("n"))).list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    /** 개별 목록(최근 순) */
    public List<ItemRow> findPage(FailureFilter filter, long offset, int limit) {
        return bind(jdbc.sql("""
                        SELECT q.id, q.raw_message_id, q.stage, q.error_code, q.error_message, q.attempts, q.status, q.locked_by, q.created_at,
                               r.source_id, s.name AS source_name, r.device_id, d.name AS device_name
                        """ + FROM + FILTER + NARROW + " ORDER BY q.created_at DESC, q.id DESC OFFSET :offset LIMIT :limit"), filter)
                .param("offset", offset).param("limit", limit)
                .query(FailureRepository::item).list();
    }

    public long count(FailureFilter filter) {
        return bind(jdbc.sql("SELECT count(*) " + FROM + FILTER + NARROW), filter).query(Long.class).single();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, FailureFilter f) {
        return spec.param("org", f.organizationId()).param("status", f.status()).param("from", Pg.ts(f.from())).param("to", Pg.ts(f.to()))
                .param("stage", f.stage() == null ? "" : f.stage()).param("code", f.errorCode() == null ? "" : f.errorCode())
                .param("unrestricted", f.scope().unrestricted()).param("allowed", Pg.bigintArray(f.scope().allowedSpaceIds()));
    }

    /** 재처리·폐기 대상 실패 항목(조직 안). 기기 공간으로 범위를 서비스가 확인한다 */
    public List<DlqTarget> findDlqTargets(long organizationId, Collection<Long> ids) {
        return jdbc.sql("""
                        SELECT q.id, q.status, q.error_code, q.locked_by, q.locked_until, d.space_id, r.device_id
                        """ + FROM + """
                         WHERE q.organization_id = :org AND q.id = ANY(CAST(:ids AS bigint[]))""")
                .param("org", organizationId).param("ids", Pg.bigintArray(ids))
                .query((rs, n) -> new DlqTarget(rs.getLong("id"), rs.getString("status"), rs.getString("error_code"),
                        Pg.longOrNull(rs, "locked_by"), Pg.instant(rs, "locked_until"), Pg.longOrNull(rs, "space_id"),
                        Pg.longOrNull(rs, "device_id")))
                .list();
    }

    /** 재처리 대상 원본(조직 안, 보관 기간 안) */
    public List<RawTarget> findRawTargets(long organizationId, Collection<Long> ids, Instant since) {
        return jdbc.sql("""
                        SELECT r.id, r.status, r.error_code, d.space_id, r.device_id FROM data2flow_pipeline.raw_messages r
                          LEFT JOIN data2flow_core.devices d ON d.id = r.device_id AND d.organization_id = r.organization_id
                         WHERE r.organization_id = :org AND r.id = ANY(CAST(:ids AS bigint[])) AND r.received_at >= :since""")
                .param("org", organizationId).param("ids", Pg.bigintArray(ids)).param("since", Pg.ts(since))
                .query((rs, n) -> new RawTarget(rs.getLong("id"), rs.getString("status"), rs.getString("error_code"),
                        Pg.longOrNull(rs, "space_id"), Pg.longOrNull(rs, "device_id")))
                .list();
    }

    /** 재처리 미리 보기: 소스(·기기)·기간의 원본 처리 결과별 건수 */
    public Map<String, Long> countRawForReprocess(long organizationId, long sourceId, List<Long> deviceIds, Instant from, Instant to) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT status, count(*) AS n FROM data2flow_pipeline.raw_messages
                         WHERE organization_id = :org AND source_id = :source AND received_at >= :from AND received_at < :to
                           AND (cardinality(CAST(:devices AS bigint[])) = 0 OR device_id = ANY(CAST(:devices AS bigint[])))
                         GROUP BY status ORDER BY status""")
                .param("org", organizationId).param("source", sourceId).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .param("devices", Pg.bigintArray(deviceIds))
                .query((rs, n) -> Map.entry(rs.getString("status"), rs.getLong("n"))).list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    /** 소스의 디코더 키(조직 안, ARCHIVED 제외) */
    public Optional<String> findSourceDecoder(long organizationId, long sourceId) {
        return jdbc.sql("""
                        SELECT decoder_key FROM data2flow_core.data_sources
                         WHERE organization_id = :org AND id = :id AND lifecycle <> 'ARCHIVED'""")
                .param("org", organizationId).param("id", sourceId).query(String.class).optional();
    }

    /** 소스에 걸린 스크립트(소스 직접 연결 + 그 소스 기기의 모델·기기 연결)와 적용 버전 */
    public List<ScriptRow> findSourceScripts(long organizationId, long sourceId) {
        return jdbc.sql("""
                        SELECT DISTINCT b.target_type, sc.id, sc.name, v.version_no
                          FROM data2flow_core.script_bindings b
                          JOIN data2flow_core.scripts sc ON sc.id = b.script_id
                          LEFT JOIN data2flow_core.script_versions v ON v.id = sc.active_version_id
                         WHERE b.organization_id = :org AND b.enabled
                           AND ((b.target_type = 'SOURCE' AND b.target_id = CAST(:source AS varchar))
                             OR (b.target_type = 'MODEL' AND b.target_id IN (SELECT CAST(model_id AS varchar) FROM data2flow_core.devices
                                   WHERE organization_id = :org AND source_id = :source AND model_id IS NOT NULL))
                             OR (b.target_type = 'DEVICE' AND b.target_id IN (SELECT CAST(id AS varchar) FROM data2flow_core.devices
                                   WHERE organization_id = :org AND source_id = :source)))
                         ORDER BY b.target_type, sc.id""")
                .param("org", organizationId).param("source", sourceId)
                .query((rs, n) -> {
                    int version = rs.getInt("version_no");
                    return new ScriptRow(rs.getString("target_type"), rs.getLong("id"), rs.getString("name"), rs.getObject("version_no") == null ? null : version);
                })
                .list();
    }

    /** 같은 소스에 실행 중(PENDING·RUNNING) 재처리 작업이 있는가(BR-ING-12) */
    public boolean existsRunningJob(long organizationId, long sourceId) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_pipeline.reprocess_jobs
                         WHERE organization_id = :org AND source_id = :source AND status IN ('PENDING', 'RUNNING')""")
                .param("org", organizationId).param("source", sourceId).query(Long.class).single() > 0;
    }

    /** 재처리 작업 한 건 */
    public Optional<JobRow> findJob(long organizationId, long jobId) {
        return jdbc.sql("""
                        SELECT id, source_id, status, total, processed, failed, device_ids FROM data2flow_pipeline.reprocess_jobs
                         WHERE organization_id = :org AND id = :id""")
                .param("org", organizationId).param("id", jobId)
                .query((rs, n) -> new JobRow(rs.getLong("id"), rs.getLong("source_id"), rs.getString("status"), rs.getLong("total"),
                        rs.getLong("processed"), rs.getLong("failed"), Pg.longList(rs, "device_ids")))
                .optional();
    }

    /** 기기들의 공간(조직 안, 없는 기기는 빠짐) */
    public Map<Long, Long> findDeviceSpaces(long organizationId, Collection<Long> deviceIds) {
        Map<Long, Long> spaces = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT id, space_id FROM data2flow_core.devices
                         WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[])) AND status <> 'DELETED'""")
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds))
                .query((rs, n) -> new long[]{rs.getLong("id"), rs.getLong("space_id"), rs.wasNull() ? 1 : 0}).list()
                .forEach(r -> spaces.put(r[0], r[2] == 1 ? null : r[1]));
        return spaces;
    }

    private static ItemRow item(ResultSet rs, int n) throws SQLException {
        return new ItemRow(rs.getLong("id"), rs.getLong("raw_message_id"), rs.getString("stage"), rs.getString("error_code"),
                rs.getString("error_message"), rs.getInt("attempts"), rs.getString("status"), Pg.longOrNull(rs, "locked_by"),
                Pg.instant(rs, "created_at"), Pg.longOrNull(rs, "source_id"), rs.getString("source_name"), Pg.longOrNull(rs, "device_id"),
                rs.getString("device_name"));
    }

    /** 오류 코드 묶음 */
    public record GroupRow(String errorCode, long count, Instant firstAt, Instant lastAt, String sampleMessage) {
    }

    /** 실패 항목 한 행 */
    public record ItemRow(long id, long rawMessageId, String stage, String errorCode, String errorMessage, int attempts, String status,
                          Long lockedBy, Instant createdAt, Long sourceId, String sourceName, Long deviceId, String deviceName) {
    }

    /** 재처리·폐기 대상 실패 항목 */
    public record DlqTarget(long id, String status, String errorCode, Long lockedBy, Instant lockedUntil, Long spaceId, Long deviceId) {
    }

    /** 재처리 대상 원본 */
    public record RawTarget(long id, String status, String errorCode, Long spaceId, Long deviceId) {
    }

    /** 연결 스크립트 */
    public record ScriptRow(String scope, long scriptId, String name, Integer version) {
    }

    /** 재처리 작업 */
    public record JobRow(long id, long sourceId, String status, long total, long processed, long failed, List<Long> deviceIds) {
    }
}
