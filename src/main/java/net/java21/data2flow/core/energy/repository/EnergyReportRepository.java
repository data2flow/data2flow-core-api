package net.java21.data2flow.core.energy.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 월간 에너지 리포트 저장소(ENE-01.05, {@code energy_reports}) */
@Repository
public class EnergyReportRepository {

    private static final String REPORT = """
            SELECT r.id, r.organization_id, r.scope_space_id, s.name AS scope_space_name, r.month, r.format, r.status, r.progress, r.request::text AS request,
                   r.model::text AS model, r.file_object_key, r.sha256, r.size_bytes, r.failure_reason, r.created_by, u.name AS created_by_name,
                   r.created_at, r.updated_at
              FROM data2flow_core.energy_reports r
              LEFT JOIN data2flow_core.spaces s ON s.id = r.scope_space_id
              LEFT JOIN data2flow_core.app_users u ON u.id = r.created_by
            """;

    private final JdbcClient jdbc;

    public EnergyReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ReportRow(long id, long organizationId, Long scopeSpaceId, String scopeSpaceName, LocalDate month, String format, String status,
                            int progress, String requestJson, String modelJson, String fileObjectKey, String sha256, Long sizeBytes, String failureReason,
                            long createdBy, String createdByName, Instant createdAt, Instant updatedAt) {
    }

    public record Claimed(long id, long organizationId, long createdBy) {
    }

    public long insert(long orgId, Long scopeSpaceId, LocalDate month, String format, String requestJson, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.energy_reports (organization_id, scope_space_id, month, format, request, created_by, created_at, updated_at)
                        VALUES (:org, :scope, :month, :format, CAST(:request AS jsonb), :by, :now, :now) RETURNING id""")
                .param("org", orgId).param("scope", scopeSpaceId).param("month", month).param("format", format).param("request", requestJson)
                .param("by", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    /** 조직 리포트(최신 순). {@code scopeSpaceId}가 있으면 그 범위만 */
    public List<ReportRow> list(long orgId, Long scopeSpaceId) {
        return jdbc.sql(REPORT + " WHERE r.organization_id = :org AND (CAST(:scope AS bigint) IS NULL OR r.scope_space_id = :scope) ORDER BY r.id DESC")
                .param("org", orgId).param("scope", scopeSpaceId).query(EnergyReportRepository::report).list();
    }

    public Optional<ReportRow> find(long orgId, long id) {
        return jdbc.sql(REPORT + " WHERE r.organization_id = :org AND r.id = :id").param("org", orgId).param("id", id)
                .query(EnergyReportRepository::report).optional();
    }

    /** 생성할 리포트 하나를 잡는다(여러 파드 중 하나만). staleBefore보다 먼저 잡혀 멈춘 것도 다시 잡는다 */
    public Optional<Claimed> claimNext(Instant now, Instant staleBefore) {
        return jdbc.sql("""
                        UPDATE data2flow_core.energy_reports SET claimed_at = :now, attempts = attempts + 1, progress = 10, updated_at = :now
                         WHERE id = (SELECT id FROM data2flow_core.energy_reports
                                      WHERE status = 'GENERATING' AND (claimed_at IS NULL OR claimed_at < :stale)
                                      ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)
                        RETURNING id, organization_id, created_by, attempts""")
                .param("now", Pg.ts(now)).param("stale", Pg.ts(staleBefore))
                .query((rs, n) -> new Claimed(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("created_by"))).optional();
    }

    public int attempts(long id) {
        return jdbc.sql("SELECT attempts FROM data2flow_core.energy_reports WHERE id = :id").param("id", id).query(Integer.class).single();
    }

    public boolean ready(long orgId, long id, String modelJson, String fileKey, String sha256, Long sizeBytes, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.energy_reports SET status = 'READY', progress = 100, model = CAST(:model AS jsonb), file_object_key = :key,
                               sha256 = :sha, size_bytes = :size, failure_reason = NULL, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'GENERATING'""")
                .param("model", modelJson).param("key", fileKey).param("sha", sha256).param("size", sizeBytes).param("now", Pg.ts(now))
                .param("org", orgId).param("id", id).update() > 0;
    }

    public void failed(long orgId, long id, String reason, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.energy_reports SET status = 'FAILED', failure_reason = :reason, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'GENERATING'""")
                .param("reason", reason.length() > 300 ? reason.substring(0, 300) : reason).param("now", Pg.ts(now)).param("org", orgId).param("id", id)
                .update();
    }

    /** 다시 시도할 수 있게 놓아준다(analytics 일시 장애) */
    public void release(long id, Instant now) {
        jdbc.sql("UPDATE data2flow_core.energy_reports SET claimed_at = NULL, progress = 0, updated_at = :now WHERE id = :id AND status = 'GENERATING'")
                .param("id", id).param("now", Pg.ts(now)).update();
    }

    private static ReportRow report(ResultSet rs, int n) throws SQLException {
        long size = rs.getLong("size_bytes");
        Long sizeBytes = rs.wasNull() ? null : size;
        return new ReportRow(rs.getLong("id"), rs.getLong("organization_id"), Pg.longOrNull(rs, "scope_space_id"), rs.getString("scope_space_name"),
                rs.getObject("month", LocalDate.class), rs.getString("format"), rs.getString("status"), rs.getInt("progress"), rs.getString("request"),
                rs.getString("model"), rs.getString("file_object_key"), rs.getString("sha256"), sizeBytes, rs.getString("failure_reason"),
                rs.getLong("created_by"), rs.getString("created_by_name"), Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"));
    }
}
