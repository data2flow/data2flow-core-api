package net.java21.data2flow.core.telemetry.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 채우기 방식(TSD-06.02, BR-TSD-10): none(기본) / previous(직전 값, 최대 기기 예상 주기 × 3까지만) / linear(앞뒤 값 사이).
 * 데이터 공백({@code data_gaps}) 구간과 공백을 건너는 구간은 previous·linear로도 채우지 않는다(AT-TSD-01.4).
 * 집계 단위 조회에만 적용한다. 원본은 정해진 격자가 없으므로 그대로 돌려준다.
 */
public enum FillMode {
    NONE, PREVIOUS, LINEAR;

    /** 빈 값이면 NONE, 모르는 값이면 IllegalArgumentException */
    public static FillMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        return valueOf(raw.strip().toUpperCase(Locale.ROOT));
    }

    /** 공백 구간 [from, to) */
    public record Gap(Instant from, Instant to) {
        boolean overlaps(Instant a, Instant b) {
            return from.isBefore(b) && to.isAfter(a);
        }
    }

    /**
     * @param points 집계 점(시각 오름차순)
     * @param grid   구간 시작 시각 목록(오름차순)
     * @param gaps   데이터 공백
     * @param hold   previous가 직전 값을 이어 쓸 수 있는 최대 시간(예상 주기 × 3)
     */
    public List<SeriesPoint> fill(List<SeriesPoint> points, List<Instant> grid, List<Gap> gaps, Duration hold) {
        if (this == NONE || points.isEmpty()) {
            return points;
        }
        TreeMap<Instant, SeriesPoint> real = new TreeMap<>();
        for (SeriesPoint p : points) {
            if (p.value() != null) {
                real.put(p.t(), p);
            }
        }
        TreeMap<Instant, SeriesPoint> out = new TreeMap<>();
        for (SeriesPoint p : points) {
            out.put(p.t(), p);
        }
        for (Instant t : grid) {
            if (out.containsKey(t) && out.get(t).value() != null) {
                continue;
            }
            Map.Entry<Instant, SeriesPoint> before = real.lowerEntry(t);
            if (before == null || crossesGap(gaps, before.getKey(), t)) {
                continue;
            }
            Double value = null;
            if (this == PREVIOUS) {
                if (!Duration.between(before.getKey(), t).minus(hold).isPositive()) {
                    value = before.getValue().value();
                }
            } else {
                Map.Entry<Instant, SeriesPoint> after = real.higherEntry(t);
                if (after != null && !crossesGap(gaps, t, after.getKey())) {
                    double total = Duration.between(before.getKey(), after.getKey()).toMillis();
                    double part = Duration.between(before.getKey(), t).toMillis();
                    double a = before.getValue().value();
                    double b = after.getValue().value();
                    value = a + (b - a) * (part / total);
                }
            }
            if (value != null) {
                out.put(t, new SeriesPoint(t, value, 0));
            }
        }
        return new ArrayList<>(out.values());
    }

    /** (a, b] 사이에 공백이 걸쳐 있는가. 끝 구간 자체가 공백 안이어도 걸친 것이다 */
    private static boolean crossesGap(List<Gap> gaps, Instant a, Instant b) {
        for (Gap gap : gaps) {
            if (gap.overlaps(a.plusNanos(1), b.plusNanos(1))) {
                return true;
            }
        }
        return false;
    }
}
