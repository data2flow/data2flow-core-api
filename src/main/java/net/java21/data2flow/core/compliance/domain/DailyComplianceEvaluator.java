package net.java21.data2flow.core.compliance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;

/**
 * 분 단위 기준 판정과 일 집계(CMP-02.01, BR-CMP-07·10, TC-CMP-039).
 *
 * <ul>
 *   <li>분마다 공간 평균값(정상 품질 값만)이 있으면 평가 단위로 판정한다. INSTANT는 그 분 값, HOUR_1·HOUR_8은 그 분까지 60·480분 이동 평균
 *       (창 안에 있는 분만), DAY_1은 그날(조직 시간대) 평균이다. 값이 없는 분은 결측이다</li>
 *   <li>일 충족률 = 충족 분 / (충족 + 이탈 분) × 100. 결측은 분자·분모 어디에도 넣지 않고, 결측률은 결측 분 / 하루 분이다.
 *       결측률이 10%를 넘으면 품질 경고다</li>
 *   <li>이탈 구간은 이탈 분이 이어지는 구간이다. 충족 분을 만나면 닫고(끝 = 마지막 이탈 분 + 1분), 결측 분은 구간을 끊지도 늘리지도 않는다.
 *       평가 끝까지 열려 있어도 마지막 이탈 분이 60분보다 오래됐으면(데이터가 끊김) 닫는다. 최고값은 기준에서 가장 멀리 벗어난 평가값이다</li>
 * </ul>
 */
public final class DailyComplianceEvaluator {

    /** 데이터 품질 기준: 결측률이 이 값을 넘으면 경고(scenarios.md 3b, TC-CMP-079) */
    public static final BigDecimal QUALITY_MISSING_LIMIT = new BigDecimal("0.10");
    static final Duration STALE_RUN = Duration.ofMinutes(60);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private DailyComplianceEvaluator() {
    }

    /** 기준 경계. null 쪽은 제한이 없다 */
    public record Limit(BigDecimal lower, boolean lowerInclusive, BigDecimal upper, boolean upperInclusive) {

        public boolean meets(double v) {
            if (upper != null) {
                double u = upper.doubleValue();
                if (upperInclusive ? v > u : v >= u) {
                    return false;
                }
            }
            if (lower != null) {
                double l = lower.doubleValue();
                return lowerInclusive ? v >= l : v > l;
            }
            return true;
        }

        /** 기준에서 벗어난 양(충족이면 0) */
        public double deviation(double v) {
            double d = 0;
            if (upper != null) {
                d = Math.max(d, v - upper.doubleValue());
            }
            if (lower != null) {
                d = Math.max(d, lower.doubleValue() - v);
            }
            return d;
        }
    }

    /** 하루 집계. {@code dailyRatio}는 %(소수 둘째 자리), 충족·이탈 분이 모두 0이면 null */
    public record DayStats(LocalDate day, int dayMinutes, int metMinutes, int exceededMinutes, int missingMinutes, BigDecimal dailyRatio,
                           Double maxValue, Double avgValue, boolean qualityWarning) {

        public BigDecimal missingRatio() {
            return BigDecimal.valueOf(missingMinutes).divide(BigDecimal.valueOf(dayMinutes), 4, RoundingMode.HALF_UP);
        }
    }

    /**
     * 이탈 구간. {@code endedAt}이 null이면 진행 중. {@code fromStart}는 평가 첫 분부터 이 구간 시작까지 충족 분이 없었다는 뜻이다(평가 전에
     * 시작한 진행 중 구간을 이어 붙일 때 쓴다)
     */
    public record Run(Instant startedAt, Instant endedAt, double peakValue, boolean fromStart) {
    }

    public record Result(List<DayStats> days, List<Run> runs) {
    }

    /**
     * {@code days}의 날을 차례로 판정한다. {@code values}는 분 시작 시각 → 공간 평균값이고, 이동 평균을 위해 첫날 0시보다 8시간 앞부터 있으면
     * 된다. {@code until}(분 경계) 뒤의 분은 아직 오지 않은 분이라 세지 않는다(오늘 행)
     */
    public static Result evaluate(NavigableMap<Instant, Double> values, String evalWindow, Limit limit, List<LocalDate> days, ZoneId zone,
                                  Instant until) {
        List<DayStats> stats = new ArrayList<>();
        List<Run> runs = new ArrayList<>();
        Instant runStart = null;
        Instant lastExceeded = null;
        double peak = 0;
        double peakDeviation = -1;
        boolean metSeen = false;
        boolean runFromStart = false;
        for (LocalDate day : days) {
            Instant start = day.atStartOfDay(zone).toInstant();
            Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
            int dayMinutes = (int) Duration.between(start, end).toMinutes();
            Instant stop = end.isAfter(until) ? until : end;
            Double dayAvg = "DAY_1".equals(evalWindow) ? average(values.subMap(start, true, stop, false)) : null;
            int window = windowMinutes(evalWindow);
            int met = 0;
            int exceeded = 0;
            int elapsed = 0;
            double max = Double.NEGATIVE_INFINITY;
            double sum = 0;
            int count = 0;
            for (Instant m = start; m.isBefore(stop); m = m.plus(MINUTE)) {
                elapsed++;
                Double raw = values.get(m);
                if (raw == null) {
                    continue;
                }
                max = Math.max(max, raw);
                sum += raw;
                count++;
                double v = dayAvg != null ? dayAvg : window <= 1 ? raw
                        : average(values.subMap(m.minus(Duration.ofMinutes(window - 1L)), true, m, true));
                if (limit.meets(v)) {
                    met++;
                    if (runStart != null) {
                        runs.add(new Run(runStart, lastExceeded.plus(MINUTE), peak, runFromStart));
                        runStart = null;
                    }
                    metSeen = true;
                } else {
                    exceeded++;
                    double deviation = limit.deviation(v);
                    if (runStart == null) {
                        runStart = m;
                        runFromStart = !metSeen;
                        peakDeviation = -1;
                    }
                    lastExceeded = m;
                    if (deviation > peakDeviation) {
                        peakDeviation = deviation;
                        peak = v;
                    }
                }
            }
            int missing = elapsed - met - exceeded;
            BigDecimal ratio = met + exceeded == 0 ? null
                    : BigDecimal.valueOf(met * 100L).divide(BigDecimal.valueOf(met + exceeded), 2, RoundingMode.HALF_UP);
            boolean warning = BigDecimal.valueOf(missing).divide(BigDecimal.valueOf(dayMinutes), 6, RoundingMode.HALF_UP)
                    .compareTo(QUALITY_MISSING_LIMIT) > 0;
            stats.add(new DayStats(day, dayMinutes, met, exceeded, missing, ratio, count == 0 ? null : max, count == 0 ? null : sum / count,
                    warning));
        }
        if (runStart != null) {
            boolean stale = lastExceeded.plus(MINUTE).plus(STALE_RUN).isBefore(until);
            runs.add(new Run(runStart, stale ? lastExceeded.plus(MINUTE) : null, peak, runFromStart));
        }
        return new Result(stats, runs);
    }

    static int windowMinutes(String evalWindow) {
        return switch (evalWindow == null ? "INSTANT" : evalWindow) {
            case "HOUR_1" -> 60;
            case "HOUR_8" -> 480;
            default -> 1;
        };
    }

    private static Double average(NavigableMap<Instant, Double> window) {
        if (window.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (double v : window.values()) {
            sum += v;
        }
        return sum / window.size();
    }
}
