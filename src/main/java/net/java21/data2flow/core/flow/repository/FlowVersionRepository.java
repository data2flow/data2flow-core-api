package net.java21.data2flow.core.flow.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 플로우 버전({@code data2flow_core.flow_versions}, FLW-01.06). 플로우당 DRAFT는 하나이고 초안을 저장할 때마다 새 번호를 받는다
 * (편집 기준 버전 baseVersion으로 동시 편집 충돌을 알 수 있게, BR-FLW-09). ACTIVE는 하나(부분 UNIQUE)라 전환은
 * "이전 ACTIVE → ARCHIVED, 대상 → ACTIVE" 순서로 한다.
 */
@Repository
public class FlowVersionRepository {

    private static final String COLUMNS = """
            v.flow_id, v.version_no, v.organization_id, v.state, v.base_version, v.definition::text AS definition, v.definition_hash,
            v.rate_limit_per_sec, v.validation::text AS validation, v.change_summary::text AS change_summary, v.has_control_node,
            v.memo, v.applied_by, au.name AS applied_by_name, v.applied_at, v.created_by, cu.name AS created_by_name, v.created_at""";
    private static final String FROM = """
             FROM data2flow_core.flow_versions v
             LEFT JOIN data2flow_core.app_users au ON au.id = v.applied_by AND au.organization_id = v.organization_id
             LEFT JOIN data2flow_core.app_users cu ON cu.id = v.created_by AND cu.organization_id = v.organization_id""";

    private final JdbcClient jdbc;

    public FlowVersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record VersionRow(UUID flowId, int versionNo, long organizationId, String state, Integer baseVersion, String definition,
                             String definitionHash, int rateLimitPerSec, String validation, String changeSummary, boolean hasControlNode,
                             String memo, Long appliedBy, String appliedByName, Instant appliedAt, long createdBy, String createdByName,
                             Instant createdAt) {
    }

