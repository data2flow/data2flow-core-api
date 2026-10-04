package net.java21.data2flow.core.calendar.domain;

import net.java21.data2flow.core.calendar.domain.CalendarModels.EventRow;
import net.java21.data2flow.core.space.domain.SpaceMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** DEV-11.02 운영 모드 합치기(BR-DEV-23)와 휴일 판정 — TC-DEV-286 */
class CalendarModelsTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final long SITE = 1;
    static final long MAIN = 2;
    static final long ROOM = 3;
    static final List<Long> CHAIN = List.of(SITE, MAIN, ROOM);

    static EventRow event(String type, String affects, String from, String to, List<Long> scope, String origin) {
        return new EventRow(1, 1, "e", type, LocalDate.parse(from), LocalDate.parse(to), null, null, scope, origin, null, affects, null,
                false, null, 0, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    @DisplayName("[DEV-11.02][AT-DEV-17.1][TC-DEV-286] 방학(UNOCCUPIED) 07-01~08-31 범위 '본관' → 07-10 10:00 본관 실은 UNOCCUPIED(시간표 OCCUPIED보다 앞섬)")
    void vacation() {
        EventRow vacation = event("VACATION", "UNOCCUPIED", "2026-07-01", "2026-08-31", List.of(MAIN), "MANUAL");
        Instant at = Instant.parse("2026-07-10T01:00:00Z");
        Optional<SpaceMode> cal = CalendarModels.calendarMode(List.of(vacation), CHAIN, SEOUL, at);
        SpaceMode schedule = new SpaceMode("OCCUPIED", "SCHEDULE", null, null);
        SpaceMode mode = CalendarModels.combine(null, null, cal, schedule);
        assertThat(mode.mode()).isEqualTo("UNOCCUPIED");
        assertThat(mode.source()).isEqualTo("CALENDAR");
        assertThat(mode.until()).isEqualTo(Instant.parse("2026-08-31T15:00:00Z"));
        assertThat(CalendarModels.calendarMode(List.of(vacation), List.of(SITE, 9L), SEOUL, at)).isEmpty();
    }

    @Test
    @DisplayName("[DEV-11.02][AT-DEV-17.2][TC-DEV-286] 공휴일 API 개천절 + 같은 날 수동 등록 → 공존, 모드는 HOLIDAY 하나. HOLIDAY가 UNOCCUPIED보다 앞섬")
    void holidayWins() {
        EventRow api = event("HOLIDAY", "HOLIDAY", "2026-10-03", "2026-10-03", List.of(), "HOLIDAY_API");
        EventRow manual = event("HOLIDAY", "HOLIDAY", "2026-10-03", "2026-10-03", List.of(SITE), "MANUAL");
        EventRow vacation = event("VACATION", "UNOCCUPIED", "2026-10-01", "2026-10-10", List.of(), "MANUAL");
        Instant at = Instant.parse("2026-10-03T02:00:00Z");
        SpaceMode mode = CalendarModels.calendarMode(List.of(vacation, api, manual), CHAIN, SEOUL, at).orElseThrow();
        assertThat(mode.mode()).isEqualTo("HOLIDAY");
        assertThat(mode.until()).isEqualTo(Instant.parse("2026-10-03T15:00:00Z"));
        assertThat(CalendarModels.isHoliday(List.of(api, manual), CHAIN, LocalDate.of(2026, 10, 3))).isTrue();
        assertThat(CalendarModels.isHoliday(List.of(vacation), CHAIN, LocalDate.of(2026, 10, 3))).isFalse();
        EventRow none = event("EVENT", "NONE", "2026-10-03", "2026-10-03", List.of(), "MANUAL");
        assertThat(CalendarModels.calendarMode(List.of(none), CHAIN, SEOUL, at)).isEmpty();
    }

    @Test
    @DisplayName("[DEV-11.02][BR-DEV-23] 우선순위: 수동 지정 > 유지보수(끝 없음 → until null) > 달력 > 시간표, 시각 지정 일정은 그 시간 안에서만")
    void priority() {
        SpaceMode schedule = new SpaceMode("OCCUPIED", "SCHEDULE", null, null);
        SpaceMode override = SpaceMode.override("OCCUPIED", null);
        Optional<SpaceMode> cal = Optional.of(new SpaceMode("HOLIDAY", "CALENDAR", null, null));
        assertThat(CalendarModels.combine(override, Instant.MAX, cal, schedule).source()).isEqualTo("OVERRIDE");
        SpaceMode maint = CalendarModels.combine(null, Instant.MAX, cal, schedule);
        assertThat(maint.mode()).isEqualTo("MAINTENANCE");
        assertThat(maint.until()).isNull();
        Instant end = Instant.parse("2026-10-03T05:00:00Z");
        assertThat(CalendarModels.combine(null, end, cal, schedule).until()).isEqualTo(end);
        assertThat(CalendarModels.combine(null, null, Optional.empty(), schedule)).isEqualTo(schedule);
        EventRow timed = new EventRow(1, 1, "점검", "CLOSURE", LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-05"),
                LocalTime.of(13, 0), LocalTime.of(15, 0), List.of(), "MANUAL", null, "HOLIDAY", null, false, null, 0, Instant.EPOCH, Instant.EPOCH);
        assertThat(CalendarModels.calendarMode(List.of(timed), CHAIN, SEOUL, Instant.parse("2026-10-05T03:00:00Z"))).isEmpty();
        assertThat(CalendarModels.calendarMode(List.of(timed), CHAIN, SEOUL, Instant.parse("2026-10-05T04:30:00Z"))).isPresent();
        assertThat(CalendarModels.defaultAffects("CLOSURE")).isEqualTo("HOLIDAY");
        assertThat(CalendarModels.defaultAffects("EXAM")).isEqualTo("NONE");
    }
}
