package net.java21.data2flow.core.compliance.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 측정 가능성(CMP-01.04, BR-CMP-06, TC-CMP-025). 적용 대상의 말단 공간(하위 포함이면 하위의 말단들)마다 그 항목을 보고하는 ACTIVE 실제 기기가
 * 있는지 본다. 모든 말단에 있으면 MEASURABLE, 일부에만 있으면 PARTIAL(없는 공간 목록), 하나도 없으면 NOT_MEASURABLE(NO_SENSOR)이다.
 * 가상 기기와 ACTIVE가 아닌 기기는 호출하는 쪽에서 미리 뺀다. 상위 공간에 놓인 기기는 그 아래 말단 공간을 모두 덮는다.
 */
public final class MeasurabilityEvaluator {

    public static final String NO_SENSOR = "NO_SENSOR";

    private MeasurabilityEvaluator() {
    }

    public enum Status { MEASURABLE, PARTIAL, NOT_MEASURABLE }

    public record Named(long id, String name) {
    }

    /** 기기 하나: 덮는 말단 공간 ID와 보고하는 측정 항목 */
    public record DeviceCoverage(long id, String name, Set<Long> leafSpaceIds, Set<String> metricKeys) {
    }

    public record Result(String metricKey, Status status, String reason, List<Named> spacesWithout, List<Named> devices) {
    }

    public static List<Result> evaluate(List<String> metricKeys, List<Named> leaves, List<DeviceCoverage> devices) {
        List<Result> out = new ArrayList<>();
        for (String key : new LinkedHashSet<>(metricKeys)) {
            Set<Long> covered = new LinkedHashSet<>();
            List<Named> reporting = new ArrayList<>();
            for (DeviceCoverage d : devices) {
                if (d.metricKeys().contains(key)) {
                    reporting.add(new Named(d.id(), d.name()));
                    covered.addAll(d.leafSpaceIds());
                }
            }
            List<Named> without = leaves.stream().filter(s -> !covered.contains(s.id())).toList();
            Status status = reporting.isEmpty() || without.size() == leaves.size() ? Status.NOT_MEASURABLE
                    : without.isEmpty() ? Status.MEASURABLE : Status.PARTIAL;
            out.add(new Result(key, status, status == Status.NOT_MEASURABLE ? NO_SENSOR : null,
                    status == Status.MEASURABLE ? List.of() : without, reporting));
        }
        return out;
    }
}
