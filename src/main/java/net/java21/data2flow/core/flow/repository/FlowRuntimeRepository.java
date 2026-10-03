package net.java21.data2flow.core.flow.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 엔진이 보고한 적용 상태 사본({@code flow_apply_reports}, EVT-FLW-02)과 오버레이({@code flow_overlays}, 바이패스·디버그).
 * 적용 보고의 원천은 flow-engine의 {@code data2flow_flow.flow_instance_versions}이고 core는 이벤트로 받은 마지막 값만 둔다.
 */
@Repository
public class FlowRuntimeRepository {

    private final JdbcClient jdbc;

    public FlowRuntimeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ApplyReport(String instanceId, int appliedVersion, Integer overlayRevision, String error, Instant reportedAt) {
    }

    public record Overlay(List<String> bypass, List<String> debug, int revision) {
    }

    public void upsertReport(long organizationId, UUID flowId, String instanceId, int appliedVersion, Long overlayRevision,
                             long compileMs, String error, Instant at) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.flow_apply_reports (flow_id, instance_id, organization_id, applied_version,
                               overlay_revision, compile_ms, error, reported_at)
                        VALUES (:flow, :instance, :org, :version, :overlay, :compile, :error, :at)
                        ON CONFLICT (flow_id, instance_id) DO UPDATE SET applied_version = EXCLUDED.applied_version,
                               overlay_revision = EXCLUDED.overlay_revision, compile_ms = EXCLUDED.compile_ms, error = EXCLUDED.error,
                               reported_at = EXCLUDED.reported_at
                         WHERE data2flow_core.flow_apply_reports.reported_at <= EXCLUDED.reported_at""")
                .param("flow", flowId).param("instance", instanceId).param("org", organizationId).param("version", appliedVersion)
                .param("overlay", overlayRevision == null ? null : overlayRevision.intValue()).param("compile", (int) Math.min(compileMs, Integer.MAX_VALUE))
                .param("error", error == null ? null : error.substring(0, Math.min(error.length(), 500))).param("at", Pg.ts(at)).update();
    }

    /** 최근 보고(지난 기간 안) */
    public List<ApplyReport> listReports(long organizationId, UUID flowId, Instant since) {
        return jdbc.sql("""
                        SELECT instance_id, applied_version, overlay_revision, error, reported_at FROM data2flow_core.flow_apply_reports
                         WHERE organization_id = :org AND flow_id = :flow AND reported_at >= :since ORDER BY instance_id""")
                .param("org", organizationId).param("flow", flowId).param("since", Pg.ts(since))
                .query((rs, n) -> new ApplyReport(rs.getString("instance_id"), rs.getInt("applied_version"),
                        (Integer) rs.getObject("overlay_revision"), rs.getString("error"), Pg.instant(rs, "reported_at")))
                .list();
    }

    public Optional<Overlay> findOverlay(long organizationId, UUID flowId) {
        return jdbc.sql("SELECT bypass, debug, revision FROM data2flow_core.flow_overlays WHERE organization_id = :org AND flow_id = :flow")
                .param("org", organizationId).param("flow", flowId)
                .query((rs, n) -> new Overlay(Pg.stringList(rs, "bypass"), Pg.stringList(rs, "debug"), rs.getInt("revision")))
                .optional();
    }
}
