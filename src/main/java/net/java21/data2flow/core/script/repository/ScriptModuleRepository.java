package net.java21.data2flow.core.script.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 공유 모듈({@code script_modules}·{@code script_module_versions}, SCR-04.01, API-SCR-18·19). 배포된(RELEASED) 버전은 바꾸지 않는다
 * (BR-SCR-13). 모듈을 쓰는 스크립트는 스크립트 버전의 {@code module_refs}(["이름@버전"])로 찾는다.
 */
@Repository
public class ScriptModuleRepository {

    private final JdbcClient jdbc;

    public ScriptModuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ModuleRow> list(long organizationId, int limit, long offset) {
        return jdbc.sql(SELECT + " WHERE m.organization_id = :org ORDER BY m.name LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("limit", limit).param("offset", offset).query(ScriptModuleRepository::map).list();
    }

    public long countByOrganization(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.script_modules WHERE organization_id = :org")
                .param("org", organizationId).query(Long.class).single();
    }

    public Optional<ModuleRow> findById(long organizationId, long id) {
        return jdbc.sql(SELECT + " WHERE m.organization_id = :org AND m.id = :id")
                .param("org", organizationId).param("id", id).query(ScriptModuleRepository::map).optional();
    }

    /** 같은 모듈 변경을 한 번에 하나씩 */
    public Optional<Long> lockById(long organizationId, long id) {
        return jdbc.sql("SELECT id FROM data2flow_core.script_modules WHERE organization_id = :org AND id = :id FOR UPDATE")
                .param("org", organizationId).param("id", id).query(Long.class).optional();
    }

    public boolean existsName(long organizationId, String name) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.script_modules WHERE organization_id = :org AND name = :name)")
                .param("org", organizationId).param("name", name).query(Boolean.class).single();
    }

    public long insert(long organizationId, String name, String description, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.script_modules (organization_id, name, description, created_by, created_at, updated_at)
                        VALUES (:org, :name, :description, :user, :now, :now) RETURNING id""")
                .param("org", organizationId).param("name", name).param("description", description).param("user", userId)
                .param("now", Pg.ts(now)).query(Long.class).single();
    }

    public int updateMeta(long organizationId, long id, String description, Instant now) {
        return jdbc.sql("UPDATE data2flow_core.script_modules SET description = :description, updated_at = :now"
                        + " WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).param("description", description).param("now", Pg.ts(now)).update();
    }

    public List<VersionRow> listVersions(long organizationId, long moduleId) {
        return jdbc.sql("""
                        SELECT id, version_no, status, code, released_at FROM data2flow_core.script_module_versions
                         WHERE organization_id = :org AND module_id = :module ORDER BY version_no""")
                .param("org", organizationId).param("module", moduleId)
                .query((rs, n) -> new VersionRow(rs.getLong("id"), rs.getInt("version_no"), rs.getString("status"), rs.getString("code"),
                        Pg.instant(rs, "released_at")))
                .list();
    }

    /** DRAFT 코드 저장: DRAFT가 있으면 그 행, 없으면 마지막 번호 + 1로 새 DRAFT */
    public void saveDraft(long organizationId, long moduleId, String code, long userId, Instant now) {
        int updated = jdbc.sql("""
                        UPDATE data2flow_core.script_module_versions SET code = :code
                         WHERE organization_id = :org AND module_id = :module AND status = 'DRAFT'""")
                .param("org", organizationId).param("module", moduleId).param("code", code).update();
        if (updated == 0) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.script_module_versions (organization_id, module_id, version_no, code, status, created_by, created_at)
                            SELECT :org, :module, coalesce(max(version_no), 0) + 1, :code, 'DRAFT', :user, :now
                              FROM data2flow_core.script_module_versions WHERE module_id = :module""")
                    .param("org", organizationId).param("module", moduleId).param("code", code).param("user", userId)
                    .param("now", Pg.ts(now)).update();
        }
        jdbc.sql("UPDATE data2flow_core.script_modules SET updated_at = :now WHERE organization_id = :org AND id = :module")
                .param("org", organizationId).param("module", moduleId).param("now", Pg.ts(now)).update();
    }

    /** DRAFT를 RELEASED로(불변). DRAFT가 없으면 빈 값 */
    public Optional<Integer> release(long organizationId, long moduleId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.script_module_versions SET status = 'RELEASED', released_at = :now
                         WHERE organization_id = :org AND module_id = :module AND status = 'DRAFT'
                        RETURNING version_no""")
                .param("org", organizationId).param("module", moduleId).param("now", Pg.ts(now)).query(Integer.class).optional();
    }

    public int deleteVersion(long organizationId, long moduleId, int versionNo) {
        return jdbc.sql("DELETE FROM data2flow_core.script_module_versions WHERE organization_id = :org AND module_id = :module"
                        + " AND version_no = :no")
                .param("org", organizationId).param("module", moduleId).param("no", versionNo).update();
    }

    /**
     * 모듈을 참조하는 스크립트 버전(ACTIVE·DRAFT). versionNo를 주면 그 버전만. module_refs의 문자열 {@code 이름@버전}과
     * 객체 {@code {name, version}} 모두 본다
     */
    public List<UsageRow> findUsage(long organizationId, String moduleName, Integer versionNo) {
        return jdbc.sql("""
                        SELECT DISTINCT s.id AS script_id, s.name AS script_name, r.ref
                          FROM data2flow_core.script_versions v
                          JOIN data2flow_core.scripts s ON s.id = v.script_id AND s.organization_id = v.organization_id
                          CROSS JOIN LATERAL (
                                SELECT CASE WHEN jsonb_typeof(e) = 'string' THEN e #>> '{}'
                                            ELSE (e ->> 'name') || '@' || coalesce(e ->> 'version', e ->> 'versionNo') END AS ref
                                  FROM jsonb_array_elements(CASE WHEN jsonb_typeof(v.module_refs) = 'array' THEN v.module_refs
                                                                 ELSE '[]'::jsonb END) e) r
                         WHERE v.organization_id = :org AND v.status IN ('ACTIVE', 'DRAFT')
                           AND (r.ref = :exact OR (CAST(:exact AS text) IS NULL AND r.ref LIKE :prefix))
                         ORDER BY s.id""")
                .param("org", organizationId).param("exact", versionNo == null ? null : moduleName + "@" + versionNo)
                .param("prefix", moduleName + "@%")
                .query((rs, n) -> new UsageRow(rs.getLong("script_id"), rs.getString("script_name"), rs.getString("ref"))).list();
    }

    /** 실행 묶음(API-SCR-32 modules): 배포된 모듈 버전 전체 */
    @OrganizationScopeExempt("조직 전체 실행 묶음(API-SCR-32). 배포 조직(restriction)으로 좁힌다")
    public List<ReleasedRow> listReleased(Long organizationId, Long restriction) {
        return jdbc.sql("""
                        SELECT m.organization_id, m.name, v.version_no, v.code
                          FROM data2flow_core.script_module_versions v
                          JOIN data2flow_core.script_modules m ON m.id = v.module_id
                         WHERE v.status = 'RELEASED'
                           AND (CAST(:org AS bigint) IS NULL OR m.organization_id = :org)
                           AND (CAST(:restriction AS bigint) IS NULL OR m.organization_id = :restriction)
                         ORDER BY m.organization_id, m.name, v.version_no""")
                .param("org", organizationId).param("restriction", restriction)
                .query((rs, n) -> new ReleasedRow(rs.getLong("organization_id"), rs.getString("name"), rs.getInt("version_no"),
                        rs.getString("code")))
                .list();
    }

    private static final String SELECT = """
            SELECT m.id, m.name, m.description, m.updated_at,
                   (SELECT max(v.version_no) FROM data2flow_core.script_module_versions v
                     WHERE v.module_id = m.id AND v.status = 'RELEASED') AS latest_version_no
              FROM data2flow_core.script_modules m""";

    static ModuleRow map(ResultSet rs, int n) throws SQLException {
        int latest = rs.getInt("latest_version_no");
        Integer latestOrNull = rs.wasNull() ? null : latest;
        return new ModuleRow(rs.getLong("id"), rs.getString("name"), rs.getString("description"), latestOrNull,
                Pg.instant(rs, "updated_at"));
    }

    public record ModuleRow(long id, String name, String description, Integer latestVersionNo, Instant updatedAt) {
    }

    public record VersionRow(long id, int versionNo, String status, String code, Instant releasedAt) {
    }

    public record UsageRow(long scriptId, String scriptName, String ref) {
    }

    public record ReleasedRow(long organizationId, String name, int versionNo, String code) {
    }
}
