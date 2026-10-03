package net.java21.data2flow.core.catalog;

import net.java21.data2flow.contracts.message.CanonicalTelemetry;
import net.java21.data2flow.contracts.test.message.MessageFixtures;
import net.java21.data2flow.core.catalog.domain.BuiltinCatalog;
import net.java21.data2flow.core.catalog.domain.BuiltinCatalog.MetricDef;
import net.java21.data2flow.core.catalog.domain.BuiltinCatalog.ModelDef;
import net.java21.data2flow.core.catalog.domain.CatalogModels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** DEV-03.02 기본 모델 6종 카탈로그 — TC-DEV-100 */
class BuiltinModelCatalogTest {

    /** 정본: research/01 §4 "주요 측정값"과 contracts 공유 픽스처 academy-*.json의 키·단위 */
    static final Map<String, Map<String, String>> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("EM300-TH", units("temperature", "℃", "humidity", "%"));
        EXPECTED.put("EM320-TH", units("temperature", "℃", "humidity", "%", "battery", "%"));
        EXPECTED.put("EM500-CO2", units("co2", "ppm", "pressure", "hPa", "temperature", "℃", "humidity", "%"));
        EXPECTED.put("AM103", units("co2", "ppm", "temperature", "℃", "humidity", "%", "battery", "%"));
        EXPECTED.put("AM107", units("co2", "ppm", "tvoc", null, "pressure", "hPa", "illumination", "lux", "infrared", null,
                "activity", null, "temperature", "℃", "humidity", "%"));
        EXPECTED.put("WS302", units("LAeq", "dB", "LAI", "dB", "LAImax", "dB", "battery", "%"));
    }

    private static Map<String, String> units(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("[DEV-03.02][BR-DEV-14] 기본 모델 6종(EM300-TH·EM320-TH·EM500-CO2·AM103·AM107·WS302)의 측정 키·단위가 정확히 일치 — TC-DEV-100")
    void modelsAndUnitsExact() {
        assertThat(BuiltinCatalog.MODELS).extracting(ModelDef::code).containsExactlyElementsOf(EXPECTED.keySet());
        for (ModelDef model : BuiltinCatalog.MODELS) {
            Map<String, String> expected = EXPECTED.get(model.code());
            assertThat(model.metricKeys()).as(model.code()).containsExactlyElementsOf(expected.keySet());
            for (String key : model.metricKeys()) {
                MetricDef def = BuiltinCatalog.metric(key).orElseThrow();
                assertThat(def.unit()).as(model.code() + "." + key).isEqualTo(expected.get(key));
            }
            assertThat(model.vendor()).isEqualTo("Milesight");
            assertThat(model.protocol()).isEqualTo("LORAWAN");
            assertThat(model.kind()).isEqualTo("SENSOR");
            assertThat(model.code()).matches(CatalogModels.MODEL_CODE_PATTERN);
        }
        assertThat(BuiltinCatalog.METRICS).extracting(MetricDef::key)
                .containsExactlyInAnyOrder("temperature", "humidity", "co2", "battery", "pressure", "tvoc", "illumination", "infrared",
                        "activity", "LAeq", "LAI", "LAImax")
                .allMatch(k -> k.matches(CatalogModels.METRIC_KEY_PATTERN));
        assertThat(BuiltinCatalog.METRICS).allMatch(m -> CatalogModels.AGGREGATIONS.contains(m.aggDefault())
                && (m.validMin() == null || m.validMax() == null || m.validMin() < m.validMax()));
    }

    @Test
    @DisplayName("[DEV-03.02][ING-02.02] pipeline 공유 픽스처(academy-*)의 측정 키·단위가 기본 모델 정의와 같다(키가 다르면 UNVERIFIED가 생긴다) — TC-DEV-100")
    void matchesPipelineFixtures() {
        List<String> codes = List.copyOf(EXPECTED.keySet());
        for (int i = 0; i < MessageFixtures.ACADEMY_TELEMETRY.size(); i++) {
            CanonicalTelemetry telemetry = MessageFixtures.canonicalTelemetry(MessageFixtures.ACADEMY_TELEMETRY.get(i));
            ModelDef model = BuiltinCatalog.model(codes.get(i)).orElseThrow();
            assertThat(telemetry.metrics()).extracting(CanonicalTelemetry.Metric::key).as(model.code())
                    .containsExactlyInAnyOrderElementsOf(model.metricKeys());
            telemetry.metrics().forEach(m -> assertThat(Objects.equals(m.unit(),
                    BuiltinCatalog.metric(m.key()).orElseThrow().unit())).as(model.code() + "." + m.key()).isTrue());
        }
    }
}
