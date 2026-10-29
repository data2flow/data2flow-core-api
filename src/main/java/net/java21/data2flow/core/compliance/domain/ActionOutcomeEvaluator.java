package net.java21.data2flow.core.compliance.domain;

import net.java21.data2flow.core.compliance.domain.DailyComplianceEvaluator.Limit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NavigableMap;

/**
 * 개선 조치 결과 자동 확인(CMP-02.05, BR-CMP-18, TC-CMP-065): 조치 시각부터 24시간 동안의 충족률.
 *
 * <ul>
 *   <li>분 판정은 {@link DailyComplianceEvaluator}와 같다(INSTANT·HOUR_1·HOUR_8 이동 평균). DAY_1은 24시간 전체 평균 하나로 판정한다</li>
 *   <li>충족률 = 충족 분 / (충족 + 이탈 분) × 100(소수 둘째 자리). 결측률(결측 분 / 1440)이 품질 기준(10%)을 넘으면 "확인 불가"(ratio null)</li>
 * </ul>
 */
public final class ActionOutcomeEvaluator {

    public static final Duration WINDOW = Duration.ofHours(24);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private ActionOutcomeEvaluator() {
    }

    /** {@code ratio}가 null이면 확인 불가 */
    public record Outcome(int metMinutes, int exceededMinutes, int missingMinutes, BigDecimal ratio) {

        public boolean verifiable() {
            return ratio != null;
        }
    }

    /** {@code values}는 분 시작 시각 → 공간 평균값. 이동 평균을 위해 조치 시각보다 8시간 앞부터 있으면 된다 */
    public static Outcome evaluate(NavigableMap<Instant, Double> values, String evalWindow, Limit limit, Instant actedAt) {
        Instant start = actedAt.truncatedTo(ChronoUnit.MINUTES);
        Instant end = start.plus(WINDOW);
        int window = DailyComplianceEvaluator.windowMinutes(evalWindow);
        Double whole = "DAY_1".equals(evalWindow) ? DailyComplianceEvaluator.average(values.subMap(start, true, end, false)) : null;
        int met = 0;
        int exceeded = 0;
        int total = 0;
        for (Instant m = start; m.isBefore(end); m = m.plus(MINUTE)) {
            total++;
            Double raw = values.get(m);
            if (raw == null) {
                continue;
            }
            double v = whole != null ? whole : window <= 1 ? raw
                    : DailyComplianceEvaluator.average(values.subMap(m.minus(Duration.ofMinutes(window - 1L)), true, m, true));
            if (limit.meets(v)) {
                met++;
            } else {
                exceeded++;
            }
        }
        int missing = total - met - exceeded;
        boolean poor = BigDecimal.valueOf(missing).divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP)
                .compareTo(DailyComplianceEvaluator.QUALITY_MISSING_LIMIT) > 0;
        BigDecimal ratio = poor || met + exceeded == 0 ? null
                : BigDecimal.valueOf(met * 100L).divide(BigDecimal.valueOf(met + exceeded), 2, RoundingMode.HALF_UP);
        return new Outcome(met, exceeded, missing, ratio);
    }
}
