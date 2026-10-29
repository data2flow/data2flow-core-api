package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.compliance.domain.StreakCalculator;
import net.java21.data2flow.core.compliance.domain.StreakCalculator.Broken;
import net.java21.data2flow.core.compliance.domain.StreakCalculator.DayOutcome;
import net.java21.data2flow.core.compliance.domain.StreakCalculator.MetricDay;
import net.java21.data2flow.core.compliance.domain.StreakCalculator.State;
import net.java21.data2flow.core.compliance.domain.StreakCalculator.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** BR-CMP-11 연속 달성 — TC-CMP-040 */
class StreakCalculatorTest {

    static final BigDecimal RESET = new BigDecimal("95");
    static final LocalDate D1 = LocalDate.of(2026, 8, 1);

    static DayOutcome day(int n, String co2, int co2Exceeded, String pm, int pmExceeded, boolean warning) {
        return new DayOutcome(D1.plusDays(n - 1L), Map.of(
                "co2", new MetricDay(new BigDecimal(co2), co2Exceeded, 0, false),
                "pm2_5", new MetricDay(new BigDecimal(pm), pmExceeded, warning ? 216 : 0, warning)));
    }

    @Test
    @DisplayName("[TC-CMP-040] 46일 달성 뒤 47일째 80%면 0으로 돌아가고 brokenOn=47일째, brokenMetric=pm2_5(이탈 분 최대), 깨짐 1건")
    void breaksOnLowRatio() {
        List<DayOutcome> days = new ArrayList<>();
        for (int n = 1; n <= 46; n++) {
            days.add(day(n, "99.5", 7, "96", 57, false));
        }
        days.add(day(47, "97", 43, "80", 288, false));
        Step step = StreakCalculator.advance(State.EMPTY, days, RESET);
        assertThat(step.state().currentDays()).isZero();
        assertThat(step.state().brokenOn()).isEqualTo(D1.plusDays(46));
        assertThat(step.state().brokenMetric()).isEqualTo("pm2_5");
        assertThat(step.broken()).containsExactly(new Broken(D1.plusDays(46), "pm2_5", 46));
    }

    @Test
    @DisplayName("결측률이 품질 기준을 넘은 날도 깨지고, 이미 0이면 다시 깨짐으로 세지 않는다. 달성일은 이어서 하루씩 는다")
    void qualityWarningBreaks() {
        State three = StreakCalculator.advance(State.EMPTY, List.of(day(1, "100", 0, "100", 0, false), day(2, "100", 0, "100", 0, false),
                day(3, "100", 0, "100", 0, false)), RESET).state();
        assertThat(three.currentDays()).isEqualTo(3);
        assertThat(three.startedOn()).isEqualTo(D1);
        Step missing = StreakCalculator.advance(three, List.of(day(4, "100", 0, "100", 0, true), day(5, "100", 0, "100", 0, true)), RESET);
        assertThat(missing.broken()).containsExactly(new Broken(D1.plusDays(3), "pm2_5", 3));
        Step again = StreakCalculator.advance(missing.state(), List.of(day(6, "100", 0, "100", 0, false)), RESET);
        assertThat(again.state().currentDays()).isEqualTo(1);
        assertThat(again.state().startedOn()).isEqualTo(D1.plusDays(5));
        assertThat(again.state().brokenOn()).isEqualTo(D1.plusDays(3));
    }
}
