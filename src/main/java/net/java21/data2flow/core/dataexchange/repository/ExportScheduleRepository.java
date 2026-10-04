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

/** 정기 내보내기({@code export_schedules})와 실행({@code export_schedule_runs}) — TSD-04.03·07.02, BR-TSD-28 */
@Repository
public class ExportScheduleRepository {

    private static final String SELECT = """
            SELECT id, organization_id, name, query, format, cron, relative_period, delivery, recipients, target_type, target, credential_ref,
                   credential_enc, last_version, last_status, last_error, enabled, last_run_at, next_run_at, version, created_by
              FROM data2flow_core.export_schedules""";
    private static final String RUN_SELECT = """
            SELECT id, organization_id, schedule_id, period_from, period_to, file_version, job_id, status, attempts, next_attempt_at,
                   file_name, error, stale FROM data2flow_core.export_schedule_runs
            """;

    private final JdbcClient jdbc;

    public ExportScheduleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ScheduleRow(long id, long organizationId, String name, String queryJson, String format, String cron, String relativePeriod,
                              String delivery, List<String> recipients, String targetType, String targetJson, String credentialRef,
                              byte[] credentialEnc, int lastVersion, String lastStatus, String lastError, boolean enabled, Instant lastRunAt,
                              Instant nextRunAt, int version, long createdBy) {
    }

    public record RunRow(long id, long organizationId, long scheduleId, Instant periodFrom, Instant periodTo, int fileVersion, Long jobId,
                         String status, int attempts, Instant nextAttemptAt, String fileName, String error, boolean stale) {
    }

    /** 쓰기 값 묶음 */
    public record ScheduleValues(String name, String queryJson, String format, String cron, String relativePeriod, String delivery,
                                 List<String> recipients, String targetType, String targetJson, String credentialRef, byte[] credentialEnc,
                                 boolean enabled, Instant nextRunAt) {
    }

