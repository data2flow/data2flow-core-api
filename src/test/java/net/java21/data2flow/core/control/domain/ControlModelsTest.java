package net.java21.data2flow.core.control.domain;

import net.java21.data2flow.contracts.capability.AttributeConstraint;
import net.java21.data2flow.contracts.capability.CapabilityCatalog;
import net.java21.data2flow.contracts.capability.CapabilityDefinition;
import net.java21.data2flow.contracts.capability.StandardCapabilities;
import net.java21.data2flow.contracts.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 모델 제약·조직 절대 한계 규칙(ACT-01.03·06.04, BR-ACT-09)과 표준 기능 카탈로그(ACT-01.02) */
class ControlModelsTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("[ACT-01.02] 표준 기능 7종: Switch·Thermostat(mode·targetTemperature 5~35·0.5·currentTemperature 읽기 전용)·FanSpeed·Ventilation·Dimmer·Lock·Contact — TC-ACT-002")
    void standardCatalog() {
        assertThat(StandardCapabilities.all()).extracting(CapabilityDefinition::name)
                .containsExactly("Switch", "Thermostat", "FanSpeed", "Ventilation", "Dimmer", "Lock", "Contact");
        CapabilityDefinition t = StandardCapabilities.get("Thermostat");
        assertThat(t.attribute("targetTemperature").orElseThrow().range()).isEqualTo(new AttributeConstraint(5.0, 35.0, null));
        assertThat(t.attribute("currentTemperature").orElseThrow().readOnly()).isTrue();
        assertThat(t.command("set")).isPresent();
        assertThat(StandardCapabilities.get("Contact").stateOnly()).isTrue();
        for (CapabilityDefinition d : StandardCapabilities.all()) {
            d.attributes().stream().filter(a -> a.type().name().equals("NUMBER")).forEach(a -> assertThat(a.unit()).as(d.name()).isNotNull());
        }
    }

    @Test
    @DisplayName("[ACT-01.03] 모델 제약: 속성별 {속성:{min,max}}과 M2 모양 {min,max}(첫 숫자 속성), 실효 범위 = 표준 ∩ 모델 ∩ 조직")
    void modelConstraints() {
        CapabilityDefinition t = StandardCapabilities.get("Thermostat");
        var perAttr = ControlModels.modelConstraints(JSON.readTree(
                "[{\"capability\":\"Thermostat\",\"constraints\":{\"targetTemperature\":{\"min\":18,\"max\":30}}}]"), t);
        var flat = ControlModels.modelConstraints(JSON.readTree("[{\"capability\":\"Thermostat\",\"constraints\":{\"min\":18,\"max\":30}}]"), t);
        assertThat(perAttr).isEqualTo(flat).containsEntry("targetTemperature", AttributeConstraint.range(18, 30));
        assertThat(ControlModels.modelConstraints(JSON.readTree("[{\"capability\":\"Switch\"}]"), t)).isEmpty();
        var effective = ControlModels.effective(t, perAttr, Map.of("targetTemperature", AttributeConstraint.range(20, 28)));
        assertThat(effective.get("targetTemperature")).isEqualTo(AttributeConstraint.range(20, 28));
        assertThat(ControlModels.capabilityNames(JSON.readTree("[{\"capability\":\"A\"},{\"capability\":\"A\"},{\"x\":1}]"))).containsExactly("A");
        assertThat(ControlModels.defaultCapabilities("LG_THINQ")).contains("Thermostat").doesNotContain("Dimmer");
        assertThatThrownBy(() -> ControlModels.constraint(JSON.readTree("{\"min\":5,\"max\":1}"))).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("[ACT-06.04][BR-ACT-09] 조직 절대 한계는 표준 범위·모델 제약 안에서만: 넓으면 LIMIT_WIDER_THAN_MODEL — TC-ACT-119")
    void absoluteLimits() {
        CapabilityCatalog catalog = CapabilityCatalog.standard();
        List<tools.jackson.databind.JsonNode> models = List.of(JSON.readTree(
                "[{\"capability\":\"Thermostat\",\"constraints\":{\"targetTemperature\":{\"min\":18,\"max\":30}}}]"));
        assertThat(ControlModels.validateLimits(JSON.readTree("{\"Thermostat\":{\"targetTemperature\":{\"min\":18,\"max\":28}}}"), catalog, models))
                .containsKey("Thermostat");
        assertThatThrownBy(() -> ControlModels.validateLimits(JSON.readTree("{\"Thermostat\":{\"targetTemperature\":{\"min\":10,\"max\":28}}}"),
                catalog, models)).isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().code()).isEqualTo("LIMIT_WIDER_THAN_MODEL"));
        assertThatThrownBy(() -> ControlModels.validateLimits(JSON.readTree("{\"Thermostat\":{\"targetTemperature\":{\"min\":0,\"max\":40}}}"),
                catalog, List.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ControlModels.validateLimits(JSON.readTree("[]"), catalog, models)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ControlModels.validateLimits(JSON.readTree("{\"Thermostat\":1}"), catalog, models))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ControlModels.validateLimits(JSON.readTree("{\"Thermostat\":{\"targetTemperature\":1}}"), catalog, models))
                .isInstanceOf(BusinessException.class);
        assertThat(ControlModels.validateLimits(null, catalog, models)).isEmpty();
        assertThat(ControlModels.absoluteLimits(JSON.readTree("{\"Thermostat\":{\"targetTemperature\":{\"min\":18}}}"), "Thermostat"))
                .containsKey("targetTemperature");
        assertThat(ControlModels.absoluteLimits(null, "Thermostat")).isEmpty();
    }
}
