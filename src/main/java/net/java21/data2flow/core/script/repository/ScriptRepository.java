package net.java21.data2flow.core.script.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 스크립트({@code data2flow_core.scripts}, SCR-01·02·05). 모든 조회·수정은 조직 조건을 받는다 */
@Repository
public class ScriptRepository {

    private static final String COLUMNS = """
            s.id, s.organization_id, s.name, s.kind, s.description, s.active_version_id, s.status, s.auto_disabled_at,
            s.auto_disabled_reason, s.config::text AS config, s.log_capture_until, s.version, s.created_by, s.updated_by,
            s.created_at, s.updated_at, uu.name AS updated_by_name""";

    private static final String FROM = """
             FROM data2flow_core.scripts s
             LEFT JOIN data2flow_core.app_users uu ON uu.id = s.updated_by AND uu.organization_id = s.organization_id""";

    private final JdbcClient jdbc;

    public ScriptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ScriptRow> findById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM + " WHERE s.organization_id = :org AND s.id = :id")
                .param("org", organizationId).param("id", id).query(ScriptRepository::map).optional();
    }

    /** 배포·저장·연결 변경을 한 줄로 세운다(동시 배포·동시 저장 직렬화) */
    public Optional<ScriptRow> lockById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM + " WHERE s.organization_id = :org AND s.id = :id FOR UPDATE OF s")
                .param("org", organizationId).param("id", id).query(ScriptRepository::map).optional();
    }

    /** 조직 단위 직렬화(한도 300개 검사와 이름 중복 검사가 동시 생성에 뚫리지 않게) */
    public void lockOrganization(long organizationId) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('data2flow_core.scripts'), CAST(:org AS integer))) x")
                .param("org", (int) organizationId).query(Integer.class).single();
    }

    public long countByOrganization(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.scripts WHERE organization_id = :org")
                .param("org", organizationId).query(Long.class).single();
    }

    public boolean existsName(long organizationId, String name, Long excludeId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.scripts
                                        WHERE organization_id = :org AND name = :name AND (CAST(:exclude AS bigint) IS NULL OR id <> :exclude))""")
                .param("org", organizationId).param("name", name).param("exclude", excludeId).query(Boolean.class).single();
    }

    public long insert(long organizationId, String name, String kind, String description, String configJson, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.scripts (organization_id, name, kind, description, config, created_by, updated_by,
                                                            created_at, updated_at)
                        VALUES (:org, :name, :kind, :description, CAST(:config AS jsonb), :user, :user, :now, :now) RETURNING id""")
                .param("org", organizationId).param("name", name).param("kind", kind).param("description", description)
                .param("config", configJson).param("user", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    /** 이름·설명 수정(낙관적 잠금). 바뀐 행 수 */
    public int updateMeta(long organizationId, long id, long baseVersion, String name, String description, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.scripts SET name = :name, description = :description, version = version + 1,
                               updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .param("name", name).param("description", description).param("user", userId).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", id).param("base", baseVersion).update();
    }

    /** 배포·롤백: ACTIVE 버전 포인터를 바꾸고 새 version을 돌려준다 */
    public int updateActiveVersion(long organizationId, long id, long versionId, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.scripts SET active_version_id = :version, version = version + 1, updated_by = :user,
                               updated_at = :now
                         WHERE organization_id = :org AND id = :id RETURNING version""")
                .param("version", versionId).param("user", userId).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", id).query(Integer.class).single();
    }

    /** 활성·비활성·자동 비활성 전환. 새 version */
    public int updateStatus(long organizationId, long id, String status, Instant autoDisabledAt, String autoDisabledReason,
                            Long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.scripts SET status = :status, auto_disabled_at = :autoAt, auto_disabled_reason = :reason,
                               version = version + 1, updated_by = coalesce(:user, updated_by), updated_at = :now
                         WHERE organization_id = :org AND id = :id RETURNING version""")
                .param("status", status).param("autoAt", Pg.ts(autoDisabledAt)).param("reason", autoDisabledReason)
                .param("user", userId).param("now", Pg.ts(now)).param("org", organizationId).param("id", id)
                .query(Integer.class).single();
    }

    /** 연결 변경 등 스크립트 자체 값은 그대로이고 runtime 묶음이 바뀌는 변경. 새 version */
    public int touch(long organizationId, long id, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.scripts SET version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id RETURNING version""")
                .param("user", userId).param("now", Pg.ts(now)).param("org", organizationId).param("id", id)
                .query(Integer.class).single();
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.scripts WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    /** 다른 기능 테이블이 이 스크립트를 가리키는 수(소스 decode_script_id, 모델 transform·decode_script_id; FK RESTRICT) */
    public long countExternalReferences(long organizationId, long id) {
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM data2flow_core.data_sources WHERE organization_id = :org AND decode_script_id = :id)
                             + (SELECT count(*) FROM data2flow_core.device_models
                                 WHERE organization_id = :org AND (transform_script_id = :id OR decode_script_id = :id))""")
                .param("org", organizationId).param("id", id).query(Long.class).single();
    }

    /** API-SCR-01 목록 */
    public List<ScriptSummaryRow> search(ScriptSearch search, int limit, long offset) {
        return jdbc.sql("""
                        SELECT s.id, s.name, s.kind, s.status, s.description, av.version_no AS active_version_no,
                               av.deployed_at, du.name AS deployed_by_name,
                               dv.id AS draft_id, (dv.static_check ->> 'ok') AS draft_ok,
                               (SELECT count(*) FROM data2flow_core.script_bindings b WHERE b.script_id = s.id AND b.target_type = 'SOURCE') AS sources,
                               (SELECT count(*) FROM data2flow_core.script_bindings b WHERE b.script_id = s.id AND b.target_type = 'MODEL') AS models,
                               (SELECT count(*) FROM data2flow_core.script_bindings b WHERE b.script_id = s.id AND b.target_type = 'DEVICE') AS devices
                        """ + searchFrom() + " ORDER BY s.name, s.id LIMIT :limit OFFSET :offset")
                .params(searchParams(search)).param("limit", limit).param("offset", offset)
                .query(ScriptRepository::mapSummary).list();
    }

    public long countSearch(ScriptSearch search) {
        return jdbc.sql("SELECT count(*) " + searchFrom()).params(searchParams(search)).query(Long.class).single();
    }

    private static String searchFrom() {
        return """
                  FROM data2flow_core.scripts s
                  LEFT JOIN data2flow_core.script_versions av ON av.id = s.active_version_id
                  LEFT JOIN data2flow_core.app_users du ON du.id = av.deployed_by AND du.organization_id = s.organization_id
                  LEFT JOIN data2flow_core.script_versions dv ON dv.script_id = s.id AND dv.status = 'DRAFT'
                 WHERE s.organization_id = :org
                   AND (CAST(:kind AS varchar) IS NULL OR s.kind = :kind)
                   AND (CAST(:status AS varchar) IS NULL OR s.status = :status)
                   AND (:checkFailed = false OR (dv.id IS NOT NULL AND (dv.static_check ->> 'ok') = 'false'))
                   AND (CAST(:targetType AS varchar) IS NULL OR EXISTS (SELECT 1 FROM data2flow_core.script_bindings b
                                                                      WHERE b.script_id = s.id AND b.target_type = :targetType))
                   AND (CAST(:keyword AS varchar) IS NULL OR s.name ILIKE :keyword ESCAPE '\\')
                """;
    }

    private static java.util.Map<String, Object> searchParams(ScriptSearch s) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("org", s.organizationId());
        params.put("kind", s.kind());
        params.put("status", s.status());
        params.put("checkFailed", s.checkFailed());
        params.put("targetType", s.targetType());
        params.put("keyword", s.keyword() == null ? null
                : "%" + s.keyword().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        return params;
    }

    private static ScriptRow map(ResultSet rs, int n) throws SQLException {
        return new ScriptRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("kind"),
                rs.getString("description"), Pg.longOrNull(rs, "active_version_id"), rs.getString("status"),
                Pg.instant(rs, "auto_disabled_at"), rs.getString("auto_disabled_reason"), rs.getString("config"),
                Pg.instant(rs, "log_capture_until"), rs.getInt("version"), rs.getLong("created_by"), rs.getLong("updated_by"),
                rs.getString("updated_by_name"), Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"));
    }

    private static ScriptSummaryRow mapSummary(ResultSet rs, int n) throws SQLException {
        String draftOk = rs.getString("draft_ok");
        Long draftId = Pg.longOrNull(rs, "draft_id");
        Long active = Pg.longOrNull(rs, "active_version_no");
        return new ScriptSummaryRow(rs.getLong("id"), rs.getString("name"), rs.getString("kind"), rs.getString("status"),
                rs.getString("description"), active == null ? null : active.intValue(), draftId != null,
                draftId != null && "false".equals(draftOk), rs.getLong("sources"), rs.getLong("models"), rs.getLong("devices"),
                rs.getString("deployed_by_name"), Pg.instant(rs, "deployed_at"));
    }

    /** 스크립트 한 행. config는 JSON 문자열 */
    public record ScriptRow(long id, long organizationId, String name, String kind, String description, Long activeVersionId,
                            String status, Instant autoDisabledAt, String autoDisabledReason, String config,
                            Instant logCaptureUntil, int version, long createdBy, long updatedBy, String updatedByName,
                            Instant createdAt, Instant updatedAt) {
    }

    /** 목록 검색 조건(조직 조건 포함) */
    public record ScriptSearch(long organizationId, String kind, String status, boolean checkFailed, String targetType,
                               String keyword) {
    }

    public record ScriptSummaryRow(long id, String name, String kind, String status, String description, Integer activeVersionNo,
                                   boolean hasDraft, boolean checkFailed, long sources, long models, long devices,
                                   String lastDeployedBy, Instant lastDeployedAt) {
    }
}
