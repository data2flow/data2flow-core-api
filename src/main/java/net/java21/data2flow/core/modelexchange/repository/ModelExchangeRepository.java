package net.java21.data2flow.core.modelexchange.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 모델 가져오기·내보내기(DEV-03.04)와 표준 형식 내보내기(DEV-13.04)가 읽고 쓰는 것: 스크립트 코드, 범위 안의 공간·기기·점,
 * 내보내기 작업({@code device_export_jobs}), NGSI-LD 주기 전송 설정({@code ngsi_pushes}).
 */
@Repository
public class ModelExchangeRepository {

    private final JdbcClient jdbc;

    public ModelExchangeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- 스크립트

    /** 스크립트 이름·종류·코드(적용 중 버전, 없으면 최신 초안) */
    public Optional<ScriptCode> findScriptCode(long organizationId, long scriptId) {
        return jdbc.sql("""
                        SELECT s.kind, s.name, v.code FROM data2flow_core.scripts s
                          JOIN data2flow_core.script_versions v ON v.script_id = s.id AND v.organization_id = s.organization_id
                         WHERE s.organization_id = :org AND s.id = :id
                         ORDER BY (v.id = s.active_version_id) DESC, v.version_no DESC LIMIT 1""")
                .param("org", organizationId).param("id", scriptId)
                .query((rs, n) -> new ScriptCode(rs.getString("kind"), rs.getString("name"), rs.getString("code"))).optional();
    }

    /** 가져온 코드는 아직 정적 검사 전(편집기에서 저장하면 pipeline이 검사한다) */
    static final String UNCHECKED = "{\"ok\":false,\"problems\":[{\"line\":1,\"col\":1,\"severity\":\"ERROR\","
            + "\"code\":\"SCRIPT_CHECK_UNAVAILABLE\",\"message\":\"가져온 스크립트입니다. 편집기에서 저장하면 검사합니다\"}]}";

