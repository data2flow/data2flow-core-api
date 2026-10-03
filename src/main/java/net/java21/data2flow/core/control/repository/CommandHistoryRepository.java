package net.java21.data2flow.core.control.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 명령 이력·액추에이터 상태 구간 읽기(ACT-04.03 API-ACT-02, TSD-01.03 API-TSD-05). 원천은 action 소유 {@code data2flow_action}이고
 * core-api는 읽기만 한다(conventions §6 예외). 기기 이름·플로우 이름·사용자 이름은 core 표에서 붙인다(화면 "플로우 고온이면 냉방 v13 · n-act-1").
 */
@Repository
public class CommandHistoryRepository {

    private final JdbcClient jdbc;

    public CommandHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 검색 조건.
     *
     * @param spaceIds 이 공간들의 기기만(null이면 제한 없음)
     * @param cursorAt 커서(requested_at, id) — 이보다 오래된 것만
     */
    public record Search(long organizationId, Long deviceId, Collection<Long> spaceIds, Instant from, Instant to, String sourceType,
                         String status, String capability, Instant cursorAt, UUID cursorId, int fetchSize) {
    }

    public record CommandRow(UUID id, long deviceId, String deviceName, String capability, String command, String args,
                             String priority, String source, String sourceType, String flowName, String userName, String status,
                             String statusReason, Instant validUntil, Instant executeAfter, Instant expectedDeliveryAt,
                             Instant requestedAt, String idempotencyKey) {
    }

    public record EventRow(UUID commandId, Instant at, String toStatus, String reason) {
    }

    public record StateRow(String capability, String attribute, String value, String label, Instant validFrom, Instant validTo,
                           String source) {
    }

