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
 * 스크립트 버전({@code data2flow_core.script_versions}, SCR-03.04). 스크립트당 DRAFT·ACTIVE는 각각 하나(부분 UNIQUE 색인)이고,
 * 배포는 "이전 ACTIVE → ARCHIVED, 대상 → ACTIVE"를 한 트랜잭션에서 이 순서로 한다(색인 충돌 방지).
 */
@Repository
public class ScriptVersionRepository {

    private static final String COLUMNS = """
            v.id, v.organization_id, v.script_id, v.version_no, v.code, v.code_sha256, v.status, v.static_check::text AS static_check,
            v.module_refs::text AS module_refs, v.deploy_memo, v.forced, v.force_reason, v.test_result::text AS test_result,
            v.deployed_by, du.name AS deployed_by_name, v.deployed_at, v.created_by, cu.name AS created_by_name, v.created_at,
            v.updated_at""";

    private static final String FROM = """
             FROM data2flow_core.script_versions v
             LEFT JOIN data2flow_core.app_users du ON du.id = v.deployed_by AND du.organization_id = v.organization_id
             LEFT JOIN data2flow_core.app_users cu ON cu.id = v.created_by AND cu.organization_id = v.organization_id""";

    private final JdbcClient jdbc;

    public ScriptVersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<VersionRow> findById(long organizationId, long scriptId, long versionId) {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM + " WHERE v.organization_id = :org AND v.script_id = :script AND v.id = :id")
                .param("org", organizationId).param("script", scriptId).param("id", versionId)
                .query(ScriptVersionRepository::map).optional();
    }

    public Optional<VersionRow> findDraft(long organizationId, long scriptId) {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM + " WHERE v.organization_id = :org AND v.script_id = :script AND v.status = 'DRAFT'")
                .param("org", organizationId).param("script", scriptId).query(ScriptVersionRepository::map).optional();
    }

    /** 최근 버전부터(최대 limit개, 상세의 versions[]) */
    public List<VersionRow> listByScript(long organizationId, long scriptId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM + " " + """
                         WHERE v.organization_id = :org AND v.script_id = :script ORDER BY v.version_no DESC LIMIT :limit""")
                .param("org", organizationId).param("script", scriptId).param("limit", limit)
                .query(ScriptVersionRepository::map).list();
    }

    /** 마지막 버전 번호(없으면 0). DRAFT 저장의 baseVersionNo와 비교한다 */
    public int maxVersionNo(long organizationId, long scriptId) {
        return jdbc.sql("""
                        SELECT coalesce(max(version_no), 0) FROM data2flow_core.script_versions
                         WHERE organization_id = :org AND script_id = :script""")
                .param("org", organizationId).param("script", scriptId).query(Integer.class).single();
    }

    public long countByScript(long organizationId, long scriptId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.script_versions WHERE organization_id = :org AND script_id = :script")
                .param("org", organizationId).param("script", scriptId).query(Long.class).single();
    }

    public long insertDraft(long organizationId, long scriptId, int versionNo, String code, String sha256, String staticCheckJson,
                            long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.script_versions (organization_id, script_id, version_no, code, code_sha256, status,
                                                                    static_check, created_by, created_at, updated_at)
                        VALUES (:org, :script, :no, :code, :sha, 'DRAFT', CAST(:check AS jsonb), :user, :now, :now) RETURNING id""")
                .param("org", organizationId).param("script", scriptId).param("no", versionNo).param("code", code)
                .param("sha", sha256).param("check", staticCheckJson).param("user", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    /** DRAFT 다시 저장(같은 행 갱신, 상태 전이 "DRAFT → DRAFT"). 저장자는 created_by에 남긴다(화면의 savedBy) */
    public int updateDraft(long organizationId, long versionId, String code, String sha256, String staticCheckJson, long userId,
                           Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.script_versions SET code = :code, code_sha256 = :sha, static_check = CAST(:check AS jsonb),
                               created_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'DRAFT'""")
                .param("code", code).param("sha", sha256).param("check", staticCheckJson).param("user", userId)
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", versionId).update();
    }

    /** 이전 ACTIVE → ARCHIVED */
    public int archiveActive(long organizationId, long scriptId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.script_versions SET status = 'ARCHIVED', updated_at = :now
                         WHERE organization_id = :org AND script_id = :script AND status = 'ACTIVE'""")
                .param("now", Pg.ts(now)).param("org", organizationId).param("script", scriptId).update();
    }

    /** 대상 → ACTIVE(배포 메모·배포자·강제 여부·테스트 결과). 롤백은 같은 행을 다시 ACTIVE로(코드 복사 없음) */
    public int activate(long organizationId, long versionId, String memo, boolean forced, String forceReason, String testResultJson,
                        long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.script_versions SET status = 'ACTIVE', deploy_memo = :memo, forced = :forced,
                               force_reason = :reason, test_result = CAST(:test AS jsonb), deployed_by = :user, deployed_at = :now,
                               updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("memo", memo).param("forced", forced).param("reason", forceReason).param("test", testResultJson)
                .param("user", userId).param("now", Pg.ts(now)).param("org", organizationId).param("id", versionId).update();
    }

    /**
     * 보관 정리(SCR-04.03, BR-SCR-14): keep개를 넘으면 가장 오래된 ARCHIVED부터 지운다. ACTIVE·DRAFT는 지우지 않는다.
     *
     * @return 지운 버전 수
     */
    public int deleteOldArchived(long organizationId, long scriptId, int keep) {
        return jdbc.sql("""
                        DELETE FROM data2flow_core.script_versions WHERE id IN (
                            SELECT id FROM data2flow_core.script_versions
                             WHERE organization_id = :org AND script_id = :script AND status = 'ARCHIVED'
                             ORDER BY version_no
                             LIMIT GREATEST(0, (SELECT count(*) FROM data2flow_core.script_versions
                                                 WHERE organization_id = :org AND script_id = :script) - :keep))""")
                .param("org", organizationId).param("script", scriptId).param("keep", keep).update();
    }

    private static VersionRow map(ResultSet rs, int n) throws SQLException {
        return new VersionRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("script_id"), rs.getInt("version_no"),
                rs.getString("code"), rs.getString("code_sha256"), rs.getString("status"), rs.getString("static_check"),
                rs.getString("module_refs"), rs.getString("deploy_memo"), rs.getBoolean("forced"), rs.getString("force_reason"),
                rs.getString("test_result"), Pg.longOrNull(rs, "deployed_by"), rs.getString("deployed_by_name"),
                Pg.instant(rs, "deployed_at"), rs.getLong("created_by"), rs.getString("created_by_name"),
                Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"));
    }

    /** 버전 한 행. staticCheck·testResult는 JSON 문자열 */
    public record VersionRow(long id, long organizationId, long scriptId, int versionNo, String code, String codeSha256, String status,
                             String staticCheck, String moduleRefs, String deployMemo, boolean forced, String forceReason,
                             String testResult, Long deployedBy, String deployedByName, Instant deployedAt, long createdBy,
                             String createdByName, Instant createdAt, Instant updatedAt) {
    }
}
