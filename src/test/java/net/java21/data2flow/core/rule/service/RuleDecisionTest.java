package net.java21.data2flow.core.rule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RuleDecisionTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest(name = "[{index}] {0}/{1} 대상 {2} 항목없음 {3} → {4}/{5}")
    @CsvSource(nullValues = "null", value = {"ACTIVE,null,0,false,ERROR,NO_TARGET", "ACTIVE,null,3,true,ERROR,METRIC_DELETED",
            "ERROR,NO_TARGET,2,false,ACTIVE,null", "ERROR,METRIC_DELETED,2,false,ACTIVE,null", "ERROR,FLOW_ERROR,2,false,ERROR,FLOW_ERROR",
            "ACTIVE,null,5,false,ACTIVE,null"})
    @DisplayName("[RUL-06.03][TC-RUL-110] BR-RUL-20 대상 0대·측정 항목 삭제는 ERROR, 원인이 풀리면 ACTIVE, 플로우 오류는 그대로")
    void health(String status, String reason, int targets, boolean missing, String nextStatus, String nextReason) {
        RuleHealthService.Decision d = RuleHealthService.decide(status, reason, targets, missing);
        assertThat(d.status()).isEqualTo(nextStatus);
        assertThat(d.reason()).isEqualTo(nextReason);
    }

    @Test
    @DisplayName("[RUL-06.02][TC-RUL-104] BR-RUL-22 튜닝 조정안: 지속 5분 → 15분(3배, 최소 15분·최대 6시간), 다른 조건은 그대로, 원본은 바꾸지 않음")
    void tuningProposal() {
        var current = JSON.readTree("{\"kind\":\"threshold\",\"metric\":\"t\",\"op\":\">\",\"value\":28,\"for\":\"PT5M\"}");
        var proposed = RuleTuningService.propose(current);
        assertThat(proposed.path("for").asString()).isEqualTo("PT15M");
        assertThat(current.path("for").asString()).isEqualTo("PT5M");
        assertThat(RuleTuningService.propose(JSON.readTree("{\"kind\":\"threshold\",\"metric\":\"t\",\"op\":\">\",\"value\":1}"))
                .path("for").asString()).isEqualTo("PT15M");
        assertThat(RuleTuningService.propose(JSON.readTree("{\"kind\":\"threshold\",\"metric\":\"t\",\"op\":\">\",\"value\":1,\"for\":\"PT3H\"}"))
                .path("for").asString()).isEqualTo("PT6H");
        var other = JSON.readTree("{\"kind\":\"noData\",\"window\":\"PT30M\"}");
        assertThat(RuleTuningService.propose(other)).isEqualTo(other);
    }

    @Test
    @DisplayName("[RUL-01.01][TC-RUL-001] 내부 플로우 이름은 [규칙] 접두어, 100자로 자른다")
    void flowName() {
        assertThat(RuleService.flowName("고CO2")).isEqualTo("[규칙] 고CO2");
        assertThat(RuleService.flowName("a".repeat(120))).hasSize(100);
    }
}
