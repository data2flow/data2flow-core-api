package net.java21.data2flow.core.alarm.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** 공간 이벤트(같은 원인 묶음, RUL-04.03·BR-RUL-11) */
@Repository
public class SpaceEventRepository {

    private final JdbcClient jdbc;

    public SpaceEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record OpenEvent(long id, Instant openedAt) {
    }

    /** 그 공간의 가장 최근 묶음(창 판정은 서비스가) */
    public Optional<OpenEvent> findLatest(long organizationId, long spaceId) {
        return jdbc.sql("""
                        SELECT id, opened_at FROM data2flow_core.space_events WHERE organization_id = :org AND space_id = :space
                         ORDER BY opened_at DESC LIMIT 1""")
                .param("org", organizationId).param("space", spaceId)
                .query((rs, n) -> new OpenEvent(rs.getLong("id"), Pg.instant(rs, "opened_at"))).optional();
    }

    public long insert(long organizationId, long spaceId, Instant openedAt) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.space_events (organization_id, space_id, opened_at, alarm_count)
                        VALUES (:org, :space, :at, 0) RETURNING id""")
                .param("org", organizationId).param("space", spaceId).param("at", Pg.ts(openedAt)).query(Long.class).single();
    }

    public void updateCount(long organizationId, long id) {
        jdbc.sql("""
                        UPDATE data2flow_core.space_events SET alarm_count =
                               (SELECT count(*) FROM data2flow_core.alarms a WHERE a.organization_id = :org AND a.space_event_id = :id)
                         WHERE organization_id = :org AND id = :id""")
                .param("org", organizationId).param("id", id).update();
    }
}
