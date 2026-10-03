package net.java21.data2flow.core.flow.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 제어 노드 배포 승인({@code data2flow_core.flow_version_approvals}, FLW-05.06·UC-FLW-23, API-FLW-24) */
@Repository
public class FlowApprovalRepository {

    private static final String SELECT = """
            SELECT a.id, a.organization_id, a.flow_id, f.name AS flow_name, a.version_no, a.kind, a.memo, a.requested_by,
                   ru.name AS requested_by_name, a.requested_at, a.decision, a.decided_by, du.name AS decided_by_name, a.decided_at,
                   a.reason, v.has_control_node
              FROM data2flow_core.flow_version_approvals a
              JOIN data2flow_core.flows f ON f.id = a.flow_id AND f.organization_id = a.organization_id
              LEFT JOIN data2flow_core.flow_versions v ON v.flow_id = a.flow_id AND v.version_no = a.version_no
              LEFT JOIN data2flow_core.app_users ru ON ru.id = a.requested_by AND ru.organization_id = a.organization_id
              LEFT JOIN data2flow_core.app_users du ON du.id = a.decided_by AND du.organization_id = a.organization_id""";

    private final JdbcClient jdbc;

    public FlowApprovalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ApprovalRow(long id, long organizationId, UUID flowId, String flowName, int versionNo, String kind, String memo,
                              long requestedBy, String requestedByName, Instant requestedAt, String decision, Long decidedBy,
                              String decidedByName, Instant decidedAt, String reason, boolean hasControlNode) {

        /** PENDING·APPROVED·REJECTED */
        public String status() {
            return decision == null ? "PENDING" : decision;
        }
    }

    public long insert(long organizationId, UUID flowId, int versionNo, String kind, String memo, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.flow_version_approvals (organization_id, flow_id, version_no, kind, memo, requested_by,
                                                                           requested_at)
                        VALUES (:org, :flow, :no, :kind, :memo, :user, :now) RETURNING id""")
                .param("org", organizationId).param("flow", flowId).param("no", versionNo).param("kind", kind).param("memo", memo)
                .param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public Optional<ApprovalRow> findById(long organizationId, long approvalId) {
        return jdbc.sql(SELECT + " WHERE a.organization_id = :org AND a.id = :id").param("org", organizationId).param("id", approvalId)
                .query(FlowApprovalRepository::map).optional();
    }

    public Optional<ApprovalRow> lockPending(long organizationId, long approvalId) {
        return jdbc.sql("SELECT id FROM data2flow_core.flow_version_approvals WHERE organization_id = :org AND id = :id"
                        + " AND decision IS NULL FOR UPDATE")
                .param("org", organizationId).param("id", approvalId).query(Long.class).optional()
                .flatMap(id -> findById(organizationId, id));
    }

    /** 플로우의 결정 전 요청(새 적용 요청이 오면 이전 것을 대체) */
    public List<Long> listPendingIds(long organizationId, UUID flowId) {
        return jdbc.sql("SELECT id FROM data2flow_core.flow_version_approvals WHERE organization_id = :org AND flow_id = :flow"
                        + " AND decision IS NULL")
                .param("org", organizationId).param("flow", flowId).query(Long.class).list();
    }

    public void decide(long organizationId, long approvalId, String decision, long userId, String reason, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.flow_version_approvals SET decision = :decision, decided_by = :user, decided_at = :now,
                               reason = :reason
                         WHERE organization_id = :org AND id = :id AND decision IS NULL""")
                .param("decision", decision).param("user", userId).param("now", Pg.ts(now)).param("reason", reason)
                .param("org", organizationId).param("id", approvalId).update();
    }

    public List<ApprovalRow> list(long organizationId, String status, Long requestedBy, int limit, long offset) {
        Map<String, Object> params = new HashMap<>();
        String where = where(organizationId, status, requestedBy, params);
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql(SELECT + where + " ORDER BY a.requested_at DESC, a.id DESC LIMIT :limit OFFSET :offset").params(params)
                .query(FlowApprovalRepository::map).list();
    }

    public long count(long organizationId, String status, Long requestedBy) {
        Map<String, Object> params = new HashMap<>();
        return jdbc.sql("SELECT count(*) FROM data2flow_core.flow_version_approvals a" + where(organizationId, status, requestedBy, params))
                .params(params).query(Long.class).single();
    }

    private static String where(long organizationId, String status, Long requestedBy, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE a.organization_id = :org");
        params.put("org", organizationId);
        if ("PENDING".equals(status)) {
            sql.append(" AND a.decision IS NULL");
        } else if (status != null) {
            sql.append(" AND a.decision = :decision");
            params.put("decision", status);
        }
        if (requestedBy != null) {
            sql.append(" AND a.requested_by = :requestedBy");
            params.put("requestedBy", requestedBy);
        }
        return sql.toString();
    }

    private static ApprovalRow map(ResultSet rs, int row) throws SQLException {
        return new ApprovalRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getObject("flow_id", UUID.class), rs.getString("flow_name"),
                rs.getInt("version_no"), rs.getString("kind"), rs.getString("memo"), rs.getLong("requested_by"),
                rs.getString("requested_by_name"), Pg.instant(rs, "requested_at"), rs.getString("decision"), Pg.longOrNull(rs, "decided_by"),
                rs.getString("decided_by_name"), Pg.instant(rs, "decided_at"), rs.getString("reason"), rs.getBoolean("has_control_node"));
    }
}
