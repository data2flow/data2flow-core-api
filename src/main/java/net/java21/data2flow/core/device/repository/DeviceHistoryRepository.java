package net.java21.data2flow.core.device.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** 기기 변경 이력(API-DEV-27, DEV-02.07): 감사 로그에서 대상이 그 기기인 기록(변경·승인·이동·속성·효과 없음 등) */
@Repository
public class DeviceHistoryRepository {

    private final JdbcClient jdbc;

    public DeviceHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record HistoryRow(long id, Instant at, String actorType, String actorId, String actorName, String action, String result,
                             String detail) {
    }

    public List<HistoryRow> list(long organizationId, long deviceId, int limit, long offset) {
        return jdbc.sql("""
                        SELECT id, occurred_at, actor_type, actor_id, actor_name, action, result, detail::text AS detail
                          FROM data2flow_core.audit_logs
                         WHERE organization_id = :org AND target_type = 'DEVICE' AND target_id = :id
                         ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset""")
                .param("org", organizationId).param("id", Long.toString(deviceId)).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new HistoryRow(rs.getLong("id"), Pg.instant(rs, "occurred_at"), rs.getString("actor_type"),
                        rs.getString("actor_id"), rs.getString("actor_name"), rs.getString("action"), rs.getString("result"),
                        rs.getString("detail"))).list();
    }

    public long count(long organizationId, long deviceId) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.audit_logs
                         WHERE organization_id = :org AND target_type = 'DEVICE' AND target_id = :id""")
                .param("org", organizationId).param("id", Long.toString(deviceId)).query(Long.class).single();
    }
}
