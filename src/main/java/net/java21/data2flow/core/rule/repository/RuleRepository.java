package net.java21.data2flow.core.rule.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 규칙({@code data2flow_core.rules}, RUL-01·06)과 범위 대상 기기 판정(BR-RUL-06). 삭제는 status=DELETED(소프트) */
@Repository
public class RuleRepository {

    static final String SELECT = """
            SELECT r.id, r.organization_id, r.name, r.template_key, r.status, r.error_reason, r.scope_type, r.scope_ids::text AS scope_ids,
                   r.include_children, r.condition::text AS condition, r.time_condition::text AS time_condition, r.severity, r.title_template,
                   r.auto_clear, r.policy_id, r.flow_id, r.target_count, r.version, r.created_by, r.updated_by, u.name AS updated_by_name,
                   r.created_at, r.updated_at,
                   (SELECT count(*) FROM data2flow_core.alarms a WHERE a.organization_id = r.organization_id AND a.rule_id = r.id
                       AND a.status IN ('ACTIVE','ACKNOWLEDGED','SUPPRESSED')) AS open_alarms,
                   (SELECT count(*) FROM data2flow_core.alarms a WHERE a.organization_id = r.organization_id AND a.rule_id = r.id
                       AND a.raised_at >= :since7d) AS raised_7d
              FROM data2flow_core.rules r
              LEFT JOIN data2flow_core.app_users u ON u.id = r.updated_by AND u.organization_id = r.organization_id""";

    private final JdbcClient jdbc;

    public RuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record RuleRow(long id, long organizationId, String name, String templateKey, String status, String errorReason,
                          String scopeType, String scopeIds, boolean includeChildren, String condition, String timeCondition,
                          String severity, String titleTemplate, boolean autoClear, Long policyId, UUID flowId, int targetCount,
                          int version, long createdBy, long updatedBy, String updatedByName, Instant createdAt, Instant updatedAt,
                          long openAlarms, long raised7d) {
    }

    /** 저장 값 */
    public record RuleValues(long organizationId, String name, String templateKey, String scopeType, String scopeIdsJson,
                             boolean includeChildren, String conditionJson, String timeConditionJson, String severity, String titleTemplate,
                             boolean autoClear, Long policyId, int targetCount) {
    }

    /**
     * @param scopeSpaceIds 공간 범위가 제한된 사용자의 범위 공간(null이면 제한 없음). 범위 안 공간을 대상으로 하는 SPACE 규칙과
     *                      범위 안 기기를 대상으로 하는 DEVICE 규칙만 보인다
     */
    public record Search(long organizationId, String keyword, Collection<String> statuses, String severity, String templateKey,
                         Long spaceId, Instant since7d, Collection<Long> scopeSpaceIds) {
    }

    public Optional<RuleRow> findById(long organizationId, long id, Instant since7d) {
        return jdbc.sql(SELECT + " WHERE r.organization_id = :org AND r.id = :id AND r.status <> 'DELETED'")
                .param("org", organizationId).param("id", id).param("since7d", Pg.ts(since7d)).query(RuleRepository::map).optional();
    }

    public boolean lockById(long organizationId, long id) {
        return jdbc.sql("SELECT 1 FROM data2flow_core.rules WHERE organization_id = :org AND id = :id AND status <> 'DELETED' FOR UPDATE")
                .param("org", organizationId).param("id", id).query(Integer.class).optional().isPresent();
    }

    public List<RuleRow> search(Search s, int limit, long offset) {
        Map<String, Object> params = new HashMap<>();
        String where = where(s, params);
        params.put("limit", limit);
        params.put("offset", offset);
        params.put("since7d", Pg.ts(s.since7d()));
        return jdbc.sql(SELECT + where + " ORDER BY (r.status = 'ERROR') DESC, r.updated_at DESC, r.id DESC LIMIT :limit OFFSET :offset")
                .params(params).query(RuleRepository::map).list();
    }

    public long count(Search s) {
        Map<String, Object> params = new HashMap<>();
        return jdbc.sql("SELECT count(*) FROM data2flow_core.rules r" + where(s, params)).params(params).query(Long.class).single();
    }

