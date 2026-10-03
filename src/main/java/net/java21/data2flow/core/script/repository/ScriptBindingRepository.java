package net.java21.data2flow.core.script.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 스크립트 연결({@code data2flow_core.script_bindings}, SCR-01.01·01.02·02.03). target_id는 소스 ID, 모델 코드, 기기 ID(문자열)이다.
 * 대상 이름·영향 기기 수는 다른 기능 테이블(data_sources·device_models·devices)을 읽기만 해서 채운다.
 */
@Repository
public class ScriptBindingRepository {

    private static final String SELECT = """
            SELECT b.id, b.script_id, b.kind, b.target_type, b.target_id, b.failure_policy, b.enabled,
                   CASE b.target_type
                     WHEN 'SOURCE' THEN (SELECT ds.name FROM data2flow_core.data_sources ds
                                          WHERE ds.organization_id = b.organization_id AND ds.id::text = b.target_id)
                     WHEN 'MODEL' THEN (SELECT m.name FROM data2flow_core.device_models m
                                         WHERE m.organization_id = b.organization_id AND m.code = b.target_id)
                     ELSE (SELECT d.name FROM data2flow_core.devices d
                            WHERE d.organization_id = b.organization_id AND d.id::text = b.target_id)
                   END AS target_name,
                   CASE b.target_type
                     WHEN 'SOURCE' THEN (SELECT count(*) FROM data2flow_core.devices d
                                          WHERE d.organization_id = b.organization_id AND d.source_id::text = b.target_id
                                            AND d.status <> 'DELETED')
                     WHEN 'MODEL' THEN (SELECT count(*) FROM data2flow_core.devices d
                                         JOIN data2flow_core.device_models m ON m.id = d.model_id
                                        WHERE d.organization_id = b.organization_id AND m.code = b.target_id AND d.status <> 'DELETED')
                     ELSE (SELECT count(*) FROM data2flow_core.devices d
                            WHERE d.organization_id = b.organization_id AND d.id::text = b.target_id AND d.status <> 'DELETED')
                   END AS device_count
              FROM data2flow_core.script_bindings b""";

    private final JdbcClient jdbc;

    public ScriptBindingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<BindingRow> findByScript(long organizationId, long scriptId) {
        return jdbc.sql(SELECT + " WHERE b.organization_id = :org AND b.script_id = :script ORDER BY b.target_type, b.target_id")
                .param("org", organizationId).param("script", scriptId).query(ScriptBindingRepository::map).list();
    }

    public long countByScript(long organizationId, long scriptId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.script_bindings WHERE organization_id = :org AND script_id = :script")
                .param("org", organizationId).param("script", scriptId).query(Long.class).single();
    }

    /** 같은 대상·같은 종류를 다른 스크립트가 이미 가졌는가(대상마다 종류별 1개) */
    public Optional<Long> findHolder(long organizationId, String kind, String targetType, String targetId, long excludeScriptId) {
        return jdbc.sql("""
                        SELECT script_id FROM data2flow_core.script_bindings
                         WHERE organization_id = :org AND kind = :kind AND target_type = :type AND target_id = :target
                           AND script_id <> :script""")
                .param("org", organizationId).param("kind", kind).param("type", targetType).param("target", targetId)
                .param("script", excludeScriptId).query(Long.class).optional();
    }

    public int deleteByScript(long organizationId, long scriptId) {
        return jdbc.sql("DELETE FROM data2flow_core.script_bindings WHERE organization_id = :org AND script_id = :script")
                .param("org", organizationId).param("script", scriptId).update();
    }

    public long insert(long organizationId, long scriptId, String kind, String targetType, String targetId, String failurePolicy,
                       boolean enabled, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.script_bindings (organization_id, script_id, kind, target_type, target_id,
                                                                    failure_policy, enabled, created_at, updated_at)
                        VALUES (:org, :script, :kind, :type, :target, :policy, :enabled, :now, :now) RETURNING id""")
                .param("org", organizationId).param("script", scriptId).param("kind", kind).param("type", targetType)
                .param("target", targetId).param("policy", failurePolicy).param("enabled", enabled).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    private static BindingRow map(ResultSet rs, int n) throws SQLException {
        return new BindingRow(rs.getLong("id"), rs.getLong("script_id"), rs.getString("kind"), rs.getString("target_type"),
                rs.getString("target_id"), rs.getString("failure_policy"), rs.getBoolean("enabled"), rs.getString("target_name"),
                rs.getLong("device_count"));
    }

    public record BindingRow(long id, long scriptId, String kind, String targetType, String targetId, String failurePolicy,
                             boolean enabled, String targetName, long deviceCount) {
    }
}
