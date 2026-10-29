package net.java21.data2flow.core.compliance.repository;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository.ExcursionRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 개선 조치 저장소(CMP-02.05, {@code corrective_actions}). 조회는 이탈 구간과 조치자 이름을 함께 읽는다 */
@Repository
public class CorrectiveActionRepository {

    private final JdbcClient jdbc;

    public CorrectiveActionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ActionRow(long id, ExcursionRow excursion, String cause, String causeNote, String actionText, long actorUserId, String actorName,
                            Instant actedAt, List<Long> linkedAlarmIds, List<Long> linkedWorkOrderIds, String resultMode, BigDecimal resultRatio24h,
                            String resultNote, Instant resultCheckedAt, Instant createdAt, Instant updatedAt) {
    }

    /** 저장할 값 */
    public record Values(String cause, String causeNote, String actionText, long actorUserId, Instant actedAt, List<Long> linkedAlarmIds,
                         List<Long> linkedWorkOrderIds, String resultMode, String resultNote) {
    }

    /** 목록 조건. spaceIds가 null이면 공간으로 거르지 않는다 */
    public record Filter(Collection<Long> spaceIds, Instant from, Instant to, String cause) {
    }

    public Optional<ExcursionRow> excursion(long orgId, long id) {
        return jdbc.sql("""
                        SELECT id, space_id, profile_version_id, metric_key, started_at, ended_at, peak_value, alarm_id
                          FROM data2flow_core.compliance_excursions WHERE organization_id = :org AND id = :id""")
                .param("org", orgId).param("id", id).query(ComplianceStatusRepository::excursion).optional();
    }

