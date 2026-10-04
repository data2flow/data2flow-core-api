package net.java21.data2flow.core.alarm.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** 알람 타임라인({@code data2flow_core.alarm_events}, INSERT 전용, RUL-02.04) */
@Repository
public class AlarmEventRepository {

    private final JdbcClient jdbc;

    public AlarmEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record EventRow(long id, long alarmId, Instant at, String type, String actorType, Long actorId, String actorName, String data) {
    }

    public long insert(long organizationId, long alarmId, Instant at, String type, String actorType, Long actorId, String dataJson) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.alarm_events (organization_id, alarm_id, at, type, actor_type, actor_id, data)
                        VALUES (:org, :alarm, :at, :type, :actorType, :actorId, CAST(:data AS jsonb)) RETURNING id""")
                .param("org", organizationId).param("alarm", alarmId).param("at", Pg.ts(at)).param("type", type)
                .param("actorType", actorType).param("actorId", actorId).param("data", dataJson).query(Long.class).single();
    }

    public List<EventRow> listByAlarm(long organizationId, long alarmId) {
        return jdbc.sql("""
                        SELECT e.id, e.alarm_id, e.at, e.type, e.actor_type, e.actor_id, u.name AS actor_name, e.data::text AS data
                          FROM data2flow_core.alarm_events e
                          LEFT JOIN data2flow_core.app_users u ON u.id = e.actor_id AND e.actor_type IN ('USER','MESSENGER')
                               AND u.organization_id = e.organization_id
                         WHERE e.organization_id = :org AND e.alarm_id = :alarm ORDER BY e.at, e.id""")
                .param("org", organizationId).param("alarm", alarmId)
                .query((rs, n) -> new EventRow(rs.getLong("id"), rs.getLong("alarm_id"), Pg.instant(rs, "at"), rs.getString("type"),
                        rs.getString("actor_type"), Pg.longOrNull(rs, "actor_id"), rs.getString("actor_name"), rs.getString("data")))
                .list();
    }

    /** 같은 발송 결과(NOTIFIED) 중복 방지 */
    public boolean existsNotified(long organizationId, long alarmId, String deliveryId, String status) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.alarm_events WHERE organization_id = :org AND alarm_id = :alarm
                                         AND type = 'NOTIFIED' AND data ->> 'deliveryId' = :delivery AND data ->> 'status' = :status)""")
                .param("org", organizationId).param("alarm", alarmId).param("delivery", deliveryId).param("status", status)
                .query(Boolean.class).single();
    }
}
