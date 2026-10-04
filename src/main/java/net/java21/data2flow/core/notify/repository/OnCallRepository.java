package net.java21.data2flow.core.notify.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/** 당직(on_call_schedules·shifts·overrides, RUL-05.03). v1은 조직당 일정 1개 */
@Repository
public class OnCallRepository {

    private final JdbcClient jdbc;

    public OnCallRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ScheduleRow(long id, String name, String timezone, int version, Instant updatedAt) {
    }

    public record ShiftRow(long id, int dayOfWeek, LocalTime from, LocalTime to, long userId, String userName) {
    }

    public record OverrideRow(long id, Instant startsAt, Instant endsAt, long originalUserId, String originalUserName,
                              long substituteUserId, String substituteUserName, long createdBy, Instant createdAt) {
    }

    public Optional<ScheduleRow> findSchedule(long organizationId) {
        return jdbc.sql("""
                        SELECT id, name, timezone, version, updated_at FROM data2flow_core.on_call_schedules
                         WHERE organization_id = :org ORDER BY id LIMIT 1""")
                .param("org", organizationId)
                .query((rs, n) -> new ScheduleRow(rs.getLong("id"), rs.getString("name"), rs.getString("timezone"), rs.getInt("version"),
                        Pg.instant(rs, "updated_at"))).optional();
    }

    /** 일정 행 잠금(근무표 저장 직렬화) */
    public Optional<Long> lockSchedule(long organizationId) {
        return jdbc.sql("SELECT id FROM data2flow_core.on_call_schedules WHERE organization_id = :org ORDER BY id LIMIT 1 FOR UPDATE")
                .param("org", organizationId).query(Long.class).optional();
    }

    public long insertSchedule(long organizationId, String name, String timezone, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.on_call_schedules (organization_id, name, timezone, version, created_at, updated_at)
                        VALUES (:org, :name, :tz, 1, :now, :now) RETURNING id""")
                .param("org", organizationId).param("name", name).param("tz", timezone).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public int updateSchedule(long organizationId, long id, int baseVersion, String name, String timezone, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.on_call_schedules SET name = :name, timezone = :tz, version = version + 1, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .param("name", name).param("tz", timezone).param("now", Pg.ts(now)).param("org", organizationId).param("id", id)
                .param("base", baseVersion).update();
    }

    public void replaceShifts(long organizationId, long scheduleId, List<ShiftRow> shifts) {
        jdbc.sql("DELETE FROM data2flow_core.on_call_shifts WHERE organization_id = :org AND schedule_id = :id")
                .param("org", organizationId).param("id", scheduleId).update();
        for (ShiftRow s : shifts) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.on_call_shifts (organization_id, schedule_id, day_of_week, from_time, to_time, user_id)
                            VALUES (:org, :id, :day, :from, :to, :user)""")
                    .param("org", organizationId).param("id", scheduleId).param("day", s.dayOfWeek()).param("from", s.from())
                    .param("to", s.to()).param("user", s.userId()).update();
        }
    }

    public List<ShiftRow> listShifts(long organizationId, long scheduleId) {
        return jdbc.sql("""
                        SELECT s.id, s.day_of_week, s.from_time, s.to_time, s.user_id, u.name AS user_name
                          FROM data2flow_core.on_call_shifts s
                          LEFT JOIN data2flow_core.app_users u ON u.id = s.user_id AND u.organization_id = s.organization_id
                         WHERE s.organization_id = :org AND s.schedule_id = :id ORDER BY s.day_of_week, s.from_time, s.id""")
                .param("org", organizationId).param("id", scheduleId)
                .query((rs, n) -> new ShiftRow(rs.getLong("id"), rs.getInt("day_of_week"), rs.getObject("from_time", LocalTime.class),
                        rs.getObject("to_time", LocalTime.class), rs.getLong("user_id"), rs.getString("user_name"))).list();
    }

    public long insertOverride(long organizationId, long scheduleId, Instant startsAt, Instant endsAt, long originalUserId,
                               long substituteUserId, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.on_call_overrides (organization_id, schedule_id, starts_at, ends_at, original_user_id,
                               substitute_user_id, created_by, created_at)
                        VALUES (:org, :id, :starts, :ends, :original, :substitute, :user, :now) RETURNING id""")
                .param("org", organizationId).param("id", scheduleId).param("starts", Pg.ts(startsAt)).param("ends", Pg.ts(endsAt))
                .param("original", originalUserId).param("substitute", substituteUserId).param("user", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    public List<OverrideRow> listOverrides(long organizationId, long scheduleId, Instant endsAfter) {
        return jdbc.sql("""
                        SELECT o.id, o.starts_at, o.ends_at, o.original_user_id, uo.name AS original_name, o.substitute_user_id,
                               us.name AS substitute_name, o.created_by, o.created_at
                          FROM data2flow_core.on_call_overrides o
                          LEFT JOIN data2flow_core.app_users uo ON uo.id = o.original_user_id AND uo.organization_id = o.organization_id
                          LEFT JOIN data2flow_core.app_users us ON us.id = o.substitute_user_id AND us.organization_id = o.organization_id
                         WHERE o.organization_id = :org AND o.schedule_id = :id AND o.ends_at > :after ORDER BY o.starts_at, o.id""")
                .param("org", organizationId).param("id", scheduleId).param("after", Pg.ts(endsAfter))
                .query((rs, n) -> new OverrideRow(rs.getLong("id"), Pg.instant(rs, "starts_at"), Pg.instant(rs, "ends_at"),
                        rs.getLong("original_user_id"), rs.getString("original_name"), rs.getLong("substitute_user_id"),
                        rs.getString("substitute_name"), rs.getLong("created_by"), Pg.instant(rs, "created_at"))).list();
    }

    public int deleteOverride(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.on_call_overrides WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    /** 이 조직의 활성 사용자인가 */
    public boolean existsActiveUser(long organizationId, long userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.app_users WHERE organization_id = :org AND id = :id AND status = 'ACTIVE')")
                .param("org", organizationId).param("id", userId).query(Boolean.class).single();
    }

    public Optional<String> findUserName(long organizationId, long userId) {
        return jdbc.sql("SELECT name FROM data2flow_core.app_users WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", userId).query(String.class).optional();
    }
}
