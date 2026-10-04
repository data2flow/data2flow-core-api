package net.java21.data2flow.core.rule.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleSimulatorTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant T = Instant.parse("2026-10-01T00:00:00Z");

    static List<RuleSimulator.Point> series(String metric, double... values) {
        List<RuleSimulator.Point> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            out.add(new RuleSimulator.Point(T.plus(Duration.ofMinutes(i)), metric, values[i]));
        }
        return out;
    }

    @Test
    @DisplayName("[RUL-01.11][TC-RUL-027] co2>1000 5분(해제 900): 4분만 넘으면 0건, 5분 넘으면 1건(측정 시각 기준), 950은 유지·880에서 해제")
    void thresholdForAndHysteresis() {
        var cond = JSON.readTree("{\"kind\":\"threshold\",\"metric\":\"co2\",\"op\":\">\",\"value\":1000,\"for\":\"PT5M\",\"clear\":900}");
        assertThat(RuleSimulator.run(cond, Map.of(1L, series("co2", 1050, 1050, 1050, 1050, 980, 1050)), T.plus(Duration.ofHours(1))))
                .isEmpty();
        List<RuleSimulator.Episode> e = RuleSimulator.run(cond, Map.of(1L, series("co2", 1050, 1050, 1050, 1050, 1050, 1050, 950, 880)),
                T.plus(Duration.ofHours(1)));
        assertThat(e).hasSize(1);
        assertThat(e.getFirst().raisedAt()).isEqualTo(T.plus(Duration.ofMinutes(5)));
        assertThat(e.getFirst().clearedAt()).isEqualTo(T.plus(Duration.ofMinutes(7)));
        assertThat(e.getFirst().triggerValue()).isEqualTo(1050);
        assertThat(e.getFirst().durationSec(T)).isEqualTo(120);
    }

    @Test
    @DisplayName("[RUL-01.11][TC-RUL-028] 기준 1200으로 다시 계산하면 더 적게 발생(비교 표시용)")
    void stricterThresholdFewerAlarms() {
        var series = Map.of(1L, series("co2", 1100, 900, 1100, 900, 1300, 900));
        var low = JSON.readTree("{\"kind\":\"threshold\",\"metric\":\"co2\",\"op\":\">\",\"value\":1000}");
        var high = JSON.readTree("{\"kind\":\"threshold\",\"metric\":\"co2\",\"op\":\">\",\"value\":1200}");
        assertThat(RuleSimulator.run(low, series, T.plus(Duration.ofHours(1)))).hasSize(3);
        assertThat(RuleSimulator.run(high, series, T.plus(Duration.ofHours(1)))).hasSize(1);
    }

    @Test
    @DisplayName("[RUL-01.11][TC-RUL-030] BR-RUL-23 변화율·무수신·반복·복합·공간 평균도 같은 판정, 마지막 발생은 해제 없음")
    void otherKinds() {
        Instant end = T.plus(Duration.ofHours(2));
        var rate = JSON.readTree("{\"kind\":\"rateOfChange\",\"metric\":\"t\",\"window\":\"PT10M\",\"delta\":3,\"direction\":\"up\"}");
        assertThat(RuleSimulator.run(rate, Map.of(1L, series("t", 22, 23, 24, 25.5)), end)).hasSize(1);
        assertThat(RuleSimulator.run(rate, Map.of(1L, series("t", 22, 23, 24, 24.5)), end)).isEmpty();
        var down = JSON.readTree("{\"kind\":\"rateOfChange\",\"metric\":\"t\",\"window\":\"PT10M\",\"delta\":3,\"direction\":\"down\"}");
        assertThat(RuleSimulator.run(down, Map.of(1L, series("t", 25, 21, 25)), end)).hasSize(1);
        var noData = JSON.readTree("{\"kind\":\"noData\",\"window\":\"PT30M\"}");
        List<RuleSimulator.Point> gap = List.of(new RuleSimulator.Point(T, "t", 1), new RuleSimulator.Point(T.plus(Duration.ofMinutes(45)), "t", 1));
        List<RuleSimulator.Episode> nd = RuleSimulator.run(noData, Map.of(1L, gap), end);
        assertThat(nd).hasSize(2);
        assertThat(nd.getFirst().raisedAt()).isEqualTo(T.plus(Duration.ofMinutes(30)));
        assertThat(nd.get(1).clearedAt()).isNull();
        var repeat = JSON.readTree("{\"kind\":\"threshold\",\"metric\":\"c\",\"op\":\">\",\"value\":1,\"repeat\":3}");
        assertThat(RuleSimulator.run(repeat, Map.of(1L, series("c", 2, 2, 0, 2, 2, 2)), end)).singleElement()
                .satisfies(ep -> assertThat(ep.raisedAt()).isEqualTo(T.plus(Duration.ofMinutes(5))));
        var group = JSON.readTree("{\"kind\":\"group\",\"op\":\"AND\",\"items\":[{\"kind\":\"threshold\",\"metric\":\"co2\",\"op\":\">\",\"value\":1000},"
                + "{\"kind\":\"threshold\",\"metric\":\"occupancy\",\"op\":\"==\",\"value\":1}]}");
        List<RuleSimulator.Point> mixed = List.of(new RuleSimulator.Point(T, "co2", 1100), new RuleSimulator.Point(T.plusSeconds(1), "occupancy", 0),
                new RuleSimulator.Point(T.plusSeconds(60), "occupancy", 1));
        assertThat(RuleSimulator.run(group, Map.of(1L, mixed), end)).singleElement()
                .satisfies(ep -> assertThat(ep.raisedAt()).isEqualTo(T.plusSeconds(60)));
        var avg = JSON.readTree("{\"kind\":\"threshold\",\"metric\":\"co2\",\"op\":\">\",\"value\":1200,\"aggregate\":\"spaceAvg\"}");
        List<RuleSimulator.Episode> sp = RuleSimulator.run(avg, Map.of(1L, series("co2", 1300), 2L, series("co2", 1250)), end);
        assertThat(sp).singleElement().satisfies(ep -> assertThat(ep.targetId()).isEqualTo(RuleSimulator.SPACE_TARGET));
        var range = JSON.readTree("{\"kind\":\"threshold\",\"metric\":\"h\",\"op\":\"outside\",\"range\":[30,70]}");
        assertThat(RuleSimulator.run(range, Map.of(1L, series("h", 50, 75, 50, 20)), end)).hasSize(2);
        assertThat(RuleSimulator.byTarget(sp, end)).containsKey(RuleSimulator.SPACE_TARGET);
        var anomaly = JSON.readTree("{\"kind\":\"anomaly\",\"minScore\":3}");
        assertThat(RuleSimulator.run(anomaly, Map.of(1L, series("t", 1)), end)).isEmpty();
    }

    @Test
    @DisplayName("[RUL-01.01][TC-RUL-005] 연산자 경계: >1000은 1000 미발생·1000.1 발생, >=1000은 1000 발생, 범위 안 [900,1100] 양 끝 포함")
    void operatorBoundaries() {
        var c = JSON.readTree("{\"value\":1000,\"range\":[900,1100]}");
        assertThat(RuleSimulator.compare(">", 1000, c)).isFalse();
        assertThat(RuleSimulator.compare(">", 1000.1, c)).isTrue();
        assertThat(RuleSimulator.compare(">=", 1000, c)).isTrue();
        assertThat(RuleSimulator.compare("<", 999.9, c)).isTrue();
        assertThat(RuleSimulator.compare("<=", 1000, c)).isTrue();
        assertThat(RuleSimulator.compare("==", 1000, c)).isTrue();
        assertThat(RuleSimulator.compare("!=", 1000.0001, c)).isTrue();
        assertThat(RuleSimulator.compare("inside", 900, c)).isTrue();
        assertThat(RuleSimulator.compare("inside", 1100, c)).isTrue();
        assertThat(RuleSimulator.compare("outside", 1100, c)).isFalse();
        assertThat(RuleSimulator.compare("?", 1, c)).isFalse();
    }
}
