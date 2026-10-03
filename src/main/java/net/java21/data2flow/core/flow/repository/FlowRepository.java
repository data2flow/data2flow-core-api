package net.java21.data2flow.core.flow.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 플로우 머리({@code data2flow_core.flows}, FLW-01). 삭제는 status=DELETED(소프트)이고 조회에서 뺀다.
 * 목록의 공간 조건·공간 범위는 현재 정의(초안이 있으면 초안, 없으면 ACTIVE)의 노드 대상 공간과 관련 공간(related_space_ids)으로 본다.
 */
@Repository
public class FlowRepository {

    static final String COLUMNS = """
            f.id, f.organization_id, f.name, f.purpose, f.description, f.kind, f.status, f.status_reason, f.environment, f.active_version,
            f.draft_version, f.pause_mode, f.auto_pause_on_degraded, f.error_rate_threshold, f.rate_limit_per_sec, f.catch_flow_id,
            f.owner_user_id, f.related_space_ids, f.tags, f.version, f.created_by, f.updated_by, u.name AS updated_by_name,
            f.created_at, f.updated_at""";
    static final String FROM = """
             FROM data2flow_core.flows f
             LEFT JOIN data2flow_core.app_users u ON u.id = f.updated_by AND u.organization_id = f.organization_id""";
    /** 현재 정의의 대상 공간 ID(문자열·숫자 모두) */
    static final String SPACES = """
            ARRAY(SELECT DISTINCT (sp #>> '{}')::bigint
                    FROM data2flow_core.flow_versions cv,
                         jsonb_path_query(cv.definition, '$.nodes[*].config.target.spaceId') sp
                   WHERE cv.flow_id = f.id AND cv.organization_id = f.organization_id
                     AND cv.version_no = coalesce(f.draft_version, f.active_version)
                     AND (sp #>> '{}') ~ '^[0-9]{1,18}$')""";

    private final JdbcClient jdbc;

    public FlowRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record FlowRow(UUID id, long organizationId, String name, String purpose, String description, String kind, String status,
                          String statusReason, String environment, Integer activeVersion, Integer draftVersion, String pauseMode,
                          boolean autoPauseOnDegraded, BigDecimal errorRateThreshold, int rateLimitPerSec, UUID catchFlowId,
                          Long ownerUserId, List<Long> relatedSpaceIds, List<String> tags, int version, long createdBy, long updatedBy,
                          String updatedByName, Instant createdAt, Instant updatedAt, List<Long> spaceIds, boolean hasControlNode) {
    }

    /**
     * 목록 조건.
     *
     * @param scope 사용자 공간 범위(null이면 제한 없음). 대상 공간이 없는 플로우는 범위가 제한된 사용자에게 보이지 않는다
     */
    public record Search(long organizationId, String keyword, Long ownerUserId, Collection<String> statuses, String kind,
                         String environment, Long spaceId, Collection<Long> scope, String orderBy) {
    }

    private static String select() {
        return "SELECT " + COLUMNS + ", " + SPACES + " AS space_ids, "
                + "EXISTS (SELECT 1 FROM data2flow_core.flow_versions hv WHERE hv.flow_id = f.id AND hv.organization_id = f.organization_id"
                + " AND hv.version_no = coalesce(f.draft_version, f.active_version) AND hv.has_control_node) AS has_control_node " + FROM;
    }

    public Optional<FlowRow> findById(long organizationId, UUID flowId) {
        return jdbc.sql(select() + " WHERE f.organization_id = :org AND f.id = :id AND f.status <> 'DELETED'")
                .param("org", organizationId).param("id", flowId).query(FlowRepository::map).optional();
    }

    /** 같은 트랜잭션에서 행 잠금(적용·상태 변경 직렬화) */
    public boolean lockById(long organizationId, UUID flowId) {
        return jdbc.sql("SELECT 1 FROM data2flow_core.flows WHERE organization_id = :org AND id = :id AND status <> 'DELETED' FOR UPDATE")
                .param("org", organizationId).param("id", flowId).query(Integer.class).optional().isPresent();
    }