    public boolean existsScriptName(long organizationId, String name) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.scripts WHERE organization_id = :org AND name = :name)")
                .param("org", organizationId).param("name", name).query(Boolean.class).single();
    }

    /** 가져온 스크립트를 v1 DRAFT로 만든다(AT-DEV-09.4). 스크립트 ID */
    public long insertDraftScript(long organizationId, String name, String kind, String code, String sha256, long userId, Instant now) {
        long id = jdbc.sql("""
                        INSERT INTO data2flow_core.scripts (organization_id, name, kind, description, config, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :name, :kind, :desc, CAST('{}' AS jsonb), :user, :user, :now, :now) RETURNING id""")
                .param("org", organizationId).param("name", name).param("kind", kind).param("desc", "모델 가져오기(DEV-03.04)")
                .param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO data2flow_core.script_versions (organization_id, script_id, version_no, code, code_sha256, status, static_check,
                                                                    created_by, created_at, updated_at)
                        VALUES (:org, :script, 1, :code, :sha, 'DRAFT', CAST(:check AS jsonb), :user, :now, :now)""")
                .param("org", organizationId).param("script", id).param("code", code).param("sha", sha256).param("user", userId)
                .param("check", UNCHECKED)
                .param("now", Pg.ts(now)).update();
        return id;
    }

    public record ScriptCode(String kind, String name, String code) {
    }

    // ---- 표준 형식 내보내기 범위

    /** 범위 공간(하위 포함)·기기. 둘 다 비면 조직 전체. allowed가 있으면 그 공간만 */
    public List<ExportDevice> findDevices(long organizationId, Collection<Long> spaceIds, Collection<Long> deviceIds, Set<Long> allowed) {
        boolean all = spaceIds.isEmpty() && deviceIds.isEmpty();
        return jdbc.sql("""
                        SELECT d.id, d.name, d.external_id, d.space_id, d.model_id, m.code AS model_code, m.name AS model_name, m.vendor,
                               st.latest::text AS latest, st.last_seen_at, st.battery,
                               ARRAY(SELECT mm.metric_key FROM data2flow_core.model_metrics mm
                                      WHERE mm.model_id = d.model_id AND mm.organization_id = d.organization_id ORDER BY mm.metric_key) AS model_metrics
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_core.device_models m ON m.id = d.model_id AND m.organization_id = d.organization_id
                          LEFT JOIN data2flow_pipeline.device_state st ON st.device_id = d.id AND st.organization_id = d.organization_id
                         WHERE d.organization_id = :org AND d.status IN ('ACTIVE', 'INACTIVE')
                           AND (:all OR d.id = ANY(CAST(:ids AS bigint[]))
                                OR d.space_id IN (SELECT ch.id FROM data2flow_core.spaces ch JOIN data2flow_core.spaces p ON ch.path LIKE p.path || '%'
                                                   WHERE p.organization_id = :org AND ch.organization_id = :org AND p.id = ANY(CAST(:spaces AS bigint[]))))
                           AND (CAST(:allowed AS bigint[]) IS NULL OR d.space_id = ANY(CAST(:allowed AS bigint[])))
                         ORDER BY d.id""")
                .param("org", organizationId).param("all", all).param("ids", Pg.bigintArray(deviceIds)).param("spaces", Pg.bigintArray(spaceIds))
                .param("allowed", allowed == null ? null : Pg.bigintArray(allowed))
                .query((rs, n) -> new ExportDevice(rs.getLong("id"), rs.getString("name"), rs.getString("external_id"), Pg.longOrNull(rs, "space_id"),
                        Pg.longOrNull(rs, "model_id"), rs.getString("model_code"), rs.getString("model_name"), rs.getString("vendor"),
                        rs.getString("latest"), Pg.instant(rs, "last_seen_at"), rs.getBigDecimal("battery"), Pg.stringList(rs, "model_metrics")))
                .list();
    }

    /** 기기들이 놓인 공간과 그 조상(Brick 위치 계층) */
    public List<ExportSpace> findSpacesWithAncestors(long organizationId, Collection<Long> spaceIds) {
        if (spaceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT DISTINCT a.id, a.parent_id, a.type, a.name, a.path FROM data2flow_core.spaces s
                          JOIN data2flow_core.spaces a ON s.path LIKE a.path || '%' AND a.organization_id = s.organization_id
                         WHERE s.organization_id = :org AND s.id = ANY(CAST(:ids AS bigint[])) ORDER BY a.path""")
                .param("org", organizationId).param("ids", Pg.bigintArray(spaceIds))
                .query((rs, n) -> new ExportSpace(rs.getLong("id"), Pg.longOrNull(rs, "parent_id"), rs.getString("type"), rs.getString("name")))
                .list();
    }

    /** 기기의 시맨틱 점(DEV-13.01) */
    public List<ExportPoint> findPoints(long organizationId, Collection<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT e.device_id, e.equip_class, p.id, p.metric_key, p.point_type, p.quantity, p.tags
                          FROM data2flow_core.points p JOIN data2flow_core.equipment e ON e.id = p.equipment_id AND e.organization_id = p.organization_id
                         WHERE p.organization_id = :org AND e.device_id = ANY(CAST(:ids AS bigint[])) ORDER BY e.device_id, p.id""")
                .param("org", organizationId).param("ids", Pg.bigintArray(deviceIds))
                .query((rs, n) -> new ExportPoint(rs.getLong("device_id"), rs.getString("equip_class"), rs.getLong("id"), rs.getString("metric_key"),
                        rs.getString("point_type"), rs.getString("quantity"), Pg.stringList(rs, "tags")))
                .list();
    }

    public record ExportDevice(long id, String name, String externalId, Long spaceId, Long modelId, String modelCode, String modelName,
                               String vendor, String latestJson, Instant lastSeenAt, java.math.BigDecimal battery, List<String> modelMetricKeys) {
    }

    public record ExportSpace(long id, Long parentId, String type, String name) {
    }

    public record ExportPoint(long deviceId, String equipClass, long pointId, String metricKey, String pointType, String quantity,
                              List<String> tags) {
    }

    // ---- 내보내기 작업

    public long insertJob(long organizationId, String format, String scopeJson, boolean includeValues, long createdBy, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.device_export_jobs (organization_id, format, scope, include_values, status, created_by, created_at, updated_at)
                        VALUES (:org, :format, CAST(:scope AS jsonb), :values, 'RUNNING', :by, :now, :now) RETURNING id""")
                .param("org", organizationId).param("format", format).param("scope", scopeJson).param("values", includeValues)
                .param("by", createdBy).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public int finishJob(long organizationId, long id, String status, String fileRef, String reportJson, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.device_export_jobs SET status = :status, file_ref = :file, report = CAST(:report AS jsonb), updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("status", status).param("file", fileRef).param("report", reportJson).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", id).update();
    }

    public Optional<Job> findJob(long organizationId, long id) {
        return jdbc.sql("""
                        SELECT id, format, status, file_ref, report::text AS report, include_values, created_by, created_at, updated_at
                          FROM data2flow_core.device_export_jobs WHERE organization_id = :org AND id = :id""")
                .param("org", organizationId).param("id", id)
                .query((rs, n) -> new Job(rs.getLong("id"), rs.getString("format"), rs.getString("status"), rs.getString("file_ref"),
                        rs.getString("report"), rs.getBoolean("include_values"), rs.getLong("created_by"), Pg.instant(rs, "created_at"),
                        Pg.instant(rs, "updated_at")))
                .optional();
    }

    public record Job(long id, String format, String status, String fileRef, String reportJson, boolean includeValues, long createdBy,
                      Instant createdAt, Instant updatedAt) {
    }

    // ---- NGSI-LD 주기 전송

    public long insertPush(long organizationId, long outputConnectionId, String scopeJson, int intervalSec, long createdBy, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.ngsi_pushes (organization_id, output_connection_id, scope, interval_sec, created_by, created_at, updated_at)
                        VALUES (:org, :out, CAST(:scope AS jsonb), :interval, :by, :now, :now) RETURNING id""")
                .param("org", organizationId).param("out", outputConnectionId).param("scope", scopeJson).param("interval", intervalSec)
                .param("by", createdBy).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public List<Push> listPushes(long organizationId) {
        return jdbc.sql("""
                        SELECT id, output_connection_id, scope::text AS scope, interval_sec, enabled, last_sent_at, last_error
                          FROM data2flow_core.ngsi_pushes WHERE organization_id = :org ORDER BY id""")
                .param("org", organizationId).query(ModelExchangeRepository::push).list();
    }

    public Optional<Push> findPush(long organizationId, long id) {
        return jdbc.sql("""
                        SELECT id, output_connection_id, scope::text AS scope, interval_sec, enabled, last_sent_at, last_error
                          FROM data2flow_core.ngsi_pushes WHERE organization_id = :org AND id = :id""")
                .param("org", organizationId).param("id", id).query(ModelExchangeRepository::push).optional();
    }

    public int deletePush(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.ngsi_pushes WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    private static Push push(ResultSet rs, int n) throws SQLException {
        return new Push(rs.getLong("id"), rs.getLong("output_connection_id"), rs.getString("scope"), rs.getInt("interval_sec"),
                rs.getBoolean("enabled"), Pg.instant(rs, "last_sent_at"), rs.getString("last_error"));
    }

    public record Push(long id, long outputConnectionId, String scopeJson, int intervalSec, boolean enabled, Instant lastSentAt,
                       String lastError) {
    }
}
