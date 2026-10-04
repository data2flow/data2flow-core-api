package net.java21.data2flow.core.calendar.repository;

import net.java21.data2flow.core.calendar.domain.CalendarModels.EventRow;
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

/** 조직 달력({@code calendar_events}, DEV-12.01·BR-DSC-18). 모든 조회는 조직 조건을 건다 */
@Repository
public class CalendarEventRepository {

    static final String COLUMNS = """
            id, organization_id, title, type, starts_on, ends_on, start_time, end_time, scope_space_ids, origin, origin_uid, affects_mode,
            source_id, locally_modified, origin_deleted_at, version, created_at, updated_at""";

    private final JdbcClient jdbc;

    public CalendarEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 새 일정 값 */
    public record Values(String title, String type, LocalDate startsOn, LocalDate endsOn, LocalTime startTime, LocalTime endTime,
                         List<Long> scopeSpaceIds, String affectsMode) {
    }

    public Optional<EventRow> findById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.calendar_events WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).query(CalendarEventRepository::map).optional();
    }

    /** [from, to] 날짜와 겹치는 일정(시작일 순) */
    public List<EventRow> listOverlapping(long organizationId, LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.calendar_events WHERE organization_id = :org"
                        + " AND starts_on <= :to AND ends_on >= :from ORDER BY starts_on, start_time NULLS FIRST, id")
                .param("org", organizationId).param("from", from).param("to", to).query(CalendarEventRepository::map).list();
    }

    /** 출처 소스의 자동 생성 일정(UID → 행) */
    public List<EventRow> listBySource(long organizationId, long sourceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.calendar_events WHERE organization_id = :org AND source_id = :source")
                .param("org", organizationId).param("source", sourceId).query(CalendarEventRepository::map).list();
    }

    public Optional<EventRow> findByOriginUid(long organizationId, String origin, String originUid) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.calendar_events WHERE organization_id = :org AND origin = :origin"
                        + " AND origin_uid = :uid")
                .param("org", organizationId).param("origin", origin).param("uid", originUid).query(CalendarEventRepository::map).optional();
    }

    public long insert(long organizationId, Values v, String origin, String originUid, Long sourceId, Long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.calendar_events (organization_id, title, type, starts_on, ends_on, start_time, end_time,
                            scope_space_ids, origin, origin_uid, affects_mode, source_id, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :title, :type, :startsOn, :endsOn, :startTime, :endTime, CAST(:scope AS bigint[]), :origin, :uid,
                            :affects, :source, :by, :by, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("title", v.title()).param("type", v.type()).param("startsOn", v.startsOn())
                .param("endsOn", v.endsOn()).param("startTime", v.startTime()).param("endTime", v.endTime())
                .param("scope", Pg.bigintArray(v.scopeSpaceIds())).param("origin", origin).param("uid", originUid)
                .param("affects", v.affectsMode()).param("source", sourceId).param("by", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    /** 사람이 고친 값(낙관적 잠금). locallyModified는 자동 생성 일정을 고쳤을 때 true */
    public int update(long organizationId, long id, int baseVersion, Values v, boolean locallyModified, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.calendar_events
                           SET title = :title, type = :type, starts_on = :startsOn, ends_on = :endsOn, start_time = :startTime, end_time = :endTime,
                               scope_space_ids = CAST(:scope AS bigint[]), affects_mode = :affects, locally_modified = :modified,
                               version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .param("title", v.title()).param("type", v.type()).param("startsOn", v.startsOn()).param("endsOn", v.endsOn())
                .param("startTime", v.startTime()).param("endTime", v.endTime()).param("scope", Pg.bigintArray(v.scopeSpaceIds()))
                .param("affects", v.affectsMode()).param("modified", locallyModified).param("by", userId).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", id).param("base", baseVersion).update();
    }

    /** 원본(동기화)에서 온 값으로 덮어쓴다. 원본 삭제 표시는 지운다 */
    public void updateFromOrigin(long organizationId, long id, String title, String type, LocalDate startsOn, LocalDate endsOn,
                                 LocalTime startTime, LocalTime endTime, List<Long> scope, String affectsMode, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.calendar_events
                           SET title = :title, type = :type, starts_on = :startsOn, ends_on = :endsOn, start_time = :startTime, end_time = :endTime,
                               scope_space_ids = CAST(:scope AS bigint[]), affects_mode = :affects, origin_deleted_at = NULL,
                               version = version + 1, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("title", title).param("type", type).param("startsOn", startsOn).param("endsOn", endsOn).param("startTime", startTime)
                .param("endTime", endTime).param("scope", Pg.bigintArray(scope)).param("affects", affectsMode).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", id).update();
    }

    /** 원본에서 다시 나타난 수동 수정 일정: 삭제 표시만 지운다 */
    public void clearOriginDeleted(long organizationId, long id, Instant now) {
        jdbc.sql("UPDATE data2flow_core.calendar_events SET origin_deleted_at = NULL, updated_at = :now"
                        + " WHERE organization_id = :org AND id = :id AND origin_deleted_at IS NOT NULL")
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    public void markOriginDeleted(long organizationId, long id, Instant now) {
        jdbc.sql("UPDATE data2flow_core.calendar_events SET origin_deleted_at = :now, version = version + 1, updated_at = :now"
                        + " WHERE organization_id = :org AND id = :id AND origin_deleted_at IS NULL")
                .param("now", Pg.ts(now)).param("org", organizationId).param("id", id).update();
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.calendar_events WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    /** 공간 경로(루트~자기)에 해당하는 일정 중 [from, to]와 겹치는 것(운영 모드·휴일 판정) */
    public List<EventRow> listAffecting(long organizationId, Collection<Long> chainIds, LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.calendar_events WHERE organization_id = :org"
                        + " AND starts_on <= :to AND ends_on >= :from AND affects_mode <> 'NONE'"
                        + " AND (cardinality(scope_space_ids) = 0 OR scope_space_ids && CAST(:chain AS bigint[]))")
                .param("org", organizationId).param("from", from).param("to", to).param("chain", Pg.bigintArray(chainIds))
                .query(CalendarEventRepository::map).list();
    }

    static EventRow map(ResultSet rs, int n) throws SQLException {
        return new EventRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("title"), rs.getString("type"),
                rs.getObject("starts_on", LocalDate.class), rs.getObject("ends_on", LocalDate.class),
                rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class), Pg.longList(rs, "scope_space_ids"),
                rs.getString("origin"), rs.getString("origin_uid"), rs.getString("affects_mode"), Pg.longOrNull(rs, "source_id"),
                rs.getBoolean("locally_modified"), Pg.instant(rs, "origin_deleted_at"), rs.getInt("version"), Pg.instant(rs, "created_at"),
                Pg.instant(rs, "updated_at"));
    }
}
