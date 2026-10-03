package net.java21.data2flow.core.space.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.TreeSet;

/**
 * 운영 모드 계산(API-DEV-08 조회, BR-DEV-23 일부). M2는 수동 지정(OVERRIDE) > 운영 시간표(SCHEDULE)만 본다.
 * 유지보수 모드(OPS-05)와 달력(DEV-12, M5)은 그 기능이 생길 때 이 우선순위 사이에 끼운다.
 *
 * @param mode         OCCUPIED·UNOCCUPIED·HOLIDAY·MAINTENANCE
 * @param source       OVERRIDE·SCHEDULE
 * @param until        수동 지정 만료(없으면 null)
 * @param nextChangeAt 다음에 모드가 바뀌는 시각(7일 안에 없으면 null)
 */
public record SpaceMode(String mode, String source, Instant until, Instant nextChangeAt) {

    public static final String OCCUPIED = "OCCUPIED";
    public static final String UNOCCUPIED = "UNOCCUPIED";

    /** 수동 지정 */
    public static SpaceMode override(String mode, Instant until) {
        return new SpaceMode(mode, "OVERRIDE", until, until);
    }

    /** 시간표 기준 모드. 구간이 없으면 늘 UNOCCUPIED */
    public static SpaceMode fromSchedule(List<ScheduleSlot> slots, ZoneId zone, Instant now) {
        LocalDateTime local = LocalDateTime.ofInstant(now, zone);
        boolean occupied = occupied(slots, local);
        Instant next = null;
        TreeSet<LocalDateTime> boundaries = new TreeSet<>();
        LocalDate today = local.toLocalDate();
        for (int d = 0; d <= 8; d++) {
            LocalDate date = today.plusDays(d);
            int dow = date.getDayOfWeek().getValue();
            for (ScheduleSlot slot : slots) {
                if (slot.dayOfWeek() == dow) {
                    boundaries.add(at(date, slot.startMinute()));
                    boundaries.add(at(date, slot.endMinute()));
                }
            }
        }
        for (LocalDateTime candidate : boundaries) {
            if (candidate.isAfter(local) && occupied(slots, candidate) != occupied) {
                next = candidate.atZone(zone).toInstant();
                break;
            }
        }
        return new SpaceMode(occupied ? OCCUPIED : UNOCCUPIED, "SCHEDULE", null, next);
    }

    private static LocalDateTime at(LocalDate date, int minute) {
        return minute >= 1440 ? date.plusDays(1).atStartOfDay() : date.atTime(LocalTime.of(minute / 60, minute % 60));
    }

    private static boolean occupied(List<ScheduleSlot> slots, LocalDateTime local) {
        int dow = local.getDayOfWeek().getValue();
        int minute = local.getHour() * 60 + local.getMinute();
        return slots.stream().anyMatch(s -> s.contains(dow, minute));
    }
}
