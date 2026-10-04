package net.java21.data2flow.core.alarm.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** 게이트웨이 연결 판정(DEV-05.03): 마지막 수신 + offline_after_sec 기준으로 ONLINE ↔ OFFLINE 전환을 찾는다 */
@Repository
public class GatewayHealthRepository {

    private final JdbcClient jdbc;

    public GatewayHealthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record GatewayState(long id, String gatewayEui, Instant lastSeenAt) {
    }

    /** 저장 상태는 ONLINE·UNKNOWN인데 기준 시간 넘게 수신이 없는 게이트웨이(→ OFFLINE) */
    public List<GatewayState> listNewlyOffline(long organizationId, Instant now) {
        return jdbc.sql("""
                        SELECT id, gateway_eui, last_seen_at FROM data2flow_core.gateways
                         WHERE organization_id = :org AND status <> 'OFFLINE' AND last_seen_at IS NOT NULL
                           AND last_seen_at + make_interval(secs => offline_after_sec) < :now ORDER BY id""")
                .param("org", organizationId).param("now", Pg.ts(now))
                .query((rs, n) -> new GatewayState(rs.getLong("id"), rs.getString("gateway_eui"), Pg.instant(rs, "last_seen_at"))).list();
    }

    /** 오프라인 알람이 열려 있는데 다시 수신된 게이트웨이(→ ONLINE) */
    public List<GatewayState> listRecovered(long organizationId, Instant now) {
        return jdbc.sql("""
                        SELECT g.id, g.gateway_eui, g.last_seen_at FROM data2flow_core.gateways g
                          JOIN data2flow_core.alarms a ON a.organization_id = g.organization_id AND a.alarm_key = 'system:GATEWAY_OFFLINE:' || g.id
                               AND a.status IN ('ACTIVE','ACKNOWLEDGED','SUPPRESSED')
                         WHERE g.organization_id = :org AND g.last_seen_at IS NOT NULL
                           AND g.last_seen_at + make_interval(secs => g.offline_after_sec) >= :now ORDER BY g.id""")
                .param("org", organizationId).param("now", Pg.ts(now))
                .query((rs, n) -> new GatewayState(rs.getLong("id"), rs.getString("gateway_eui"), Pg.instant(rs, "last_seen_at"))).list();
    }

    public void updateStatus(long organizationId, long id, String status) {
        jdbc.sql("UPDATE data2flow_core.gateways SET status = :status WHERE organization_id = :org AND id = :id")
                .param("status", status).param("org", organizationId).param("id", id).update();
    }
}
