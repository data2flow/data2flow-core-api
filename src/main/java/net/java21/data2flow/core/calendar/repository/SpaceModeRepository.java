package net.java21.data2flow.core.calendar.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 운영 모드 계산 재료와 마지막 계산값(DEV-11.02, BR-DEV-23): 진행 중인 공간 유지보수({@code maintenance_windows}, OPS-05),
 * 수동 지정({@code spaces.mode_override}), 마지막 모드({@code space_mode_states}).
 */
@Repository
public class SpaceModeRepository {

    private final JdbcClient jdbc;

    public SpaceModeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 공간 경로(루트~자기) 중 하나를 대상으로 진행 중(ACTIVE)인 유지보수의 끝. 진행 중이 없으면 빈 값, 끝이 없는 유지보수면 {@link Instant#MAX}.
     */
    public Optional<Instant> activeMaintenanceEnd(long organizationId, Collection<Long> chainIds) {
        List<Optional<Instant>> ends = jdbc.sql("""
                        SELECT ends_at FROM data2flow_core.maintenance_windows
                         WHERE organization_id = :org AND status = 'ACTIVE' AND target_type = 'SPACE'
                           AND target_id = ANY(CAST(:chain AS bigint[]))""")
                .param("org", organizationId).param("chain", Pg.bigintArray(chainIds))
                .query((rs, n) -> Optional.ofNullable(Pg.instant(rs, "ends_at"))).list();
        if (ends.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ends.stream().map(e -> e.orElse(Instant.MAX)).max(Instant::compareTo).orElse(Instant.MAX));
    }

    /** 조직의 진행 중인 공간 유지보수: 공간 ID → 끝(끝이 없으면 {@link Instant#MAX}) */
    public Map<Long, Instant> activeMaintenanceBySpace(long organizationId) {
        Map<Long, Instant> out = new HashMap<>();
        jdbc.sql("""
                        SELECT target_id, ends_at FROM data2flow_core.maintenance_windows
                         WHERE organization_id = :org AND status = 'ACTIVE' AND target_type = 'SPACE'""")
                .param("org", organizationId)
                .query((rs, n) -> {
                    Instant end = Pg.instant(rs, "ends_at");
                    out.merge(rs.getLong("target_id"), end == null ? Instant.MAX : end, (a, b) -> a.isAfter(b) ? a : b);
                    return null;
                }).list();
        return out;
    }

    /** 마지막 계산값 */
    public record ModeState(String mode, String source) {
    }

    public Map<Long, ModeState> listStates(long organizationId) {
        Map<Long, ModeState> out = new HashMap<>();
        jdbc.sql("SELECT space_id, mode, source FROM data2flow_core.space_mode_states WHERE organization_id = :org")
                .param("org", organizationId)
                .query((rs, n) -> out.put(rs.getLong("space_id"), new ModeState(rs.getString("mode"), rs.getString("source")))).list();
        return out;
    }

    public void upsertState(long organizationId, long spaceId, String mode, String source, boolean modeChanged, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.space_mode_states (space_id, organization_id, mode, source, changed_at, updated_at)
                        VALUES (:space, :org, :mode, :source, :now, :now)
                        ON CONFLICT (space_id) DO UPDATE SET mode = EXCLUDED.mode, source = EXCLUDED.source, updated_at = EXCLUDED.updated_at,
                            changed_at = CASE WHEN :changed THEN EXCLUDED.changed_at ELSE data2flow_core.space_mode_states.changed_at END""")
                .param("space", spaceId).param("org", organizationId).param("mode", mode).param("source", source)
                .param("changed", modeChanged).param("now", Pg.ts(now)).update();
    }

    /** 수동 지정(API-DEV-08 POST). mode가 null이면 해제 */
    public int updateOverride(long organizationId, long spaceId, String mode, Instant until, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.spaces SET mode_override = :mode, mode_override_until = :until, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'ACTIVE'""")
                .param("mode", mode).param("until", until == null ? null : Pg.ts(until)).param("by", userId).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", spaceId).update();
    }
}