    public List<CommandRow> search(Search s) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.device_id, d.name AS device_name, c.capability, c.command, c.args::text AS args, c.priority,
                       c.source::text AS source, c.source_type, f.name AS flow_name, u.name AS user_name, c.status, c.status_reason,
                       c.valid_until, c.execute_after, c.expected_delivery_at, c.requested_at, c.idempotency_key
                  FROM data2flow_action.commands c
                  LEFT JOIN data2flow_core.devices d ON d.id = c.device_id AND d.organization_id = c.organization_id
                  LEFT JOIN data2flow_core.flows f ON f.organization_id = c.organization_id
                        AND c.source ->> 'flowId' ~ '^[0-9a-fA-F-]{36}$' AND f.id = CAST(c.source ->> 'flowId' AS uuid)
                  LEFT JOIN data2flow_core.app_users u ON u.organization_id = c.organization_id
                        AND c.source ->> 'userId' ~ '^[0-9]{1,18}$' AND u.id = CAST(c.source ->> 'userId' AS bigint)
                 WHERE c.organization_id = :org""");
        if (s.deviceId() != null) {
            sql.append(" AND c.device_id = :device");
        }
        if (s.spaceIds() != null) {
            sql.append(" AND d.space_id = ANY(CAST(:spaces AS bigint[]))");
        }
        if (s.from() != null) {
            sql.append(" AND c.requested_at >= :from");
        }
        if (s.to() != null) {
            sql.append(" AND c.requested_at < :to");
        }
        if (s.sourceType() != null) {
            sql.append(" AND c.source_type = :sourceType");
        }
        if (s.status() != null) {
            sql.append(" AND c.status = :status");
        }
        if (s.capability() != null) {
            sql.append(" AND c.capability = :capability");
        }
        if (s.cursorAt() != null) {
            sql.append(" AND (c.requested_at, c.id) < (:cursorAt, :cursorId)");
        }
        sql.append(" ORDER BY c.requested_at DESC, c.id DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("org", s.organizationId()).param("limit", s.fetchSize());
        if (s.deviceId() != null) {
            spec = spec.param("device", s.deviceId());
        }
        if (s.spaceIds() != null) {
            spec = spec.param("spaces", Pg.bigintArray(s.spaceIds()));
        }
        if (s.from() != null) {
            spec = spec.param("from", Pg.ts(s.from()));
        }
        if (s.to() != null) {
            spec = spec.param("to", Pg.ts(s.to()));
        }
        if (s.sourceType() != null) {
            spec = spec.param("sourceType", s.sourceType());
        }
        if (s.status() != null) {
            spec = spec.param("status", s.status());
        }
        if (s.capability() != null) {
            spec = spec.param("capability", s.capability());
        }
        if (s.cursorAt() != null) {
            spec = spec.param("cursorAt", Pg.ts(s.cursorAt())).param("cursorId", s.cursorId());
        }
        return spec.query(CommandHistoryRepository::map).list();
    }

    public List<EventRow> listEvents(long organizationId, Collection<UUID> commandIds) {
        if (commandIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT command_id, at, to_status, reason FROM data2flow_action.command_events
                         WHERE organization_id = :org AND command_id = ANY(:ids) ORDER BY command_id, at, id""")
                .param("org", organizationId).param("ids", new ArrayList<>(commandIds).toArray(new UUID[0]))
                .query((rs, n) -> new EventRow(rs.getObject("command_id", UUID.class), Pg.instant(rs, "at"), rs.getString("to_status"),
                        rs.getString("reason")))
                .list();
    }

    /** 출처 표시 이름: 플로우 ID → 이름(ADR-043, action은 ID만 저장) */
    public java.util.Map<String, String> findFlowNames(long organizationId, Collection<UUID> flowIds) {
        java.util.Map<String, String> names = new java.util.HashMap<>();
        if (flowIds.isEmpty()) {
            return names;
        }
        jdbc.sql("SELECT id, name FROM data2flow_core.flows WHERE organization_id = :org AND id = ANY(:ids)")
                .param("org", organizationId).param("ids", flowIds.toArray(new UUID[0]))
                .query((rs, n) -> names.put(rs.getObject("id", UUID.class).toString(), rs.getString("name"))).list();
        return names;
    }

    /** 출처 표시 이름: 사용자 ID → 이름 */
    public java.util.Map<String, String> findUserNames(long organizationId, Collection<Long> userIds) {
        java.util.Map<String, String> names = new java.util.HashMap<>();
        if (userIds.isEmpty()) {
            return names;
        }
        jdbc.sql("SELECT id, name FROM data2flow_core.app_users WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))")
                .param("org", organizationId).param("ids", Pg.bigintArray(userIds))
                .query((rs, n) -> names.put(Long.toString(rs.getLong("id")), rs.getString("name"))).list();
        return names;
    }

    /** 공간과 하위 공간 ID(core 공간 트리) */
    public List<Long> listSubtreeIds(long organizationId, long spaceId) {
        return jdbc.sql("""
                        SELECT s.id FROM data2flow_core.spaces s
                          JOIN data2flow_core.spaces p ON p.id = :space AND p.organization_id = s.organization_id
                         WHERE s.organization_id = :org AND s.path LIKE p.path || '%'""")
                .param("org", organizationId).param("space", spaceId).query(Long.class).list();
    }

    /** 액추에이터 상태 구간(API-TSD-05 actuator=true). 기간과 겹치는 구간 */
    public List<StateRow> listStateIntervals(long organizationId, long deviceId, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT capability, attribute, value::text AS value, label, valid_from, valid_to, source::text AS source
                          FROM data2flow_action.device_state_history
                         WHERE organization_id = :org AND device_id = :device AND valid_from < :to
                           AND (valid_to IS NULL OR valid_to > :from)
                         ORDER BY valid_from, id LIMIT :limit""")
                .param("org", organizationId).param("device", deviceId).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .param("limit", limit)
                .query((rs, n) -> new StateRow(rs.getString("capability"), rs.getString("attribute"), rs.getString("value"),
                        rs.getString("label"), Pg.instant(rs, "valid_from"), Pg.instant(rs, "valid_to"), rs.getString("source")))
                .list();
    }

    private static CommandRow map(ResultSet rs, int row) throws SQLException {
        return new CommandRow(rs.getObject("id", UUID.class), rs.getLong("device_id"), rs.getString("device_name"),
                rs.getString("capability"), rs.getString("command"), rs.getString("args"), rs.getString("priority"),
                rs.getString("source"), rs.getString("source_type"), rs.getString("flow_name"), rs.getString("user_name"),
                rs.getString("status"), rs.getString("status_reason"), Pg.instant(rs, "valid_until"), Pg.instant(rs, "execute_after"),
                Pg.instant(rs, "expected_delivery_at"), Pg.instant(rs, "requested_at"), rs.getString("idempotency_key"));
    }
}
