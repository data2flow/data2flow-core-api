package net.java21.data2flow.core.notify.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 알림 정책·에스컬레이션 단계({@code notification_policies}·{@code policy_steps}, RUL-03.02·03.03) */
@Repository
public class PolicyRepository {

    static final String SELECT = """
            SELECT p.id, p.organization_id, p.name, p.min_severity, p.space_id, sp.path AS space_path, p.include_children,
                   p.rule_ids, p.time_window::text AS time_window, p.recipients::text AS recipients, p.channels, p.templates::text AS templates,
                   p.renotify_minutes, p.aggregate_window_sec, p.notify_on_clear, p.version, p.created_at, p.updated_at,
                   (SELECT coalesce(jsonb_agg(jsonb_build_object('stepNo', st.step_no, 'waitMinutes', st.wait_minutes,
                                                                  'recipients', st.recipients) ORDER BY st.step_no), '[]'::jsonb)::text
                      FROM data2flow_core.policy_steps st WHERE st.policy_id = p.id AND st.organization_id = p.organization_id) AS steps
              FROM data2flow_core.notification_policies p
              LEFT JOIN data2flow_core.spaces sp ON sp.id = p.space_id AND sp.organization_id = p.organization_id""";

    private final JdbcClient jdbc;

    public PolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record PolicyRow(long id, long organizationId, String name, String minSeverity, Long spaceId, String spacePath,
                            boolean includeChildren, List<Long> ruleIds, String timeWindow, String recipients, List<String> channels,
                            String templates, int renotifyMinutes, int aggregateWindowSec, boolean notifyOnClear, int version,
                            Instant createdAt, Instant updatedAt, String steps) {
    }

    /** 저장 값 */
    public record PolicyValues(long organizationId, String name, String minSeverity, Long spaceId, boolean includeChildren,
                               List<Long> ruleIds, String timeWindowJson, String recipientsJson, List<String> channels, String templatesJson,
                               int renotifyMinutes, int aggregateWindowSec, boolean notifyOnClear) {
    }

    public record Step(int stepNo, int waitMinutes, String recipientsJson) {
    }

    public Optional<PolicyRow> findById(long organizationId, long id) {
        return jdbc.sql(SELECT + " WHERE p.organization_id = :org AND p.id = :id").param("org", organizationId).param("id", id)
                .query(PolicyRepository::map).optional();
    }

    public List<PolicyRow> listAll(long organizationId) {
        return jdbc.sql(SELECT + " WHERE p.organization_id = :org ORDER BY p.id").param("org", organizationId)
                .query(PolicyRepository::map).list();
    }

    public List<PolicyRow> list(long organizationId, int limit, long offset) {
        return jdbc.sql(SELECT + " WHERE p.organization_id = :org ORDER BY p.name, p.id LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("limit", limit).param("offset", offset).query(PolicyRepository::map).list();
    }

    public long count(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.notification_policies WHERE organization_id = :org")
                .param("org", organizationId).query(Long.class).single();
    }

    public boolean existsName(long organizationId, String name, Long exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.notification_policies WHERE organization_id = :org AND name = :name
                                         AND (CAST(:except AS bigint) IS NULL OR id <> :except))""")
                .param("org", organizationId).param("name", name).param("except", exceptId).query(Boolean.class).single();
    }

    public long insert(PolicyValues v, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.notification_policies (organization_id, name, min_severity, space_id, include_children, rule_ids,
                               time_window, recipients, channels, templates, renotify_minutes, aggregate_window_sec, notify_on_clear, created_by,
                               updated_by, created_at, updated_at)
                        VALUES (:org, :name, :min, :space, :children, CAST(:rules AS bigint[]), CAST(:window AS jsonb), CAST(:recipients AS jsonb),
                                CAST(:channels AS text[]), CAST(:templates AS jsonb), :renotify, :aggregate, :onClear, :user, :user, :now, :now)
                        RETURNING id""")
                .params(params(v)).param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    /** 낙관적 잠금(baseVersion). 바뀐 행 수 */
    public int update(long id, int baseVersion, PolicyValues v, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.notification_policies SET name = :name, min_severity = :min, space_id = :space,
                               include_children = :children, rule_ids = CAST(:rules AS bigint[]), time_window = CAST(:window AS jsonb),
                               recipients = CAST(:recipients AS jsonb), channels = CAST(:channels AS text[]), templates = CAST(:templates AS jsonb),
                               renotify_minutes = :renotify, aggregate_window_sec = :aggregate, notify_on_clear = :onClear, version = version + 1,
                               updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .params(params(v)).param("user", userId).param("now", Pg.ts(now)).param("id", id).param("base", baseVersion).update();
    }