    private static String where(Search s, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE r.organization_id = :org AND r.status NOT IN ('DELETED','CONVERTED')");
        params.put("org", s.organizationId());
        if (s.keyword() != null) {
            sql.append(" AND r.name ILIKE :kw");
            params.put("kw", "%" + s.keyword().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (s.statuses() != null && !s.statuses().isEmpty()) {
            sql.append(" AND r.status = ANY(CAST(:statuses AS text[]))");
            params.put("statuses", Pg.textArray(s.statuses()));
        }
        if (s.severity() != null) {
            sql.append(" AND r.severity = :severity");
            params.put("severity", s.severity());
        }
        if (s.templateKey() != null) {
            sql.append(" AND r.template_key = :template");
            params.put("template", s.templateKey());
        }
        if (s.spaceId() != null) {
            sql.append(" AND r.scope_type = 'SPACE' AND r.scope_ids @> to_jsonb(CAST(:space AS text))");
            params.put("space", Long.toString(s.spaceId()));
        }
        if (s.scopeSpaceIds() != null) {
            sql.append(" ").append("""
                     AND ((r.scope_type = 'SPACE' AND jsonb_exists_any(r.scope_ids, CAST(:scopeIds AS text[])))
                       OR (r.scope_type = 'DEVICE' AND NOT EXISTS (
                            SELECT 1 FROM jsonb_array_elements_text(r.scope_ids) x
                              LEFT JOIN data2flow_core.devices d ON d.organization_id = r.organization_id AND d.id::text = x
                             WHERE d.space_id IS NULL OR NOT (d.space_id = ANY(CAST(:scopeLongs AS bigint[]))))))""");
            params.put("scopeIds", Pg.textArray(s.scopeSpaceIds().stream().map(String::valueOf).toList()));
            params.put("scopeLongs", Pg.bigintArray(s.scopeSpaceIds()));
        }
        return sql.toString();
    }

    /** 한도 계산용: 삭제·변환 제외 규칙 수 */
    public long countLive(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.rules WHERE organization_id = :org AND status NOT IN ('DELETED','CONVERTED')")
                .param("org", organizationId).query(Long.class).single();
    }

    public boolean existsName(long organizationId, String name, Long exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.rules WHERE organization_id = :org AND name = :name AND status <> 'DELETED'
                                         AND (CAST(:except AS bigint) IS NULL OR id <> :except))""")
                .param("org", organizationId).param("name", name).param("except", exceptId).query(Boolean.class).single();
    }

    public long insert(RuleValues v, UUID flowId, String status, String errorReason, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.rules (organization_id, name, template_key, status, error_reason, scope_type, scope_ids,
                               include_children, condition, time_condition, severity, title_template, auto_clear, policy_id, flow_id,
                               target_count, version, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :name, :template, :status, :reason, :scopeType, CAST(:scopeIds AS jsonb), :children,
                                CAST(:condition AS jsonb), CAST(:time AS jsonb), :severity, :title, :autoClear, :policy, :flow, :targets, 1,
                                :user, :user, :now, :now)
                        RETURNING id""")
                .params(params(v)).param("status", status).param("reason", errorReason).param("flow", flowId).param("user", userId)
                .param("now", Pg.ts(now)).query(Long.class).single();
    }

