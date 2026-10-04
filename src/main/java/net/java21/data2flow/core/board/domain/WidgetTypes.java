package net.java21.data2flow.core.board.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 위젯 종류와 대상 규칙(design/api/DSH-api.md §3 위젯 옵션, API-DSH-13). M5에서 데이터를 주는 종류만 저장할 수 있다.
 * 제어(control, DSH-04.03 M7)·분석(analysis, M6)·KPI(kpi, M6)는 그 기능이 생길 때 열고 지금은 WIDGET_TYPE_UNSUPPORTED다.
 */
public final class WidgetTypes {

    /** 대상 종류 */
    public static final String DEVICE_METRIC = "DEVICE_METRIC";
    public static final String SPACE_AGGREGATE = "SPACE_AGGREGATE";
    public static final String DEVICE = "DEVICE";
    public static final String SPACE = "SPACE";

    private static final List<String> METRIC_KINDS = List.of(DEVICE_METRIC, SPACE_AGGREGATE);
    private static final List<String> THING_KINDS = List.of(DEVICE, SPACE);

    /** 위젯 종류 하나: 이름, 표시 이름, 대상 수 범위, 허용 대상 종류, 옵션 스키마(JSON Schema 요약) */
    public record WidgetType(String type, String label, int minTargets, int maxTargets, List<String> kinds, Map<String, Object> optionsSchema) {
    }

    /** 나중 마일스톤에서 여는 종류(문서 §3에는 있음) */
    public static final Set<String> DEFERRED = Set.of("control", "analysis", "kpi");

    private static final Map<String, WidgetType> TYPES = new LinkedHashMap<>();

    static {
        add("stat", "현재값", 1, 1, METRIC_KINDS, props(
                "unit", str(), "decimals", integer(0, 4), "thresholds", array(), "trend", bool(), "sparkline", bool()));
        add("line", "선 차트", 1, 20, METRIC_KINDS, props(
                "yMin", num(), "yMax", num(), "thresholds", array(), "legend", enumOf("bottom", "right", "hidden"),
                "showQuality", bool(), "annotations", array()));
        add("area", "영역 차트", 1, 20, METRIC_KINDS, props(
                "yMin", num(), "yMax", num(), "legend", enumOf("bottom", "right", "hidden"), "stacked", bool()));
        add("bar", "막대 차트", 1, 20, METRIC_KINDS, props(
                "orientation", enumOf("vertical", "horizontal"), "agg", enumOf("avg", "min", "max", "sum", "last"),
                "groupBy", enumOf("space", "device"), "sort", enumOf("asc", "desc", "none")));
        add("gauge", "게이지", 1, 1, METRIC_KINDS, props("min", num(), "max", num(), "ranges", array()));
        add("heatmap", "히트맵", 1, 1, METRIC_KINDS, props("agg", enumOf("avg", "max"), "days", integer(7, 90)));
        add("table", "표", 1, 20, METRIC_KINDS, props("columns", array(), "sort", Map.of("type", "object")));
        add("status-list", "기기 상태 목록", 1, 20, THING_KINDS, props("fields", array()));
        add("alarm-list", "알람 목록", 0, 20, THING_KINDS, props(
                "severities", array(), "states", array(), "maxRows", integer(5, 50), "showAck", bool()));
        add("floorplan", "평면도", 1, 1, List.of(SPACE), props("metricKey", str(), "heat", bool()));
        add("markdown", "메모", 0, 0, List.of(), props("content", Map.of("type", "string", "maxLength", 10_000)));
    }

    private WidgetTypes() {
    }

    public static List<WidgetType> all() {
        return List.copyOf(TYPES.values());
    }

    public static Optional<WidgetType> find(String type) {
        return Optional.ofNullable(type == null ? null : TYPES.get(type));
    }

    /** 이 종류의 대상이 측정 항목 계열(DEVICE_METRIC·SPACE_AGGREGATE)인가 */
    public static boolean metricKind(String kind) {
        return METRIC_KINDS.contains(kind);
    }

    private static void add(String type, String label, int min, int max, List<String> kinds, Map<String, Object> options) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", options);
        schema.put("additionalProperties", true);
        TYPES.put(type, new WidgetType(type, label, min, max, kinds, schema));
    }

    private static Map<String, Object> props(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> str() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> num() {
        return Map.of("type", "number");
    }

    private static Map<String, Object> bool() {
        return Map.of("type", "boolean");
    }

    private static Map<String, Object> array() {
        return Map.of("type", "array");
    }

    private static Map<String, Object> integer(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private static Map<String, Object> enumOf(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }
}
