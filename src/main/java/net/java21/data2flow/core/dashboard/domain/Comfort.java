package net.java21.data2flow.core.dashboard.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 공간 쾌적도(DSH-01.02, spec/detail/DSH/domain-model.md "계산 모델"). 저장하지 않고 목표 환경(DEV-01.04)과 최신값으로 매번 계산한다.
 *
 * <p>판정(TC-DSH-006, AT-DSH-01.2):
 * <ul>
 *   <li>목표 범위 안이면 NORMAL, 하나라도 벗어나면 CAUTION</li>
 *   <li>벗어난 정도가 경고 폭을 넘으면 WARNING. 경고 폭은 양쪽 경계가 있으면 범위 폭의 20%(domain-model), 한쪽 경계만 있으면
 *       그 경계 값의 10%(TC-DSH-006: CO2 ≤ 1,000ppm에서 1,150ppm → WARNING, 목표 초과 10% 이내 → CAUTION)</li>
 *   <li>같은 공간의 MAJOR 이상 알람으로 WARNING이 되는 규칙은 알람이 생기는 M4에서 더한다</li>
 *   <li>목표 값이 있는 측정 항목의 최신값이 하나도 없으면 UNKNOWN</li>
 *   <li>공간과 조상 어디에도 목표가 없으면 기본 기준({@link #DEFAULT_TARGETS}: CO2 1,000ppm 이하, 실내공기질 관리법 유지 기준)을 쓴다</li>
 * </ul>
 * 원인은 가장 많이 벗어난(경고 폭 대비 비율이 큰) 측정 항목부터, 측정 항목마다 가장 나쁜 값 하나다.
 */
public final class Comfort {

    /** 목표 환경이 없는 공간의 기본 기준(TC-DSH-006 "목표 환경 없는 공간은 기본 기준") */
    public static final List<Target> DEFAULT_TARGETS = List.of(new Target("co2", null, 1000.0));

    static final double RANGE_WARNING_FRACTION = 0.20;
    static final double BOUND_WARNING_FRACTION = 0.10;

    private Comfort() {
    }

    public enum State {
        NORMAL, CAUTION, WARNING, UNKNOWN;

        /** 나쁜 순서(WARNING이 가장 나쁨). 목록 정렬에 쓴다 */
        public int badness() {
            return switch (this) {
                case WARNING -> 3;
                case CAUTION -> 2;
                case UNKNOWN -> 1;
                case NORMAL -> 0;
            };
        }
    }

    /** 목표 범위 하나(min·max 중 하나 이상) */
    public record Target(String metricKey, Double min, Double max) {
    }

    /** 기기 하나의 최신값 하나 */
    public record Reading(long deviceId, String metricKey, double value, String unit, Integer quality, Instant at) {
    }

    /** 원인 측정 항목. target은 판정에 쓴 범위 */
    public record Cause(String metricKey, double value, String unit, Target target, double severity) {
    }

    /** 판정 결과 */
    public record Result(State state, List<Cause> causes, Instant updatedAt) {

        public static final Result UNKNOWN = new Result(State.UNKNOWN, List.of(), null);
    }

    /**
     * 판정한다.
     *
     * @param targets  공간의 실제 목표(상속 반영). 비었으면 기본 기준
     * @param readings 공간(하위 포함)의 기기 최신값
     */
    public static Result evaluate(List<Target> targets, List<Reading> readings) {
        List<Target> effective = targets == null || targets.isEmpty() ? DEFAULT_TARGETS : targets;
        State state = State.NORMAL;
        boolean evaluated = false;
        Instant updatedAt = null;
        List<Cause> causes = new ArrayList<>();
        for (Target target : effective) {
            Cause worst = null;
            for (Reading r : readings) {
                if (!r.metricKey().toLowerCase(Locale.ROOT).equals(target.metricKey().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                evaluated = true;
                if (r.at() != null && (updatedAt == null || r.at().isAfter(updatedAt))) {
                    updatedAt = r.at();
                }
                double severity = severity(target, r.value());
                if (severity > 0 && (worst == null || severity > worst.severity())) {
                    worst = new Cause(r.metricKey(), r.value(), r.unit(), target, severity);
                }
            }
            if (worst != null) {
                causes.add(worst);
                State s = worst.severity() > 1.0 ? State.WARNING : State.CAUTION;
                if (s.badness() > state.badness()) {
                    state = s;
                }
            }
        }
        if (!evaluated) {
            return Result.UNKNOWN;
        }
        causes.sort(Comparator.comparingDouble(Cause::severity).reversed());
        return new Result(state, List.copyOf(causes), updatedAt);
    }

    /**
     * 벗어난 정도 ÷ 경고 폭. 0이면 범위 안, 0 초과 1 이하면 주의, 1 초과면 경고.
     * 경계 값이 0이라 경고 폭이 0이면 조금만 벗어나도 경고다.
     */
    static double severity(Target target, double value) {
        double deviation = 0;
        if (target.max() != null && value > target.max()) {
            deviation = value - target.max();
        } else if (target.min() != null && value < target.min()) {
            deviation = target.min() - value;
        }
        if (deviation <= 0) {
            return 0;
        }
        double band;
        if (target.min() != null && target.max() != null) {
            band = (target.max() - target.min()) * RANGE_WARNING_FRACTION;
        } else {
            double bound = target.max() != null ? target.max() : target.min();
            band = Math.abs(bound) * BOUND_WARNING_FRACTION;
        }
        return band <= 0 ? Double.MAX_VALUE : deviation / band;
    }
}
