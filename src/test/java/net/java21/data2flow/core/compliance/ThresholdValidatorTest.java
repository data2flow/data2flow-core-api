package net.java21.data2flow.core.compliance;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.core.compliance.domain.ComplianceErrorCode;
import net.java21.data2flow.core.compliance.domain.ProfileBodyReader;
import net.java21.data2flow.core.compliance.domain.ProfileModels.Band;
import net.java21.data2flow.core.compliance.domain.ProfileModels.MetricRule;
import net.java21.data2flow.core.compliance.domain.ProfileModels.VersionSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CMP-01.01 프로필 버전 본문 검사 — TC-CMP-002(필드 경로는 명세의 items[] 대신 본문 이름 metrics[]) */
class ThresholdValidatorTest {

    static final Map<String, String> UNITS = Map.of("co2", "ppm", "pm2_5", "μg/m³", "temperature", "℃", "humidity", "%", "tvoc", "");
    final JsonMapper json = JsonMapper.builder().build();

    JsonNode body(String metrics) {
        return json.readTree("""
                {"sourceTitle":"내부 지침 2026","sourceUrl":"https://example.org/g","effectiveDate":"2026-09-01",
                 "streak":{"days":90,"dailyRatio":95.0},"changeNote":"CO2 기준 강화","metrics":[%s],
                 "coverage":[{"kind":"DATA_QUALITY","params":{"maxMissingRatio":0.1}}]}""".formatted(metrics));
    }

    @Test
    @DisplayName("[TC-CMP-002] 올바른 본문: 등급 구간·연속 측정 항목·단위 별칭(°C = ℃)을 읽는다")
    void valid() {
        VersionSpec spec = ProfileBodyReader.read(body("""
                {"metricKey":"co2","evalWindow":"HOUR_1","upper":1000,"unit":"ppm","requirement":"LIMIT","inclusive":false,
                 "bands":[{"name":"허용","upper":1000,"isBase":true},{"name":"우수","upper":700}]},
                {"metricKey":"temperature","evalWindow":"instant","unit":"°C","requirement":"CONTINUOUS_MONITORING_ONLY"},
                {"metricKey":"humidity","evalWindow":"HOUR_1","lower":40,"upper":60,"unit":"%"}"""), UNITS);
        assertThat(spec.streakDays()).isEqualTo(90);
        assertThat(spec.metrics()).extracting(MetricRule::metricKey).containsExactly("co2", "temperature", "humidity");
        MetricRule co2 = spec.metrics().getFirst();
        assertThat(co2.inclusive()).isFalse();
        assertThat(co2.bands()).extracting(Band::name).containsExactly("허용", "우수");
        assertThat(spec.metrics().get(1).continuousOnly()).isTrue();
        assertThat(spec.metrics().get(1).bands()).isEmpty();
        assertThat(spec.metrics().get(1).evalWindow()).isEqualTo("INSTANT");
        // 등급을 주지 않으면 기준값으로 기준 등급 하나
        assertThat(spec.metrics().get(2).bands()).containsExactly(new Band("기준", new BigDecimal("40"), new BigDecimal("60"), true));
        assertThat(spec.metrics().get(2).requirement()).isEqualTo("LIMIT");
        assertThat(spec.coverage()).hasSize(1);
    }

    @ParameterizedTest(name = "[TC-CMP-002] {0}")
    @CsvSource(delimiter = '|', value = {
            "하한 30·상한 20|{\"metricKey\":\"co2\",\"evalWindow\":\"HOUR_1\",\"lower\":30,\"upper\":20,\"unit\":\"ppm\"}|metrics[co2].lower|Range",
            "등급 구간 겹침|{\"metricKey\":\"co2\",\"evalWindow\":\"HOUR_1\",\"lower\":0,\"upper\":1000,\"unit\":\"ppm\",\"bands\":[{\"name\":\"기준\",\"lower\":0,\"upper\":1000,\"isBase\":true},{\"name\":\"고성능\",\"lower\":0,\"upper\":1200}]}|metrics[co2].bands[고성능]|Overlap",
            "PM2.5 단위 ppm|{\"metricKey\":\"pm2_5\",\"evalWindow\":\"HOUR_1\",\"upper\":35,\"unit\":\"ppm\"}|metrics[pm2_5].unit|Mismatch",
            "평가 단위 밖|{\"metricKey\":\"co2\",\"evalWindow\":\"MINUTE_5\",\"upper\":1000,\"unit\":\"ppm\"}|metrics[co2].evalWindow|Pattern",
            "기준값 없음|{\"metricKey\":\"co2\",\"evalWindow\":\"HOUR_1\",\"unit\":\"ppm\"}|metrics[co2].upper|NotNull",
            "기준 등급 둘|{\"metricKey\":\"co2\",\"evalWindow\":\"HOUR_1\",\"upper\":1000,\"unit\":\"ppm\",\"bands\":[{\"name\":\"a\",\"upper\":1000,\"isBase\":true},{\"name\":\"b\",\"upper\":900,\"isBase\":true}]}|metrics[co2].bands|BaseCount",
            "기준 등급이 기준값과 다름|{\"metricKey\":\"co2\",\"evalWindow\":\"HOUR_1\",\"upper\":1000,\"unit\":\"ppm\",\"bands\":[{\"name\":\"a\",\"upper\":900,\"isBase\":true}]}|metrics[co2].bands[a]|BaseMismatch",
            "단위 빈 값|{\"metricKey\":\"co2\",\"evalWindow\":\"HOUR_1\",\"upper\":1000}|metrics[co2].unit|NotBlank"})
    void thresholdInvalid(String name, String metric, String field, String code) {
        assertThatThrownBy(() -> ProfileBodyReader.read(body(metric), UNITS))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ComplianceErrorCode.CMP_THRESHOLD_INVALID);
                    assertThat(e.getErrors()).anySatisfy(d -> {
                        assertThat(d.field()).isEqualTo(field);
                        assertThat(d.code()).isEqualTo(code);
                    });
                });
    }

    @Test
    @DisplayName("[CMP-01.01] 형식 오류는 INVALID_REQUEST, 조직에 없는 항목은 METRIC_NOT_FOUND")
    void formatErrors() {
        assertThatThrownBy(() -> ProfileBodyReader.read(body("{\"metricKey\":\"radon\",\"evalWindow\":\"DAY_1\",\"upper\":148,\"unit\":\"Bq/m³\"}"),
                UNITS)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ComplianceErrorCode.METRIC_NOT_FOUND));
        assertThatThrownBy(() -> ProfileBodyReader.read(json.readTree("{\"sourceTitle\":\"a\",\"sourceUrl\":\"http://x\",\"effectiveDate\":\"2026-01-01\"}"),
                UNITS)).isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST);
                    assertThat(e.getErrors().getFirst().field()).isEqualTo("sourceUrl");
                });
        assertThatThrownBy(() -> ProfileBodyReader.read(body("""
                {"metricKey":"co2","evalWindow":"HOUR_1","upper":1000,"unit":"ppm"},
                {"metricKey":"co2","evalWindow":"HOUR_8","upper":900,"unit":"ppm"}"""), UNITS))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrors().getFirst().field()).isEqualTo("metrics[co2].metricKey"));
    }
}
