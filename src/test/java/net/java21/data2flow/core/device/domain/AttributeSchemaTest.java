package net.java21.data2flow.core.device.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeSchemaTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode node(String s) {
        return json.readTree(s);
    }

    @Test
    @DisplayName("[DEV-07.05] 모델 속성 스키마: 타입·필수 검사, 기본값 있는 필수는 지울 수 있음, additionalProperties:false면 모르는 키 거부 — TC-DEV-198")
    void validates() {
        AttributeSchema schema = AttributeSchema.of(node("""
                {"type":"object","properties":{"tempOffset":{"type":"number","default":0},"label":{"type":"string"},
                 "count":{"type":"integer"},"on":{"type":"boolean"},"cfg":{"type":"object"},"list":{"type":"array"},"any":{}},
                 "required":["tempOffset","label"]}"""));
        assertThat(schema.accepts("tempOffset", node("-0.5"))).isTrue();
        assertThat(schema.accepts("tempOffset", node("\"abc\""))).isFalse();
        assertThat(schema.accepts("count", node("3"))).isTrue();
        assertThat(schema.accepts("count", node("3.5"))).isFalse();
        assertThat(schema.accepts("on", node("true"))).isTrue();
        assertThat(schema.accepts("cfg", node("{}"))).isTrue();
        assertThat(schema.accepts("list", node("[1]"))).isTrue();
        assertThat(schema.accepts("any", node("1"))).isTrue();
        assertThat(schema.accepts("label", null)).isFalse();
        assertThat(schema.accepts("tempOffset", null)).isTrue();
        assertThat(schema.accepts("installedOn", node("\"2026-10-01\""))).isTrue();
        assertThat(schema.defaults()).containsOnlyKeys("tempOffset");

        AttributeSchema closed = AttributeSchema.of(node("{\"properties\":{\"a\":{\"type\":\"string\"}},\"additionalProperties\":false}"));
        assertThat(closed.accepts("b", node("1"))).isFalse();
        assertThat(AttributeSchema.of(null).accepts("x", node("1"))).isTrue();
        assertThat(AttributeSchema.of(node("[]")).accepts("x", null)).isTrue();
    }
}
