package net.java21.data2flow.core.space.repository;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.space.domain.ScheduleSlot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 공간 목표 범위({@code space_targets}, DEV-01.04)와 운영 시간표({@code space_schedules}, DEV-11.01).
 * 행이 없으면 상위 공간 값을 상속한다(BR-DEV-04). 상속 계산은 서비스가 조상 사슬로 한다.
 */
@Repository
public class SpaceSettingsRepository {

    private final JdbcClient jdbc;

    public SpaceSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 주어진 공간들의 목표 행 */
    public List<TargetRow> findTargets(long organizationId, Collection<Long> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT space_id, metric_key, min_value, max_value FROM data2flow_core.space_targets
                         WHERE organization_id = :org AND space_id = ANY(CAST(:ids AS bigint[])) ORDER BY metric_key""")
                .param("org", organizationId).param("ids", Pg.bigintArray(spaceIds))
                .query((rs, n) -> new TargetRow(rs.getLong("space_id"), rs.getString("metric_key"),
                        (Double) rs.getObject("min_value"), (Double) rs.getObject("max_value")))
                .list();
    }

    /** 이 공간의 목표를 통째로 바꾼다(빈 목록이면 모두 상속) */
    public void replaceTargets(long organizationId, long spaceId, List<TargetRow> items, Long userId, Instant now) {
        jdbc.sql("DELETE FROM data2flow_core.space_targets WHERE organization_id = :org AND space_id = :space")
                .param("org", organizationId).param("space", spaceId).update();
        for (TargetRow item : items) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.space_targets (organization_id, space_id, metric_key, min_value, max_value, updated_by,
                                                                      created_at, updated_at)
                            VALUES (:org, :space, :key, :min, :max, :user, :now, :now)""")
                    .param("org", organizationId).param("space", spaceId).param("key", item.metricKey())
                    .param("min", item.min()).param("max", item.max()).param("user", userId).param("now", Pg.ts(now)).update();
        }
    }

    /** 조직에 있는(무시되지 않은) 측정 항목 키 */
    public Set<String> findExistingMetricKeys(long organizationId, Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT key FROM data2flow_core.metrics
                         WHERE organization_id = :org AND status <> 'IGNORED' AND key = ANY(CAST(:keys AS text[]))""")
                .param("org", organizationId).param("keys", Pg.textArray(keys)).query(String.class).list());
    }

    /** 주어진 공간들의 시간표 구간 */
    public List<SlotRow> findSlots(long organizationId, Collection<Long> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT space_id, day_of_week, to_char(start_time, 'HH24:MI') AS start_at, to_char(end_time, 'HH24:MI') AS end_at
                          FROM data2flow_core.space_schedules
                         WHERE organization_id = :org AND space_id = ANY(CAST(:ids AS bigint[]))
                         ORDER BY space_id, day_of_week, start_time""")
                .param("org", organizationId).param("ids", Pg.bigintArray(spaceIds))
                .query((rs, n) -> new SlotRow(rs.getLong("space_id"),
                        new ScheduleSlot(rs.getInt("day_of_week"), rs.getString("start_at"), rs.getString("end_at"))))
                .list();
    }

    /** 이 공간의 시간표를 통째로 바꾼다 */
    public void replaceSlots(long organizationId, long spaceId, List<ScheduleSlot> slots, Instant now) {
        jdbc.sql("DELETE FROM data2flow_core.space_schedules WHERE organization_id = :org AND space_id = :space")
                .param("org", organizationId).param("space", spaceId).update();
        for (ScheduleSlot slot : slots) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.space_schedules (organization_id, space_id, day_of_week, start_time, end_time, created_at)
                            VALUES (:org, :space, :dow, CAST(:start AS time), CAST(:end AS time), :now)""")
                    .param("org", organizationId).param("space", spaceId).param("dow", slot.dayOfWeek())
                    .param("start", slot.start()).param("end", slot.end()).param("now", Pg.ts(now)).update();
        }
    }

    /** 목표 한 행 */
    public record TargetRow(long spaceId, String metricKey, Double min, Double max) {
    }

    /** 시간표 한 구간 */
    public record SlotRow(long spaceId, ScheduleSlot slot) {
    }
}
