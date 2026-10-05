package net.java21.data2flow.core.workorder.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 정기 점검 계획({@code maintenance_plans}, DEV-08.05, BR-DEV-28) */
@Repository
public class MaintenancePlanRepository {

    private static final String COLUMNS = """
            id, organization_id, name, target_group_id, work_type, interval_days, lead_days, next_due_on, default_assignee_id,
            checklist_template::text AS checklist_template, enabled, version, updated_at""";

    private final JdbcClient jdbc;

    public MaintenancePlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Plan> list(long organizationId, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.maintenance_plans WHERE organization_id = :org ORDER BY name, id LIMIT :l OFFSET :o")
                .param("org", organizationId).param("l", limit).param("o", offset).query(MaintenancePlanRepository::map).list();
    }

    public long count(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.maintenance_plans WHERE organization_id = :org")
                .param("org", organizationId).query(Long.class).single();
    }

    public Optional<Plan> find(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.maintenance_plans WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).query(MaintenancePlanRepository::map).optional();
    }

    /** BR-DEV-28: 실행할 때가 된 계획(행 잠금, 다른 파드가 같은 계획을 동시에 잡지 않게) */
    public List<Plan> lockDue(long organizationId, LocalDate today) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM data2flow_core.maintenance_plans
                        WHERE organization_id = :org AND enabled AND next_due_on - lead_days <= :today
                        ORDER BY id FOR UPDATE SKIP LOCKED""")
                .param("org", organizationId).param("today", today).query(MaintenancePlanRepository::map).list();
    }

    public long insert(long organizationId, PlanFields f, long createdBy, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.maintenance_plans (organization_id, name, target_group_id, work_type, interval_days, lead_days,
                               next_due_on, default_assignee_id, checklist_template, enabled, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :name, :group, :type, :interval, :lead, :due, :assignee, CAST(:checklist AS jsonb), :enabled, :by, :by, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("name", f.name()).param("group", f.targetGroupId()).param("type", f.workType())
                .param("interval", f.intervalDays()).param("lead", f.leadDays()).param("due", f.nextDueOn())
                .param("assignee", f.defaultAssigneeId()).param("checklist", f.checklistTemplateJson()).param("enabled", f.enabled())
                .param("by", createdBy).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public int update(long organizationId, long id, int baseVersion, PlanFields f, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.maintenance_plans
                           SET name = :name, target_group_id = :group, work_type = :type, interval_days = :interval, lead_days = :lead,
                               next_due_on = :due, default_assignee_id = :assignee, checklist_template = CAST(:checklist AS jsonb),
                               enabled = :enabled, version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .param("name", f.name()).param("group", f.targetGroupId()).param("type", f.workType()).param("interval", f.intervalDays())
                .param("lead", f.leadDays()).param("due", f.nextDueOn()).param("assignee", f.defaultAssigneeId())
                .param("checklist", f.checklistTemplateJson()).param("enabled", f.enabled()).param("by", updatedBy)
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", id).param("base", baseVersion).update();
    }

    /** 실행 뒤 다음 예정일을 늦춘다(BR-DEV-28) */
    public int advance(long organizationId, long id, LocalDate nextDueOn, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.maintenance_plans SET next_due_on = :due, version = version + 1, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("due", nextDueOn).param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.maintenance_plans WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    public boolean existsGroup(long organizationId, long groupId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.device_groups WHERE organization_id = :org AND id = :id)")
                .param("org", organizationId).param("id", groupId).query(Boolean.class).single();
    }

    /** 대상 그룹의 살아 있는 기기 */
    public List<Long> findGroupDevices(long organizationId, long groupId) {
        return jdbc.sql("""
                        SELECT d.id FROM data2flow_core.device_group_members m
                          JOIN data2flow_core.devices d ON d.id = m.device_id AND d.organization_id = m.organization_id
                         WHERE m.organization_id = :org AND m.group_id = :g AND d.status <> 'DELETED' ORDER BY d.id""")
                .param("org", organizationId).param("g", groupId).query(Long.class).list();
    }

    /** 계획 실행 기준 시간대: 그룹 첫 기기의 사이트 시간대, 없으면 조직 시간대 */
    public Optional<String> findPlanTimezone(long organizationId, long groupId) {
        return jdbc.sql("""
                        SELECT coalesce(site.timezone, os.timezone) FROM data2flow_core.org_settings os
                          LEFT JOIN LATERAL (
                               SELECT s2.timezone FROM data2flow_core.device_group_members m
                                 JOIN data2flow_core.devices d ON d.id = m.device_id AND d.organization_id = m.organization_id
                                 JOIN data2flow_core.spaces s ON s.id = d.space_id AND s.organization_id = d.organization_id
                                 JOIN data2flow_core.spaces s2 ON s.path LIKE s2.path || '%' AND s2.organization_id = s.organization_id
                                                              AND s2.type = 'SITE' AND s2.timezone IS NOT NULL
                                WHERE m.organization_id = os.organization_id AND m.group_id = :g
                                ORDER BY d.id LIMIT 1) site ON true
                         WHERE os.organization_id = :org""")
                .param("org", organizationId).param("g", groupId).query(String.class).optional();
    }

    @OrganizationScopeExempt("매일 점검 작업이 계획이 있는 조직을 모두 돈다(각 조직 안에서는 조직 조건으로 처리)")
    public List<Long> listOrganizationsWithPlans() {
        return jdbc.sql("SELECT DISTINCT organization_id FROM data2flow_core.maintenance_plans WHERE enabled ORDER BY 1")
                .query(Long.class).list();
    }

    static Plan map(ResultSet rs, int n) throws SQLException {
        return new Plan(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getLong("target_group_id"),
                rs.getString("work_type"), rs.getInt("interval_days"), rs.getInt("lead_days"), rs.getObject("next_due_on", LocalDate.class),
                Pg.longOrNull(rs, "default_assignee_id"), rs.getString("checklist_template"), rs.getBoolean("enabled"), rs.getInt("version"),
                Pg.instant(rs, "updated_at"));
    }

    public record Plan(long id, long organizationId, String name, long targetGroupId, String workType, int intervalDays, int leadDays,
                       LocalDate nextDueOn, Long defaultAssigneeId, String checklistTemplateJson, boolean enabled, int version,
                       Instant updatedAt) {
    }

    public record PlanFields(String name, long targetGroupId, String workType, int intervalDays, int leadDays, LocalDate nextDueOn,
                             Long defaultAssigneeId, String checklistTemplateJson, boolean enabled) {
    }
}
