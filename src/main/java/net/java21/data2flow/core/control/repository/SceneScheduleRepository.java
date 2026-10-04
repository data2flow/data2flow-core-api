package net.java21.data2flow.core.control.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 장면(scenes·scene_items, ACT-05.01)과 예약 제어(control_schedules, ACT-02.07) */
@Repository
public class SceneScheduleRepository {

    private final JdbcClient jdbc;

    public SceneScheduleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 장면

    public record SceneRow(long id, long organizationId, String name, String description, Long spaceId, int itemCount, int version,
                           Instant updatedAt, String items) {
    }

    static final String SCENE = """
            SELECT s.id, s.organization_id, s.name, s.description, s.space_id, s.item_count, s.version, s.updated_at,
                   (SELECT coalesce(jsonb_agg(jsonb_build_object('seq', i.seq, 'target', i.target, 'capability', i.capability,
                                                                  'desired', i.desired) ORDER BY i.seq), '[]'::jsonb)::text
                      FROM data2flow_core.scene_items i WHERE i.scene_id = s.id AND i.organization_id = s.organization_id) AS items
              FROM data2flow_core.scenes s""";

    public List<SceneRow> listScenes(long organizationId) {
        return jdbc.sql(SCENE + " WHERE s.organization_id = :org ORDER BY s.name, s.id").param("org", organizationId)
                .query(SceneScheduleRepository::scene).list();
    }

    public Optional<SceneRow> findScene(long organizationId, long id) {
        return jdbc.sql(SCENE + " WHERE s.organization_id = :org AND s.id = :id").param("org", organizationId).param("id", id)
                .query(SceneScheduleRepository::scene).optional();
    }

    @OrganizationScopeExempt("장면 ID는 전역 고유이고 응답에 organizationId를 담는다(API-ACT-47 action 내부 호출)")
    public Optional<SceneRow> findSceneAnyOrganization(long id) {
        return jdbc.sql(SCENE + " WHERE s.id = :id").param("id", id).query(SceneScheduleRepository::scene).optional();
    }

