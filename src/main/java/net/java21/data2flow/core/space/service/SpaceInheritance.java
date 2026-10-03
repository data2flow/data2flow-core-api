package net.java21.data2flow.core.space.service;

import net.java21.data2flow.core.space.domain.ScheduleSlot;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.domain.SpaceMode;
import net.java21.data2flow.core.space.dto.SpaceDtos.EffectiveTarget;
import net.java21.data2flow.core.space.dto.SpaceDtos.ModeResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.ScheduleResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.Slot;
import net.java21.data2flow.core.space.dto.SpaceDtos.TargetItem;
import net.java21.data2flow.core.space.dto.SpaceDtos.TargetsResponse;
import net.java21.data2flow.core.space.repository.SpaceSettingsRepository.SlotRow;
import net.java21.data2flow.core.space.repository.SpaceSettingsRepository.TargetRow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 상위 상속 계산(BR-DEV-04): 목표 범위는 측정 항목마다 가장 가까운(자기 포함) 공간의 값을, 운영 시간표는 {@code schedule_inherit=false}인
 * 가장 가까운 공간의 구간을 쓴다. 운영 모드는 가장 가까운 유효 수동 지정 &gt; 시간표(BR-DEV-23 중 M2 범위).
 * {@code chain}은 루트부터 자기까지의 공간 목록이다.
 */
final class SpaceInheritance {

    private SpaceInheritance() {
    }

    static TargetsResponse targets(List<Space> chain, List<TargetRow> rows) {
        Space self = chain.getLast();
        Map<Long, List<TargetRow>> bySpace = new LinkedHashMap<>();
        for (TargetRow row : rows) {
            bySpace.computeIfAbsent(row.spaceId(), k -> new ArrayList<>()).add(row);
        }
        Map<String, EffectiveTarget> effective = new TreeMap<>();
        Space inheritedFrom = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            Space space = chain.get(i);
            List<TargetRow> own = bySpace.getOrDefault(space.id(), List.of());
            boolean inherited = space.id() != self.id();
            if (inherited && !own.isEmpty() && inheritedFrom == null) {
                inheritedFrom = space;
            }
            for (TargetRow row : own) {
                effective.putIfAbsent(row.metricKey(), new EffectiveTarget(row.metricKey(), row.min(), row.max(), inherited,
                        inherited ? Long.toString(space.id()) : null, inherited ? space.name() : null));
            }
        }
        List<TargetItem> items = bySpace.getOrDefault(self.id(), List.of()).stream()
                .map(r -> new TargetItem(r.metricKey(), r.min(), r.max())).toList();
        boolean inherit = items.isEmpty();
        return new TargetsResponse(inherit, inherit && inheritedFrom != null ? Long.toString(inheritedFrom.id()) : null,
                inherit && inheritedFrom != null ? inheritedFrom.name() : null, items, List.copyOf(effective.values()));
    }

    /** 유효 시간표와 그 출처 */
    record EffectiveSchedule(Space source, List<ScheduleSlot> slots) {
    }

    static EffectiveSchedule schedule(List<Space> chain, List<SlotRow> rows) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            Space space = chain.get(i);
            if (!space.scheduleInherit()) {
                List<ScheduleSlot> slots = rows.stream().filter(r -> r.spaceId() == space.id()).map(SlotRow::slot).toList();
                return new EffectiveSchedule(space, slots);
            }
        }
        return new EffectiveSchedule(null, List.of());
    }

    static ScheduleResponse scheduleResponse(List<Space> chain, EffectiveSchedule effective) {
        Space self = chain.getLast();
        boolean fromOther = effective.source() != null && effective.source().id() != self.id();
        return new ScheduleResponse(self.scheduleInherit(), fromOther ? Long.toString(effective.source().id()) : null,
                fromOther ? effective.source().name() : null,
                effective.slots().stream().map(s -> new Slot(s.dayOfWeek(), s.start(), s.end())).toList());
    }

    static SpaceMode mode(List<Space> chain, List<ScheduleSlot> slots, Instant now) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            Space space = chain.get(i);
            if (space.modeOverride() != null && (space.modeOverrideUntil() == null || space.modeOverrideUntil().isAfter(now))) {
                return SpaceMode.override(space.modeOverride(), space.modeOverrideUntil());
            }
        }
        return SpaceMode.fromSchedule(slots, SpaceSupport.zone(chain), now);
    }

    static ModeResponse modeResponse(SpaceMode mode) {
        return new ModeResponse(mode.mode(), mode.source(), mode.until(), mode.nextChangeAt());
    }
}