    /** 수정(낙관적 잠금 baseVersion). 바뀐 행 수. 버전 +1(= 새 플로우 버전, BR-RUL-01) */
    public int update(long id, int baseVersion, RuleValues v, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.rules SET name = :name, template_key = :template, scope_type = :scopeType,
                               scope_ids = CAST(:scopeIds AS jsonb), include_children = :children, condition = CAST(:condition AS jsonb),
                               time_condition = CAST(:time AS jsonb), severity = :severity, title_template = :title, auto_clear = :autoClear,
                               policy_id = :policy, target_count = :targets, version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base AND status NOT IN ('DELETED','CONVERTED')""")
                .params(params(v)).param("user", userId).param("now", Pg.ts(now)).param("id", id).param("base", baseVersion).update();
    }

    public void updateStatus(long organizationId, long id, String status, String errorReason, Long userId, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.rules SET status = :status, error_reason = :reason,
                               updated_by = coalesce(CAST(:user AS bigint), updated_by), updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("status", status).param("reason", errorReason).param("user", userId).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", id).update();
    }

    public void updateTargetCount(long organizationId, long id, int targetCount) {
        jdbc.sql("UPDATE data2flow_core.rules SET target_count = :n WHERE organization_id = :org AND id = :id")
                .param("n", Math.min(targetCount, 5000)).param("org", organizationId).param("id", id).update();
    }

    /** 점검 대상(ACTIVE·ERROR 규칙) */
    public List<RuleRow> listLive(long organizationId, Instant since7d) {
        return jdbc.sql(SELECT + " WHERE r.organization_id = :org AND r.status IN ('ACTIVE','ERROR') ORDER BY r.id")
                .param("org", organizationId).param("since7d", Pg.ts(since7d)).query(RuleRepository::map).list();
    }

    /** 규칙 템플릿(시스템 + 조직) */
    public record TemplateRow(long id, String templateKey, String name, String description, String category, String paramsSchema,
                              String conditionTemplate, String defaultSeverity, boolean builtin) {
    }

    public List<TemplateRow> listTemplates(long organizationId) {
        return jdbc.sql("""
                        SELECT id, template_key, name, description, category, params_schema::text AS params_schema,
                               condition_template::text AS condition_template, default_severity, builtin
                          FROM data2flow_core.rule_templates WHERE organization_id IN (0, :org) AND status = 'ACTIVE'
                         ORDER BY builtin DESC, id""")
                .param("org", organizationId).query(RuleRepository::template).list();
    }

    public Optional<TemplateRow> findTemplate(long organizationId, String key) {
        return jdbc.sql("""
                        SELECT id, template_key, name, description, category, params_schema::text AS params_schema,
                               condition_template::text AS condition_template, default_severity, builtin
                          FROM data2flow_core.rule_templates WHERE organization_id IN (0, :org) AND template_key = :key AND status = 'ACTIVE'
                         ORDER BY organization_id DESC LIMIT 1""")
                .param("org", organizationId).param("key", key).query(RuleRepository::template).optional();
    }

    // ------------------------------------------------------------------ 범위 대상(BR-RUL-06)

    /**
     * 범위 안 ACTIVE 기기. {@code metrics}가 비어 있지 않으면 그 측정 항목 중 하나를 내는 기기만(모델 측정 항목 또는 마지막 수신값).
     *
     * @param scopeIds DEVICE: 기기 ID, SPACE: 공간 ID, MODEL: 모델 ID 또는 코드, TAG: 태그
     */
    public List<Long> listTargetDevices(long organizationId, String scopeType, List<String> scopeIds, boolean includeChildren,
                                        Collection<String> metrics, int limit) {
        String scope = switch (scopeType) {
            case "DEVICE" -> "d.id::text = ANY(CAST(:ids AS text[]))";
            case "SPACE" -> includeChildren
                    ? "EXISTS (SELECT 1 FROM data2flow_core.spaces t JOIN data2flow_core.spaces s ON s.organization_id = t.organization_id"
                    + " AND s.path LIKE t.path || '%' WHERE t.organization_id = d.organization_id AND t.id::text = ANY(CAST(:ids AS text[]))"
                    + " AND s.id = d.space_id)"
                    : "d.space_id::text = ANY(CAST(:ids AS text[]))";
            case "MODEL" -> "EXISTS (SELECT 1 FROM data2flow_core.device_models m WHERE m.organization_id = d.organization_id"
                    + " AND m.id = d.model_id AND (m.id::text = ANY(CAST(:ids AS text[])) OR m.code = ANY(CAST(:ids AS text[]))))";
            default -> "EXISTS (SELECT 1 FROM data2flow_core.device_tags g WHERE g.organization_id = d.organization_id AND g.device_id = d.id"
                    + " AND g.tag = ANY(CAST(:ids AS text[])))";
        };
        String metricFilter = metrics == null || metrics.isEmpty() ? "" : " " + """
                 AND (EXISTS (SELECT 1 FROM data2flow_core.model_metrics mm WHERE mm.organization_id = d.organization_id
                               AND mm.model_id = d.model_id AND mm.metric_key = ANY(CAST(:metrics AS text[])))
                      OR EXISTS (SELECT 1 FROM data2flow_pipeline.device_state st WHERE st.device_id = d.id
                                  AND st.organization_id = d.organization_id AND jsonb_exists_any(st.latest, CAST(:metrics AS text[]))))""";
        return jdbc.sql("SELECT d.id FROM data2flow_core.devices d WHERE d.organization_id = :org AND d.status = 'ACTIVE' AND " + scope
                        + metricFilter + " ORDER BY d.id LIMIT :limit")
                .param("org", organizationId).param("ids", Pg.textArray(scopeIds)).param("metrics", Pg.textArray(
                        metrics == null ? List.of() : metrics)).param("limit", limit).query(Long.class).list();
    }

    /** 조직에 있는(삭제되지 않은) 측정 항목 키 */
    public List<String> listMetricKeys(long organizationId) {
        return jdbc.sql("SELECT key FROM data2flow_core.metrics WHERE organization_id = :org AND status <> 'IGNORED'")
                .param("org", organizationId).query(String.class).list();
    }

    /** 범위 공간이 이 조직에 있는가(SPACE 범위 검증) */
    public List<Long> listExistingSpaces(long organizationId, Collection<Long> ids) {
        return jdbc.sql("SELECT id FROM data2flow_core.spaces WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))")
                .param("org", organizationId).param("ids", Pg.bigintArray(ids)).query(Long.class).list();
    }

    /** 기기들의 공간(범위 검사) */
    public Map<Long, Long> findDeviceSpaces(long organizationId, Collection<Long> deviceIds) {
        Map<Long, Long> result = new HashMap<>();
        jdbc.sql("SELECT id, space_id FROM data2flow_core.devices WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))"
                        + " AND status <> 'DELETED'")
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds))
                .query(rs -> {
                    result.put(rs.getLong("id"), Pg.longOrNull(rs, "space_id"));
                });
        return result;
    }

    public boolean existsPolicy(long organizationId, long policyId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.notification_policies WHERE organization_id = :org AND id = :id)")
                .param("org", organizationId).param("id", policyId).query(Boolean.class).single();
    }

    /** 규칙 대상 알람의 열린 기기(범위에서 빠진 기기 정리용) */
    public List<Map.Entry<Long, Long>> listOpenAlarmDevices(long organizationId, long ruleId) {
        return jdbc.sql("""
                        SELECT id, device_id FROM data2flow_core.alarms WHERE organization_id = :org AND rule_id = :rule
                           AND status IN ('ACTIVE','ACKNOWLEDGED','SUPPRESSED') AND device_id IS NOT NULL ORDER BY id""")
                .param("org", organizationId).param("rule", ruleId)
                .query((rs, n) -> Map.entry(rs.getLong("id"), rs.getLong("device_id"))).list();
    }

    private static Map<String, Object> params(RuleValues v) {
        Map<String, Object> p = new HashMap<>();
        p.put("org", v.organizationId());
        p.put("name", v.name());
        p.put("template", v.templateKey());
        p.put("scopeType", v.scopeType());
        p.put("scopeIds", v.scopeIdsJson());
        p.put("children", v.includeChildren());
        p.put("condition", v.conditionJson());
        p.put("time", v.timeConditionJson());
        p.put("severity", v.severity());
        p.put("title", v.titleTemplate());
        p.put("autoClear", v.autoClear());
        p.put("policy", v.policyId());
        p.put("targets", Math.min(v.targetCount(), 5000));
        return p;
    }

    static RuleRow map(ResultSet rs, int n) throws SQLException {
        return new RuleRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("template_key"),
                rs.getString("status"), rs.getString("error_reason"), rs.getString("scope_type"), rs.getString("scope_ids"),
                rs.getBoolean("include_children"), rs.getString("condition"), rs.getString("time_condition"), rs.getString("severity"),
                rs.getString("title_template"), rs.getBoolean("auto_clear"), Pg.longOrNull(rs, "policy_id"),
                rs.getObject("flow_id", UUID.class), rs.getInt("target_count"), rs.getInt("version"), rs.getLong("created_by"),
                rs.getLong("updated_by"), rs.getString("updated_by_name"), Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"),
                rs.getLong("open_alarms"), rs.getLong("raised_7d"));
    }

    static TemplateRow template(ResultSet rs, int n) throws SQLException {
        return new TemplateRow(rs.getLong("id"), rs.getString("template_key"), rs.getString("name"), rs.getString("description"),
                rs.getString("category"), rs.getString("params_schema"), rs.getString("condition_template"),
                rs.getString("default_severity"), rs.getBoolean("builtin"));
    }
}
