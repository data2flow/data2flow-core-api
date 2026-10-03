package net.java21.data2flow.core.catalog.domain;

import net.java21.data2flow.core.catalog.domain.CatalogModels.ModelMetric;

import java.util.List;
import java.util.Optional;

/**
 * 기본 제공 카탈로그(DEV-03.02, BR-DEV-14): 아카데미 실측 모델 6종(Milesight EM300-TH, EM320-TH, EM500-CO2, AM103, AM107, WS302)과
 * 그 모델이 내는 측정 항목·단위.
 *
 * <p>측정 키는 ChirpStack v4 {@code object}의 키 그대로다(research/01 §4 "주요 측정값", ADR-001). pipeline의 chirpstack-v4 디코더가
 * 이 키로 표준 텔레메트리를 내고(contracts 공유 픽스처 {@code academy-*.json}), 단위도 그 픽스처와 같다(단위가 없는 tvoc·infrared·activity는 null).
 * {@code BuiltinModelCatalogTest}가 픽스처와 정확히 같은지 확인한다.
 *
 * <p>모델 코드는 화면 경로(/models/{code})와 기기 이름에 쓰는 제품명 그대로다(예: EM300-TH). 보고 주기 기본값은 Milesight 공장 설정 600초
 * (현장 기기는 1~10분으로 설정, research/01 §4). 기기별로 바꿀 수 있다.
 */
public final class BuiltinCatalog {

    public static final String VENDOR = "Milesight";

    /** 기본 측정 항목 정의 */
    public record MetricDef(String key, String displayName, String unit, String valueType, Double validMin, Double validMax,
                            int precision, String aggDefault, boolean stateType, String semantic) {
    }

    /** 기본 모델 정의 */
    public record ModelDef(String code, String vendor, String name, String protocol, String kind, int defaultIntervalSec,
                           String description, List<ModelMetric> metrics) {

        public List<String> metricKeys() {
            return metrics.stream().map(ModelMetric::key).toList();
        }
    }

    /** 측정 항목 12종. 유효 범위는 센서 측정 범위(데이터시트)로, 벗어나면 pipeline이 품질 코드 1을 붙인다(ING-04.01) */
    public static final List<MetricDef> METRICS = List.of(
            new MetricDef("temperature", "온도", "℃", "NUMBER", -40.0, 85.0, 1, "AVG", false, "temperature"),
            new MetricDef("humidity", "습도", "%", "NUMBER", 0.0, 100.0, 1, "AVG", false, "humidity"),
            new MetricDef("co2", "CO2", "ppm", "NUMBER", 0.0, 10000.0, 0, "AVG", false, "co2"),
            new MetricDef("battery", "배터리", "%", "NUMBER", 0.0, 100.0, 0, "LAST", false, "battery"),
            new MetricDef("pressure", "기압", "hPa", "NUMBER", 300.0, 1100.0, 1, "AVG", false, "pressure"),
            new MetricDef("tvoc", "TVOC", null, "NUMBER", 0.0, 60000.0, 0, "AVG", false, "tvoc"),
            new MetricDef("illumination", "조도", "lux", "NUMBER", 0.0, 100000.0, 0, "AVG", false, "illuminance"),
            new MetricDef("infrared", "적외선", null, "NUMBER", 0.0, null, 0, "AVG", false, null),
            new MetricDef("activity", "활동량(PIR)", null, "NUMBER", 0.0, null, 0, "SUM", false, "occupancy"),
            new MetricDef("LAeq", "등가 소음(LAeq)", "dB", "NUMBER", 0.0, 140.0, 1, "AVG", false, "noise"),
            new MetricDef("LAI", "순간 소음(LAI)", "dB", "NUMBER", 0.0, 140.0, 1, "AVG", false, "noise"),
            new MetricDef("LAImax", "최대 소음(LAImax)", "dB", "NUMBER", 0.0, 140.0, 1, "MAX", false, "noise"));

    /** 모델 6종. 배터리는 필수가 아니다(전원형 설치·보고 생략 가능) */
    public static final List<ModelDef> MODELS = List.of(
            model("EM300-TH", "온습도 센서(LoRaWAN)", "temperature", "humidity"),
            model("EM320-TH", "온습도 센서(LoRaWAN, 배터리 보고)", "temperature", "humidity", "battery"),
            model("EM500-CO2", "CO2·기압·온습도 센서(LoRaWAN)", "co2", "pressure", "temperature", "humidity"),
            model("AM103", "실내 공기질 센서: CO2·온습도(LoRaWAN)", "co2", "temperature", "humidity", "battery"),
            model("AM107", "실내 공기질 센서: CO2·TVOC·기압·조도·적외선·활동량·온습도(LoRaWAN)",
                    "co2", "tvoc", "pressure", "illumination", "infrared", "activity", "temperature", "humidity"),
            model("WS302", "소음 센서(LoRaWAN)", "LAeq", "LAI", "LAImax", "battery"));

    private BuiltinCatalog() {
    }

    public static Optional<MetricDef> metric(String key) {
        return METRICS.stream().filter(m -> m.key().equals(key)).findFirst();
    }

    public static Optional<ModelDef> model(String code) {
        return MODELS.stream().filter(m -> m.code().equals(code)).findFirst();
    }

    private static ModelDef model(String code, String description, String... keys) {
        List<ModelMetric> metrics = java.util.Arrays.stream(keys).map(k -> new ModelMetric(k, !"battery".equals(k))).toList();
        return new ModelDef(code, VENDOR, code, "LORAWAN", "SENSOR", 600, description, metrics);
    }
}
