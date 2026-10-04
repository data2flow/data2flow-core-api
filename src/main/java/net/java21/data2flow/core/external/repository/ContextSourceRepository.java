package net.java21.data2flow.core.external.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 외부 맥락 소스(DSC-06): {@code data_sources}의 KMA_WEATHER·AIRKOREA·HOLIDAY·ICAL 행(사이트당 유형별 1개, BR-DSC-15), 동기화 상태
 * ({@code context_source_syncs}), 일일 호출량({@code source_api_usage_daily}), 업로드한 iCal 파일({@code ical_files}).
 */
@Repository
public class ContextSourceRepository {

    public static final String TYPES_SQL = "('KMA_WEATHER','AIRKOREA','HOLIDAY','ICAL')";
    static final String COLUMNS = "id, organization_id, code, name, type, lifecycle, connection, site_id, version, updated_at";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ContextSourceRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 외부 맥락 소스 한 행 */
    public record ContextSource(long id, long organizationId, String code, String name, String type, String lifecycle, JsonNode connection,
                                Long siteId, int version, Instant updatedAt) {
    }

    /** 동기화 상태 */
    public record SyncState(long sourceId, Instant nextDueAt, int retryCount, Instant lastAttemptAt, Instant lastSuccessAt,
                            String lastStatus, String lastError, int added, int updated, int removed) {
    }

    /** 하루 호출량 */
    public record UsageRow(LocalDate day, int calls, int failures, Integer quota, Instant warnedAt) {
    }

