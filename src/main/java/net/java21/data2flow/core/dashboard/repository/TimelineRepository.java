package net.java21.data2flow.core.dashboard.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 홈 타임라인(DSH-01.03, API-DSH-01 timeline): 최근 알람 발생·해제와 최근 제어(사용자·플로우·규칙·예약 출처)를 시각 순으로. 제어 명령은
 * action 소유 {@code data2flow_action.commands}를 읽기만 한다(domain-map §4.4 읽기 허용). 공간 범위가 제한된 사용자는 범위 공간 아래만.
 */
@Repository
public class TimelineRepository {

    private final JdbcClient jdbc;

    public TimelineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Item(String type, Instant at, String title, String severity, String origin, String link) {
    }

    public List<Item> recent(long organizationId, Collection<String> scopePaths, Instant since, int limit) {
        Map<String, Object> p = new HashMap<>();
        p.put("org", organizationId);
        p.put("since", Pg.ts(since));
        p.put("limit", limit);
        String scope = "";
        if (scopePaths != null) {
            scope = " AND EXISTS (SELECT 1 FROM unnest(CAST(:scopePaths AS text[])) sp WHERE s.path LIKE sp || '%')";
            p.put("scopePaths", Pg.textArray(scopePaths));
        }
        String sql = """
                SELECT * FROM (
                  SELECT 'ALARM_RAISED' AS type, a.raised_at AS at, a.title, a.severity, a.source_type AS origin, '/alarms/' || a.id AS link
                    FROM data2flow_core.alarms a LEFT JOIN data2flow_core.spaces s ON s.id = a.space_id AND s.organization_id = a.organization_id
                   WHERE a.organization_id = :org AND a.raised_at >= :since""" + scope + " " + """
                  UNION ALL
                  SELECT 'ALARM_CLEARED', a.cleared_at, a.title, a.severity, a.source_type, '/alarms/' || a.id
                    FROM data2flow_core.alarms a LEFT JOIN data2flow_core.spaces s ON s.id = a.space_id AND s.organization_id = a.organization_id
                   WHERE a.organization_id = :org AND a.cleared_at >= :since""" + scope + " " + """
                  UNION ALL
                  SELECT 'CONTROL', c.requested_at, d.name || ' · ' || c.capability || '.' || c.command, NULL,
                         CASE c.source_type WHEN 'USER' THEN 'USER' WHEN 'FLOW' THEN 'FLOW' WHEN 'RULE' THEN 'RULE' WHEN 'SCHEDULE' THEN 'SCHEDULE'
                              ELSE c.source_type END, '/devices/' || c.device_id
                    FROM data2flow_action.commands c
                    JOIN data2flow_core.devices d ON d.id = c.device_id AND d.organization_id = c.organization_id
                    LEFT JOIN data2flow_core.spaces s ON s.id = d.space_id AND s.organization_id = d.organization_id
                   WHERE c.organization_id = :org AND c.requested_at >= :since AND c.status NOT IN ('REJECTED')""" + scope + " " + """
                ) t ORDER BY at DESC LIMIT :limit""";
        return jdbc.sql(sql).params(p)
                .query((rs, n) -> new Item(rs.getString("type"), Pg.instant(rs, "at"), rs.getString("title"), rs.getString("severity"),
                        rs.getString("origin"), rs.getString("link"))).list();
    }
}