    public long insert(long orgId, long excursionId, Values v, long createdBy, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.corrective_actions (organization_id, excursion_id, cause, cause_note, action_text, actor_user_id,
                               acted_at, linked_alarm_ids, linked_work_order_ids, result_mode, result_note, created_by, created_at, updated_at)
                        VALUES (:org, :excursion, :cause, :causeNote, :text, :actor, :actedAt, CAST(:alarms AS bigint[]), CAST(:orders AS bigint[]),
                                :mode, :note, :by, :now, :now)
                        RETURNING id""")
                .param("org", orgId).param("excursion", excursionId).param("cause", v.cause()).param("causeNote", v.causeNote())
                .param("text", v.actionText()).param("actor", v.actorUserId()).param("actedAt", Pg.ts(v.actedAt()))
                .param("alarms", Pg.bigintArray(v.linkedAlarmIds())).param("orders", Pg.bigintArray(v.linkedWorkOrderIds()))
                .param("mode", v.resultMode()).param("note", v.resultNote()).param("by", createdBy).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    /** 고친다. 조치 시각이나 결과 방식이 바뀌면 자동 확인을 다시 한다(결과를 비운다) */
    public int update(long orgId, long id, Values v, boolean resetResult, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.corrective_actions SET cause = :cause, cause_note = :causeNote, action_text = :text, actor_user_id = :actor,
                               acted_at = :actedAt, linked_alarm_ids = CAST(:alarms AS bigint[]), linked_work_order_ids = CAST(:orders AS bigint[]),
                               result_mode = :mode, result_note = :note,
                               result_ratio_24h = CASE WHEN :reset THEN NULL ELSE result_ratio_24h END,
                               result_checked_at = CASE WHEN :reset THEN NULL ELSE result_checked_at END, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("org", orgId).param("id", id).param("cause", v.cause()).param("causeNote", v.causeNote()).param("text", v.actionText())
                .param("actor", v.actorUserId()).param("actedAt", Pg.ts(v.actedAt())).param("alarms", Pg.bigintArray(v.linkedAlarmIds()))
                .param("orders", Pg.bigintArray(v.linkedWorkOrderIds())).param("mode", v.resultMode()).param("note", v.resultNote())
                .param("reset", resetResult).param("now", Pg.ts(now)).update();
    }

    public int delete(long orgId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.corrective_actions WHERE organization_id = :org AND id = :id").param("org", orgId).param("id", id)
                .update();
    }

    public Optional<ActionRow> find(long orgId, long id) {
        return jdbc.sql(SELECT + " WHERE a.organization_id = :org AND a.id = :id").param("org", orgId).param("id", id)
                .query(CorrectiveActionRepository::row).optional();
    }

    /** 이탈 구간들의 조치(조치 시각 순) */
    public List<ActionRow> byExcursions(long orgId, Collection<Long> excursionIds) {
        if (excursionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " WHERE a.organization_id = :org AND a.excursion_id = ANY(CAST(:ids AS bigint[])) ORDER BY a.acted_at, a.id")
                .param("org", orgId).param("ids", Pg.bigintArray(excursionIds)).query(CorrectiveActionRepository::row).list();
    }

    /** 조치가 하나라도 있는 이탈 구간 */
    public Set<Long> excursionsWithActions(long orgId, Collection<Long> excursionIds) {
        if (excursionIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                        SELECT DISTINCT excursion_id FROM data2flow_core.corrective_actions
                         WHERE organization_id = :org AND excursion_id = ANY(CAST(:ids AS bigint[]))""")
                .param("org", orgId).param("ids", Pg.bigintArray(excursionIds)).query(Long.class).list());
    }

    /** API-CMP-24: 조치 시각이 [from, to)인 조치(최근부터) */
    public List<ActionRow> list(long orgId, Filter f, int limit, long offset) {
        return jdbc.sql(SELECT + WHERE + " ORDER BY a.acted_at DESC, a.id DESC LIMIT :limit OFFSET :offset")
                .params(params(orgId, f)).param("limit", limit).param("offset", offset).query(CorrectiveActionRepository::row).list();
    }

    public long count(long orgId, Filter f) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.corrective_actions a JOIN data2flow_core.compliance_excursions x ON x.id = a.excursion_id\n"
                + WHERE).params(params(orgId, f)).query(Long.class).single();
    }

    /** 자동 확인할 조치: AUTO이고 아직 계산하지 않았고 조치 후 24시간이 지난 것 */
    public List<ActionRow> due(long orgId, Instant actedBefore, int limit) {
        return jdbc.sql(SELECT + """
                         WHERE a.organization_id = :org AND a.result_mode = 'AUTO' AND a.result_checked_at IS NULL AND a.acted_at <= :before
                         ORDER BY a.acted_at, a.id LIMIT :limit""")
                .param("org", orgId).param("before", Pg.ts(actedBefore)).param("limit", limit).query(CorrectiveActionRepository::row).list();
    }

    public void saveOutcome(long orgId, long id, BigDecimal ratio, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.corrective_actions SET result_ratio_24h = :ratio, result_checked_at = :now
                         WHERE organization_id = :org AND id = :id AND result_mode = 'AUTO'""")
                .param("org", orgId).param("id", id).param("ratio", ratio).param("now", Pg.ts(now)).update();
    }

    /** 조직의 사용자인가 */
    public Optional<String> userName(long orgId, long userId) {
        return jdbc.sql("SELECT name FROM data2flow_core.app_users WHERE organization_id = :org AND id = :id").param("org", orgId)
                .param("id", userId).query(String.class).optional();
    }

    /** 조직에 있는 알람 ID만 */
    public Set<Long> existingAlarms(long orgId, Collection<Long> ids) {
        return existing("data2flow_core.alarms", orgId, ids);
    }

    /** 조직에 있는 작업 지시 ID만 */
    public Set<Long> existingWorkOrders(long orgId, Collection<Long> ids) {
        return existing("data2flow_core.work_orders", orgId, ids);
    }

    private Set<Long> existing(String table, long orgId, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT id FROM " + table + " WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))")
                .param("org", orgId).param("ids", Pg.bigintArray(ids)).query(Long.class).list());
    }

    private static Map<String, Object> params(long orgId, Filter f) {
        Map<String, Object> p = new HashMap<>();
        p.put("org", orgId);
        p.put("filterSpaces", f.spaceIds() != null);
        p.put("spaces", Pg.bigintArray(f.spaceIds() == null ? List.of() : f.spaceIds()));
        p.put("from", f.from() == null ? null : Pg.ts(f.from()));
        p.put("to", f.to() == null ? null : Pg.ts(f.to()));
        p.put("cause", f.cause());
        return p;
    }

    private static final String SELECT = """
            SELECT a.id, a.cause, a.cause_note, a.action_text, a.actor_user_id, u.name AS actor_name, a.acted_at, a.linked_alarm_ids,
                   a.linked_work_order_ids, a.result_mode, a.result_ratio_24h, a.result_note, a.result_checked_at, a.created_at, a.updated_at,
                   x.id AS x_id, x.space_id, x.profile_version_id, x.metric_key, x.started_at, x.ended_at, x.peak_value, x.alarm_id
              FROM data2flow_core.corrective_actions a
              JOIN data2flow_core.compliance_excursions x ON x.id = a.excursion_id
              LEFT JOIN data2flow_core.app_users u ON u.organization_id = a.organization_id AND u.id = a.actor_user_id
            """;

    private static final String WHERE = """
             WHERE a.organization_id = :org
               AND (NOT :filterSpaces OR x.space_id = ANY(CAST(:spaces AS bigint[])))
               AND (CAST(:from AS timestamptz) IS NULL OR a.acted_at >= :from)
               AND (CAST(:to AS timestamptz) IS NULL OR a.acted_at < :to)
               AND (CAST(:cause AS varchar) IS NULL OR a.cause = :cause)""";

    static ActionRow row(ResultSet rs, int n) throws SQLException {
        ExcursionRow x = new ExcursionRow(rs.getLong("x_id"), rs.getLong("space_id"), rs.getLong("profile_version_id"), rs.getString("metric_key"),
                Pg.instant(rs, "started_at"), Pg.instant(rs, "ended_at"), rs.getBigDecimal("peak_value"), Pg.longOrNull(rs, "alarm_id"));
        return new ActionRow(rs.getLong("id"), x, rs.getString("cause"), rs.getString("cause_note"), rs.getString("action_text"),
                rs.getLong("actor_user_id"), rs.getString("actor_name"), Pg.instant(rs, "acted_at"), Pg.longList(rs, "linked_alarm_ids"),
                Pg.longList(rs, "linked_work_order_ids"), rs.getString("result_mode"), rs.getBigDecimal("result_ratio_24h"),
                rs.getString("result_note"), Pg.instant(rs, "result_checked_at"), Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"));
    }
}
