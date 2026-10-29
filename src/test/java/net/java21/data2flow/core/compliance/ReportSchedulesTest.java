package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.compliance.domain.ReportSchedules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ReportSchedulesTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final LocalTime NINE = LocalTime.of(9, 0);

    @Test
    @DisplayName("월간은 매월 N일, 분기는 1·4·7·10월 N일의 발행 시각(조직 시간대)이고 그 시각 자체는 지난 것으로 본다")
    void nextRun() {
        Instant oct3 = Instant.parse("2026-10-03T00:00:00Z");
        assertThat(ReportSchedules.nextRun("MONTHLY", 1, NINE, SEOUL, oct3)).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(ReportSchedules.nextRun("MONTHLY", 5, NINE, SEOUL, oct3)).isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));
        assertThat(ReportSchedules.nextRun("MONTHLY", 1, NINE, SEOUL, Instant.parse("2026-11-01T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(ReportSchedules.nextRun("QUARTERLY", 1, NINE, SEOUL, oct3)).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(ReportSchedules.nextRun("QUARTERLY", 28, NINE, SEOUL, Instant.parse("2026-12-31T00:00:00Z")))
                .isEqualTo(Instant.parse("2027-01-28T00:00:00Z"));
        // 서울 자정 직전(UTC 전날)도 서울 날짜로 본다
        assertThat(ReportSchedules.nextRun("MONTHLY", 1, LocalTime.of(0, 30), SEOUL, Instant.parse("2026-10-31T15:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-31T15:30:00Z"));
    }

    @Test
    @DisplayName("직전 달·직전 분기 기간은 실행 시각의 조직 날짜로 정한다")
    void period() {
        Instant nov1 = Instant.parse("2026-11-01T00:00:00Z");
        assertThat(ReportSchedules.period("PREVIOUS_MONTH", nov1, SEOUL)).containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(ReportSchedules.period("PREVIOUS_QUARTER", nov1, SEOUL)).containsExactly(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30));
        assertThat(ReportSchedules.period("PREVIOUS_QUARTER", Instant.parse("2027-01-01T00:00:00Z"), SEOUL))
                .containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31));
        assertThat(ReportSchedules.period("PREVIOUS_MONTH", Instant.parse("2027-03-01T00:00:00Z"), SEOUL))
                .containsExactly(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28));
    }
}