    public void replaceSteps(long organizationId, long policyId, List<Step> steps) {
        jdbc.sql("DELETE FROM data2flow_core.policy_steps WHERE organization_id = :org AND policy_id = :id")
                .param("org", organizationId).param("id", policyId).update();
        for (Step s : steps) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.policy_steps (policy_id, step_no, organization_id, wait_minutes, recipients)
                            VALUES (:id, :no, :org, :wait, CAST(:recipients AS jsonb))""")
                    .param("id", policyId).param("no", s.stepNo()).param("org", organizationId).param("wait", s.waitMinutes())
                    .param("recipients", s.recipientsJson()).update();
        }
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.notification_policies WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    /** 이 정책을 쓰는 규칙 수(삭제 거부 POLICY_IN_USE) */
    public long countRulesUsing(long organizationId, long id) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.rules WHERE organization_id = :org AND policy_id = :id AND status <> 'DELETED'")
                .param("org", organizationId).param("id", id).query(Long.class).single();
    }

    /** 채널 키를 쓰는 정책 수(채널 삭제 CHANNEL_IN_USE) */
    public long countUsingChannel(long organizationId, String channel) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.notification_policies WHERE organization_id = :org AND :channel = ANY(channels)")
                .param("org", organizationId).param("channel", channel).query(Long.class).single();
    }

    @net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt("action 내부 API가 정책 ID(전역 고유)로 조직을 찾은 뒤 그 조직 조건으로 다시 읽는다")
    public Optional<Long> findOrganization(long policyId) {
        return jdbc.sql("SELECT organization_id FROM data2flow_core.notification_policies WHERE id = :id").param("id", policyId)
                .query(Long.class).optional();
    }

    /** 조직 시간대(org_settings → Asia/Seoul) */
    public String findTimezone(long organizationId) {
        return jdbc.sql("SELECT coalesce((SELECT timezone FROM data2flow_core.org_settings WHERE organization_id = :org), 'Asia/Seoul')")
                .param("org", organizationId).query(String.class).single();
    }

    private static java.util.Map<String, Object> params(PolicyValues v) {
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("org", v.organizationId());
        p.put("name", v.name());
        p.put("min", v.minSeverity());
        p.put("space", v.spaceId());
        p.put("children", v.includeChildren());
        p.put("rules", v.ruleIds() == null ? null : Pg.bigintArray(v.ruleIds()));
        p.put("window", v.timeWindowJson());
        p.put("recipients", v.recipientsJson());
        p.put("channels", Pg.textArray(v.channels()));
        p.put("templates", v.templatesJson());
        p.put("renotify", v.renotifyMinutes());
        p.put("aggregate", v.aggregateWindowSec());
        p.put("onClear", v.notifyOnClear());
        return p;
    }

    static PolicyRow map(ResultSet rs, int n) throws SQLException {
        return new PolicyRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("min_severity"),
                Pg.longOrNull(rs, "space_id"), rs.getString("space_path"), rs.getBoolean("include_children"),
                rs.getArray("rule_ids") == null ? List.of() : Pg.longList(rs, "rule_ids"), rs.getString("time_window"),
                rs.getString("recipients"), Pg.stringList(rs, "channels"), rs.getString("templates"), rs.getInt("renotify_minutes"),
                rs.getInt("aggregate_window_sec"), rs.getBoolean("notify_on_clear"), rs.getInt("version"), Pg.instant(rs, "created_at"),
                Pg.instant(rs, "updated_at"), rs.getString("steps"));
    }
}
