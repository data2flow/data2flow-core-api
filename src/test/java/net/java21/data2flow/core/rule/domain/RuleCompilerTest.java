package net.java21.data2flow.core.rule.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.flow.FlowDefinition;
import net.java21.data2flow.contracts.message.MessageSchemas;
import net.java21.data2flow.core.flow.domain.NodeCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 규칙 → 플로우 컴파일 골든 파일(TC-RUL-002). 입력은 {@code fixtures/rules/source/{이름}.json}(규칙 저장 본문), 기대 결과는
 * {@code fixtures/rules/compiled/{이름}.json}. 컴파일러를 바꿔 결과가 달라지면 {@code -Dgolden.update=true}로 다시 만들고 차이를 검토한다.
 * flow-engine의 동등성 시험(TC-RUL-003 {@code RuleFlowEquivalenceTest})이 같은 골든 파일을 쓸 수 있다.
 */
class RuleCompilerTest {

    static final Path SOURCE = Path.of("src/test/resources/fixtures/rules/source");
    static final Path COMPILED = Path.of("src/test/resources/fixtures/rules/compiled");
    static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"threshold-gt", "threshold-gte", "threshold-lt-hysteresis", "threshold-eq", "threshold-ne", "range-inside",
            "range-outside", "rate-of-change", "no-data", "group-and", "group-or-space-avg", "time-off-hours", "anomaly", "repeat-for",
            "space-avg"})
    @DisplayName("[RUL-01.01][TC-RUL-002] 조건 종류별 규칙 → 컴파일 결과가 골든 파일과 같고, contracts flow-definition 스키마와 노드 카탈로그를 통과")
    void golden(String name) throws IOException {
        JsonNode rule = JSON.readTree(Files.readString(SOURCE.resolve(name + ".json"), StandardCharsets.UTF_8));
        ObjectNode compiled = RuleCompiler.compile(input(42, rule), JSON);
        String actual = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(compiled) + "\n";
        Path golden = COMPILED.resolve(name + ".json");
        if (Boolean.getBoolean("golden.update") || !Files.exists(golden)) {
            Files.writeString(golden, actual, StandardCharsets.UTF_8);
        }
        assertThat(actual).isEqualTo(Files.readString(golden, StandardCharsets.UTF_8));
        MessageSchemas.assertValid(MessageSchemas.FLOW_DEFINITION, compiled);
        FlowDefinition definition = JSON.treeToValue(compiled, FlowDefinition.class);
        assertThat(definition.structuralErrors()).isEmpty();
        NodeCatalog catalog = NodeCatalog.load(JSON);
        for (JsonNode node : compiled.path("nodes").values()) {
            assertThat(catalog.find(node.path("type").asString())).as(node.path("type").asString()).isPresent();
        }
    }

    @Test
    @DisplayName("[RUL-01.01][TC-RUL-001] BR-RUL-01 표준 플로우(트리거 → 조건 → 알람 발생·해제): 같은 규칙은 같은 결과(결정적), autoClear 꺼지면 해제 노드 없음")
    void deterministicAndAutoClear() throws IOException {
        JsonNode rule = JSON.readTree(Files.readString(SOURCE.resolve("threshold-gt.json"), StandardCharsets.UTF_8));
        String a = JSON.writeValueAsString(RuleCompiler.compile(input(1, rule), JSON));
        String b = JSON.writeValueAsString(RuleCompiler.compile(input(1, rule), JSON));
        assertThat(a).isEqualTo(b);
        ObjectNode noClear = (ObjectNode) rule.deepCopy();
        noClear.put("autoClear", false);
        ObjectNode compiled = RuleCompiler.compile(input(1, noClear), JSON);
        List<String> types = new ArrayList<>();
        compiled.path("nodes").values().forEach(n -> types.add(n.path("type").asString() + ":" + n.path("config").path("mode").asString("")));
        assertThat(types).containsExactly("trigger.telemetry:", "condition.threshold:", "action.alarm:raise");
        assertThat(compiled.path("nodes").get(2).path("config").path("ruleId").asString()).isEqualTo("1");
    }

    @Test
    @DisplayName("[RUL-01.01][TC-RUL-001] 잘못된 조건은 컴파일하지 않는다(RULE_CONDITION_INVALID)")
    void invalidConditionRejected() {
        ObjectNode rule = JSON.createObjectNode();
        rule.put("name", "x");
        rule.putObject("scope").put("type", "DEVICE").putArray("ids").add("1");
        rule.putObject("condition").put("kind", "unknown");
        rule.put("severity", "MAJOR");
        rule.put("titleTemplate", "t");
        assertThatThrownBy(() -> RuleCompiler.compile(input(1, rule), JSON)).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", RuleErrorCode.RULE_CONDITION_INVALID);
    }

    static RuleCompiler.Input input(long ruleId, JsonNode rule) {
        List<String> ids = new ArrayList<>();
        rule.path("scope").path("ids").values().forEach(v -> ids.add(v.asString()));
        return new RuleCompiler.Input(ruleId, rule.path("name").asString(), rule.path("scope").path("type").asString(), ids,
                rule.path("scope").path("includeChildren").asBoolean(true), rule.get("condition"),
                rule.hasNonNull("timeCondition") ? rule.get("timeCondition") : null, rule.path("severity").asString(),
                rule.path("titleTemplate").asString(), !rule.has("autoClear") || rule.get("autoClear").asBoolean());
    }
}