    public Optional<ContextSource> findById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.data_sources WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).query(this::map).optional();
    }

    /** 내부(이벤트·ingress): 소스 ID로 조직을 정한다. 배포 조직으로 좁힌다(ADR-030) */
    @OrganizationScopeExempt("내부 이벤트·API는 소스 ID로 조직을 정한다. 배포 조직 조건으로 좁힌다(ADR-030)")
    public Optional<ContextSource> findInternal(long id, OptionalLong restriction) {
        Long org = restriction.isPresent() ? restriction.getAsLong() : null;
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.data_sources WHERE id = :id"
                        + " AND (CAST(:org AS bigint) IS NULL OR organization_id = :org)")
                .param("id", id).param("org", org).query(this::map).optional();
    }

    /** 사이트의 외부 맥락 소스(보관 제외) */
    public List<ContextSource> listBySite(long organizationId, long siteId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.data_sources WHERE organization_id = :org AND site_id = :site"
                        + " AND type IN " + TYPES_SQL + " AND lifecycle <> 'ARCHIVED' ORDER BY id")
                .param("org", organizationId).param("site", siteId).query(this::map).list();
    }

    public long insert(long organizationId, String code, String name, String type, String lifecycle, JsonNode connection, String decoderKey,
                       long siteId, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.data_sources (organization_id, code, name, type, lifecycle, connection, decoder_key, site_id,
                            unknown_device_policy, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :code, :name, :type, :lifecycle, CAST(:conn AS jsonb), :decoder, :site, 'REJECT', :by, :by, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("code", code).param("name", name).param("type", type).param("lifecycle", lifecycle)
                .param("conn", json.writeValueAsString(connection)).param("decoder", decoderKey).param("site", siteId)
                .param("by", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public int update(long organizationId, long id, String lifecycle, JsonNode connection, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.data_sources SET lifecycle = :lifecycle, connection = CAST(:conn AS jsonb), version = version + 1,
                               updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("lifecycle", lifecycle).param("conn", json.writeValueAsString(connection)).param("by", userId)
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    public boolean existsCode(long organizationId, String code) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.data_sources WHERE organization_id = :org AND code = :code)")
                .param("org", organizationId).param("code", code).query(Boolean.class).single();
    }

    // ---------------------------------------------------------------- 동기화

    /** 때가 된 ACTIVE HOLIDAY·ICAL 소스(배포 조직) */
    @OrganizationScopeExempt("주기 작업: 배포 조직 목록(InternalOrganizations)으로 좁힌다")
    public List<ContextSource> listDue(Collection<Long> organizations, Instant now, int limit) {
        if (organizations.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT s.id, s.organization_id, s.code, s.name, s.type, s.lifecycle, s.connection, s.site_id, s.version, s.updated_at"
                        + " FROM data2flow_core.data_sources s"
                        + " LEFT JOIN data2flow_core.context_source_syncs c ON c.source_id = s.id"
                        + " WHERE s.organization_id = ANY(CAST(:orgs AS bigint[])) AND s.type IN ('HOLIDAY','ICAL') AND s.lifecycle = 'ACTIVE'"
                        + " AND (c.next_due_at IS NULL OR c.next_due_at <= :now) ORDER BY c.next_due_at NULLS FIRST, s.id LIMIT :limit")
                .param("orgs", Pg.bigintArray(organizations)).param("now", Pg.ts(now)).param("limit", limit).query(this::map).list();
    }

    public Optional<SyncState> findSync(long organizationId, long sourceId) {
        return jdbc.sql("SELECT * FROM data2flow_core.context_source_syncs WHERE organization_id = :org AND source_id = :id")
                .param("org", organizationId).param("id", sourceId).query(ContextSourceRepository::sync).optional();
    }

    public void saveSync(long organizationId, SyncState s, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.context_source_syncs (source_id, organization_id, next_due_at, retry_count, last_attempt_at,
                            last_success_at, last_status, last_error, added, updated, removed, updated_at)
                        VALUES (:id, :org, :next, :retry, :attempt, :success, :status, :error, :added, :updated, :removed, :now)
                        ON CONFLICT (source_id) DO UPDATE SET next_due_at = EXCLUDED.next_due_at, retry_count = EXCLUDED.retry_count,
                            last_attempt_at = EXCLUDED.last_attempt_at, last_success_at = EXCLUDED.last_success_at,
                            last_status = EXCLUDED.last_status, last_error = EXCLUDED.last_error, added = EXCLUDED.added,
                            updated = EXCLUDED.updated, removed = EXCLUDED.removed, updated_at = EXCLUDED.updated_at""")
                .param("id", s.sourceId()).param("org", organizationId).param("next", s.nextDueAt() == null ? null : Pg.ts(s.nextDueAt()))
                .param("retry", s.retryCount()).param("attempt", s.lastAttemptAt() == null ? null : Pg.ts(s.lastAttemptAt()))
                .param("success", s.lastSuccessAt() == null ? null : Pg.ts(s.lastSuccessAt())).param("status", s.lastStatus())
                .param("error", s.lastError()).param("added", s.added()).param("updated", s.updated()).param("removed", s.removed())
                .param("now", Pg.ts(now)).update();
    }

    /** 때가 된 소스를 이 파드가 맡는다(다음 실행을 lease로 미룸). 다른 파드가 먼저 맡았으면 false */
    public boolean claim(long organizationId, long sourceId, Instant now, Instant lease) {
        return !jdbc.sql("""
                        INSERT INTO data2flow_core.context_source_syncs (source_id, organization_id, next_due_at, updated_at)
                        VALUES (:id, :org, :lease, :now)
                        ON CONFLICT (source_id) DO UPDATE SET next_due_at = EXCLUDED.next_due_at, updated_at = EXCLUDED.updated_at
                         WHERE context_source_syncs.next_due_at IS NULL OR context_source_syncs.next_due_at <= :now
                        RETURNING source_id""")
                .param("id", sourceId).param("org", organizationId).param("lease", Pg.ts(lease)).param("now", Pg.ts(now))
                .query(Long.class).list().isEmpty();
    }

    /** 다음 실행만 당긴다(설정 변경·지금 갱신) */
    public void scheduleNow(long organizationId, long sourceId, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.context_source_syncs (source_id, organization_id, next_due_at, updated_at)
                        VALUES (:id, :org, :now, :now)
                        ON CONFLICT (source_id) DO UPDATE SET next_due_at = EXCLUDED.next_due_at, retry_count = 0, updated_at = EXCLUDED.updated_at""")
                .param("id", sourceId).param("org", organizationId).param("now", Pg.ts(now)).update();
    }

    // ---------------------------------------------------------------- 호출량

    /** 더하고 결과 행을 돌려준다 */
    public UsageRow addUsage(long organizationId, long sourceId, LocalDate day, int calls, int failures, Integer quota) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.source_api_usage_daily (source_id, day, organization_id, calls, failures, quota)
                        VALUES (:id, :day, :org, :calls, :failures, :quota)
                        ON CONFLICT (source_id, day) DO UPDATE SET calls = source_api_usage_daily.calls + EXCLUDED.calls,
                            failures = source_api_usage_daily.failures + EXCLUDED.failures, quota = EXCLUDED.quota
                        RETURNING day, calls, failures, quota, warned_at""")
                .param("id", sourceId).param("day", day).param("org", organizationId).param("calls", calls).param("failures", failures)
                .param("quota", quota).query(ContextSourceRepository::usage).single();
    }

    public Optional<UsageRow> findUsage(long organizationId, long sourceId, LocalDate day) {
        return jdbc.sql("SELECT day, calls, failures, quota, warned_at FROM data2flow_core.source_api_usage_daily"
                        + " WHERE organization_id = :org AND source_id = :id AND day = :day")
                .param("org", organizationId).param("id", sourceId).param("day", day).query(ContextSourceRepository::usage).optional();
    }

    /** 경고를 처음 내는 것이면 true(하루 1회, AT-DSC-09.4) */
    public boolean markWarned(long organizationId, long sourceId, LocalDate day, Instant now) {
        return jdbc.sql("UPDATE data2flow_core.source_api_usage_daily SET warned_at = :now"
                        + " WHERE organization_id = :org AND source_id = :id AND day = :day AND warned_at IS NULL")
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", sourceId).param("day", day).update() == 1;
    }

    public List<UsageRow> listUsage(long organizationId, long sourceId, LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT day, calls, failures, quota, warned_at FROM data2flow_core.source_api_usage_daily"
                        + " WHERE organization_id = :org AND source_id = :id AND day BETWEEN :from AND :to ORDER BY day")
                .param("org", organizationId).param("id", sourceId).param("from", from).param("to", to)
                .query(ContextSourceRepository::usage).list();
    }

    // ---------------------------------------------------------------- iCal 파일

    public long insertIcalFile(long organizationId, String fileName, byte[] data, int eventCount, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.ical_files (organization_id, file_name, data, event_count, uploaded_by, created_at)
                        VALUES (:org, :name, :data, :count, :by, :now) RETURNING id""")
                .param("org", organizationId).param("name", fileName).param("data", data).param("count", eventCount)
                .param("by", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public Optional<byte[]> findIcalFile(long organizationId, long id) {
        return jdbc.sql("SELECT data FROM data2flow_core.ical_files WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).query((rs, n) -> rs.getBytes("data")).optional();
    }

    private ContextSource map(ResultSet rs, int n) throws SQLException {
        String conn = rs.getString("connection");
        return new ContextSource(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("code"), rs.getString("name"),
                rs.getString("type"), rs.getString("lifecycle"), conn == null ? json.createObjectNode() : json.readTree(conn),
                Pg.longOrNull(rs, "site_id"), rs.getInt("version"), Pg.instant(rs, "updated_at"));
    }

    static SyncState sync(ResultSet rs, int n) throws SQLException {
        return new SyncState(rs.getLong("source_id"), Pg.instant(rs, "next_due_at"), rs.getInt("retry_count"),
                Pg.instant(rs, "last_attempt_at"), Pg.instant(rs, "last_success_at"), rs.getString("last_status"),
                rs.getString("last_error"), rs.getInt("added"), rs.getInt("updated"), rs.getInt("removed"));
    }

    static UsageRow usage(ResultSet rs, int n) throws SQLException {
        return new UsageRow(rs.getObject("day", LocalDate.class), rs.getInt("calls"), rs.getInt("failures"),
                (Integer) rs.getObject("quota"), Pg.instant(rs, "warned_at"));
    }
}
