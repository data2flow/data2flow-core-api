package net.java21.data2flow.core.modelexchange.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DEV-03.04·13.04 DTDL 매핑·규칙 검사(BR-DEV-36) — TC-DEV-107·318 */
class DtdlTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private ModelDocument doc() {
        return new ModelDocument(1, "data2flow", new ModelDocument.Model("EM300-TH_", "Milesight", "온습도", "LORAWAN", "SENSOR", 600, 3.0, null),
                List.of(new ModelDocument.MetricDef("temperature", "온도", "℃", "NUMBER", null, null, 1, "AVG", null, true),
                        new ModelDocument.MetricDef("humidity", "습도", "%", "NUMBER", null, null, 1, "AVG", null, true),
                        new ModelDocument.MetricDef("pir_", "재실", null, "BOOLEAN", null, null, null, "LAST", null, false),
                        new ModelDocument.MetricDef("pressure", "기압", "hPa", "NUMBER", null, null, null, "AVG", null, false)),
                List.of(new ModelDocument.Capability("custom.vent", null)),
                json.readTree("{\"type\":\"object\",\"properties\":{\"floor\":{\"type\":\"integer\"},\"note\":{\"type\":\"string\"}}}"),
                List.of());
    }

    @Test
    @DisplayName("[DEV-03.04][TC-DEV-107] 모델 → DTDL v3(DTMI, 의미 형식·단위, 끝 밑줄 없는 이름, Property·Command)이 규칙을 통과하고 다시 가져오면 같은 항목")
    void roundTrip() {
        ObjectNode iface = Dtdl.toInterface(json, doc());
        assertThat(iface.get("@id").asString()).isEqualTo("dtmi:data2flow:model:em300_th;1");
        assertThat(Dtdl.validate(iface)).isEmpty();
        Dtdl.Imported back = Dtdl.fromInterface(json, iface, name -> name.equals("custom.vent") ? "custom.vent" : null);
        assertThat(back.unmapped()).isEmpty();
        assertThat(back.document().metrics()).extracting(ModelDocument.MetricDef::key).containsExactly("temperature", "humidity", "pir_", "pressure");
        assertThat(back.document().metrics()).extracting(ModelDocument.MetricDef::unit).containsExactly("℃", "%", null, "hPa");
        assertThat(back.document().metrics().get(2).valueType()).isEqualTo("BOOLEAN");
        assertThat(back.document().capabilities()).extracting(ModelDocument.Capability::capability).containsExactly("custom.vent");
        assertThat(back.document().attributeSchema().get("properties").get("floor").get("type").asString()).isEqualTo("integer");
        assertThat(back.document().model().code()).isEqualTo("EM300_TH");
    }

    @Test
    @DisplayName("[DEV-13.04][BR-DEV-36] DTDL 규칙 위반을 찾는다: 컨텍스트·DTMI·이름·겹친 이름·의미 형식 없는 단위·schema")
    void violations() {
        JsonNode bad = json.readTree("""
                {"@context":"dtmi:dtdl:context;2","@id":"dtmi:x:1bad;1","@type":"Interface","contents":[
                  {"@type":"Telemetry","name":"a_","schema":"double"},
                  {"@type":"Telemetry","name":"b","schema":"double","unit":"degreeCelsius"},
                  {"@type":"Telemetry","name":"b","schema":"map"},
                  {"@type":"Property","name":"c","schema":"string","displayName":"%s"}]}""".formatted("x".repeat(65)));
        List<String> problems = Dtdl.validate(bad);
        assertThat(problems).hasSize(7);
        Dtdl.Imported imported = Dtdl.fromInterface(json, json.readTree("""
                {"@id":"dtmi:a:b;1","contents":[{"@type":"Telemetry","name":"x","schema":{"@type":"Object"}},
                  {"@type":"Relationship","name":"r"},{"@type":"Command","name":"fly"},{"@type":"Property","name":"p","schema":"map"}]}"""),
                name -> null);
        assertThat(imported.unmapped()).hasSize(4);
        assertThat(Dtdl.segment("1abc")).isEqualTo("m1abc");
    }
}
