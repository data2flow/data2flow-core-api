package net.java21.data2flow.core.compliance.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 정기 발행 일정과 실행 기록 저장소(CMP-02.04, {@code compliance_report_schedules}·{@code compliance_report_schedule_runs}) */
@Repository
public class ReportScheduleRepository {

    private final JdbcClient jdbc;

    public ReportScheduleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 템플릿·수신자는 JSON 문자열 그대로 */
    public record ScheduleRow(long id, long organizationId, String name, String templateJson, String cadence, int dayOfPeriod, LocalTime publishTime,
                              String periodRule, String recipientsJson, String format, boolean enabled, Instant nextRunAt, int version,
                              long createdBy, Instant updatedAt) {
    }

    /** 저장할 값 */
    public record Values(String name, String templateJson, String cadence, int dayOfPeriod, LocalTime publishTime, String periodRule,
                         String recipientsJson, String format, boolean enabled, Instant nextRunAt) {
    }

    public record RunRow(long id, long organizationId, long scheduleId, Instant scheduledFor, String trigger, String status, int attempts,
                         Long reportId, Long revisionId, Integer revisionNo, String deliveriesJson, String error, Instant updatedAt) {
    }

    public long insert(long orgId, Values v, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.compliance_report_schedules (organization_id, name, report_template, cadence, day_of_period, publish_time,
                               period_rule, recipients, format, enabled, next_run_at, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :name, CAST(:template AS jsonb), :cadence, :day, :time, :rule, CAST(:recipients AS jsonb), :format, :enabled, :next,
                                :by, :by, :now, :now)
                        RETURNING id""")
                .params(values(v)).param("org", orgId).param("by", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    /** 버전이 맞을 때만 고친다(고친 행 수) */
    public int update(long orgId, long id, int baseVersion, Values v, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.compliance_report_schedules SET name = :name, report_template = CAST(:template AS jsonb), cadence = :cadence,
                               day_of_period = :day, publish_time = :time, period_rule = :rule, recipients = CAST(:recipients AS jsonb), format = :format,
                               enabled = :enabled, next_run_at = :next, version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :version""")
                .params(values(v)).param("org", orgId).param("id", id).param("version", baseVersion).param("by", userId).param("now", Pg.ts(now))
                .update();
    }

    public int delete(long orgId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.compliance_report_schedules WHERE organization_id = :org AND id = :id").param("org", orgId)
                .param("id", id).update();
    }

    public Optional<ScheduleRow> find(long orgId, long id) {
        return jdbc.sql(SCHEDULE + " WHERE organization_id = :org AND id = :id").param("org", orgId).param("id", id)
                .query(ReportScheduleRepository::schedule).optional();
    }

    /** 조직의 일정(이름 순) */
    public List<ScheduleRow> list(long orgId) {
        return jdbc.sql(SCHEDULE + " WHERE organization_id = :org ORDER BY name, id LIMIT 500").param("org", orgId)
                .query(ReportScheduleRepository::schedule).list();
    }

    /** 실행 시각이 된 켜진 일정 */
    public List<ScheduleRow> due(long orgId, Instant now) {
        return jdbc.sql(SCHEDULE + " WHERE organization_id = :org AND enabled AND next_run_at <= :now ORDER BY next_run_at, id")
                .param("org", orgId).param("now", Pg.ts(now)).query(ReportScheduleRepository::schedule).list();
    }

    public void advance(long orgId, long id, Instant next) {
        jdbc.sql("UPDATE data2flow_core.compliance_report_schedules SET next_run_at = :next WHERE organization_id = :org AND id = :id")
                .param("org", orgId).param("id", id).param("next", Pg.ts(next)).update();
    }

    /** 회차를 넣는다. 같은 회차가 이미 있으면 빈 값 */
    public Optional<Long> insertRun(long orgId, long scheduleId, Instant scheduledFor, String trigger, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.compliance_report_schedule_runs (organization_id, schedule_id, scheduled_for, trigger, created_at, updated_at)
                        VALUES (:org, :schedule, :at, :trigger, :now, :now)
                        ON CONFLICT (schedule_id, scheduled_for) DO NOTHING
                        RETURNING id""")
                .param("org", orgId).param("schedule", scheduleId).param("at", Pg.ts(scheduledFor)).param("trigger", trigger).param("now", Pg.ts(now))
                .query(Long.class).optional();
    }

    public Optional<RunRow> run(long orgId, long id) {
        return jdbc.sql(RUN + " WHERE organization_id = :org AND id = :id").param("org", orgId).param("id", id)
                .query(ReportScheduleRepository::run).optional();
    }

    /** 끝나지 않은 회차(오래된 순) */
    public List<RunRow> activeRuns(long orgId) {
        return jdbc.sql(RUN + " WHERE organization_id = :org AND status IN ('PENDING', 'RUNNING') ORDER BY scheduled_for, id LIMIT 100")
                .param("org", orgId).query(ReportScheduleRepository::run).list();
    }

    /** 일정의 회차(최근부터) */
    public List<RunRow> runs(long orgId, long scheduleId, int limit, long offset) {
        return jdbc.sql(RUN + " WHERE organization_id = :org AND schedule_id = :schedule ORDER BY scheduled_for DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("org", orgId).param("schedule", scheduleId).param("limit", limit).param("offset", offset)
                .query(ReportScheduleRepository::run).list();
    }

    public long countRuns(long orgId, long scheduleId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.compliance_report_schedule_runs WHERE organization_id = :org AND schedule_id = :schedule")
                .param("org", orgId).param("schedule", scheduleId).query(Long.class).single();
    }

    /** 일정마다 마지막 회차 */
    public Map<Long, RunRow> lastRuns(long orgId, Collection<Long> scheduleIds) {
        Map<Long, RunRow> out = new HashMap<>();
        if (scheduleIds.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT DISTINCT ON (schedule_id) " + RUN.substring("SELECT ".length()) + """
                         WHERE organization_id = :org AND schedule_id = ANY(CAST(:ids AS bigint[]))
                         ORDER BY schedule_id, scheduled_for DESC, id DESC""")
                .param("org", orgId).param("ids", Pg.bigintArray(scheduleIds)).query(ReportScheduleRepository::run).list()
                .forEach(r -> out.put(r.scheduleId(), r));
        return out;
    }

    /** 생성 요청 시도 +1(시도 수) */
    public int attempt(long orgId, long runId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.compliance_report_schedule_runs SET attempts = attempts + 1, updated_at = :now
                         WHERE organization_id = :org AND id = :id RETURNING attempts""")
                .param("org", orgId).param("id", runId).param("now", Pg.ts(now)).query(Integer.class).single();
    }

    /** 생성을 요청했다: RUNNING */
    public void markRunning(long orgId, long runId, long reportId, long revisionId, int revisionNo, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.compliance_report_schedule_runs SET status = 'RUNNING', report_id = :report,
                               revision_id = :revision, revision_no = :no, error = NULL, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("org", orgId).param("id", runId).param("report", reportId).param("revision", revisionId).param("no", revisionNo)
                .param("now", Pg.ts(now)).update();
    }

    /** 다시 요청할 수 있게 PENDING으로(사유를 남긴다) */
    public void retry(long orgId, long runId, String error, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.compliance_report_schedule_runs SET status = 'PENDING', error = :error, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("org", orgId).param("id", runId).param("error", cut(error)).param("now", Pg.ts(now)).update();
    }

    public void finish(long orgId, long runId, String status, String deliveriesJson, String error, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.compliance_report_schedule_runs SET status = :status, deliveries = CAST(:deliveries AS jsonb), error = :error,
                               updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("org", orgId).param("id", runId).param("status", status).param("deliveries", deliveriesJson).param("error", cut(error))
                .param("now", Pg.ts(now)).update();
    }

    /** 일정을 만든 사람의 이름 */
    public Optional<String> userName(long orgId, long userId) {
        return jdbc.sql("SELECT name FROM data2flow_core.app_users WHERE organization_id = :org AND id = :id").param("org", orgId)
                .param("id", userId).query(String.class).optional();
    }

    /** 조직에 있는 사용자 ID만 */
    public List<Long> existingUsers(long orgId, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id FROM data2flow_core.app_users WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))")
                .param("org", orgId).param("ids", Pg.bigintArray(ids)).query(Long.class).list();
    }

    private static String cut(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }

    private static Map<String, Object> values(Values v) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", v.name());
        p.put("template", v.templateJson());
        p.put("cadence", v.cadence());
        p.put("day", v.dayOfPeriod());
        p.put("time", v.publishTime());
        p.put("rule", v.periodRule());
        p.put("recipients", v.recipientsJson());
        p.put("format", v.format());
        p.put("enabled", v.enabled());
        p.put("next", v.nextRunAt() == null ? null : Pg.ts(v.nextRunAt()));
        return p;
    }

    private static final String SCHEDULE = """
            SELECT id, organization_id, name, report_template::text AS template, cadence, day_of_period, publish_time, period_rule,
                   recipients::text AS recipients, format, enabled, next_run_at, version, created_by, updated_at
              FROM data2flow_core.compliance_report_schedules
            """;

    private static final String RUN = """
            SELECT id, organization_id, schedule_id, scheduled_for, trigger, status, attempts, report_id, revision_id, revision_no,
                   deliveries::text AS deliveries, error, updated_at
              FROM data2flow_core.compliance_report_schedule_runs
            """;

    static ScheduleRow schedule(ResultSet rs, int n) throws SQLException {
        return new ScheduleRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("template"), rs.getString("cadence"),
                rs.getInt("day_of_period"), rs.getObject("publish_time", LocalTime.class), rs.getString("period_rule"), rs.getString("recipients"),
                rs.getString("format"), rs.getBoolean("enabled"), Pg.instant(rs, "next_run_at"), rs.getInt("version"), rs.getLong("created_by"),
                Pg.instant(rs, "updated_at"));
    }

    static RunRow run(ResultSet rs, int n) throws SQLException {
        Integer no = (Integer) rs.getObject("revision_no");
        return new RunRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("schedule_id"), Pg.instant(rs, "scheduled_for"),
                rs.getString("trigger"), rs.getString("status"), rs.getInt("attempts"), Pg.longOrNull(rs, "report_id"),
                Pg.longOrNull(rs, "revision_id"), no, rs.getString("deliveries"), rs.getString("error"), Pg.instant(rs, "updated_at"));
    }
}
