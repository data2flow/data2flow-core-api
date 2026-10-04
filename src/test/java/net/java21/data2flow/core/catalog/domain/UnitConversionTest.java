package net.java21.data2flow.core.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** DEV-04.04 단위 체계 변환 표시 — TC-DEV-140 */
class UnitConversionTest {

    @Test
    @DisplayName("[DEV-04.04][TC-DEV-140] ℉로 바꾸면 22.0℃는 71.6℉로 표시, 저장값은 그대로, 온도 외 단위는 바꾸지 않음, 내보내기에는 저장·표시 단위 함께")
    void celsiusToFahrenheit() {
        assertThat(UnitConversion.toDisplay(new BigDecimal("22.0"), "℃", "F")).contains(new BigDecimal("71.6"));
        assertThat(UnitConversion.toDisplay(new BigDecimal("-40"), "°C", "F")).contains(new BigDecimal("-40.0"));
        assertThat(UnitConversion.toDisplay(new BigDecimal("22.0"), "℃", "C")).contains(new BigDecimal("22.0"));
        assertThat(UnitConversion.toDisplay(new BigDecimal("812"), "ppm", "F")).contains(new BigDecimal("812"));
        assertThat(UnitConversion.toDisplay(null, "℃", "F")).isEmpty();
        assertThat(UnitConversion.displayUnit("℃", "F")).isEqualTo("℉");
        assertThat(UnitConversion.displayUnit("%", "F")).isEqualTo("%");
        assertThat(UnitConversion.exportUnitLabel("℃", "F")).isEqualTo("℉ (stored: ℃)");
        assertThat(UnitConversion.exportUnitLabel("℃", "C")).isEqualTo("℃");
        assertThat(UnitConversion.exportUnitLabel(null, "C")).isEmpty();
        assertThat(UnitConversion.temperatureUnitOf("IMPERIAL")).isEqualTo("F");
        assertThat(UnitConversion.unitSystemOf("C")).isEqualTo("METRIC");
        assertThat(UnitConversion.isCelsius("celsius")).isTrue();
        assertThat(UnitConversion.isCelsius(null)).isFalse();
    }
}
