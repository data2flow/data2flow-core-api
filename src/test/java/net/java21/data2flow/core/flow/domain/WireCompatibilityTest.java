package net.java21.data2flow.core.flow.domain;

import net.java21.data2flow.core.flow.domain.FlowValidator.Issue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** BR-FLW-03 연결선 규칙 — TC-FLW-012 */
class WireCompatibilityTest {

    @ParameterizedTest(name = "{0} → {1}: {2}")
    @CsvSource({"message,message,true", "any,message,true", "message,any,true", "error,message,true", "message,error,false",
            "telemetry,message,false"})
    @DisplayName("[FLW-01.03][BR-FLW-03] 출력 포트 타입과 입력 포트 타입: 같거나 any이거나 error → message만 — TC-FLW-012")
    void compatibility(String out, String in, boolean ok) {
        assertThat(FlowValidator.compatible(out, in)).isEqualTo(ok);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "true 포트(조건 분기) 정상|true|true",
            "false 포트 정상|false|true",
            "공통 error 포트 정상|error|true",
            "없는 포트|maybe|false"})
    @DisplayName("[FLW-01.03] 분기 노드 출력 포트(true·false·error)만 이을 수 있고 트리거로는 들어갈 수 없다 — TC-FLW-012")
    void ports(String name, String port, boolean ok) {
        String d = FlowValidatorTest.def(FlowValidatorTest.TRIGGER + ",{\"id\":\"n-thr\",\"type\":\"condition.threshold\",\"config\":{\"metric\":\"t\","
                        + "\"op\":\">\",\"value\":1}}," + FlowValidatorTest.DEBUG,
                "{\"from\":\"n-trg\",\"to\":\"n-thr\"},{\"from\":\"n-thr\",\"port\":\"" + port + "\",\"to\":\"n-dbg\"}");
        var result = FlowValidatorTest.VALIDATOR.validate(FlowValidatorTest.JSON.readTree(d), "FLOW", FlowValidatorTest.TARGETS);
        assertThat(result.errors().stream().noneMatch(i -> "TYPE_MISMATCH".equals(i.code()) || "UNKNOWN_PORT".equals(i.code())))
                .as(name).isEqualTo(ok);
        String intoTrigger = FlowValidatorTest.def(FlowValidatorTest.TRIGGER + "," + FlowValidatorTest.DEBUG,
                "{\"from\":\"n-trg\",\"to\":\"n-dbg\"},{\"from\":\"n-dbg\",\"to\":\"n-trg\"}");
        assertThat(FlowValidatorTest.VALIDATOR.validate(FlowValidatorTest.JSON.readTree(intoTrigger), "FLOW", FlowValidatorTest.TARGETS).errors())
                .extracting(Issue::code).contains("TYPE_MISMATCH");
    }
}
