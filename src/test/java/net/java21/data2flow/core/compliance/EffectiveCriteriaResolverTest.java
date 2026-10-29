package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver;
import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver.AppliedRule;
import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver.Criterion;
import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver.Source;
import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver.Target;
import net.java21.data2flow.core.compliance.domain.ProfileModels.Band;
import net.java21.data2flow.core.compliance.domain.ProfileModels.MetricRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BR-CMP-04·05 엄격 기준 — TC-CMP-017. ①의 "RESET 고성능 &lt;600"은 적용에 등급 선택이 없어 기준 등급 값으로 둔 조직 프로필로 확인한다
 * (등급별 판정은 점수·현황 단계에서 다룬다).
 */
class EffectiveCriteriaResolverTest {

    static MetricRule upper(String key, String value, boolean inclusive) {
        BigDecimal v = new BigDecimal(value);
        return new MetricRule(key, null, "HOUR_1", null, v, inclusive, "ppm", "LIMIT", null, List.of(new Band("기준", null, v, true)));
    }

    static MetricRule range(String key, String lower, String upper) {
        BigDecimal l = lower == null ? null : new BigDecimal(lower);
        BigDecimal u = upper == null ? null : new BigDecimal(upper);
        return new MetricRule(key, null, "HOUR_1", l, u, true, "%", "LIMIT", null, List.of(new Band("기준", l, u, true)));
    }

    static AppliedRule kr(MetricRule r) {
        return new AppliedRule(1, "국내법", 1, r);
    }

    static AppliedRule reset(MetricRule r) {
        return new AppliedRule(2, "RESET 고성능", 1, r);
    }

    static Criterion only(List<Criterion> out, String key) {
        return out.stream().filter(c -> c.metricKey().equals(key)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("[TC-CMP-017 ①⑤] 국내법 CO2 ≤1000 + <600 → 유효 상한 <600, 출처 둘·가장 엄격한 출처는 RESET 고성능")
    void stricterUpper() {
        Criterion c = only(EffectiveCriteriaResolver.resolve(List.of(kr(upper("co2", "1000", true)), reset(upper("co2", "600", false))),
                List.of()), "co2");
        assertThat(c.upper().value()).isEqualByComparingTo("600");
        assertThat(c.upper().inclusive()).isFalse();
        assertThat(c.sources()).extracting(Source::profileName).containsExactly("국내법", "RESET 고성능");
        assertThat(c.strictestSource().profileName()).isEqualTo("RESET 고성능");
        assertThat(c.strictestSource().band()).isEqualTo("기준");
    }

    @Test
    @DisplayName("[TC-CMP-017 ②] 같은 값에 연산자만 다르면(≤1000과 <1000) <1000, 순서와 관계없다")
    void exclusiveWinsTie() {
        for (List<AppliedRule> rules : List.of(List.of(kr(upper("co2", "1000", true)), reset(upper("co2", "1000", false))),
                List.of(reset(upper("co2", "1000", false)), kr(upper("co2", "1000", true))))) {
            Criterion c = only(EffectiveCriteriaResolver.resolve(rules, List.of()), "co2");
            assertThat(c.upper().inclusive()).isFalse();
            assertThat(c.strictestSource().profileId()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("[TC-CMP-017 ③④] 온도 하한 18과 20 → 20, 습도 30~70과 40~60 → 40~60")
    void lowerAndRange() {
        List<Criterion> out = EffectiveCriteriaResolver.resolve(List.of(kr(range("temperature", "18", null)), reset(range("temperature", "20", null)),
                kr(range("humidity", "30", "70")), reset(range("humidity", "40", "60"))), List.of());
        assertThat(only(out, "temperature").lower().value()).isEqualByComparingTo("20");
        assertThat(only(out, "temperature").upper()).isNull();
        assertThat(only(out, "humidity").lower().value()).isEqualByComparingTo("40");
        assertThat(only(out, "humidity").upper().value()).isEqualByComparingTo("60");
    }

    @Test
    @DisplayName("[BR-CMP-05] 프로필 기준은 목표 환경보다 우선(overridesTarget), 프로필에 없는 항목은 목표 환경을 그대로 쓴다")
    void targets() {
        List<Criterion> out = EffectiveCriteriaResolver.resolve(List.of(kr(upper("co2", "1000", true))),
                List.of(new Target("co2", null, 800.0), new Target("temperature", 20.0, 26.0)));
        Criterion co2 = only(out, "co2");
        assertThat(co2.upper().value()).isEqualByComparingTo("1000");
        assertThat(co2.overridesTarget()).isTrue();
        Criterion temp = only(out, "temperature");
        assertThat(temp.fromTarget()).isTrue();
        assertThat(temp.lower().value()).isEqualByComparingTo("20");
        assertThat(temp.strictestSource()).isNull();
    }

    @Test
    @DisplayName("[BR-CMP-04] 권고기준과 연속 측정만 요구하는 항목은 판정 기준에서 뺀다")
    void limitOnly() {
        MetricRule recommended = new MetricRule("tvoc", null, "HOUR_1", null, new BigDecimal("500"), true, "μg/m³", "RECOMMENDED", null, List.of());
        MetricRule continuous = new MetricRule("temperature", null, "INSTANT", null, null, true, "℃", "CONTINUOUS_MONITORING_ONLY", null, List.of());
        assertThat(EffectiveCriteriaResolver.resolve(List.of(kr(recommended), kr(continuous)), List.of())).isEmpty();
    }
}
