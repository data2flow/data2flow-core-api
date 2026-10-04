package net.java21.data2flow.core.calendar.domain;

import net.java21.data2flow.core.space.domain.SpaceMode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 조직 달력 규칙(DEV-12.01, DEV-11.02, BR-DEV-23·BR-DSC-18).
 * <ul>
 *   <li>일정 유형: HOLIDAY·CLOSURE·EVENT·VACATION·EXAM·OTHER, 출처: MANUAL·HOLIDAY_API·ICAL</li>
 *   <li>운영 모드 영향 기본값: HOLIDAY·CLOSURE → HOLIDAY, VACATION → UNOCCUPIED, 나머지 → NONE(직접 바꿀 수 있음)</li>
 *   <li>기간은 366일까지(화면 규칙과 같음)</li>
 * </ul>
 */
public final class CalendarModels {

    public static final Set<String> TYPES = Set.of("HOLIDAY", "CLOSURE", "EVENT", "VACATION", "EXAM", "OTHER");
    public static final Set<String> AFFECTS = Set.of("HOLIDAY", "UNOCCUPIED", "NONE");
    public static final String MANUAL = "MANUAL";
    public static final String HOLIDAY_API = "HOLIDAY_API";
    public static final String ICAL = "ICAL";
    public static final int MAX_DAYS = 366;

    private CalendarModels() {
    }

    public static String defaultAffects(String type) {
        return switch (type) {
            case "HOLIDAY", "CLOSURE" -> "HOLIDAY";
            case "VACATION" -> "UNOCCUPIED";
            default -> "NONE";
        };
    }

    /** 달력 일정 한 행 */
    public record EventRow(long id, long organizationId, String title, String type, LocalDate startsOn, LocalDate endsOn,
                           LocalTime startTime, LocalTime endTime, List<Long> scopeSpaceIds, String origin, String originUid,
                           String affectsMode, Long sourceId, boolean locallyModified, Instant originDeletedAt, int version,
                           Instant createdAt, Instant updatedAt) {

        /** 적용 범위가 비었거나(조직 전체) 공간 경로(루트~자기)의 하나를 포함하는가 */
        public boolean appliesTo(Collection<Long> chainIds) {
            return scopeSpaceIds.isEmpty() || scopeSpaceIds.stream().anyMatch(chainIds::contains);
        }

        /** 현지 시작(포함)·끝(제외) */
        public LocalDateTime localStart() {
            return startsOn.atTime(startTime == null ? LocalTime.MIDNIGHT : startTime);
        }

        public LocalDateTime localEnd() {
            return endTime == null ? endsOn.plusDays(1).atStartOfDay() : endsOn.atTime(endTime);
        }

        public boolean covers(LocalDate date) {
            return !date.isBefore(startsOn) && !date.isAfter(endsOn);
        }
    }

    /**
     * 달력이 지금 운영 모드를 정하는가(BR-DEV-23 세 번째 단계). HOLIDAY가 UNOCCUPIED보다 앞선다. until은 고른 일정 중 가장 늦은 끝.
     */
    public static Optional<SpaceMode> calendarMode(List<EventRow> events, Collection<Long> chainIds, ZoneId zone, Instant now) {
        LocalDateTime local = LocalDateTime.ofInstant(now, zone);
        String best = null;
        LocalDateTime until = null;
        for (EventRow e : events) {
            if (!e.appliesTo(chainIds) || "NONE".equals(e.affectsMode())) {
                continue;
            }
            if (local.isBefore(e.localStart()) || !local.isBefore(e.localEnd())) {
                continue;
            }
            String mode = e.affectsMode();
            if (best == null || ("HOLIDAY".equals(mode) && !"HOLIDAY".equals(best))) {
                best = mode;
                until = e.localEnd();
            } else if (best.equals(mode) && e.localEnd().isAfter(until)) {
                until = e.localEnd();
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        Instant end = until.atZone(zone).toInstant();
        return Optional.of(new SpaceMode(best, "CALENDAR", end, end));
    }

    /** 그날이 휴일인가(영향 HOLIDAY 일정이 그 날짜를 덮음, 예약 제어 skipHolidays) */
    public static boolean isHoliday(List<EventRow> events, Collection<Long> chainIds, LocalDate date) {
        return events.stream().anyMatch(e -> "HOLIDAY".equals(e.affectsMode()) && e.covers(date) && e.appliesTo(chainIds));
    }

    /**
     * 운영 모드 합치기(BR-DEV-23): 수동 지정 > 유지보수 모드 > 달력(HOLIDAY/UNOCCUPIED) > 운영 시간표.
     *
     * @param override         수동 지정(없으면 null)
     * @param maintenanceUntil 진행 중인 유지보수의 끝(진행 중이 아니면 null, 끝이 정해지지 않았으면 {@link Instant#MAX})
     */
    public static SpaceMode combine(SpaceMode override, Instant maintenanceUntil, Optional<SpaceMode> calendar, SpaceMode schedule) {
        if (override != null) {
            return override;
        }
        if (maintenanceUntil != null) {
            Instant until = maintenanceUntil.equals(Instant.MAX) ? null : maintenanceUntil;
            return new SpaceMode("MAINTENANCE", "MAINTENANCE", until, until);
        }
        return calendar.orElse(schedule);
    }
}
