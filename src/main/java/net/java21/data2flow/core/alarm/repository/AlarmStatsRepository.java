package net.java21.data2flow.core.alarm.repository;

import net.java21.data2flow.core.alarm.dto.AlarmDtos.AlarmStats;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.DailyCount;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.RankItem;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 알람 통계(RUL-06.01, API-RUL-20): 기간 안에 발생한 알람 수, MTTA(발생 → 확인 평균), MTTR(발생 → 해제 평균), 확인 안 된 비율,
 * 많이 발생한 규칙·공간·기기 상위 5, 날짜별 발생 수(조직 시간대).
 */
@Repository
public class AlarmStatsRepository {

    private final JdbcClient jdbc;

    public AlarmStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AlarmStats compute(long organizationId, Instant from, Instant to, String spacePathPrefix, Collection<String> scopePaths,
                              ZoneId zone) {
        Map<String, Object> p = new HashMap<>();
        p.put("org", organizationId);
        p.put("from", Pg.ts(from));
        p.put("to", Pg.ts(to));
        p.put("prefix", spacePathPrefix);
        p.put("tz", zone.getId());
        String scope = "";
        if (scopePaths != null) {
            scope = " AND EXISTS (SELECT 1 FROM unnest(CAST(:scopePaths AS text[])) sp WHERE s.path LIKE sp || '%')";
            p.put("scopePaths", Pg.textArray(scopePaths));
        }
        String base = """
                 FROM data2flow_core.alarms a
                 LEFT JOIN data2flow_core.spaces s ON s.id = a.space_id AND s.organization_id = a.organization_id
                WHERE a.organization_id = :org AND a.raised_at >= :from AND a.raised_at < :to
                  AND (CAST(:prefix AS text) IS NULL OR s.path LIKE :prefix || '%')""" + scope;
        Object[] totals = jdbc.sql("SELECT count(*) AS n, avg(extract(epoch FROM a.acked_at - a.raised_at)) AS mtta,"
                        + " avg(extract(epoch FROM a.cleared_at - a.raised_at)) AS mttr,"
                        + " count(*) FILTER (WHERE a.acked_at IS NULL) AS unacked" + base)
                .params(p).query((rs, n) -> new Object[]{rs.getLong("n"), nullableLong(rs, "mtta"), nullableLong(rs, "mttr"),
                        rs.getLong("unacked")}).single();
        long raised = (long) totals[0];
        List<RankItem> topRules = jdbc.sql("SELECT a.rule_id AS id, max(r.name) AS name, count(*) AS n" + base.replace(
                        "LEFT JOIN data2flow_core.spaces s", "LEFT JOIN data2flow_core.rules r ON r.id = a.rule_id AND r.organization_id = a.organization_id"
                                + " LEFT JOIN data2flow_core.spaces s")
                        + " AND a.rule_id IS NOT NULL GROUP BY a.rule_id ORDER BY n DESC, a.rule_id LIMIT 5")
                .params(p).query((rs, n) -> new RankItem(Long.toString(rs.getLong("id")), rs.getString("name"), rs.getLong("n"))).list();
        List<RankItem> topSpaces = jdbc.sql("SELECT a.space_id AS id, max(s.name) AS name, count(*) AS n" + base
                        + " AND a.space_id IS NOT NULL GROUP BY a.space_id ORDER BY n DESC, a.space_id LIMIT 5")
                .params(p).query((rs, n) -> new RankItem(Long.toString(rs.getLong("id")), rs.getString("name"), rs.getLong("n"))).list();
        List<RankItem> topDevices = jdbc.sql("SELECT a.device_id AS id, max(d.name) AS name, count(*) AS n" + base.replace(
                        "LEFT JOIN data2flow_core.spaces s", "LEFT JOIN data2flow_core.devices d ON d.id = a.device_id AND d.organization_id = a.organization_id"
                                + " LEFT JOIN data2flow_core.spaces s")
                        + " AND a.device_id IS NOT NULL GROUP BY a.device_id ORDER BY n DESC, a.device_id LIMIT 5")
                .params(p).query((rs, n) -> new RankItem(Long.toString(rs.getLong("id")), rs.getString("name"), rs.getLong("n"))).list();
        List<DailyCount> daily = jdbc.sql("SELECT to_char(a.raised_at AT TIME ZONE :tz, 'YYYY-MM-DD') AS day, count(*) AS n" + base
                        + " GROUP BY 1 ORDER BY 1")
                .params(p).query((rs, n) -> new DailyCount(rs.getString("day"), rs.getLong("n"))).list();
        double unackedRatio = raised == 0 ? 0 : Math.round((double) (long) totals[3] / raised * 1000) / 1000.0;
        return new AlarmStats(raised, (Long) totals[1], (Long) totals[2], unackedRatio, topRules, topSpaces, topDevices, daily);
    }

    public String findTimezone(long organizationId) {
        return jdbc.sql("SELECT coalesce((SELECT timezone FROM data2flow_core.org_settings WHERE organization_id = :org), 'Asia/Seoul')")
                .param("org", organizationId).query(String.class).single();
    }

    static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : Math.round(v);
    }
}