    public boolean existsName(long organizationId, String name, UUID exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.flows WHERE organization_id = :org AND name = :name
                                         AND status <> 'DELETED' AND (CAST(:except AS uuid) IS NULL OR id <> :except))""")
                .param("org", organizationId).param("name", name).param("except", exceptId).query(Boolean.class).single();
    }

    public long countRunning(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.flows WHERE organization_id = :org AND status IN ('ACTIVE', 'PAUSED', 'DEGRADED')")
                .param("org", organizationId).query(Long.class).single();
    }

    public void insert(UUID id, long organizationId, String name, String description, String environment, int draftVersion,
                       List<Long> relatedSpaceIds, long userId, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.flows (id, organization_id, name, description, kind, status, environment, draft_version,
                               related_space_ids, owner_user_id, created_by, updated_by, created_at, updated_at)
                        VALUES (:id, :org, :name, :description, 'FLOW', 'DRAFT', :env, :draft, CAST(:spaces AS bigint[]), :user, :user, :user,
                                :now, :now)""")
                .param("id", id).param("org", organizationId).param("name", name).param("description", description)
                .param("env", environment).param("draft", draftVersion).param("spaces", Pg.bigintArray(relatedSpaceIds))
                .param("user", userId).param("now", Pg.ts(now)).update();
    }

    public void updateDraftVersion(long organizationId, UUID flowId, Integer draftVersion, long userId, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.flows SET draft_version = :draft, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("draft", draftVersion).param("user", userId).param("now", Pg.ts(now)).param("org", organizationId)
                .param("id", flowId).update();
    }

    /** 적용·롤백·승인 반영: ACTIVE 버전·초안·상태·실행 한도 */
    public void updateApplied(long organizationId, UUID flowId, int activeVersion, Integer draftVersion, String status,
                              int rateLimitPerSec, long userId, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.flows SET active_version = :active, draft_version = :draft, status = :status,
                               status_reason = NULL, rate_limit_per_sec = :rate, version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("active", activeVersion).param("draft", draftVersion).param("status", status).param("rate", rateLimitPerSec)
                .param("user", userId).param("now", Pg.ts(now)).param("org", organizationId).param("id", flowId).update();
    }

    public void updateStatus(long organizationId, UUID flowId, String status, String reason, Long userId, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.flows SET status = :status, status_reason = :reason, version = version + 1,
                               updated_by = coalesce(CAST(:user AS bigint), updated_by), updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("status", status).param("reason", reason).param("user", userId).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", flowId).update();
    }

    public void updatePauseMode(long organizationId, UUID flowId, String pauseMode) {
        jdbc.sql("UPDATE data2flow_core.flows SET pause_mode = :mode WHERE organization_id = :org AND id = :id")
                .param("mode", pauseMode).param("org", organizationId).param("id", flowId).update();
    }

    /** API-FLW-10 설정 변경(전체 값). 바뀐 행 수(낙관적 잠금 version) */
    public int updateSettings(long organizationId, UUID flowId, int baseVersion, String name, String purpose, String description,
                              List<String> tags, String pauseMode, boolean autoPause, BigDecimal errorRateThreshold, UUID catchFlowId,
                              Long ownerUserId, List<Long> relatedSpaceIds, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.flows SET name = :name, purpose = :purpose, description = :description,
                               tags = CAST(:tags AS text[]), pause_mode = :pause, auto_pause_on_degraded = :auto,
                               error_rate_threshold = :threshold, catch_flow_id = :catch, owner_user_id = :owner,
                               related_space_ids = CAST(:spaces AS bigint[]), version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .param("name", name).param("purpose", purpose).param("description", description).param("tags", Pg.textArray(tags))
                .param("pause", pauseMode).param("auto", autoPause).param("threshold", errorRateThreshold).param("catch", catchFlowId)
                .param("owner", ownerUserId).param("spaces", Pg.bigintArray(relatedSpaceIds)).param("user", userId)
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", flowId).param("base", baseVersion).update();
    }

    public List<FlowRow> search(Search s, int limit, long offset) {
        Map<String, Object> params = new HashMap<>();
        String where = where(s, params);
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql(select() + where + " ORDER BY " + s.orderBy() + " LIMIT :limit OFFSET :offset").params(params)
                .query(FlowRepository::map).list();
    }

    public long countSearch(Search s) {
        Map<String, Object> params = new HashMap<>();
        return jdbc.sql("SELECT count(*) " + FROM + where(s, params)).params(params).query(Long.class).single();
    }

    private static String where(Search s, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE f.organization_id = :org AND f.status <> 'DELETED'");
        params.put("org", s.organizationId());
        if (s.keyword() != null) {
            sql.append(" AND (f.name ILIKE :kw OR f.purpose ILIKE :kw)");
            params.put("kw", "%" + s.keyword().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (s.ownerUserId() != null) {
            sql.append(" AND f.owner_user_id = :owner");
            params.put("owner", s.ownerUserId());
        }
        if (s.statuses() != null && !s.statuses().isEmpty()) {
            sql.append(" AND f.status = ANY(CAST(:statuses AS text[]))");
            params.put("statuses", Pg.textArray(s.statuses()));
        }
        if (s.kind() != null) {
            sql.append(" AND f.kind = :kind");
            params.put("kind", s.kind());
        }
        if (s.environment() != null) {
            sql.append(" AND f.environment = :env");
            params.put("env", s.environment());
        }
        if (s.spaceId() != null) {
            sql.append(" AND (:space = ANY(f.related_space_ids) OR :space = ANY(").append(SPACES).append("))");
            params.put("space", s.spaceId());
        }
        if (s.scope() != null) {
            sql.append(" AND (f.related_space_ids && CAST(:scope AS bigint[]) OR ").append(SPACES).append(" && CAST(:scope AS bigint[]))");
            params.put("scope", Pg.bigintArray(s.scope()));
        }
        return sql.toString();
    }

    /** 내부(엔진, API-FLW-80): 실행 대상(ACTIVE·DEGRADED·PAUSED, 적용 버전 있음) */
    public List<FlowRow> listRuntime(long organizationId) {
        return jdbc.sql(select() + " WHERE f.organization_id = :org AND f.status IN ('ACTIVE', 'DEGRADED', 'PAUSED')"
                        + " AND f.active_version IS NOT NULL ORDER BY f.id")
                .param("org", organizationId).query(FlowRepository::map).list();
    }

    /** 실행 대상 목록 버전(API-FLW-80 sinceVersion): 적용·상태·설정이 바뀔 때마다 오르는 flows.version의 합 + 플로우 수 */
    public long runtimeVersion(long organizationId) {
        return jdbc.sql("SELECT coalesce(sum(version), 0) + count(*) FROM data2flow_core.flows WHERE organization_id = :org")
                .param("org", organizationId).query(Long.class).single();
    }

    /** 이벤트 처리용(엔진 보고). 조직은 이벤트 봉투에서 */
    @OrganizationScopeExempt("엔진 이벤트의 flowId(UUID 전역 고유)로 찾고 조직을 함께 비교한다")
    public Optional<Long> findOrganization(UUID flowId) {
        return jdbc.sql("SELECT organization_id FROM data2flow_core.flows WHERE id = :id").param("id", flowId).query(Long.class).optional();
    }

    private static FlowRow map(ResultSet rs, int row) throws SQLException {
        return new FlowRow(rs.getObject("id", UUID.class), rs.getLong("organization_id"), rs.getString("name"), rs.getString("purpose"),
                rs.getString("description"), rs.getString("kind"), rs.getString("status"), rs.getString("status_reason"),
                rs.getString("environment"), (Integer) rs.getObject("active_version"), (Integer) rs.getObject("draft_version"),
                rs.getString("pause_mode"), rs.getBoolean("auto_pause_on_degraded"), rs.getBigDecimal("error_rate_threshold"),
                rs.getInt("rate_limit_per_sec"), rs.getObject("catch_flow_id", UUID.class), Pg.longOrNull(rs, "owner_user_id"),
                Pg.longList(rs, "related_space_ids"), Pg.stringList(rs, "tags"), rs.getInt("version"), rs.getLong("created_by"),
                rs.getLong("updated_by"), rs.getString("updated_by_name"), Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"),
                Pg.longList(rs, "space_ids"), rs.getBoolean("has_control_node"));
    }
}
