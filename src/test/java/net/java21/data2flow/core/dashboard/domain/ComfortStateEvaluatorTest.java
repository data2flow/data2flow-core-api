package net.java21.data2flow.core.dashboard.domain;

import net.java21.data2flow.core.dashboard.domain.Comfort.Reading;
import net.java21.data2flow.core.dashboard.domain.Comfort.Result;
import net.java21.data2flow.core.dashboard.domain.Comfort.State;
import net.java21.data2flow.core.dashboard.domain.Comfort.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DSH-01.02 공간 쾌적도 판정(spec/detail/DSH/domain-model.md 계산 모델) */
class ComfortStateEvaluatorTest {

    private static final Instant T = Instant.parse("2026-10-03T00:00:00Z");
    private static final List<Target> CO2 = List.of(new Target("co2", null, 1000.0));

    private static Reading co2(double v) {
        return new Reading(17, "co2", v, "ppm", 0, T);
    }

    @ParameterizedTest(name = "CO2 {0}ppm → {1}")
    @CsvSource({"1150, WARNING", "1000, NORMAL", "999, NORMAL", "1050, CAUTION", "1100, CAUTION", "1100.5, WARNING"})
    @DisplayName("[DSH-01.02][AT-DSH-01.2] 목표 CO2 ≤ 1,000ppm 경계값: 초과 10% 이내 주의, 넘으면 경고 — TC-DSH-006")
    void co2Boundaries(double value, State expected) {
        Result result = Comfort.evaluate(CO2, List.of(co2(value)));
        assertThat(result.state()).isEqualTo(expected);
        if (expected != State.NORMAL) {
            assertThat(result.causes()).singleElement().satisfies(c -> {
                assertThat(c.metricKey()).isEqualTo("co2");
                assertThat(c.value()).isEqualTo(value);
                assertThat(c.unit()).isEqualTo("ppm");
            });
        } else {
            assertThat(result.causes()).isEmpty();
        }
        assertThat(result.updatedAt()).isEqualTo(T);
    }

    @Test
    @DisplayName("[DSH-01.02][AT-DSH-01.2] 실습실 CO2 1,150ppm, 목표 1,000ppm 이하 → 경고, 원인 CO2 1,150ppm — TC-DSH-006")
    void labWarning() {
        Result result = Comfort.evaluate(CO2, List.of(co2(1150), new Reading(18, "co2", 900, "ppm", 0, T.plusSeconds(5))));
        assertThat(result.state()).isEqualTo(State.WARNING);
        assertThat(result.causes()).extracting(Comfort.Cause::value).containsExactly(1150.0);
        assertThat(result.updatedAt()).isEqualTo(T.plusSeconds(5));
    }

    @ParameterizedTest(name = "온도 {0}℃ → {1}")
    @CsvSource({"23, NORMAL", "26, NORMAL", "27, CAUTION", "27.2, CAUTION", "27.3, WARNING", "19, CAUTION", "18.7, WARNING"})
    @DisplayName("[DSH-01.02] 양쪽 경계(20~26℃): 범위 폭의 20%(1.2℃)를 넘으면 경고 — TC-DSH-006")
    void twoSidedRange(double value, State expected) {
        Result result = Comfort.evaluate(List.of(new Target("temperature", 20.0, 26.0)),
                List.of(new Reading(1, "temperature", value, "°C", 0, T)));
        assertThat(result.state()).isEqualTo(expected);
    }

    @Test
    @DisplayName("[DSH-01.02] 목표 환경 없는 공간은 기본 기준(CO2 ≤ 1,000ppm, 키 대소문자 무관) — TC-DSH-006")
    void defaultBaseline() {
        assertThat(Comfort.evaluate(List.of(), List.of(new Reading(1, "CO2", 1200, null, 0, T))).state()).isEqualTo(State.WARNING);
        assertThat(Comfort.evaluate(null, List.of(new Reading(1, "co2", 600, null, 0, T))).state()).isEqualTo(State.NORMAL);
    }

    @Test
    @DisplayName("[DSH-01.02] 목표 항목의 최신값이 없으면 UNKNOWN, 원인은 가장 많이 벗어난 항목부터")
    void unknownAndCauseOrder() {
        assertThat(Comfort.evaluate(CO2, List.of(new Reading(1, "temperature", 30, null, 0, T)))).isEqualTo(Result.UNKNOWN);
        Result result = Comfort.evaluate(List.of(new Target("temperature", 20.0, 26.0), new Target("co2", null, 1000.0)),
                List.of(new Reading(1, "temperature", 26.5, "°C", 0, T), co2(1300)));
        assertThat(result.state()).isEqualTo(State.WARNING);
        assertThat(result.causes()).extracting(Comfort.Cause::metricKey).containsExactly("co2", "temperature");
    }

    @Test
    @DisplayName("[DSH-01.02] 경계 값이 0인 한쪽 목표는 조금만 벗어나도 경고, 하한 미달도 판정")
    void zeroBoundAndMin() {
        assertThat(Comfort.evaluate(List.of(new Target("leak", null, 0.0)), List.of(new Reading(1, "leak", 1, null, 0, T))).state())
                .isEqualTo(State.WARNING);
        assertThat(Comfort.evaluate(List.of(new Target("humidity", 40.0, null)), List.of(new Reading(1, "humidity", 37, null, 0, T))).state())
                .isEqualTo(State.CAUTION);
        assertThat(State.WARNING.badness()).isGreaterThan(State.CAUTION.badness());
        assertThat(State.CAUTION.badness()).isGreaterThan(State.UNKNOWN.badness());
        assertThat(State.UNKNOWN.badness()).isGreaterThan(State.NORMAL.badness());
    }

    @Test
    @DisplayName("[DSH-01.02][BR-DEV-04] 목표 환경은 가장 가까운 상위 공간의 묶음을 물려받는다")
    void inheritance() {
        SpaceTree tree = new SpaceTree(List.of(
                new SpaceTree.Node(1, null, "캠퍼스", "SITE", "/1/", 0),
                new SpaceTree.Node(2, 1L, "본관", "BUILDING", "/1/2/", 0),
                new SpaceTree.Node(3, 2L, "실습실", "ROOM", "/1/2/3/", 1),
                new SpaceTree.Node(4, 2L, "강의실", "ROOM", "/1/2/4/", 0)),
                Map.of(1L, CO2, 4L, List.of(new Target("temperature", 20.0, 26.0))));
        assertThat(tree.effectiveTargets(3)).isEqualTo(new SpaceTree.EffectiveTargets(CO2, 1L));
        assertThat(tree.effectiveTargets(4).fromSpaceId()).isEqualTo(4L);
        assertThat(tree.effectiveTargets(99).targets()).isEmpty();
        assertThat(tree.subtree(2)).containsExactlyInAnyOrder(2L, 3L, 4L);
        assertThat(tree.subtree(99)).isEmpty();
        assertThat(tree.pathOf(3)).extracting(SpaceTree.Node::name).containsExactly("캠퍼스", "본관", "실습실");
        assertThat(tree.childrenOf(2)).extracting(SpaceTree.Node::name).containsExactly("강의실", "실습실");
        assertThat(tree.contains(3)).isTrue();
        assertThat(tree.all()).hasSize(4);
    }
}