    public Optional<VersionRow> find(long organizationId, UUID flowId, int versionNo) {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM + " WHERE v.organization_id = :org AND v.flow_id = :flow AND v.version_no = :no")
                .param("org", organizationId).param("flow", flowId).param("no", versionNo).query(FlowVersionRepository::map).optional();
    }

    /** 최근 번호부터 */
    public List<VersionRow> listByFlow(long organizationId, UUID flowId) {
        return jdbc.sql("SELECT " + COLUMNS + " " + FROM + " WHERE v.organization_id = :org AND v.flow_id = :flow ORDER BY v.version_no DESC")
                .param("org", organizationId).param("flow", flowId).query(FlowVersionRepository::map).list();
    }

    public int maxVersionNo(long organizationId, UUID flowId) {
        return jdbc.sql("SELECT coalesce(max(version_no), 0) FROM data2flow_core.flow_versions WHERE organization_id = :org AND flow_id = :flow")
                .param("org", organizationId).param("flow", flowId).query(Integer.class).single();
    }

    public void insert(long organizationId, UUID flowId, int versionNo, String state, Integer baseVersion, String definition,
                       String hash, String validation, boolean hasControlNode, long userId, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.flow_versions (flow_id, version_no, organization_id, state, base_version, definition,
                               definition_hash, validation, has_control_node, created_by, created_at, updated_at)
                        VALUES (:flow, :no, :org, :state, :base, CAST(:def AS jsonb), :hash, CAST(:validation AS jsonb), :control, :user,
                                :now, :now)""")
                .param("flow", flowId).param("no", versionNo).param("org", organizationId).param("state", state)
                .param("base", baseVersion).param("def", definition).param("hash", hash).param("validation", validation)
                .param("control", hasControlNode).param("user", userId).param("now", Pg.ts(now)).update();
    }

    /** DRAFT 행 지우기(새 번호로 다시 저장하기 전) */
    public int deleteDraft(long organizationId, UUID flowId, int versionNo) {
        return jdbc.sql("DELETE FROM data2flow_core.flow_versions WHERE organization_id = :org AND flow_id = :flow AND version_no = :no"
                        + " AND state = 'DRAFT'")
                .param("org", organizationId).param("flow", flowId).param("no", versionNo).update();
    }

    public void updateState(long organizationId, UUID flowId, int versionNo, String state, Instant now) {
        jdbc.sql("UPDATE data2flow_core.flow_versions SET state = :state, updated_at = :now"
                        + " WHERE organization_id = :org AND flow_id = :flow AND version_no = :no")
                .param("state", state).param("now", Pg.ts(now)).param("org", organizationId).param("flow", flowId).param("no", versionNo)
                .update();
    }

    /** 적용 직전 검증 결과·변경 요약·메모 기록 */
    public void updateApplyInfo(long organizationId, UUID flowId, int versionNo, String validation, String changeSummary, String memo,
                                Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.flow_versions SET validation = CAST(:validation AS jsonb),
                               change_summary = CAST(:summary AS jsonb), memo = coalesce(:memo, memo), updated_at = :now
                         WHERE organization_id = :org AND flow_id = :flow AND version_no = :no""")
                .param("validation", validation).param("summary", changeSummary).param("memo", memo).param("now", Pg.ts(now))
                .param("org", organizationId).param("flow", flowId).param("no", versionNo).update();
    }

    /** 원자적 전환: 지금 ACTIVE → ARCHIVED, 대상 → ACTIVE(적용자·시각) */
    public void activate(long organizationId, UUID flowId, int versionNo, long userId, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.flow_versions SET state = 'ARCHIVED', updated_at = :now
                         WHERE organization_id = :org AND flow_id = :flow AND state = 'ACTIVE' AND version_no <> :no""")
                .param("now", Pg.ts(now)).param("org", organizationId).param("flow", flowId).param("no", versionNo).update();
        jdbc.sql("""
                        UPDATE data2flow_core.flow_versions SET state = 'ACTIVE', applied_by = :user, applied_at = :now, updated_at = :now
                         WHERE organization_id = :org AND flow_id = :flow AND version_no = :no""")
                .param("user", userId).param("now", Pg.ts(now)).param("org", organizationId).param("flow", flowId).param("no", versionNo)
                .update();
    }

    /** 보관 한도(100개): ACTIVE·DRAFT·승인 대기와 keep 번호를 빼고 오래된 보관 버전부터 지운다 */
    public int deleteOverflow(long organizationId, UUID flowId, int keepVersionNo, int max) {
        return jdbc.sql("""
                        DELETE FROM data2flow_core.flow_versions WHERE organization_id = :org AND flow_id = :flow
                           AND state IN ('ARCHIVED', 'REJECTED') AND version_no <> :keep
                           AND version_no IN (SELECT version_no FROM data2flow_core.flow_versions
                                               WHERE organization_id = :org AND flow_id = :flow
                                               ORDER BY version_no DESC OFFSET :max)
                           AND NOT EXISTS (SELECT 1 FROM data2flow_core.flow_version_approvals a
                                            WHERE a.flow_id = flow_versions.flow_id AND a.version_no = flow_versions.version_no
                                              AND a.decision IS NULL)""")
                .param("org", organizationId).param("flow", flowId).param("keep", keepVersionNo).param("max", max).update();
    }

    private static VersionRow map(ResultSet rs, int row) throws SQLException {
        return new VersionRow(rs.getObject("flow_id", UUID.class), rs.getInt("version_no"), rs.getLong("organization_id"),
                rs.getString("state"), (Integer) rs.getObject("base_version"), rs.getString("definition"), rs.getString("definition_hash"),
                rs.getInt("rate_limit_per_sec"), rs.getString("validation"), rs.getString("change_summary"),
                rs.getBoolean("has_control_node"), rs.getString("memo"), Pg.longOrNull(rs, "applied_by"), rs.getString("applied_by_name"),
                Pg.instant(rs, "applied_at"), rs.getLong("created_by"), rs.getString("created_by_name"), Pg.instant(rs, "created_at"));
    }
}