    public boolean existsSceneName(long organizationId, String name, Long exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.scenes WHERE organization_id = :org AND name = :name
                                         AND (CAST(:except AS bigint) IS NULL OR id <> :except))""")
                .param("org", organizationId).param("name", name).param("except", exceptId).query(Boolean.class).single();
    }

    public long insertScene(long organizationId, String name, String description, Long spaceId, int itemCount, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.scenes (organization_id, name, description, space_id, item_count, version, created_by, updated_by,
                               created_at, updated_at)
                        VALUES (:org, :name, :description, :space, :count, 1, :user, :user, :now, :now) RETURNING id""")
                .param("org", organizationId).param("name", name).param("description", description).param("space", spaceId)
                .param("count", itemCount).param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public int updateScene(long organizationId, long id, Integer baseVersion, String name, String description, Long spaceId, int itemCount,
                           long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.scenes SET name = :name, description = :description, space_id = :space, item_count = :count,
                               version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND (CAST(:base AS integer) IS NULL OR version = :base)""")
                .param("name", name).param("description", description).param("space", spaceId).param("count", itemCount)
                .param("user", userId).param("now", Pg.ts(now)).param("org", organizationId).param("id", id).param("base", baseVersion)
                .update();
    }

    public record ItemValues(String targetJson, String capability, String desiredJson) {
    }

    public void replaceItems(long organizationId, long sceneId, List<ItemValues> items) {
        jdbc.sql("DELETE FROM data2flow_core.scene_items WHERE organization_id = :org AND scene_id = :id")
                .param("org", organizationId).param("id", sceneId).update();
        int seq = 1;
        for (ItemValues i : items) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.scene_items (scene_id, seq, organization_id, target, capability, desired)
                            VALUES (:id, :seq, :org, CAST(:target AS jsonb), :cap, CAST(:desired AS jsonb))""")
                    .param("id", sceneId).param("seq", seq++).param("org", organizationId).param("target", i.targetJson())
                    .param("cap", i.capability()).param("desired", i.desiredJson()).update();
        }
    }

    public int deleteScene(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.scenes WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    static SceneRow scene(ResultSet rs, int n) throws SQLException {
        return new SceneRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("description"),
                Pg.longOrNull(rs, "space_id"), rs.getInt("item_count"), rs.getInt("version"), Pg.instant(rs, "updated_at"),
                rs.getString("items"));
    }

    // ------------------------------------------------------------------ 예약

    public record ScheduleRow(long id, long organizationId, String name, String target, String kind, Instant runAt, String cron,
                              String spaceHours, LocalDate validFrom, LocalDate validTo, boolean skipHolidays, String timezone,
                              boolean enabled, Instant nextRunAt, String lastRun, int version, Instant updatedAt) {
    }

    public record ScheduleValues(long organizationId, String name, String targetJson, String kind, Instant runAt, String cron,
                                 String spaceHoursJson, LocalDate validFrom, LocalDate validTo, boolean skipHolidays, String timezone,
                                 Instant nextRunAt) {
    }

    static final String SCHEDULE = """
            SELECT id, organization_id, name, target::text AS target, kind, run_at, cron, space_hours::text AS space_hours, valid_from, valid_to,
                   skip_holidays, timezone, enabled, next_run_at, last_run::text AS last_run, version, updated_at
              FROM data2flow_core.control_schedules""";

    public List<ScheduleRow> listSchedules(long organizationId) {
        return jdbc.sql(SCHEDULE + " WHERE organization_id = :org ORDER BY name, id").param("org", organizationId)
                .query(SceneScheduleRepository::schedule).list();
    }

    public Optional<ScheduleRow> findSchedule(long organizationId, long id) {
        return jdbc.sql(SCHEDULE + " WHERE organization_id = :org AND id = :id").param("org", organizationId).param("id", id)
                .query(SceneScheduleRepository::schedule).optional();
    }

    public boolean existsScheduleName(long organizationId, String name, Long exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.control_schedules WHERE organization_id = :org AND name = :name
                                         AND (CAST(:except AS bigint) IS NULL OR id <> :except))""")
                .param("org", organizationId).param("name", name).param("except", exceptId).query(Boolean.class).single();
    }

    public long insertSchedule(ScheduleValues v, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.control_schedules (organization_id, name, target, kind, run_at, cron, space_hours, valid_from,
                               valid_to, skip_holidays, timezone, enabled, next_run_at, version, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :name, CAST(:target AS jsonb), :kind, :runAt, :cron, CAST(:spaceHours AS jsonb), :from, :to, :skip, :tz,
                                true, :next, 1, :user, :user, :now, :now) RETURNING id""")
                .param("org", v.organizationId()).param("name", v.name()).param("target", v.targetJson()).param("kind", v.kind())
                .param("runAt", Pg.ts(v.runAt())).param("cron", v.cron()).param("spaceHours", v.spaceHoursJson()).param("from", v.validFrom())
                .param("to", v.validTo()).param("skip", v.skipHolidays()).param("tz", v.timezone()).param("next", Pg.ts(v.nextRunAt()))
                .param("user", userId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public int updateSchedule(long id, ScheduleValues v, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.control_schedules SET name = :name, target = CAST(:target AS jsonb), kind = :kind, run_at = :runAt,
                               cron = :cron, space_hours = CAST(:spaceHours AS jsonb), valid_from = :from, valid_to = :to, skip_holidays = :skip,
                               timezone = :tz, next_run_at = CASE WHEN enabled THEN :next ELSE NULL END, version = version + 1,
                               updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("org", v.organizationId()).param("name", v.name()).param("target", v.targetJson()).param("kind", v.kind())
                .param("runAt", Pg.ts(v.runAt())).param("cron", v.cron()).param("spaceHours", v.spaceHoursJson()).param("from", v.validFrom())
                .param("to", v.validTo()).param("skip", v.skipHolidays()).param("tz", v.timezone()).param("next", Pg.ts(v.nextRunAt()))
                .param("user", userId).param("now", Pg.ts(now)).param("id", id).update();
    }

    public void updateEnabled(long organizationId, long id, boolean enabled, Instant nextRunAt, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.control_schedules SET enabled = :enabled, next_run_at = :next, version = version + 1, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("enabled", enabled).param("next", Pg.ts(nextRunAt)).param("now", Pg.ts(now)).param("org", organizationId)
                .param("id", id).update();
    }

    public void updateRun(long organizationId, long id, Instant nextRunAt, String lastRunJson) {
        jdbc.sql("""
                        UPDATE data2flow_core.control_schedules SET next_run_at = :next, last_run = CAST(:last AS jsonb)
                         WHERE organization_id = :org AND id = :id""")
                .param("next", Pg.ts(nextRunAt)).param("last", lastRunJson).param("org", organizationId).param("id", id).update();
    }

    public int deleteSchedule(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.control_schedules WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    /** 실행할 때가 된 예약(잠금 건너뛰기: 파드 여러 개여도 한 번만, BR-ACT-18) */
    @OrganizationScopeExempt("배포 조직 목록으로 거른다(예약 실행기는 배포 조직 전체를 돈다)")
    public List<ScheduleRow> lockDue(Collection<Long> organizationIds, Instant now, int limit) {
        return jdbc.sql(SCHEDULE + " WHERE enabled AND next_run_at <= :now AND organization_id = ANY(CAST(:orgs AS bigint[]))"
                        + " ORDER BY next_run_at LIMIT :limit FOR UPDATE SKIP LOCKED")
                .param("now", Pg.ts(now)).param("orgs", Pg.bigintArray(organizationIds)).param("limit", limit)
                .query(SceneScheduleRepository::schedule).list();
    }

    /** 공간 운영 시간(요일 1~7, 시작·끝) */
    public record HoursRow(int dayOfWeek, LocalTime start, LocalTime end) {
    }

    public List<HoursRow> listSpaceHours(long organizationId, long spaceId) {
        return jdbc.sql("""
                        SELECT day_of_week, start_time, end_time FROM data2flow_core.space_schedules WHERE organization_id = :org AND space_id = :space
                         ORDER BY day_of_week, start_time""")
                .param("org", organizationId).param("space", spaceId)
                .query((rs, n) -> new HoursRow(rs.getInt(1), rs.getObject(2, LocalTime.class), rs.getObject(3, LocalTime.class))).list();
    }

    static ScheduleRow schedule(ResultSet rs, int n) throws SQLException {
        return new ScheduleRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("target"),
                rs.getString("kind"), Pg.instant(rs, "run_at"), rs.getString("cron"), rs.getString("space_hours"),
                rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class), rs.getBoolean("skip_holidays"),
                rs.getString("timezone"), rs.getBoolean("enabled"), Pg.instant(rs, "next_run_at"), rs.getString("last_run"),
                rs.getInt("version"), Pg.instant(rs, "updated_at"));
    }
}
