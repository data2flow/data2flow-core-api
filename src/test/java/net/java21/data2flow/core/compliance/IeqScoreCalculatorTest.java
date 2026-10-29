package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.compliance.domain.DefaultCriteria;
import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver;
import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver.AppliedRule;
import net.java21.data2flow.core.compliance.domain.EffectiveCriteriaResolver.Target;
import net.java21.data2flow.core.compliance.domain.IeqScoreCalculator;
import net.java21.data2flow.core.compliance.domain.IeqScoreCalculator.Boundary;
import net.java21.data2flow.core.compliance.domain.IeqScoreCalculator.Grade;
import net.java21.data2flow.core.compliance.domain.IeqScoreCalculator.Input;
import net.java21.data2flow.core.compliance.domain.IeqScoreCalculator.Score;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** BR-CMP-08·09 종합 점수 — TC-CMP-028(지수 경계), 029(고정 입력), 030(전부 5·전부 1·10분 무수신), 031(기본 기준) */
class IeqScoreCalculatorTest {

    static final Boundary CO2 = new Boundary(null, new BigDecimal("1000"));
    static final Boundary KR_PM25 = new Boundary(null, new BigDecimal("50"));
    static final Boundary TEMP = new Boundary(new BigDecimal("20"), new BigDecimal("26"));

    @ParameterizedTest(name = "CO2 {0} → {1}")
    @CsvSource({"500,5", "501,4", "1000,4", "1250,3", "1251,2", "1500,2", "1501,1"})
    @DisplayName("[TC-CMP-028] 상한형 지수 경계(CO2 1000)")
    void upperIndex(double value, int index) {
        assertThat(IeqScoreCalculator.index(value, CO2)).isEqualTo(index);
    }

    @ParameterizedTest(name = "온도 {0} → {1}")
    @CsvSource({"23,5", "21.5,5", "25,4", "26,4", "27.2,3", "29,2", "29.1,1", "17,2"})
    @DisplayName("BR-CMP-08 범위형 지수(온도 20~26, 폭 6)")
    void rangeIndex(double value, int index) {
        assertThat(IeqScoreCalculator.index(value, TEMP)).isEqualTo(index);
    }

    @Test
    @DisplayName("[TC-CMP-029] CO2 1200·PM2.5 10(국내법)은 두 번 계산해도 같고 CO2 3·PM2.5 5, 주 감점 co2, 점수 75")
    void fixedInputs() {
        List<Input> inputs = List.of(new Input("co2", 1200.0, 1.0, CO2), new Input("pm2_5", 10.0, 1.0, KR_PM25));
        Score first = IeqScoreCalculator.calculate(inputs);
        Score second = IeqScoreCalculator.calculate(inputs);
        assertThat(first).isEqualTo(second);
        assertThat(first.indices()).containsEntry("co2", 3).containsEntry("pm2_5", 5);
        assertThat(first.topPenaltyMetric()).isEqualTo("co2");
        assertThat(first.topPenaltyValue()).isEqualTo(1200.0);
        assertThat(first.score()).isEqualTo(75);
        assertThat(first.grade()).isEqualTo(Grade.MODERATE);
    }

    @Test
    @DisplayName("[TC-CMP-030] 6개 항목 모두 지수 5면 100, 모두 1이면 0. PM2.5가 10분 무수신이면 Σw에서 빠지고 partial")
    void extremesAndExcluded() {
        List<Input> best = new ArrayList<>();
        List<Input> worst = new ArrayList<>();
        IeqScoreCalculator.DEFAULT_WEIGHTS.forEach((k, w) -> {
            best.add(new Input(k, 0.0, w.doubleValue(), new Boundary(null, new BigDecimal("100"))));
            worst.add(new Input(k, 1000.0, w.doubleValue(), new Boundary(null, new BigDecimal("100"))));
        });
        assertThat(IeqScoreCalculator.calculate(best).score()).isEqualTo(100);
        assertThat(IeqScoreCalculator.calculate(best).topPenaltyMetric()).isNull();
        Score zero = IeqScoreCalculator.calculate(worst);
        assertThat(zero.score()).isZero();
        assertThat(zero.grade()).isEqualTo(Grade.BAD);

        Score partial = IeqScoreCalculator.calculate(List.of(new Input("co2", 1200.0, 1.0, CO2), new Input("pm2_5", null, 1.0, KR_PM25)));
        assertThat(partial.partial()).isTrue();
        assertThat(partial.excludedMetrics()).containsExactly("pm2_5");
        assertThat(partial.indices()).containsEntry("pm2_5", null);
        assertThat(partial.score()).isEqualTo(50);

        Score none = IeqScoreCalculator.calculate(List.of(new Input("co2", null, 1.0, CO2)));
        assertThat(none.score()).isNull();
        assertThat(none.grade()).isNull();
    }

    @Test
    @DisplayName("[TC-CMP-031] 프로필 → 목표 환경 → 시스템 기본 순, 프로필이 없으면 basis=DEFAULT")
    void defaultCriteria() {
        List<String> keys = List.of("co2", "pm2_5", "temperature", "humidity", "tvoc");
        // ① 아무것도 없으면 시스템 기본(tvoc는 기준이 없어 빠진다)
        DefaultCriteria.Resolved system = DefaultCriteria.resolve(List.of(), keys);
        assertThat(system.basis()).isEqualTo(DefaultCriteria.Basis.DEFAULT);
        assertThat(system.boundaries()).containsOnlyKeys("co2", "pm2_5", "temperature", "humidity");
        assertThat(system.boundaries().get("pm2_5").upper()).isEqualByComparingTo("35");
        // ② 목표 환경이 시스템 기본보다 앞선다
        DefaultCriteria.Resolved target = DefaultCriteria.resolve(
                EffectiveCriteriaResolver.resolve(List.of(), List.of(new Target("co2", null, 800.0))), keys);
        assertThat(target.basis()).isEqualTo(DefaultCriteria.Basis.DEFAULT);
        assertThat(target.boundaries().get("co2").upper()).isEqualByComparingTo("800");
        // ③ 프로필 기준이 있으면 PROFILE, 프로필에 없는 항목은 목표 환경·시스템 기본으로 채운다
        DefaultCriteria.Resolved profile = DefaultCriteria.resolve(EffectiveCriteriaResolver.resolve(
                List.of(new AppliedRule(1, "국내법", 1, EffectiveCriteriaResolverTest.upper("pm2_5", "50", true))),
                List.of(new Target("co2", null, 800.0))), keys);
        assertThat(profile.basis()).isEqualTo(DefaultCriteria.Basis.PROFILE);
        assertThat(profile.boundaries().get("pm2_5").upper()).isEqualByComparingTo("50");
        assertThat(profile.boundaries().get("co2").upper()).isEqualByComparingTo("800");
        assertThat(profile.boundaries().get("humidity").lower()).isEqualByComparingTo("40");
    }
}
