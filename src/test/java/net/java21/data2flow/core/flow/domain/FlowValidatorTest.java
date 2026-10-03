package net.java21.data2flow.core.flow.domain;

import net.java21.data2flow.contracts.capability.AttributeConstraint;
import net.java21.data2flow.contracts.capability.CapabilityCatalog;
import net.java21.data2flow.core.flow.domain.FlowValidator.Issue;
import net.java21.data2flow.core.flow.domain.FlowValidator.Targets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 적용 전 검증(FLW-06.06, BR-FLW-02·03·04·05·37) */
class FlowValidatorTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final NodeCatalog CATALOG = NodeCatalog.load(JSON);
    static final FlowValidator VALIDATOR = new FlowValidator(CATALOG, JSON);

    /** 공간 31은 있음(제어 기기 1대·측정 0대), 32는 범위 밖, 기기 7은 있음, 8은 범위 밖 */
    static final Targets TARGETS = new Targets() {
        @Override
        public Status space(long spaceId) {
            return spaceId == 31 ? Status.OK : spaceId == 32 ? Status.OUT_OF_SCOPE : Status.MISSING;
        }

        @Override
        public Status device(long deviceId) {
            return deviceId == 7 ? Status.OK : deviceId == 8 ? Status.OUT_OF_SCOPE : Status.MISSING;
        }

        @Override
        public long related(long spaceId, String relation, boolean includeChildren, String capability) {
            return "controls".equalsIgnoreCase(relation) ? 1 : 0;
        }

        @Override
        public CapabilityCatalog capabilities() {
            return CapabilityCatalog.standard();
        }

        @Override
        public Map<String, AttributeConstraint> absoluteLimits(String capability) {
            return "Thermostat".equals(capability) ? Map.of("targetTemperature", AttributeConstraint.range(18, 28)) : Map.of();
        }
    };

    static String def(String nodes, String wires) {
        return "{\"schema\":\"data2flow.flow-definition/v1\",\"nodes\":[" + nodes + "],\"wires\":[" + wires + "]}";
    }

    static final String TRIGGER = "{\"id\":\"n-trg\",\"type\":\"trigger.telemetry\",\"config\":{\"target\":{\"spaceId\":31},\"metrics\":[\"t\"]}}";
    static final String DEBUG = "{\"id\":\"n-dbg\",\"type\":\"debug.log\",\"config\":{}}";

    static List<String> codes(List<Issue> issues) {
        return issues.stream().map(Issue::code).toList();
    }

    @ParameterizedTest(name = "kind={0}, 트리거 {1} → NO_TRIGGER {2}")
    @CsvSource({"FLOW,true,false", "FLOW,false,true", "CATCH,false,false", "RULE,false,true"})
    @DisplayName("[FLW-01.01][BR-FLW-02] 트리거 노드가 1개 이상 있어야 적용할 수 있다(CATCH는 제외) — TC-FLW-004")
    void triggerRequired(String kind, boolean withTrigger, boolean expectNoTrigger) {
        String d = withTrigger ? def(TRIGGER + "," + DEBUG, "{\"from\":\"n-trg\",\"to\":\"n-dbg\"}") : def(DEBUG, "");
        var result = VALIDATOR.validate(JSON.readTree(d), kind, TARGETS);
        assertThat(codes(result.errors()).contains("NO_TRIGGER")).isEqualTo(expectNoTrigger);
    }

    @Test
    @DisplayName("[FLW-01.02][BR-FLW-05] 노드 설정이 스키마에 맞지 않으면 INVALID_CONFIG(필드 경로), 모르는 노드 종류도 — TC-FLW-011")
    void invalidConfig() {
        String d = def(TRIGGER + ",{\"id\":\"n-thr\",\"type\":\"condition.threshold\",\"config\":{\"metric\":\"t\",\"op\":\"~\",\"for\":\"5분\"}},"
                + "{\"id\":\"n-x\",\"type\":\"teleport.now\"}", "{\"from\":\"n-trg\",\"to\":\"n-thr\"},{\"from\":\"n-thr\",\"port\":\"true\",\"to\":\"n-x\"}");
        var result = VALIDATOR.validate(JSON.readTree(d), "FLOW", TARGETS);
        assertThat(result.errors()).filteredOn(i -> "n-thr".equals(i.nodeId())).extracting(Issue::field)
                .contains("nodes[n-thr].config.op", "nodes[n-thr].config.for");
        assertThat(result.errors()).filteredOn(i -> "n-x".equals(i.nodeId())).extracting(Issue::code).containsExactly("UNKNOWN_NODE_TYPE");
        assertThat(VALIDATOR.validate(JSON.readTree("[]"), "FLOW", TARGETS).errors()).extracting(Issue::field).containsExactly("definition");
        assertThat(VALIDATOR.validate(JSON.readTree("{\"schema\":\"x\",\"nodes\":[]}"), "FLOW", TARGETS).errors())
                .extracting(Issue::field).contains("schema");
    }

    @Test
    @DisplayName("[BR-FLW-04] 순환·자기 연결은 CYCLE, 입력 없는 노드·없는 노드 연결은 UNCONNECTED, 노드 ID 중복")
    void cyclesAndConnections() {
        String d = def(TRIGGER + ",{\"id\":\"a\",\"type\":\"debug.log\"},{\"id\":\"b\",\"type\":\"debug.log\"},{\"id\":\"c\",\"type\":\"debug.log\"},"
                        + "{\"id\":\"c\",\"type\":\"debug.log\"}",
                "{\"from\":\"n-trg\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"b\"},{\"from\":\"b\",\"to\":\"a\"},{\"from\":\"a\",\"to\":\"a\"},"
                        + "{\"from\":\"a\",\"to\":\"ghost\"}");
        var result = VALIDATOR.validate(JSON.readTree(d), "FLOW", TARGETS);
        assertThat(codes(result.errors())).contains("CYCLE", "UNCONNECTED", "INVALID_CONFIG");
        assertThat(result.errors()).anyMatch(i -> "CYCLE".equals(i.code()) && "nodes[a]".equals(i.field()));
        // 엔진 검증(API-FLW-84)을 받은 경우: 구조 검사는 하지 않고 업무 검사만
        assertThat(VALIDATOR.validate(JSON.readTree(d), "FLOW", TARGETS, false).errors()).isEmpty();
        assertThat(result.errors()).anyMatch(i -> "UNCONNECTED".equals(i.code()) && "c".equals(i.nodeId()));
        // 트리거 출력이 연결되지 않음
        var lonely = VALIDATOR.validate(JSON.readTree(def(TRIGGER + "," + DEBUG, "")), "FLOW", TARGETS);
        assertThat(lonely.errors()).anyMatch(i -> "UNCONNECTED".equals(i.code()) && "n-trg".equals(i.nodeId()));
        // 비활성 노드는 설정·연결을 보지 않는다
        var disabled = VALIDATOR.validate(JSON.readTree(def(TRIGGER + ",{\"id\":\"off\",\"type\":\"condition.threshold\",\"disabled\":true},"
                + DEBUG, "{\"from\":\"n-trg\",\"to\":\"n-dbg\"}")), "FLOW", TARGETS);
        assertThat(disabled.ok()).isTrue();
        assertThat(VALIDATOR.validate(JSON.readTree(def(TRIGGER + "," + "{\"id\":\"n-js\",\"type\":\"transform.js\",\"config\":{\"outputs\":2}},"
                + DEBUG, "{\"from\":\"n-trg\",\"to\":\"n-js\"},{\"from\":\"n-js\",\"port\":\"out2\",\"to\":\"n-dbg\"}")), "FLOW", TARGETS).ok()).isTrue();
    }

    @Test
    @DisplayName("[ACT-01.01][BR-FLW-37] 제어 노드: 명령 인자 검증(기능 스키마·조직 절대 한계), priority는 AUTO만, 대상 없음 TARGET_MISSING·범위 밖 기록·대상 기기 없음 경고")
    void controlNode() {
        String control = "{\"id\":\"n-ctl\",\"type\":\"action.control\",\"config\":{\"target\":{\"spaceId\":\"31\",\"relation\":\"controls\"},"
                + "\"capability\":\"Thermostat\",\"command\":\"set\",\"args\":{\"targetTemperature\":30},\"priority\":\"MANUAL\"}}";
        var result = VALIDATOR.validate(JSON.readTree(def(TRIGGER + "," + control, "{\"from\":\"n-trg\",\"to\":\"n-ctl\"}")), "FLOW", TARGETS);
        assertThat(result.hasControlNode()).isTrue();
        assertThat(result.errors()).extracting(Issue::message).anyMatch(m -> m.startsWith("COMMAND_ABSOLUTE_LIMIT"))
                .anyMatch(m -> m.contains("AUTO"));
        assertThat(codes(result.warnings())).contains("TARGET_EMPTY");
        String devices = "{\"id\":\"n-ctl\",\"type\":\"action.control\",\"config\":{\"target\":{\"deviceIds\":[\"7\",\"8\",\"9\"]},"
                + "\"capability\":\"Fly\",\"command\":\"set\",\"args\":{}}}";
        var r2 = VALIDATOR.validate(JSON.readTree(def(TRIGGER + "," + devices, "{\"from\":\"n-trg\",\"to\":\"n-ctl\"}")), "FLOW", TARGETS);
        assertThat(r2.outOfScope()).containsExactly("device:8");
        assertThat(codes(r2.errors())).contains("TARGET_MISSING", "INVALID_CONFIG");
        String spaces = "{\"id\":\"n-trg\",\"type\":\"trigger.telemetry\",\"config\":{\"target\":{\"spaceId\":32},\"metrics\":[\"t\"]}},"
                + "{\"id\":\"n-t2\",\"type\":\"trigger.telemetry\",\"config\":{\"target\":{\"spaceId\":\"abc\"},\"metrics\":[\"t\"]}}," + DEBUG;
        var r3 = VALIDATOR.validate(JSON.readTree(def(spaces, "{\"from\":\"n-trg\",\"to\":\"n-dbg\"},{\"from\":\"n-t2\",\"to\":\"n-dbg\"}")),
                "FLOW", TARGETS);
        assertThat(r3.outOfScope()).containsExactly("space:32");
        assertThat(codes(r3.errors())).contains("TARGET_MISSING");
    }
}
