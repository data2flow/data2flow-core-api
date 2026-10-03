package net.java21.data2flow.core.catalog;

import net.java21.data2flow.core.catalog.domain.MetricValueConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DEV-04.01 값 형식·소수 자릿수·집계 방식 — TC-DEV-127 */
class MetricValueConverterTest {

    static final Map<String, Double> DOOR = Map.of("open", 1.0, "close", 0.0);

    @Test
    @DisplayName("[DEV-04.01][AT-DEV-10.3] door ENUM {open:1, close:0}: \"open\"→1, \"CLOSE\"→0, 모르는 라벨은 변환 불가 — TC-DEV-127")
    void enumMapping() {
        assertThat(MetricValueConverter.toNumber("ENUM", DOOR, "open")).hasValue(1.0);
        assertThat(MetricValueConverter.toNumber("ENUM", DOOR, " CLOSE ")).hasValue(0.0);
        assertThat(MetricValueConverter.toNumber("ENUM", DOOR, "ajar")).isEmpty();
        assertThat(MetricValueConverter.toNumber("ENUM", DOOR, 1)).hasValue(1.0);
    }

    @Test
    @DisplayName("[DEV-04.01] 숫자·불리언·문자열 숫자 변환, null·NaN·글자는 변환 불가 — TC-DEV-127")
    void numbers() {
        assertThat(MetricValueConverter.toNumber("NUMBER", null, 22.5)).hasValue(22.5);
        assertThat(MetricValueConverter.toNumber("NUMBER", null, "21.5")).hasValue(21.5);
        assertThat(MetricValueConverter.toNumber("BOOLEAN", null, true)).hasValue(1.0);
        assertThat(MetricValueConverter.toNumber("BOOLEAN", null, "false")).hasValue(0.0);
        assertThat(MetricValueConverter.toNumber("NUMBER", null, null)).isEmpty();
        assertThat(MetricValueConverter.toNumber("NUMBER", null, Double.NaN)).isEmpty();
        assertThat(MetricValueConverter.toNumber("NUMBER", null, "abc")).isEmpty();
        assertThat(MetricValueConverter.toNumber("NUMBER", null, "Infinity")).isEmpty();
        assertThat(MetricValueConverter.toNumber("NUMBER", null, List.of())).isEmpty();
    }

    @ParameterizedTest(name = "{0} 자릿수 {1} → {2}")
    @CsvSource({"22.345,1,22.3", "22.35,1,22.4", "1004.25,0,1004", "0.1234567,6,0.123457", "5.5,9,5.5", "-0.05,1,-0.1"})
    @DisplayName("[DEV-04.01] 소수 자릿수 반올림(HALF_UP, 0~6) — TC-DEV-127")
    void rounding(double value, int precision, double expected) {
        assertThat(MetricValueConverter.round(value, precision)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"AVG,2.0", "SUM,6.0", "MAX,3.0", "MIN,1.0", "LAST,3.0", "COUNT,3.0"})
    @DisplayName("[DEV-04.01] 집계 방식(avg·sum·max·min·last·count) 선택 — TC-DEV-127")
    void aggregation(String aggregation, double expected) {
        assertThat(MetricValueConverter.aggregate(aggregation, List.of(1.0, 2.0, 3.0))).hasValue(expected);
    }

    @Test
    @DisplayName("[DEV-04.01] 값이 없으면 COUNT는 0, 나머지는 빈 값. 모르는 집계 방식은 오류")
    void emptyAggregation() {
        assertThat(MetricValueConverter.aggregate("COUNT", List.of())).hasValue(0.0);
        assertThat(MetricValueConverter.aggregate("AVG", List.of())).isEqualTo(OptionalDouble.empty());
        assertThatThrownBy(() -> MetricValueConverter.aggregate("MEDIAN", List.of(1.0))).isInstanceOf(IllegalArgumentException.class);
    }
}
