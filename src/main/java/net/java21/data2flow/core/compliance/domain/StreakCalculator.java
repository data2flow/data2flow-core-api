package net.java21.data2flow.core.compliance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 연속 달성(CMP-02.01, BR-CMP-11, TC-CMP-040). 하루는 프로필의 모든 판정 항목이 일 충족률 기준 이상이고 품질 경고가 없을 때 달성이다.
 * 달성하면 하루씩 늘고, 못 하면 0으로 돌아가며 깨진 날과 원인 항목을 남긴다. 원인 항목은 이탈 분이 가장 큰 항목이고, 이탈이 없으면(결측으로
 * 깨짐) 결측 분이 가장 큰 항목이다. 이어지던 연속이 있을 때만 "깨짐"으로 센다(EVT-CMP-05).
 */
public final class StreakCalculator {

    private StreakCalculator() {
    }

    /** 항목 하루 결과. {@code ratio}는 %이고 판정할 분이 없으면 null */
    public record MetricDay(BigDecimal ratio, int exceededMinutes, int missingMinutes, boolean qualityWarning) {
    }

    public record DayOutcome(LocalDate day, Map<String, MetricDay> metrics) {
    }

    public record State(int currentDays, LocalDate startedOn, LocalDate brokenOn, String brokenMetric) {

        public static final State EMPTY = new State(0, null, null, null);
    }

    public record Broken(LocalDate day, String metric, int previousDays) {
    }

    public record Step(State state, List<Broken> broken) {
    }

    public static Step advance(State start, List<DayOutcome> days, BigDecimal requiredRatio) {
        State s = start == null ? State.EMPTY : start;
        List<Broken> broken = new ArrayList<>();
        for (DayOutcome d : days) {
            if (achieved(d, requiredRatio)) {
                s = new State(s.currentDays() + 1, s.startedOn() == null ? d.day() : s.startedOn(), s.brokenOn(), s.brokenMetric());
            } else if (s.currentDays() > 0) {
                String metric = cause(d);
                broken.add(new Broken(d.day(), metric, s.currentDays()));
                s = new State(0, null, d.day(), metric);
            }
        }
        return new Step(s, List.copyOf(broken));
    }

    static boolean achieved(DayOutcome d, BigDecimal requiredRatio) {
        if (d.metrics().isEmpty()) {
            return false;
        }
        for (MetricDay m : d.metrics().values()) {
            if (m.qualityWarning() || m.ratio() == null || m.ratio().compareTo(requiredRatio) < 0) {
                return false;
            }
        }
        return true;
    }

    static String cause(DayOutcome d) {
        String byExceeded = null;
        int exceeded = 0;
        String byMissing = null;
        int missing = -1;
        for (Map.Entry<String, MetricDay> e : d.metrics().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (e.getValue().exceededMinutes() > exceeded) {
                exceeded = e.getValue().exceededMinutes();
                byExceeded = e.getKey();
            }
            if (e.getValue().missingMinutes() > missing) {
                missing = e.getValue().missingMinutes();
                byMissing = e.getKey();
            }
        }
        return byExceeded != null ? byExceeded : byMissing;
    }
}
