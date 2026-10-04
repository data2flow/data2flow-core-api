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
 * 스크립트 운영(SCR-03.05·04.02·05.01·05.02): 설정값·설정 판·로그 수집 시각({@code scripts}), 배포 표시({@code script_versions}),
 * 오류 스냅샷·운영 로그(pipeline 소유 {@code data2flow_pipeline.script_errors}·{@code script_logs}, 읽기만).
 */
@Repository
public class ScriptOpsRepository {

    private final JdbcClient jdbc;

    public ScriptOpsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 설정값 저장(낙관적 잠금). 설정 판도 1 올린다. 새 행 버전, 기준이 다르면 빈 값 */
    public Optional<ConfigResult> updateConfig(long organizationId, long scriptId, int baseVersion, String configJson, long userId,
                                               Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.scripts
                           SET config = CAST(:config AS jsonb), config_revision = config_revision + 1, version = version + 1,
                               updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base
                        RETURNING version, config_revision""")
                .param("org", organizationId).param("id", scriptId).param("base", baseVersion).param("config", configJson)
                .param("user", userId).param("now", Pg.ts(now))
                .query((rs, n) -> new ConfigResult(rs.getInt("version"), rs.getInt("config_revision"))).optional();
    }

    public int updateLogCapture(long organizationId, long scriptId, Instant until) {
        return jdbc.sql("UPDATE data2flow_core.scripts SET log_capture_until = :until WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", scriptId).param("until", until == null ? null : Pg.ts(until)).update();
    }

    /** 기간 안 배포 표시(API-SCR-12 deployMarks) */
    public List<DeployMark> findDeployMarks(long organizationId, long scriptId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT version_no, deployed_at FROM data2flow_core.script_versions
                         WHERE organization_id = :org AND script_id = :script AND deployed_at >= :from AND deployed_at < :to
                         ORDER BY deployed_at""")
                .param("org", organizationId).param("script", scriptId).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query((rs, n) -> new DeployMark(rs.getInt("version_no"), Pg.instant(rs, "deployed_at"))).list();
    }

    /** 오류 스냅샷(최근부터, pipeline이 스크립트별 100건만 남긴다) */
    public List<ErrorRow> findErrors(long organizationId, long scriptId, int limit, long offset) {
        return jdbc.sql("""
                        SELECT id, occurred_at, version_no, error_code, message, line, col, device_id, input_snapshot::text AS input_snapshot
                          FROM data2flow_pipeline.script_errors
                         WHERE organization_id = :org AND script_id = :script
                         ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset""")
                .param("org", organizationId).param("script", scriptId).param("limit", limit).param("offset", offset)
                .query(ScriptOpsRepository::mapError).list();
    }

    public long countErrors(long organizationId, long scriptId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_pipeline.script_errors WHERE organization_id = :org AND script_id = :script")
                .param("org", organizationId).param("script", scriptId).query(Long.class).single();
    }

    /** 운영 로그(최근부터). from이 있으면 그 뒤만 */
    public List<LogRow> findLogs(long organizationId, long scriptId, Instant from, int limit, long offset) {
        return jdbc.sql("""
                        SELECT at, version_no, device_id, message FROM data2flow_pipeline.script_logs
                         WHERE organization_id = :org AND script_id = :script AND (CAST(:from AS timestamptz) IS NULL OR at >= CAST(:from AS timestamptz))
                         ORDER BY at DESC, id DESC LIMIT :limit OFFSET :offset""")
                .param("org", organizationId).param("script", scriptId).param("from", from == null ? null : Pg.ts(from))
                .param("limit", limit).param("offset", offset)
                .query((rs, n) -> new LogRow(Pg.instant(rs, "at"), rs.getInt("version_no"), Pg.longOrNull(rs, "device_id"),
                        rs.getString("message")))
                .list();
    }

    public long countLogs(long organizationId, long scriptId, Instant from) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_pipeline.script_logs
                         WHERE organization_id = :org AND script_id = :script AND (CAST(:from AS timestamptz) IS NULL OR at >= CAST(:from AS timestamptz))""")
                .param("org", organizationId).param("script", scriptId).param("from", from == null ? null : Pg.ts(from))
                .query(Long.class).single();
    }

    /** 모델(코드)의 기기 ID(재처리 제안, 최대 limit개) */
    public List<Long> findDeviceIdsByModelCode(long organizationId, String modelCode, int limit) {
        return jdbc.sql("""
                        SELECT d.id FROM data2flow_core.devices d
                          JOIN data2flow_core.device_models m ON m.id = d.model_id AND m.organization_id = d.organization_id
                         WHERE d.organization_id = :org AND m.code = :code ORDER BY d.id LIMIT :limit""")
                .param("org", organizationId).param("code", modelCode).param("limit", limit).query(Long.class).list();
    }

    /** 기기 → 소스(재처리는 소스 단위, API-ING-10) */
    public java.util.Map<Long, Long> findDeviceSources(long organizationId, java.util.Collection<Long> deviceIds) {
        java.util.Map<Long, Long> out = new java.util.LinkedHashMap<>();
        if (deviceIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT id, source_id FROM data2flow_core.devices
                         WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[])) AND source_id IS NOT NULL ORDER BY id""")
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds))
                .query((rs, n) -> out.put(rs.getLong("id"), rs.getLong("source_id"))).list();
        return out;
    }

    /** 스크립트 버전의 가져오는 모듈(["이름@버전"]) 저장 */
    public int updateModuleRefs(long organizationId, long versionId, String moduleRefsJson) {
        return jdbc.sql("UPDATE data2flow_core.script_versions SET module_refs = CAST(:refs AS jsonb) WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", versionId).param("refs", moduleRefsJson).update();
    }

    static ErrorRow mapError(ResultSet rs, int n) throws SQLException {
        int line = rs.getInt("line");
        Integer lineOrNull = rs.wasNull() ? null : line;
        int col = rs.getInt("col");
        Integer colOrNull = rs.wasNull() ? null : col;
        return new ErrorRow(rs.getLong("id"), Pg.instant(rs, "occurred_at"), rs.getInt("version_no"), rs.getString("error_code"),
                rs.getString("message"), lineOrNull, colOrNull, Pg.longOrNull(rs, "device_id"), rs.getString("input_snapshot"));
    }

    public record ConfigResult(int version, int configRevision) {
    }

    public record DeployMark(int versionNo, Instant at) {
    }

    public record ErrorRow(long id, Instant occurredAt, int versionNo, String errorCode, String message, Integer line, Integer col,
                           Long deviceId, String inputSnapshot) {
    }

    public record LogRow(Instant at, int versionNo, Long deviceId, String message) {
    }
}
