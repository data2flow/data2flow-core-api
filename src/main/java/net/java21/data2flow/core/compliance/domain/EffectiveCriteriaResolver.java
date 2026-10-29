package net.java21.data2flow.core.compliance.domain;

import net.java21.data2flow.core.compliance.domain.ProfileModels.Band;
import net.java21.data2flow.core.compliance.domain.ProfileModels.MetricRule;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공간의 유효 기준(CMP-01.03, BR-CMP-04·05, TC-CMP-017·019). 한 공간에 여러 프로필이 걸리면 항목마다 더 엄격한 값을 쓴다: 상한은 작은 값,
 * 하한은 큰 값, 값이 같으면 경계를 포함하지 않는 쪽("&lt;1000"이 "≤1000"보다 엄격). 기준을 준 프로필은 모두 출처로 남기고, 실제로 쓰인 경계를
 * 준 프로필을 {@code strictestSource}로 표시한다. 프로필이 정한 항목은 공간 목표 환경(DEV-01.04)보다 우선하고, 프로필에 없는 항목은 목표 환경을
 * 그대로 쓴다. 연속 측정만 요구하는 항목과 권고기준은 판정 기준이 아니라서 빼고 유지기준(LIMIT)만 쓴다.
 */
public final class EffectiveCriteriaResolver {

    private EffectiveCriteriaResolver() {
    }

    /** 공간에 걸린 프로필 규칙 하나(시설 유형으로 이미 거른 것) */
    public record AppliedRule(long profileId, String profileName, int versionNo, MetricRule rule) {
    }

    /** 공간 목표 환경 한 항목 */
    public record Target(String metricKey, Double min, Double max) {
    }

    public record Source(long profileId, String profileName, int versionNo, String band) {
    }

    /** 경계 하나: 값과 포함 여부, 그 경계를 준 출처 */
    public record Bound(BigDecimal value, boolean inclusive, Source source) {
    }

    /**
     * 항목의 유효 기준. {@code lower}·{@code upper}는 null이면 그쪽 제한이 없다. {@code sources}가 비면 목표 환경에서 온 기준이다
     */
    public record Criterion(String metricKey, Bound lower, Bound upper, String unit, String evalWindow, List<Source> sources,
                            Source strictestSource, boolean overridesTarget) {

        public boolean fromTarget() {
            return sources.isEmpty();
        }
    }

    public static List<Criterion> resolve(List<AppliedRule> rules, List<Target> targets) {
        Map<String, List<AppliedRule>> byMetric = new LinkedHashMap<>();
        for (AppliedRule r : rules) {
            if ("LIMIT".equals(r.rule().requirement()) && (r.rule().lower() != null || r.rule().upper() != null)) {
                byMetric.computeIfAbsent(r.rule().metricKey(), k -> new ArrayList<>()).add(r);
            }
        }
        Map<String, Target> targetByMetric = new LinkedHashMap<>();
        for (Target t : targets) {
            targetByMetric.put(t.metricKey(), t);
        }
        List<Criterion> out = new ArrayList<>();
        for (Map.Entry<String, List<AppliedRule>> e : byMetric.entrySet()) {
            Bound lower = null;
            Bound upper = null;
            String unit = null;
            String window = null;
            List<Source> sources = new ArrayList<>();
            for (AppliedRule r : e.getValue()) {
                MetricRule rule = r.rule();
                Source source = new Source(r.profileId(), r.profileName(), r.versionNo(), baseBand(rule));
                sources.add(source);
                if (rule.upper() != null) {
                    Bound b = new Bound(rule.upper(), rule.inclusive(), source);
                    if (upper == null || stricterUpper(b, upper)) {
                        upper = b;
                    }
                }
                if (rule.lower() != null) {
                    Bound b = new Bound(rule.lower(), rule.inclusive(), source);
                    if (lower == null || stricterLower(b, lower)) {
                        lower = b;
                    }
                }
                if (unit == null) {
                    unit = rule.unit();
                    window = rule.evalWindow();
                }
            }
            Source strictest = upper != null ? upper.source() : lower.source();
            String strictestWindow = e.getValue().stream().filter(r -> r.profileId() == strictest.profileId()).findFirst()
                    .map(r -> r.rule().evalWindow()).orElse(window);
            out.add(new Criterion(e.getKey(), lower, upper, unit, strictestWindow, List.copyOf(sources), strictest,
                    targetByMetric.containsKey(e.getKey())));
        }
        for (Target t : targets) {
            if (!byMetric.containsKey(t.metricKey()) && (t.min() != null || t.max() != null)) {
                out.add(new Criterion(t.metricKey(), t.min() == null ? null : new Bound(BigDecimal.valueOf(t.min()), true, null),
                        t.max() == null ? null : new Bound(BigDecimal.valueOf(t.max()), true, null), null, null, List.of(), null, false));
            }
        }
        return out;
    }

    /** 상한 a가 b보다 엄격한가: 작은 값, 같으면 경계 미포함 */
    static boolean stricterUpper(Bound a, Bound b) {
        int c = a.value().compareTo(b.value());
        return c < 0 || (c == 0 && !a.inclusive() && b.inclusive());
    }

    /** 하한 a가 b보다 엄격한가: 큰 값, 같으면 경계 미포함 */
    static boolean stricterLower(Bound a, Bound b) {
        int c = a.value().compareTo(b.value());
        return c > 0 || (c == 0 && !a.inclusive() && b.inclusive());
    }

    static String baseBand(MetricRule rule) {
        return rule.bands().stream().filter(Band::base).map(Band::name).findFirst().orElse(null);
    }
}
