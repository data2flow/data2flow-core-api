package net.java21.data2flow.core.rule.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 규칙 튜닝 제안(rule_tuning_suggestions, RUL-06.02) */
@Repository
public class TuningRepository {

    static final String SELECT = """
            SELECT t.id, t.rule_id, r.name AS rule_name, t.problem, t.current::text AS current, t.proposed::text AS proposed,
                   t.simulation::text AS simulation, t.status, t.created_at
              FROM data2flow_core.rule_tuning_suggestions t
              JOIN data2flow_core.rules r ON r.id = t.rule_id AND r.organization_id = t.organization_id""";

    private final JdbcClient jdbc;

    public TuningRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record TuningRow(long id, long ruleId, String ruleName, String problem, String current, String proposed, String simulation,
                            String status, Instant createdAt) {
    }

    /** 같은 규칙·문제의 열린 제안이 없을 때만 만든다. 만든 ID */
    public Optional<Long> insertIfAbsent(long organizationId, long ruleId, String problem, String current, String proposed, String simulation,
                                         Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.rule_tuning_suggestions (organization_id, rule_id, problem, current, proposed, simulation, status,
                               created_at, updated_at)
                        VALUES (:org, :rule, :problem, CAST(:current AS jsonb), CAST(:proposed AS jsonb), CAST(:simulation AS jsonb), 'OPEN', :now, :now)
                        ON CONFLICT (rule_id, problem) WHERE status = 'OPEN' DO NOTHING RETURNING id""")
                .param("org", organizationId).param("rule", ruleId).param("problem", problem).param("current", current)
                .param("proposed", proposed).param("simulation", simulation).param("now", Pg.ts(now)).query(Long.class).optional();
    }

    public List<TuningRow> list(long organizationId, String status, int limit, long offset) {
        return jdbc.sql(SELECT + " WHERE t.organization_id = :org AND (CAST(:status AS text) IS NULL OR t.status = :status)"
                        + " AND r.status <> 'DELETED' ORDER BY t.created_at DESC, t.id DESC LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("status", status).param("limit", limit).param("offset", offset)
                .query(TuningRepository::map).list();
    }

    public long count(long organizationId, String status) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.rule_tuning_suggestions t JOIN data2flow_core.rules r ON r.id = t.rule_id"
                        + " AND r.organization_id = t.organization_id WHERE t.organization_id = :org"
                        + " AND (CAST(:status AS text) IS NULL OR t.status = :status) AND r.status <> 'DELETED'")
                .param("org", organizationId).param("status", status).query(Long.class).single();
    }

    public Optional<TuningRow> findById(long organizationId, long id) {
        return jdbc.sql(SELECT + " WHERE t.organization_id = :org AND t.id = :id").param("org", organizationId).param("id", id)
                .query(TuningRepository::map).optional();
    }

    public int updateStatus(long organizationId, long id, String from, String to, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.rule_tuning_suggestions SET status = :to, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = :from""")
                .param("to", to).param("now", Pg.ts(now)).param("org", organizationId).param("id", id).param("from", from).update();
    }

    /** 규칙별 최근 7일 알람 통계: 발생 수, 플래핑 알람 수, 확인된 알람 수 */
    public record RuleAlarmStats(long ruleId, long raised, long flapping, long acked) {
    }

    public List<RuleAlarmStats> listRuleStats(long organizationId, Instant since) {
        return jdbc.sql("""
                        SELECT a.rule_id, sum(a.occurrence_count) AS raised, count(*) FILTER (WHERE a.flapping
                               OR EXISTS (SELECT 1 FROM data2flow_core.alarm_events e WHERE e.alarm_id = a.id AND e.type = 'FLAPPING_ON')) AS flapping,
                               count(*) FILTER (WHERE a.acked_at IS NOT NULL) AS acked
                          FROM data2flow_core.alarms a
                         WHERE a.organization_id = :org AND a.rule_id IS NOT NULL AND a.raised_at >= :since
                         GROUP BY a.rule_id""")
                .param("org", organizationId).param("since", Pg.ts(since))
                .query((rs, n) -> new RuleAlarmStats(rs.getLong("rule_id"), rs.getLong("raised"), rs.getLong("flapping"), rs.getLong("acked")))
                .list();
    }

    static TuningRow map(ResultSet rs, int n) throws SQLException {
        return new TuningRow(rs.getLong("id"), rs.getLong("rule_id"), rs.getString("rule_name"), rs.getString("problem"),
                rs.getString("current"), rs.getString("proposed"), rs.getString("simulation"), rs.getString("status"),
                Pg.instant(rs, "created_at"));
    }
}
