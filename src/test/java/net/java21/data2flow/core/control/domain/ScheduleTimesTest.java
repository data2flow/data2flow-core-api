package net.java21.data2flow.core.control.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleTimesTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Instant T0 = Instant.parse("2026-10-03T00:00:00Z"); // 토 09:00 KST

    @Test
    @DisplayName("[ACT-02.07][TC-ACT-063] 5필드 cron: 평일 08:50 → 다음은 월요일 08:50 KST, 6필드·빈 값은 거부")
    void cron() {
        assertThat(ScheduleTimes.next("RECURRING", null, "50 8 * * MON-FRI", null, 0, null, null, null, SEOUL, T0))
                .isEqualTo(Instant.parse("2026-10-04T23:50:00Z"));
        assertThatThrownBy(() -> ScheduleTimes.cron("0 50 8 * * *")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleTimes.cron(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[ACT-02.07][TC-ACT-060] 운영 시간 끝 18:00 + 10분 = 18:10, 시작 기준은 다음 날 09:00")
    void spaceHours() {
        ScheduleTimes.Hours[] hours = {new ScheduleTimes.Hours(6, LocalTime.of(9, 0), LocalTime.of(18, 0)),
                new ScheduleTimes.Hours(7, LocalTime.of(9, 0), LocalTime.of(18, 0))};
        assertThat(ScheduleTimes.next("SPACE_HOURS", null, null, hours, 10, "END", null, null, SEOUL, T0))
                .isEqualTo(Instant.parse("2026-10-03T09:10:00Z"));
        assertThat(ScheduleTimes.next("SPACE_HOURS", null, null, hours, 0, "START", null, null, SEOUL, T0))
                .isEqualTo(Instant.parse("2026-10-04T00:00:00Z"));
        assertThat(ScheduleTimes.next("SPACE_HOURS", null, null, new ScheduleTimes.Hours[0], 0, "START", null, null, SEOUL, T0)).isNull();
    }

    @Test
    @DisplayName("[ACT-02.07][TC-ACT-063] 한 번(ONCE)은 지나면 없음, 유효 기간 밖이면 없음, 시작 전이면 시작일부터")
    void onceAndValidity() {
        assertThat(ScheduleTimes.next("ONCE", T0.plusSeconds(60), null, null, 0, null, null, null, SEOUL, T0)).isEqualTo(T0.plusSeconds(60));
        assertThat(ScheduleTimes.next("ONCE", T0.minusSeconds(60), null, null, 0, null, null, null, SEOUL, T0)).isNull();
        assertThat(ScheduleTimes.next("RECURRING", null, "0 9 * * *", null, 0, null, null, LocalDate.of(2026, 10, 3), SEOUL, T0)).isNull();
        assertThat(ScheduleTimes.next("RECURRING", null, "0 9 * * *", null, 0, null, LocalDate.of(2026, 10, 10), null, SEOUL, T0))
                .isEqualTo(Instant.parse("2026-10-10T00:00:00Z"));
        assertThat(ScheduleTimes.next("OTHER", null, null, null, 0, null, null, null, SEOUL, T0)).isNull();
    }
}
