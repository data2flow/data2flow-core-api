package net.java21.data2flow.core.source.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.source.domain.ConnectionStates.Representative;
import net.java21.data2flow.core.source.domain.SourceModels.RuntimeRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DSC 순수 규칙: 대표 상태(domain-model §2.5), 토픽·주소·JSONPath, 커넥터 스키마 검증(BR-DSC-22) — TC-DSC-025·055·229 단위 */
class SourceDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-03T00:10:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static RuntimeRow row(String instance, String state, String kind, int secondsAgo) {
        return new RuntimeRow(1, instance, state, kind, null, null, null, 0, NOW.minusSeconds(secondsAgo));
    }

    @Test
    @DisplayName("[DSC-02.01] 대표 상태: 비활성은 DISABLED, 하나라도 CONNECTED, CONNECTING 우선, 모두 ERROR면 최근 종류, 섞이면 DISCONNECTED, 90초 지난 인스턴스 제외")
    void representative() {
        assertThat(ConnectionStates.representative("PAUSED", List.of(row("a", "CONNECTED", null, 1)), null, NOW).state()).isEqualTo("DISABLED");
        Representative r = ConnectionStates.representative("ACTIVE", List.of(row("a", "CONNECTED", null, 1), row("b", "ERROR", "TLS", 5)), null, NOW);
        assertThat(r).isEqualTo(new Representative("CONNECTED", null, 2, 1));
        assertThat(ConnectionStates.representative("ACTIVE", List.of(row("a", "CONNECTING", null, 1), row("b", "ERROR", "TLS", 5)), null, NOW).state())
                .isEqualTo("CONNECTING");
        assertThat(ConnectionStates.representative("ACTIVE", List.of(row("a", "ERROR", "DNS", 10), row("b", "ERROR", "AUTH", 2)), null, NOW))
                .isEqualTo(new Representative("ERROR", "AUTH", 2, 0));
        assertThat(ConnectionStates.representative("ACTIVE", List.of(row("a", "ERROR", null, 10)), null, NOW).errorKind()).isEqualTo("OTHER");
        assertThat(ConnectionStates.representative("ACTIVE", List.of(row("a", "DISCONNECTED", null, 1), row("b", "ERROR", "TLS", 1)), null, NOW).state())
                .isEqualTo("DISCONNECTED");
        assertThat(ConnectionStates.representative("ACTIVE", List.of(row("a", "CONNECTED", null, 91)), NOW.minusSeconds(30), NOW).state())
                .isEqualTo("CONNECTING");
        assertThat(ConnectionStates.representative("ACTIVE", List.of(row("a", "CONNECTED", null, 91)), NOW.minusSeconds(300), NOW).state())
                .isEqualTo("DISCONNECTED");
        assertThat(ConnectionStates.representative("ACTIVE", List.of(), null, NOW).state()).isEqualTo("DISCONNECTED");
    }

    @Test
    @DisplayName("[DSC-01.04][DSC-02.06] 토픽 필터 형식과 와일드카드 일치, 브로커 주소 스킴·포트")
    void topicsAndUrls() {
        assertThat(SourceConfigValidator.checkTopic("a/+/b/#")).isTrue();
        assertThat(SourceConfigValidator.checkTopic("a/#/b")).isFalse();
        assertThat(SourceConfigValidator.checkTopic("a/b+")).isFalse();
        assertThat(SourceConfigValidator.checkTopic("$share/g/a")).isFalse();
        assertThat(SourceConfigValidator.checkTopic("")).isFalse();
        assertThat(SourceConfigValidator.topicMatches("a/+/c", "a/b/c")).isTrue();
        assertThat(SourceConfigValidator.topicMatches("a/#", "a/b/c")).isTrue();
        assertThat(SourceConfigValidator.topicMatches("a/+", "a/b/c")).isFalse();
        assertThat(SourceConfigValidator.topicMatches("a/b/c", "a/b")).isFalse();
        assertThat(SourceConfigValidator.topicMatches(null, "x")).isTrue();
        assertThat(SourceConfigValidator.topicMatches("a", null)).isFalse();
        assertThat(SourceConfigValidator.checkUrl("wss://iot-data.java21.net:443/mqtt")).isTrue();
        assertThat(SourceConfigValidator.checkUrl("tcp://h:70000")).isFalse();
        assertThat(SourceConfigValidator.checkUrl("mqtt://h")).isFalse();
        assertThat(JsonPathLite.valid("$.a['b c'][0][*].d")).isTrue();
        assertThat(JsonPathLite.valid("a.b")).isFalse();
        assertThat(JsonPathLite.valid("$.")).isFalse();
        assertThat(JsonPathLite.valid(null)).isFalse();
    }

    @Test
    @DisplayName("[DSC-01.04] 검증기는 문제를 모아 SOURCE_CONFIG_INVALID 하나로, 유형 밖은 type, connection이 객체가 아니면 Type")
    void validatorCollects() {
        SourceConfigValidator v = SourceConfigValidator.start();
        v.connection("EDGE", null, false); // WEBHOOK은 M5(DSC-01.03)부터 받는다
        v.connection("MQTT_SUBSCRIBE", JSON.readTree("[1]"), false);
        assertThat(v.errors()).extracting(FieldErrorDetail::field).containsExactly("type", "connection");
        assertThatThrownBy(v::throwIfInvalid).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(SourceErrorCode.SOURCE_CONFIG_INVALID));
        SourceConfigValidator t = SourceConfigValidator.start();
        t.topics(JSON.readTree("[\"a/b\", {\"topic\":\"a/b\"}, {\"topic\":\"c\",\"qos\":\"1\"}]"), 1, 20, true);
        t.topics(JSON.readTree("{}"), 1, 20, true);
        t.topics(null, 1, 20, true);
        assertThat(t.errors()).extracting(FieldErrorDetail::code).containsExactly("DUPLICATE", "Range", "Type", "NotEmpty");
        SourceConfigValidator d = SourceConfigValidator.start();
        assertThat(d.decoderConfig("chirpstack-v4", JSON.readTree("{\"x\":1}"))).isNull();
        assertThat(d.decoderConfig("single-value", null)).isNull();
        d.decoderConfig("single-value", JSON.readTree("[1]"));
        d.decoderConfig("generic-json", JSON.readTree("[1]"));
        d.decoderConfig("generic-json", JSON.readTree("{\"deviceIdFrom\":\"topic[1]\",\"metrics\":[1]}"));
        d.decoderConfig("single-value", JSON.readTree("{\"metricKey\":\"9x\"}"));
        assertThat(d.errors()).extracting(FieldErrorDetail::field)
                .containsExactly("decoderConfig", "decoderConfig", "decoderConfig.metrics[0]", "decoderConfig.metricKey");
        SourceConfigValidator c = SourceConfigValidator.start();
        c.connection("MQTT_SUBSCRIBE", JSON.readTree("{\"url\":\"ws://h/m\",\"auth\":\"HEADER\",\"headerName\":\"Bad Header\",\"cleanStart\":\"no\",\"retainHandling\":\"X\",\"sharedGroup\":\"bad group\"}"), false);
        assertThat(c.errors()).extracting(FieldErrorDetail::field)
                .contains("connection.headerName", "connection.cleanStart", "connection.retainHandling", "connection.sharedGroup");
    }

    @Test
    @DisplayName("[ACT-03.03][DSC-01.01] MQTT 구독 소스는 ingress 옵션 downlinkAck(불리언)를 받는다 — 문자열이면 400")
    void mqttDownlinkAck() {
        SourceConfigValidator ok = SourceConfigValidator.start();
        ok.connection("MQTT_SUBSCRIBE", JSON.readTree("{\"url\":\"wss://broker.example.com/mqtt\",\"downlinkAck\":true}"), false);
        assertThat(ok.errors()).isEmpty();
        SourceConfigValidator bad = SourceConfigValidator.start();
        bad.connection("MQTT_SUBSCRIBE", JSON.readTree("{\"url\":\"wss://broker.example.com/mqtt\",\"downlinkAck\":\"yes\"}"), false);
        assertThat(bad.errors()).extracting(FieldErrorDetail::field).containsExactly("connection.downlinkAck");
    }

    @Test
    @DisplayName("[DSC-09.01][BR-DSC-22] 스키마 검증: type·required·additionalProperties·enum·const·pattern·길이·범위·배열")
    void jsonSchema() {
        JsonNode schema = JSON.readTree("""
                {"type":"object","additionalProperties":false,"required":["a"],"properties":{
                  "a":{"type":"string","pattern":"^x","minLength":2,"maxLength":3},
                  "n":{"type":["integer","null"],"minimum":1,"maximum":5},
                  "f":{"type":"number"},"b":{"type":"boolean"},"c":{"const":"k"},"e":{"enum":[1,2]},
                  "arr":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"object","properties":{"q":{"type":"null"}}}},
                  "bad":{"type":"string","pattern":"("}}}""");
        assertThat(JsonSchemaLite.validate(schema, JSON.readTree("{\"a\":\"xy\",\"n\":3,\"f\":1.5,\"b\":true,\"c\":\"k\",\"e\":2.0,\"arr\":[{\"q\":null}],\"bad\":\"z\"}"), "c"))
                .isEmpty();
        List<FieldErrorDetail> errors = JsonSchemaLite.validate(schema,
                JSON.readTree("{\"a\":\"abcd\",\"n\":9,\"f\":\"x\",\"b\":1,\"c\":\"j\",\"e\":3,\"arr\":[],\"zz\":1}"), "");
        assertThat(errors).extracting(FieldErrorDetail::field)
                .contains("a", "n", "f", "b", "c", "e", "arr", "zz");
        assertThat(JsonSchemaLite.validate(schema, JSON.readTree("{\"arr\":[1,2,3]}"), "c")).extracting(FieldErrorDetail::field)
                .contains("c.a", "c.arr", "c.arr[0]");
        assertThat(JsonSchemaLite.validate(null, JSON.readTree("1"), "x")).isEmpty();
        assertThat(JsonSchemaLite.validate(JSON.readTree("{\"type\":\"object\"}"), JSON.readTree("1"), "x")).hasSize(1);
    }
}
