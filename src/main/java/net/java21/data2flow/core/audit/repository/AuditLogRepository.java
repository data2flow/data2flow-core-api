package net.java21.data2flow.core.audit.repository;

import net.java21.data2flow.contracts.audit.AuditEvent;
import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 감사 로그 저장소(IAM-06). INSERT와 SELECT만 있다. 수정·삭제 경로는 없고 DB 트리거가 UPDATE·DELETE·TRUNCATE를 거부한다
 * (V202610040910, IAM-06.02, BR-IAM-21). 월 파티션 생성은 DDL이라 트리거와 무관하다.
 */
@Repository
public class AuditLogRepository {

    private static final String COLUMNS = """
            id, organization_id, occurred_at, actor_type, actor_id, actor_name, action, target_type, target_id, result,
            detail::text AS detail, cause::text AS cause, host(ip) AS ip, user_agent, request_id""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuditLogRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(AuditEvent e) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.audit_logs (organization_id, occurred_at, actor_type, actor_id, actor_name, action,
                            target_type, target_id, result, detail, cause, ip, user_agent, request_id)
                        VALUES (:org, :at, :actorType, :actorId, :actorName, :action, :targetType, :targetId, :result,
                            CAST(:detail AS jsonb), CAST(:cause AS jsonb), CAST(:ip AS inet), :userAgent, :requestId)""")
                .param("org", e.organizationId())
                .param("at", Pg.ts(e.occurredAt()))
                .param("actorType", e.actorType().name())
                .param("actorId", e.actorId())
                .param("actorName", e.actorName())
                .param("action", e.action())
                .param("targetType", e.targetType())
                .param("targetId", e.targetId())
                .param("result", e.result().name())
                .param("detail", e.detail() == null ? null : json.writeValueAsString(e.detail()))
                .param("cause", e.cause() == null ? null : json.writeValueAsString(e.cause()))
                .param("ip", Pg.inetOrNull(e.ip()))
                .param("userAgent", e.userAgent())
                .param("requestId", e.requestId())
                .update();
    }

    /** 감사 행위자 이름 스냅샷(익명화 뒤에도 의미 유지, domain-model §2.8) */
    @OrganizationScopeExempt("감사 기록 시 행위자 이름 스냅샷. 행위자 ID는 감사 이벤트를 만든 서비스가 정한다")
    public Optional<String> findActorName(long userId) {
        return jdbc.sql("SELECT name FROM data2flow_core.app_users WHERE id = :id AND anonymized_at IS NULL")
                .param("id", userId).query(String.class).optional();
    }

    public List<AuditLogRow> search(AuditSearch s, int fetchSize) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append("""
                 FROM data2flow_core.audit_logs
                WHERE organization_id = :org AND occurred_at >= :from AND occurred_at < :to""");
        JdbcClient.StatementSpec spec;
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("org", s.organizationId());
        params.put("from", Pg.ts(s.from()));
        params.put("to", Pg.ts(s.to()));
        if (s.actorType() != null) {
            sql.append(" AND actor_type = :actorType");
            params.put("actorType", s.actorType());
        }
        if (s.actor() != null) {
            sql.append(" AND (actor_id = :actor OR actor_name ILIKE :actorLike)");
            params.put("actor", s.actor());
            params.put("actorLike", likePrefix(s.actor()));
        }
        if (s.action() != null) {
            sql.append(" AND action = :action");
            params.put("action", s.action());
        }
        if (s.targetType() != null) {
            sql.append(" AND target_type = :targetType");
            params.put("targetType", s.targetType());
        }
        if (s.targetId() != null) {
            sql.append(" AND target_id = :targetId");
            params.put("targetId", s.targetId());
        }
        if (s.result() != null) {
            sql.append(" AND result = :result");
            params.put("result", s.result());
        }
        if (s.ip() != null) {
            sql.append(" AND ip = CAST(:ip AS inet)");
            params.put("ip", s.ip());
        }
        if (s.cursorOccurredAt() != null) {
            sql.append(" AND (occurred_at, id) < (:cOccurredAt, :cId)");
            params.put("cOccurredAt", Pg.ts(s.cursorOccurredAt()));
            params.put("cId", s.cursorId());
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT :limit");
        params.put("limit", fetchSize);
        spec = jdbc.sql(sql.toString()).params(params);
        return spec.query(this::map).list();
    }

    public Optional<AuditLogRow> findByIdAndOrganizationId(long id, long organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.audit_logs WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId).query(this::map).optional();
    }

    /** 월 파티션을 미리 만든다(ERD README §10). 이미 있으면 그대로 둔다 */
    @OrganizationScopeExempt("파티션 관리 DDL. 조직과 무관")
    public void createMonthlyPartition(String name, Instant from, Instant to) {
        if (!name.matches("audit_logs_y\\d{4}m\\d{2}")) {
            throw new IllegalArgumentException("파티션 이름 형식: " + name);
        }
        jdbc.sql("CREATE TABLE IF NOT EXISTS data2flow_core." + name + " PARTITION OF data2flow_core.audit_logs FOR VALUES FROM ('"
                + from + "') TO ('" + to + "')").update();
    }

    private static String likePrefix(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private AuditLogRow map(ResultSet rs, int row) throws SQLException {
        return new AuditLogRow(rs.getLong("id"), rs.getLong("organization_id"), Pg.instant(rs, "occurred_at"),
                rs.getString("actor_type"), rs.getString("actor_id"), rs.getString("actor_name"), rs.getString("action"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getString("result"), rs.getString("detail"),
                rs.getString("cause"), rs.getString("ip"), rs.getString("user_agent"), rs.getString("request_id"));
    }

    /** 감사 로그 한 행. detail·cause는 JSON 문자열 */
    public record AuditLogRow(long id, long organizationId, Instant occurredAt, String actorType, String actorId,
                              String actorName, String action, String targetType, String targetId, String result,
                              String detail, String cause, String ip, String userAgent, String requestId) {
    }

    /** 검색 조건(API-IAM-50). 커서는 마지막 행의 (occurredAt, id) */
    public record AuditSearch(long organizationId, Instant from, Instant to, String actorType, String actor, String action,
                              String targetType, String targetId, String result, String ip,
                              Instant cursorOccurredAt, Long cursorId) {
    }
}
