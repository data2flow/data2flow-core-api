package net.java21.data2flow.core.flow.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.flow.FlowDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 버전 비교·위험 판정(API-FLW-04·06)과 기본 템플릿(FLW-01.05) */
class FlowDiffAndTemplatesTest {

    static FlowDefinition parse(JsonNode node) {
        return FlowValidatorTest.VALIDATOR.parse(node).orElseThrow();
    }

    @Test
    @DisplayName("[FLW-01.05][AT-FLW-01.1] 고온이면 냉방: 4노드(수신 → 평균 → 임계값(>27, PT5M, 해제 26) → Thermostat set cool 24), 연결 3개 — TC-FLW-019")
    void hotThenCoolTemplate() {
        JsonNode params = FlowValidatorTest.JSON.readTree("{\"spaceId\":\"31\",\"threshold\":27,\"duration\":\"PT5M\",\"targetTemperature\":24}");
        FlowDefinition def = parse(FlowTemplates.build(FlowTemplates.HOT_THEN_COOL, params, FlowValidatorTest.JSON));
        assertThat(def.nodes()).extracting(n -> n.type())
                .containsExactly("trigger.telemetry", "transform.aggregate", "condition.threshold", "action.control");
        assertThat(def.nodes().get(2).config().get("clear").asDouble()).isEqualTo(26.0);
        assertThat(def.nodes().get(3).config().at("/args/mode").asString()).isEqualTo("cool");
        assertThat(def.wires()).hasSize(3);
        var result = FlowValidatorTest.VALIDATOR.validate(FlowTemplates.build(FlowTemplates.HOT_THEN_COOL, params, FlowValidatorTest.JSON),
                "FLOW", FlowValidatorTest.TARGETS);
        assertThat(result.ok()).isTrue();
        assertThat(FlowTemplates.all(FlowValidatorTest.JSON)).extracting(FlowTemplates.Template::key)
                .containsExactly("hot-then-cool", "co2-then-ventilate");
        assertThatThrownBy(() -> FlowTemplates.build(FlowTemplates.HOT_THEN_COOL, FlowValidatorTest.JSON.readTree("{\"spaceId\":\"x\"}"),
                FlowValidatorTest.JSON)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> FlowTemplates.build(FlowTemplates.HOT_THEN_COOL,
                FlowValidatorTest.JSON.readTree("{\"spaceId\":\"1\",\"durationMin\":0}"), FlowValidatorTest.JSON)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> FlowTemplates.build(FlowTemplates.HOT_THEN_COOL,
                FlowValidatorTest.JSON.readTree("{\"spaceId\":\"1\",\"duration\":\"PT48H\"}"), FlowValidatorTest.JSON)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> FlowTemplates.build(FlowTemplates.HOT_THEN_COOL,
                FlowValidatorTest.JSON.readTree("{\"spaceId\":\"1\",\"threshold\":\"뜨거움\"}"), FlowValidatorTest.JSON)).isInstanceOf(BusinessException.class);
        FlowDefinition co2 = parse(FlowTemplates.build(FlowTemplates.CO2_THEN_VENTILATE,
                FlowValidatorTest.JSON.readTree("{\"spaceId\":31,\"threshold\":\"1100\"}"), FlowValidatorTest.JSON));
        assertThat(co2.nodes().get(2).config().get("value").asDouble()).isEqualTo(1100.0);
    }

    @Test
    @DisplayName("[FLW-01.06] 비교: 추가·삭제·설정 변경(statePolicy KEEP·MIGRATE·RESET)·연결선·실행 모드, 위험(제어 노드 변경·실행 모드 변경)")
    void diffAndRisk() {
        JsonNode params = FlowValidatorTest.JSON.readTree("{\"spaceId\":\"31\"}");
        FlowDefinition a = parse(FlowTemplates.build(FlowTemplates.HOT_THEN_COOL, params, FlowValidatorTest.JSON));
        String raw = FlowValidatorTest.JSON.writeValueAsString(FlowTemplates.build(FlowTemplates.HOT_THEN_COOL, params, FlowValidatorTest.JSON))
                .replace("\"window\":\"PT1M\"", "\"window\":\"PT2M\"").replace("\"metric\":\"temperature\",\"op\"", "\"metric\":\"humidity\",\"op\"")
                .replace("\"concurrency\":\"queued\"", "\"concurrency\":\"parallel\"");
        FlowDefinition b = parse(FlowValidatorTest.JSON.readTree(raw));
        FlowDiff.Diff diff = FlowDiff.diff(a, b, FlowValidatorTest.CATALOG);
        assertThat(diff.changed()).extracting(FlowDiff.Changed::statePolicy).containsExactly("MIGRATE", "RESET");
        assertThat(diff.settings()).contains("mode");
        FlowDiff.Risky risky = FlowDiff.risky(a, b, FlowValidatorTest.CATALOG);
        assertThat(risky.controlNodesChanged()).isFalse();
        assertThat(risky.executionModeChanged()).isTrue();
        FlowDiff.Summary summary = FlowDiff.summary(null, a, FlowValidatorTest.CATALOG);
        assertThat(summary.added()).hasSize(4);
        assertThat(FlowDiff.risky(null, a, FlowValidatorTest.CATALOG).controlNodesChanged()).isTrue();
        FlowDiff.Summary removed = FlowDiff.summary(a, parse(FlowValidatorTest.JSON.readTree(
                "{\"schema\":\"data2flow.flow-definition/v1\",\"nodes\":[]}")), FlowValidatorTest.CATALOG);
        assertThat(removed.removed()).allMatch(FlowDiff.Removed::retainedState).hasSize(4);
    }
}
