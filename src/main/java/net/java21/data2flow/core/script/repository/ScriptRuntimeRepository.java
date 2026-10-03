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
 * 실행 엔진(pipeline·flow-engine) 쪽 내부 API가 쓰는 조회: 실행 묶음(API-SCR-32), 적용 보고(API-SCR-34), 자동 비활성(API-SCR-33).
 * 조직 없이 들어오는 내부 호출이 있어 일부 메서드는 조직 조건 예외이고, 호출하는 서비스가 반드시 배포 조직
 * ({@code DeploymentOrganization}, ADR-030)으로 좁힌다.
 */
@Repository
public class ScriptRuntimeRepository {

    private final JdbcClient jdbc;

    public ScriptRuntimeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 내부 API가 스크립트 ID만 받을 때 조직을 찾는다. 결과는 반드시 배포 조직 안인지 확인한다 */
    @OrganizationScopeExempt("내부 API(API-SCR-33·34)는 스크립트 ID만 받는다. 서비스가 DeploymentOrganization.includes로 좁힌다")
    public Optional<Long> findOrganizationOfScript(long scriptId) {
        return jdbc.sql("SELECT organization_id FROM data2flow_core.scripts WHERE id = :id")
                .param("id", scriptId).query(Long.class).optional();
    }

    /**
     * 실행 묶음: ENABLED이고 ACTIVE 버전이 있는 스크립트(비활성·자동 비활성·미배포 스크립트는 빠지고, 실행 엔진은 그 단계를 건너뛴다).
     *
     * @param organizationId 한 조직만. null이면 restriction 조건만(모든 조직 또는 배포 조직)
     * @param restriction    배포 조직(ADR-030). null이면 제한 없음
     */
    @OrganizationScopeExempt("조직 전체 실행 묶음(API-SCR-32). 배포 조직(restriction)으로 좁힌다")
    public List<RuntimeScriptRow> listRuntimeScripts(Long organizationId, Long restriction) {
        return jdbc.sql("""
                        SELECT s.id, s.organization_id, s.kind, s.config::text AS config, v.id AS version_id, v.version_no, v.code,
                               v.code_sha256, v.module_refs::text AS module_refs
                          FROM data2flow_core.scripts s
                          JOIN data2flow_core.script_versions v ON v.id = s.active_version_id AND v.status = 'ACTIVE'
                         WHERE s.status = 'ENABLED'
                           AND (CAST(:org AS bigint) IS NULL OR s.organization_id = :org)
                           AND (CAST(:restriction AS bigint) IS NULL OR s.organization_id = :restriction)
                         ORDER BY s.organization_id, s.id""")
                .param("org", organizationId).param("restriction", restriction)
                .query(ScriptRuntimeRepository::mapScript).list();
    }

    /** 실행 묶음의 연결(listRuntimeScripts와 같은 조건) */
    @OrganizationScopeExempt("조직 전체 실행 묶음(API-SCR-32). 배포 조직(restriction)으로 좁힌다")
    public List<RuntimeBindingRow> listRuntimeBindings(Long organizationId, Long restriction) {
        return jdbc.sql("""
                        SELECT b.script_id, b.target_type, b.target_id, b.failure_policy, b.enabled
                          FROM data2flow_core.script_bindings b
                          JOIN data2flow_core.scripts s ON s.id = b.script_id
                         WHERE s.status = 'ENABLED' AND s.active_version_id IS NOT NULL
                           AND (CAST(:org AS bigint) IS NULL OR s.organization_id = :org)
                           AND (CAST(:restriction AS bigint) IS NULL OR s.organization_id = :restriction)
                         ORDER BY b.script_id, b.target_type, b.target_id""")
                .param("org", organizationId).param("restriction", restriction)
                .query((rs, n) -> new RuntimeBindingRow(rs.getLong("script_id"), rs.getString("target_type"), rs.getString("target_id"),
                        rs.getString("failure_policy"), rs.getBoolean("enabled")))
                .list();
    }

    /** 버전이 이 스크립트의 것인가 */
    public boolean existsVersion(long organizationId, long scriptId, long versionId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.script_versions
                                        WHERE organization_id = :org AND script_id = :script AND id = :id)""")
                .param("org", organizationId).param("script", scriptId).param("id", versionId).query(Boolean.class).single();
    }

    /** 적용 보고 저장(같은 인스턴스가 다시 보고하면 시각만 갱신, 멱등) */
    public void upsertAck(long organizationId, long scriptId, long versionId, String instance, Instant appliedAt, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.script_deploy_acks (organization_id, script_id, version_id, instance, applied_at, received_at)
                        VALUES (:org, :script, :version, :instance, :applied, :now)
                        ON CONFLICT (version_id, instance) DO UPDATE SET applied_at = EXCLUDED.applied_at, received_at = EXCLUDED.received_at""")
                .param("org", organizationId).param("script", scriptId).param("version", versionId).param("instance", instance)
                .param("applied", Pg.ts(appliedAt)).param("now", Pg.ts(now)).update();
    }

    /** 이 버전을 적용했다고 보고한 인스턴스 */
    public List<AckRow> listAcks(long organizationId, long versionId) {
        return jdbc.sql("""
                        SELECT instance, applied_at FROM data2flow_core.script_deploy_acks
                         WHERE organization_id = :org AND version_id = :version ORDER BY instance""")
                .param("org", organizationId).param("version", versionId)
                .query((rs, n) -> new AckRow(rs.getString("instance"), Pg.instant(rs, "applied_at"))).list();
    }

    /** 최근에 보고한 적 있는 인스턴스 수(적용 대상 total 추정) */
    public long countInstancesSince(long organizationId, Instant since) {
        return jdbc.sql("""
                        SELECT count(DISTINCT instance) FROM data2flow_core.script_deploy_acks
                         WHERE organization_id = :org AND received_at >= :since""")
                .param("org", organizationId).param("since", Pg.ts(since)).query(Long.class).single();
    }

    private static RuntimeScriptRow mapScript(ResultSet rs, int n) throws SQLException {
        return new RuntimeScriptRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("kind"), rs.getString("config"),
                rs.getLong("version_id"), rs.getInt("version_no"), rs.getString("code"), rs.getString("code_sha256"),
                rs.getString("module_refs"));
    }

    public record RuntimeScriptRow(long scriptId, long organizationId, String kind, String config, long versionId, int versionNo,
                                   String code, String codeSha256, String moduleRefs) {
    }

    public record RuntimeBindingRow(long scriptId, String targetType, String targetId, String failurePolicy, boolean enabled) {
    }

    public record AckRow(String instance, Instant appliedAt) {
    }
}
