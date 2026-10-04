package net.java21.data2flow.core.space.service;

import net.java21.data2flow.core.calendar.domain.CalendarModels;
import net.java21.data2flow.core.calendar.domain.CalendarModels.EventRow;
import net.java21.data2flow.core.calendar.repository.CalendarEventRepository;
import net.java21.data2flow.core.calendar.repository.SpaceModeRepository;
import net.java21.data2flow.core.space.domain.ScheduleSlot;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.domain.SpaceMode;
import net.java21.data2flow.core.space.repository.SpaceSettingsRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 공간 운영 모드(DEV-11.02, BR-DEV-23): 수동 지정 > 유지보수 모드(OPS-05) > 조직 달력(DEV-12, HOLIDAY/UNOCCUPIED) > 운영 시간표(DEV-11.01).
 * API-DEV-08·126, 공간 상세, 1분 모드 계산(EVT-DEV-06)이 같은 계산을 쓴다.
 */
@Component
public class SpaceModes {

    private final SpaceSettingsRepository settings;
    private final CalendarEventRepository calendar;
    private final SpaceModeRepository modes;

    public SpaceModes(SpaceSettingsRepository settings, CalendarEventRepository calendar, SpaceModeRepository modes) {
        this.settings = settings;
        this.calendar = calendar;
        this.modes = modes;
    }

    /** chain은 루트부터 자기까지 */
    public SpaceMode modeOf(List<Space> chain, Instant now) {
        long org = chain.getLast().organizationId();
        List<Long> chainIds = chain.stream().map(Space::id).toList();
        List<ScheduleSlot> slots = SpaceInheritance.schedule(chain, settings.findSlots(org, chainIds)).slots();
        return modeOf(chain, slots, now);
    }

    /** 시간표 구간을 이미 읽은 경우 */
    public SpaceMode modeOf(List<Space> chain, List<ScheduleSlot> slots, Instant now) {
        long org = chain.getLast().organizationId();
        List<Long> chainIds = chain.stream().map(Space::id).toList();
        SpaceMode base = SpaceInheritance.mode(chain, slots, now);
        SpaceMode override = "OVERRIDE".equals(base.source()) ? base : null;
        SpaceMode schedule = override == null ? base : SpaceMode.fromSchedule(slots, SpaceSupport.zone(chain), now);
        Instant maintenance = override == null ? modes.activeMaintenanceEnd(org, chainIds).orElse(null) : null;
        Optional<SpaceMode> fromCalendar = Optional.empty();
        if (override == null && maintenance == null) {
            ZoneId zone = SpaceSupport.zone(chain);
            LocalDate today = LocalDate.ofInstant(now, zone);
            List<EventRow> events = calendar.listAffecting(org, chainIds, today.minusDays(1), today.plusDays(1));
            fromCalendar = CalendarModels.calendarMode(events, chainIds, zone, now);
        }
        return CalendarModels.combine(override, maintenance, fromCalendar, schedule);
    }

    /** 그날 이 공간(없으면 조직 전체 일정만)이 휴일인가(예약 제어 skipHolidays) */
    public boolean isHoliday(long organizationId, List<Long> chainIds, LocalDate date) {
        List<EventRow> events = calendar.listAffecting(organizationId, chainIds, date, date);
        return CalendarModels.isHoliday(events, chainIds, date);
    }

    /** 조직 공간 전체의 모드(1분 계산 EVT-DEV-06). 시간표·유지보수·달력을 한 번씩만 읽는다 */
    public Map<Long, SpaceMode> modesOf(long organizationId, List<Space> spaces, Instant now) {
        Map<Long, Space> byId = new java.util.HashMap<>();
        spaces.forEach(s -> byId.put(s.id(), s));
        Map<Long, List<SpaceSettingsRepository.SlotRow>> slotsBySpace = new java.util.HashMap<>();
        settings.findSlots(organizationId, byId.keySet()).forEach(r -> slotsBySpace.computeIfAbsent(r.spaceId(), k -> new java.util.ArrayList<>()).add(r));
        Map<Long, Instant> maintenance = modes.activeMaintenanceBySpace(organizationId);
        LocalDate today = LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
        List<EventRow> events = calendar.listOverlapping(organizationId, today.minusDays(2), today.plusDays(2)).stream()
                .filter(e -> !"NONE".equals(e.affectsMode())).toList();
        Map<Long, SpaceMode> out = new java.util.LinkedHashMap<>();
        for (Space space : spaces) {
            List<Space> chain = chainOf(space, byId);
            List<Long> chainIds = chain.stream().map(Space::id).toList();
            List<SpaceSettingsRepository.SlotRow> rows = chainIds.stream().flatMap(id -> slotsBySpace.getOrDefault(id, List.of()).stream()).toList();
            List<ScheduleSlot> slots = SpaceInheritance.schedule(chain, rows).slots();
            SpaceMode base = SpaceInheritance.mode(chain, slots, now);
            SpaceMode override = "OVERRIDE".equals(base.source()) ? base : null;
            SpaceMode schedule = override == null ? base : SpaceMode.fromSchedule(slots, SpaceSupport.zone(chain), now);
            Instant maint = chainIds.stream().map(maintenance::get).filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
            out.put(space.id(), CalendarModels.combine(override, maint,
                    CalendarModels.calendarMode(events, chainIds, SpaceSupport.zone(chain), now), schedule));
        }
        return out;
    }

    /** 공간 ID → 루트부터의 경로 공간(모드 계산 일괄용) */
    public static List<Space> chainOf(Space space, Map<Long, Space> byId) {
        return space.pathIds().stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }
}
