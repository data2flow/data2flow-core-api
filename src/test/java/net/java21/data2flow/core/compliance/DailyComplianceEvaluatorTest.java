package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.compliance.domain.DailyComplianceEvaluator;
import net.java21.data2flow.core.compliance.domain.DailyComplianceEvaluator.DayStats;
import net.java21.data2flow.core.compliance.domain.DailyComplianceEvaluator.Limit;
import net.java21.data2flow.core.compliance.domain.DailyComplianceEvaluator.Result;
import net.java21.data2flow.core.compliance.domain.DailyComplianceEvaluator.Run;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** BR-CMP-07·10 분 판정과 일 집계 — TC-CMP-039, 이탈 구간 */
class DailyComplianceEvaluatorTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    static final Instant START = DAY.atStartOfDay(SEOUL).toInstant();
    static final Limit CO2 = new Limit(null, true, new BigDecimal("1000"), true);

    static Instant minute(int m) {
        return START.plus(Duration.ofMinutes(m));
    }

    @Test
    @DisplayName("[TC-CMP-039] 충족 1200·이탈 120·결측 120분이면 일 충족률 90.91%, 결측률 0.0833. 결측은 분자·분모 어디에도 없다")
    void dailyRatio() {
        NavigableMap<Instant, Double> values = new TreeMap<>();
        for (int m = 0; m < 1440; m++) {
            if (m < 1200) {
                values.put(minute(m), 800.0);
            } else if (m < 1320) {
                values.put(minute(m), 1200.0);
            }
        }
        Result r = DailyComplianceEvaluator.evaluate(values, "INSTANT", CO2, List.of(DAY), SEOUL, START.plus(Duration.ofDays(1)));
        DayStats d = r.days().getFirst();
        assertThat(d.metMinutes()).isEqualTo(1200);
        assertThat(d.exceededMinutes()).isEqualTo(120);
        assertThat(d.missingMinutes()).isEqualTo(120);
        assertThat(d.dailyRatio()).isEqualByComparingTo("90.91");
        assertThat(d.missingRatio()).isEqualByComparingTo("0.0833");
        assertThat(d.qualityWarning()).isFalse();
        assertThat(d.maxValue()).isEqualTo(1200.0);
        // 이탈 120분은 한 구간이고 뒤가 결측이라 마지막 이탈 뒤 60분이 지나 닫혔다
        assertThat(r.runs()).containsExactly(new Run(minute(1200), minute(1320), 1200.0, false));
    }

    @Test
    @DisplayName("HOUR_1은 60분 이동 평균으로 판정하고, 결측 15%면 품질 경고, 오늘 행은 지난 분만 센다")
    void movingAverageAndPartialDay() {
        NavigableMap<Instant, Double> values = new TreeMap<>();
        // 0~29분 1300, 30~59분 600: m분째 평균은 (30·1300 + (m−29)·600) / (m+1)이라 52분째에 처음 1000 이하가 된다
        for (int m = 0; m < 60; m++) {
            values.put(minute(m), m < 30 ? 1300.0 : 600.0);
        }
        Result r = DailyComplianceEvaluator.evaluate(values, "HOUR_1", CO2, List.of(DAY), SEOUL, minute(400));
        DayStats d = r.days().getFirst();
        assertThat(d.exceededMinutes()).isEqualTo(52);
        assertThat(d.metMinutes()).isEqualTo(8);
        assertThat(d.missingMinutes()).isEqualTo(340);
        assertThat(d.qualityWarning()).isTrue();
        Run run = r.runs().getFirst();
        assertThat(run.startedAt()).isEqualTo(minute(0));
        assertThat(run.endedAt()).isEqualTo(minute(52));
        assertThat(run.fromStart()).isTrue();
        assertThat(run.peakValue()).isEqualTo(1300.0);
    }

    @Test
    @DisplayName("이탈이 평가 끝까지 이어지면 진행 중(endedAt null), DAY_1은 하루 평균으로 모든 분을 판정한다")
    void openRunAndDailyWindow() {
        NavigableMap<Instant, Double> values = new TreeMap<>();
        values.put(minute(0), 900.0);
        values.put(minute(1), 1200.0);
        values.put(minute(2), 1300.0);
        Result instant = DailyComplianceEvaluator.evaluate(values, "INSTANT", CO2, List.of(DAY), SEOUL, minute(3));
        assertThat(instant.runs()).containsExactly(new Run(minute(1), null, 1300.0, false));
        Result daily = DailyComplianceEvaluator.evaluate(values, "DAY_1", CO2, List.of(DAY), SEOUL, minute(3));
        assertThat(daily.days().getFirst().exceededMinutes()).isEqualTo(3);
        assertThat(daily.days().getFirst().dailyRatio()).isEqualByComparingTo("0");
    }
}
