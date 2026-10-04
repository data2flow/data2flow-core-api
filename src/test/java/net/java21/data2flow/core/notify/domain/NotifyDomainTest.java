package net.java21.data2flow.core.notify.domain;

import net.java21.data2flow.contracts.alarm.AlarmSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotifyDomainTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource({"2026-10-04T22:00,true", "2026-10-05T06:59,true", "2026-10-05T07:00,false", "2026-10-04T21:59,false",
            "2026-10-04T23:00,true"})
    @DisplayName("[RUL-05.04][TC-RUL-101] 방해 금지 22~07시: 자정을 넘는 창, 시작 포함·끝 배타")
    void overnightWindow(LocalDateTime at, boolean inside) {
        assertThat(new TimeWindow(Set.of(), LocalTime.of(22, 0), LocalTime.of(7, 0)).contains(at)).isEqualTo(inside);
    }

    @Test
    @DisplayName("[RUL-03.02][TC-RUL-069] 정책 요일·시간대: 평일 09~18, 요일 범위 오류는 거부")
    void weekdayWindow() {
        TimeWindow weekdays = new TimeWindow(Set.of(1, 2, 3, 4, 5), LocalTime.of(9, 0), LocalTime.of(18, 0));
        assertThat(weekdays.contains(LocalDateTime.of(2026, 10, 5, 10, 0))).isTrue();   // 월
        assertThat(weekdays.contains(LocalDateTime.of(2026, 10, 4, 10, 0))).isFalse();  // 일
        assertThat(weekdays.contains(LocalDateTime.of(2026, 10, 5, 18, 0))).isFalse();
        assertThat(new TimeWindow(Set.of(7), null, null).contains(LocalDateTime.of(2026, 10, 4, 23, 59))).isTrue();
        assertThatThrownBy(() -> new TimeWindow(Set.of(8), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TimeWindow.time("25:00")).isInstanceOf(IllegalArgumentException.class);
        assertThat(TimeWindow.time(null)).isNull();
    }

    @Test
    @DisplayName("[RUL-03.02][TC-RUL-069] BR-RUL-12 정책 매칭: 최소 심각도, 공간 하위 포함 여부, 규칙 목록, 시간대")
    void policyMatching() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 5, 10, 0);
        assertThat(PolicyMatcher.matches(AlarmSeverity.MAJOR, null, true, null, null, AlarmSeverity.CRITICAL, null, null, t)).isTrue();
        assertThat(PolicyMatcher.matches(AlarmSeverity.MAJOR, null, true, null, null, AlarmSeverity.MINOR, null, null, t)).isFalse();
        assertThat(PolicyMatcher.matches(AlarmSeverity.INFO, "/1/4/", true, null, null, AlarmSeverity.INFO, "/1/4/9/", null, t)).isTrue();
        assertThat(PolicyMatcher.matches(AlarmSeverity.INFO, "/1/4/", false, null, null, AlarmSeverity.INFO, "/1/4/9/", null, t)).isFalse();
        assertThat(PolicyMatcher.matches(AlarmSeverity.INFO, "/1/4/", true, null, null, AlarmSeverity.INFO, null, null, t)).isFalse();
        assertThat(PolicyMatcher.matches(AlarmSeverity.INFO, null, true, List.of(7L), null, AlarmSeverity.INFO, null, 8L, t)).isFalse();
        assertThat(PolicyMatcher.matches(AlarmSeverity.INFO, null, true, List.of(7L), null, AlarmSeverity.INFO, null, 7L, t)).isTrue();
        TimeWindow night = new TimeWindow(Set.of(), LocalTime.of(18, 0), LocalTime.of(9, 0));
        assertThat(PolicyMatcher.matches(AlarmSeverity.INFO, null, true, null, night, AlarmSeverity.INFO, null, null, t)).isFalse();
        assertThat(PolicyMatcher.matches(AlarmSeverity.INFO, null, true, null, null, AlarmSeverity.UNKNOWN, null, null, t)).isFalse();
    }

    @Test
    @DisplayName("[RUL-02.07][TC-RUL-063] BR-RUL-14 무음: 10:00~11:00 끝 배타, 매주 일요일 반복, 날짜 구간, 공간 무음은 하위 포함")
    void silences() {
        Instant start = LocalDateTime.of(2026, 10, 5, 10, 0).atZone(SEOUL).toInstant();
        SilenceMatcher.Period oneTime = new SilenceMatcher.Period("ONE_TIME", start, start.plusSeconds(3600), null, null, null);
        assertThat(SilenceMatcher.active(oneTime, start.plusSeconds(3599), SEOUL)).isTrue();
        assertThat(SilenceMatcher.active(oneTime, start.plusSeconds(3600), SEOUL)).isFalse();
        SilenceMatcher.Period sundays = new SilenceMatcher.Period("RECURRING", null, null, new TimeWindow(Set.of(7), null, null), null, null);
        assertThat(SilenceMatcher.active(sundays, LocalDateTime.of(2026, 10, 4, 23, 59, 59).atZone(SEOUL).toInstant(), SEOUL)).isTrue();
        assertThat(SilenceMatcher.active(sundays, LocalDateTime.of(2026, 10, 5, 0, 0).atZone(SEOUL).toInstant(), SEOUL)).isFalse();
        SilenceMatcher.Period vacation = new SilenceMatcher.Period("RECURRING", null, null, null, LocalDate.of(2026, 12, 20),
                LocalDate.of(2027, 2, 28));
        assertThat(SilenceMatcher.active(vacation, LocalDateTime.of(2027, 2, 28, 23, 0).atZone(SEOUL).toInstant(), SEOUL)).isTrue();
        assertThat(SilenceMatcher.active(vacation, LocalDateTime.of(2027, 3, 1, 0, 0).atZone(SEOUL).toInstant(), SEOUL)).isFalse();
        SilenceMatcher.Target t = new SilenceMatcher.Target(5L, 7L, 9L, "/1/4/9/");
        assertThat(SilenceMatcher.covers("SPACE", 4, "/1/4/", t)).isTrue();
        assertThat(SilenceMatcher.covers("SPACE", 3, "/1/3/", t)).isFalse();
        assertThat(SilenceMatcher.covers("RULE", 7, null, t)).isTrue();
        assertThat(SilenceMatcher.covers("DEVICE", 8, null, t)).isFalse();
        assertThat(SilenceMatcher.covers("ALARM", 5, null, t)).isTrue();
        assertThat(SilenceMatcher.covers("OTHER", 5, null, t)).isFalse();
    }

    @Test
    @DisplayName("[RUL-05.03][TC-RUL-100] BR-RUL-19 당직: 대체 근무 > 주간 교대, 비어 있으면 없음, 근무 끝 시각")
    void onCall() {
        List<OnCallResolver.Shift> shifts = List.of(new OnCallResolver.Shift(1, LocalTime.of(18, 0), LocalTime.of(9, 0), 10L));
        Instant mondayNight = LocalDateTime.of(2026, 10, 5, 21, 0).atZone(SEOUL).toInstant();
        assertThat(OnCallResolver.resolve(shifts, List.of(), mondayNight, SEOUL)).get()
                .satisfies(c -> {
                    assertThat(c.userId()).isEqualTo(10L);
                    assertThat(c.until()).isEqualTo(LocalDateTime.of(2026, 10, 6, 9, 0).atZone(SEOUL).toInstant());
                });
        List<OnCallResolver.Override> overrides = List.of(new OnCallResolver.Override(mondayNight.minusSeconds(3600),
                mondayNight.plusSeconds(3600), 10L, 20L));
        assertThat(OnCallResolver.resolve(shifts, overrides, mondayNight, SEOUL)).get()
                .satisfies(c -> assertThat(c.userId()).isEqualTo(20L));
        Instant tuesdayNoon = LocalDateTime.of(2026, 10, 6, 12, 0).atZone(SEOUL).toInstant();
        assertThat(OnCallResolver.resolve(shifts, List.of(), tuesdayNoon, SEOUL)).isEmpty();
    }

    @Test
    @DisplayName("[RUL-05.01][TC-RUL-091] 템플릿 변수: {{foo}}는 알 수 없는 변수, 아는 변수는 치환")
    void templateVariables() {
        assertThat(TemplateVariables.unknown("{{device.name}} {{foo}} {{ foo }} {{value}}", null)).containsExactly("foo");
        assertThat(TemplateVariables.render("[{{alarm.severity}}] {{device.name}} {{value}}{{x}}", Map.of("alarm.severity", "MAJOR",
                "device.name", "CO2-1", "value", 1050))).isEqualTo("[MAJOR] CO2-1 1050");
        assertThat(TemplateVariables.render(null, Map.of())).isNull();
    }
}