    public long insert(long organizationId, ScheduleValues v, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.export_schedules (organization_id, name, query, format, cron, relative_period, delivery,
                               recipients, target_type, target, credential_ref, credential_enc, enabled, next_run_at, created_by, updated_by,
                               created_at, updated_at)
                        VALUES (:org, :name, CAST(:query AS jsonb), :format, :cron, :period, :delivery, CAST(:recipients AS text[]), :ttype,
                                CAST(:target AS jsonb), :cref, :cenc, :enabled, :next, :user, :user, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("name", v.name()).param("query", v.queryJson()).param("format", v.format())
                .param("cron", v.cron()).param("period", v.relativePeriod()).param("delivery", v.delivery())
                .param("recipients", v.recipients() == null ? null : Pg.textArray(v.recipients())).param("ttype", v.targetType())
                .param("target", v.targetJson()).param("cref", v.credentialRef()).param("cenc", v.credentialEnc())
                .param("enabled", v.enabled()).param("next", Pg.ts(v.nextRunAt())).param("user", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    /** 낙관적 잠금으로 고침. 버전이 다르면 0 */
    public int update(long organizationId, long id, int baseVersion, ScheduleValues v, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.export_schedules SET name = :name, query = CAST(:query AS jsonb), format = :format, cron = :cron,
                               relative_period = :period, delivery = :delivery, recipients = CAST(:recipients AS text[]), target_type = :ttype,
                               target = CAST(:target AS jsonb), credential_ref = :cref, credential_enc = :cenc, enabled = :enabled,
                               next_run_at = :next, version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .param("org", organizationId).param("id", id).param("base", baseVersion).param("name", v.name()).param("query", v.queryJson())
                .param("format", v.format()).param("cron", v.cron()).param("period", v.relativePeriod()).param("delivery", v.delivery())
                .param("recipients", v.recipients() == null ? null : Pg.textArray(v.recipients())).param("ttype", v.targetType())
                .param("target", v.targetJson()).param("cref", v.credentialRef()).param("cenc", v.credentialEnc())
                .param("enabled", v.enabled()).param("next", Pg.ts(v.nextRunAt())).param("user", userId).param("now", Pg.ts(now))
                .update();
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.export_schedules WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    public Optional<ScheduleRow> findById(long organizationId, long id) {
        return jdbc.sql(SELECT + " WHERE organization_id = :org AND id = :id").param("org", organizationId).param("id", id)
                .query(ExportScheduleRepository::map).optional();
    }

    public boolean existsName(long organizationId, String name, Long exceptId) {
        return jdbc.sql("""
                        SELECT count(*) > 0 FROM data2flow_core.export_schedules
                         WHERE organization_id = :org AND name = :name AND (CAST(:except AS bigint) IS NULL OR id <> :except)""")
                .param("org", organizationId).param("name", name).param("except", exceptId).query(Boolean.class).single();
    }

    public List<ScheduleRow> list(long organizationId, Long createdBy, int limit, long offset) {
        return jdbc.sql(SELECT + " WHERE organization_id = :org AND (CAST(:by AS bigint) IS NULL OR created_by = :by)"
                        + " ORDER BY name, id LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("by", createdBy).param("limit", limit).param("offset", offset)
                .query(ExportScheduleRepository::map).list();
    }

    public long count(long organizationId, Long createdBy) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.export_schedules WHERE organization_id = :org"
                        + " AND (CAST(:by AS bigint) IS NULL OR created_by = :by)")
                .param("org", organizationId).param("by", createdBy).query(Long.class).single();
    }

    /** 때가 된 일정을 잡는다(행 잠금, 여러 파드 중 하나만) */
    @OrganizationScopeExempt("정기 실행기: 모든 조직의 때가 된 일정")
    public List<ScheduleRow> lockDue(Instant now, int limit) {
        return jdbc.sql(SELECT + " WHERE enabled AND next_run_at <= :now ORDER BY next_run_at LIMIT :limit FOR UPDATE SKIP LOCKED")
                .param("now", Pg.ts(now)).param("limit", limit).query(ExportScheduleRepository::map).list();
    }

    public void updateNextRun(long organizationId, long id, Instant nextRunAt, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.export_schedules SET next_run_at = :next, last_run_at = :now, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("org", organizationId).param("id", id).param("next", Pg.ts(nextRunAt)).param("now", Pg.ts(now)).update();
    }

    public void updateResult(long organizationId, long id, String status, String error, Integer fileVersion, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.export_schedules SET last_status = :status, last_error = :error,
                               last_version = COALESCE(:fv, last_version), updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("org", organizationId).param("id", id).param("status", status).param("error", ExportJobRepository.truncate(error))
                .param("fv", fileVersion).param("now", Pg.ts(now)).update();
    }

    /** 조직 표시 시간대(org_settings → organizations → Asia/Seoul)와 코드 */
    public record OrgInfo(String code, String timezone, String locale) {
    }

    public OrgInfo findOrganization(long organizationId) {
        return jdbc.sql("""
                        SELECT o.code, COALESCE(s.timezone, o.timezone, 'Asia/Seoul') AS tz, COALESCE(s.locale, o.locale, 'ko') AS locale
                          FROM data2flow_core.organizations o LEFT JOIN data2flow_core.org_settings s ON s.organization_id = o.id
                         WHERE o.id = :org""")
                .param("org", organizationId).query((rs, n) -> new OrgInfo(rs.getString("code"), rs.getString("tz"), rs.getString("locale")))
                .optional().orElse(new OrgInfo("org" + organizationId, "Asia/Seoul", "ko"));
    }

    // ------------------------------------------------------------------ 실행

    /** 다음 판 번호(같은 일정·같은 기간) */
    public int findNextFileVersion(long organizationId, long scheduleId, Instant periodFrom) {
        return jdbc.sql("""
                        SELECT COALESCE(max(file_version), 0) + 1 FROM data2flow_core.export_schedule_runs
                         WHERE organization_id = :org AND schedule_id = :s AND period_from = :from""")
                .param("org", organizationId).param("s", scheduleId).param("from", Pg.ts(periodFrom)).query(Integer.class).single();
    }

    public long insertRun(long organizationId, long scheduleId, Instant from, Instant to, int fileVersion, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.export_schedule_runs (organization_id, schedule_id, period_from, period_to, file_version, status,
                               next_attempt_at, created_at, updated_at)
                        VALUES (:org, :s, :from, :to, :v, 'PENDING', :now, :now, :now) RETURNING id""")
                .param("org", organizationId).param("s", scheduleId).param("from", Pg.ts(from)).param("to", Pg.ts(to)).param("v", fileVersion)
                .param("now", Pg.ts(now)).query(Long.class).single();
    }

    /** 재계산으로 다시 만들어야 하는 기간(최신 판이 성공했고 stale) */
    public List<RunRow> findStale(long organizationId, long scheduleId) {
        return jdbc.sql(RUN_SELECT + """
                         WHERE organization_id = :org AND schedule_id = :s AND stale AND status = 'SUCCEEDED'
                         ORDER BY period_from""")
                .param("org", organizationId).param("s", scheduleId).query(ExportScheduleRepository::mapRun).list();
    }

    public void updateRunStale(long organizationId, long runId, boolean stale, Instant now) {
        jdbc.sql("UPDATE data2flow_core.export_schedule_runs SET stale = :stale, updated_at = :now WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", runId).param("stale", stale).param("now", Pg.ts(now)).update();
    }

    /** 재계산(EVT-TSD-03)이 겹친 성공 실행을 stale로(최근 31일) */
    public int updateStaleOverlapping(long organizationId, Instant from, Instant to, Instant since, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.export_schedule_runs SET stale = true, updated_at = :now
                         WHERE organization_id = :org AND status = 'SUCCEEDED' AND period_from < :to AND period_to > :from
                           AND created_at >= :since
                           AND file_version = (SELECT max(r2.file_version) FROM data2flow_core.export_schedule_runs r2
                                                WHERE r2.schedule_id = export_schedule_runs.schedule_id
                                                  AND r2.period_from = export_schedule_runs.period_from)""")
                .param("org", organizationId).param("from", Pg.ts(from)).param("to", Pg.ts(to)).param("since", Pg.ts(since))
                .param("now", Pg.ts(now)).update();
    }

    /** 실행할 차례인 실행을 잡는다(행 잠금) */
    @OrganizationScopeExempt("정기 실행기: 모든 조직의 대기·재시도 실행")
    public List<RunRow> lockRunnable(Instant now, int limit) {
        return jdbc.sql(RUN_SELECT + """
                         WHERE status IN ('PENDING', 'RETRYING') AND next_attempt_at <= :now
                         ORDER BY next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED""")
                .param("now", Pg.ts(now)).param("limit", limit).query(ExportScheduleRepository::mapRun).list();
    }

    public void updateRun(long organizationId, long runId, String status, int attempts, Instant nextAttemptAt, Long jobId, String fileName,
                          String error, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.export_schedule_runs SET status = :status, attempts = :attempts, next_attempt_at = :next,
                               job_id = COALESCE(:job, job_id), file_name = COALESCE(:file, file_name), error = :error, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("org", organizationId).param("id", runId).param("status", status).param("attempts", attempts)
                .param("next", Pg.ts(nextAttemptAt)).param("job", jobId).param("file", fileName).param("error", ExportJobRepository.truncate(error))
                .param("now", Pg.ts(now)).update();
    }

    public List<RunRow> listRuns(long organizationId, long scheduleId) {
        return jdbc.sql(RUN_SELECT + " WHERE organization_id = :org AND schedule_id = :s ORDER BY period_from, file_version")
                .param("org", organizationId).param("s", scheduleId).query(ExportScheduleRepository::mapRun).list();
    }

    static ScheduleRow map(ResultSet rs, int n) throws SQLException {
        return new ScheduleRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("query"),
                rs.getString("format"), rs.getString("cron"), rs.getString("relative_period"), rs.getString("delivery"),
                Pg.stringList(rs, "recipients"), rs.getString("target_type"), rs.getString("target"), rs.getString("credential_ref"),
                rs.getBytes("credential_enc"), rs.getInt("last_version"), rs.getString("last_status"), rs.getString("last_error"),
                rs.getBoolean("enabled"), Pg.instant(rs, "last_run_at"), Pg.instant(rs, "next_run_at"), rs.getInt("version"),
                rs.getLong("created_by"));
    }

    static RunRow mapRun(ResultSet rs, int n) throws SQLException {
        return new RunRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("schedule_id"), Pg.instant(rs, "period_from"),
                Pg.instant(rs, "period_to"), rs.getInt("file_version"), Pg.longOrNull(rs, "job_id"), rs.getString("status"),
                rs.getInt("attempts"), Pg.instant(rs, "next_attempt_at"), rs.getString("file_name"), rs.getString("error"),
                rs.getBoolean("stale"));
    }
}
