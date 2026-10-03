package net.java21.data2flow.core.control.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 조직 제어 설정({@code data2flow_core.control_settings}, API-ACT-17). 조직당 한 행, 없으면 기본값 */
@Repository
public class ControlSettingsRepository {

    private final JdbcClient jdbc;

    public ControlSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SettingsRow(long organizationId, String absoluteLimits, int manualOverrideMinutes, int minIntervalSec,
                              String oscillation, int defaultValiditySec, boolean scheduleRespectsManualOverride,
                              boolean requireApprovalForControlNodes, int version, long updatedBy, String updatedByName,
                              Instant updatedAt) {
    }

    public Optional<SettingsRow> find(long organizationId) {
        return jdbc.sql("""
                        SELECT s.organization_id, s.absolute_limits::text AS absolute_limits, s.manual_override_minutes, s.min_interval_sec,
                               s.oscillation::text AS oscillation, s.default_validity_sec, s.schedule_respects_manual_override,
                               s.require_approval_for_control_nodes, s.version, s.updated_by, u.name AS updated_by_name, s.updated_at
                          FROM data2flow_core.control_settings s
                          LEFT JOIN data2flow_core.app_users u ON u.id = s.updated_by AND u.organization_id = s.organization_id
                         WHERE s.organization_id = :org""")
                .param("org", organizationId).query(ControlSettingsRepository::map).optional();
    }

    /** 처음이면 만들고(version 1), 있으면 baseVersion이 맞을 때만 바꾼다. 바뀐 행 수 */
    public int upsert(long organizationId, int baseVersion, String absoluteLimits, int manualOverrideMinutes, int minIntervalSec,
                      String oscillation, int defaultValiditySec, boolean scheduleRespectsManualOverride, boolean requireApproval,
                      long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.control_settings (organization_id, absolute_limits, manual_override_minutes,
                               min_interval_sec, oscillation, default_validity_sec, schedule_respects_manual_override,
                               require_approval_for_control_nodes, version, updated_by, updated_at)
                        SELECT :org, CAST(:limits AS jsonb), :override, :interval, CAST(:osc AS jsonb), :validity, :respects, :approval, 1,
                               :user, :now
                         WHERE :base = 0
                        ON CONFLICT (organization_id) DO UPDATE SET absolute_limits = EXCLUDED.absolute_limits,
                               manual_override_minutes = EXCLUDED.manual_override_minutes, min_interval_sec = EXCLUDED.min_interval_sec,
                               oscillation = EXCLUDED.oscillation, default_validity_sec = EXCLUDED.default_validity_sec,
                               schedule_respects_manual_override = EXCLUDED.schedule_respects_manual_override,
                               require_approval_for_control_nodes = EXCLUDED.require_approval_for_control_nodes,
                               version = data2flow_core.control_settings.version + 1, updated_by = EXCLUDED.updated_by,
                               updated_at = EXCLUDED.updated_at
                         WHERE data2flow_core.control_settings.version = :base""")
                .param("org", organizationId).param("limits", absoluteLimits).param("override", manualOverrideMinutes)
                .param("interval", minIntervalSec).param("osc", oscillation).param("validity", defaultValiditySec)
                .param("respects", scheduleRespectsManualOverride).param("approval", requireApproval).param("user", userId)
                .param("now", Pg.ts(now)).param("base", baseVersion).update();
    }

    /** 조직 모든 모델의 기능 jsonb(절대 한계가 모델 범위보다 넓은지 볼 때) */
    public List<String> listModelCapabilities(long organizationId) {
        return jdbc.sql("SELECT capabilities::text FROM data2flow_core.device_models WHERE organization_id = :org AND capabilities <> '[]'::jsonb")
                .param("org", organizationId).query(String.class).list();
    }

    private static SettingsRow map(ResultSet rs, int row) throws SQLException {
        return new SettingsRow(rs.getLong("organization_id"), rs.getString("absolute_limits"), rs.getInt("manual_override_minutes"),
                rs.getInt("min_interval_sec"), rs.getString("oscillation"), rs.getInt("default_validity_sec"),
                rs.getBoolean("schedule_respects_manual_override"), rs.getBoolean("require_approval_for_control_nodes"),
                rs.getInt("version"), rs.getLong("updated_by"), rs.getString("updated_by_name"), Pg.instant(rs, "updated_at"));
    }
}
