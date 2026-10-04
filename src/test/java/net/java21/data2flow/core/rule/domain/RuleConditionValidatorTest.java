package net.java21.data2flow.core.rule.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleConditionValidatorTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest(name = "[{index}] {0} {1} 해제 {2} → {3}")
    @CsvSource({">,1000,900,true", ">,1000,1000,true", ">,1000,1100,false", ">=,1000,950,true", "<,18,19,true", "<,18,17,false",
            "<=,18,18,true", "==,1,0,false"})
    @DisplayName("[RUL-01.03][TC-RUL-011] BR-RUL-04 해제 기준은 발생 기준보다 같은 방향으로 덜 엄격(같은 값 허용), 같음 연산자는 해제 기준 없음")
    void hysteresis(String op, double value, double clear, boolean ok) {
        assertThat(RuleCondition.hysteresisOk(op, value, clear)).isEqualTo(ok);
    }

    @Test
    @DisplayName("[RUL-01.03][TC-RUL-013] 해제 기준이 더 엄격하면 RULE_CONDITION_INVALID(clear), 조건 11개·중첩 3단계는 한도 위반")
    void invalidConditions() {
        assertThatThrownBy(() -> RuleCondition.validate(JSON.readTree(
                "{\"kind\":\"threshold\",\"metric\":\"co2\",\"op\":\">\",\"value\":1000,\"clear\":1100}")))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", RuleErrorCode.RULE_CONDITION_INVALID)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrors()).anyMatch(e -> e.field().equals("condition.clear")));
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            items.append(i == 0 ? "" : ",").append("{\"kind\":\"threshold\",\"metric\":\"m").append(i).append("\",\"op\":\">\",\"value\":1}");
        }
        assertThatThrownBy(() -> RuleCondition.validate(JSON.readTree("{\"kind\":\"group\",\"op\":\"AND\",\"items\":[" + items + "]}")))
                .isInstanceOf(BusinessException.class);
        String deep = "{\"kind\":\"group\",\"op\":\"AND\",\"items\":[{\"kind\":\"group\",\"op\":\"OR\",\"items\":[{\"kind\":\"group\",\"op\":\"AND\","
                + "\"items\":[{\"kind\":\"threshold\",\"metric\":\"a\",\"op\":\">\",\"value\":1}]}]}]}";
        assertThatThrownBy(() -> RuleCondition.validate(JSON.readTree(deep))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> RuleCondition.validate(null)).isInstanceOf(BusinessException.class);
        for (String bad : new String[]{"{\"kind\":\"threshold\",\"op\":\">\",\"value\":1}", "{\"kind\":\"threshold\",\"metric\":\"a\",\"op\":\"~\"}",
                "{\"kind\":\"threshold\",\"metric\":\"a\",\"op\":\">\"}", "{\"kind\":\"threshold\",\"metric\":\"a\",\"op\":\"inside\",\"range\":[5,1]}",
                "{\"kind\":\"threshold\",\"metric\":\"a\",\"op\":\">\",\"value\":1,\"for\":\"forever\"}",
                "{\"kind\":\"threshold\",\"metric\":\"a\",\"op\":\">\",\"value\":1,\"repeat\":0}",
                "{\"kind\":\"rateOfChange\",\"metric\":\"a\",\"window\":\"PT10M\",\"delta\":-1}",
                "{\"kind\":\"rateOfChange\",\"metric\":\"a\",\"window\":\"PT10M\",\"delta\":1,\"direction\":\"sideways\"}",
                "{\"kind\":\"noData\",\"window\":\"PT10S\"}", "{\"kind\":\"anomaly\",\"minScore\":0}",
                "{\"kind\":\"threshold\",\"metric\":\"a\",\"op\":\">\",\"value\":1,\"aggregate\":\"median\"}",
                "{\"kind\":\"group\",\"op\":\"XOR\",\"items\":[]}", "{\"kind\":\"threshold\",\"metric\":\"1a\",\"op\":\">\",\"value\":1}"}) {
            assertThatThrownBy(() -> RuleCondition.validate(JSON.readTree(bad))).as(bad).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("[RUL-01.06][TC-RUL-017] 복합 조건 요약·측정 항목 모음, 공간 집계 표시, 낮을수록 나쁜 조건 표시")
    void summary() {
        RuleCondition.Summary s = RuleCondition.validate(JSON.readTree("{\"kind\":\"group\",\"op\":\"and\",\"items\":["
                + "{\"kind\":\"threshold\",\"metric\":\"co2\",\"op\":\">\",\"value\":1000,\"for\":\"5m\"},"
                + "{\"kind\":\"threshold\",\"metric\":\"occupancy\",\"op\":\"==\",\"value\":1,\"aggregate\":\"spaceAvg\"}]}"));
        assertThat(s.metrics()).containsExactly("co2", "occupancy");
        assertThat(s.leaves()).isEqualTo(2);
        assertThat(s.spaceTarget()).isTrue();
        assertThat(RuleCondition.validate(JSON.readTree("{\"kind\":\"threshold\",\"metric\":\"t\",\"op\":\"<\",\"value\":18}")).lowerIsWorse())
                .isTrue();
        assertThat(RuleCondition.summary(JSON.readTree("{\"kind\":\"group\",\"op\":\"OR\",\"items\":["
                + "{\"kind\":\"threshold\",\"metric\":\"co2\",\"op\":\">\",\"value\":1000.5,\"for\":\"PT2H\"},"
                + "{\"kind\":\"group\",\"op\":\"AND\",\"items\":[{\"kind\":\"noData\",\"window\":\"P1D\"},{\"kind\":\"anomaly\",\"minScore\":3}]},"
                + "{\"kind\":\"threshold\",\"metric\":\"h\",\"op\":\"inside\",\"range\":[1,2]},"
                + "{\"kind\":\"rateOfChange\",\"metric\":\"t\",\"window\":\"PT90S\",\"delta\":3}]}")))
                .isEqualTo("co2 > 1000.5 (2h) OR (noData 1d AND anomaly ≥ 3) OR h inside [1, 2] OR t Δ3 / 90s");
        assertThat(RuleCondition.summary(null)).isEmpty();
        assertThat(RuleCondition.parseDuration("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(RuleCondition.parseDuration("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(RuleCondition.parseDuration("1d")).isEqualTo(Duration.ofDays(1));
        assertThat(RuleCondition.parseDuration("")).isNull();
    }

    @Test
    @DisplayName("[RUL-06.04][TC-RUL-114] BR-RUL-21 한도: 규칙 1,000개, 대상 5,000대, 메모 2,000자, 일괄 200건")
    void limits() {
        assertThat(RuleLimits.canCreate(999)).isTrue();
        assertThat(RuleLimits.canCreate(1000)).isFalse();
        assertThat(RuleLimits.targetsWithin(5000)).isTrue();
        assertThat(RuleLimits.targetsWithin(5001)).isFalse();
        assertThat(RuleLimits.noteWithin("a".repeat(2000))).isTrue();
        assertThat(RuleLimits.noteWithin("a".repeat(2001))).isFalse();
        assertThat(RuleLimits.noteWithin(" ")).isFalse();
        assertThat(RuleLimits.bulkWithin(200)).isTrue();
        assertThat(RuleLimits.bulkWithin(201)).isFalse();
    }
}
