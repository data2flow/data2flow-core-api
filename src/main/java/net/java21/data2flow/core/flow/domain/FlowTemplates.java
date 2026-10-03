package net.java21.data2flow.core.flow.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.flow.FlowDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 기본 플로우 템플릿(FLW-01.05, API-FLW-20, UC-FLW-01). 키는 simulator 키트·프리셋의 {@code suggestedFlowTemplates}·
 * {@code flowTemplates[].templateKey}와 같다(SIM-09.07·04.05): {@code hot-then-cool}("고온이면 냉방"), {@code co2-then-ventilate}("CO2 높으면 환기").
 * 템플릿으로 만든 플로우는 언제나 DRAFT다(BR-FLW-15).
 *
 * <p>매개변수는 화면 이름(spaceId·threshold·duration·targetTemperature·level)과 simulator 묶음 이름(thresholdC·durationMin·setpointC·
 * thresholdPpm·airconId·ventilatorId·temperatureSensorIds·co2SensorIds)을 함께 받는다.
 */
public final class FlowTemplates {

    public static final String HOT_THEN_COOL = "hot-then-cool";
    public static final String CO2_THEN_VENTILATE = "co2-then-ventilate";
    static final String DURATION_PATTERN = "^P(T(\\d+H)?(\\d+M)?(\\d+S)?)$";

    private FlowTemplates() {
    }

    /** 템플릿 목록 항목(API-FLW-20) */
    public record Template(String key, String name, String description, String category, Map<String, List<String>> required,
                           JsonNode paramsSchema, Map<String, Object> preview) {
    }

    public static List<Template> all(JsonMapper json) {
        return List.of(hotThenCool(json), co2ThenVentilate(json));
    }

    public static Optional<Template> find(String key, JsonMapper json) {
        return all(json).stream().filter(t -> t.key().equals(key)).findFirst();
    }

    private static Template hotThenCool(JsonMapper json) {
        ObjectNode schema = json.readValue("""
                {"type":"object","required":["spaceId","threshold","duration","targetTemperature"],"properties":{
                  "spaceId":{"type":"string","title":"대상 공간","x-widget":"space"},
                  "threshold":{"type":"number","minimum":15,"maximum":40,"default":27,"title":"기준 온도(℃)"},
                  "duration":{"type":"string","format":"duration","pattern":"%s","default":"PT5M","title":"지속 시간"},
                  "targetTemperature":{"type":"number","minimum":18,"maximum":30,"default":24,"title":"냉방 설정 온도(℃)"}}}"""
                .formatted(DURATION_PATTERN.replace("\\", "\\\\")), ObjectNode.class);
        return new Template(HOT_THEN_COOL, "고온이면 냉방", "공간 평균 온도가 기준을 넘은 상태가 지속되면 에어컨을 냉방으로 켭니다", "COMFORT",
                Map.of("metrics", List.of("temperature"), "capabilities", List.of("Thermostat")), schema,
                preview("온도 수신", "에어컨 냉방"));
    }

    private static Template co2ThenVentilate(JsonMapper json) {
        ObjectNode schema = json.readValue("""
                {"type":"object","required":["spaceId","threshold","duration","level"],"properties":{
                  "spaceId":{"type":"string","title":"대상 공간","x-widget":"space"},
                  "threshold":{"type":"number","minimum":600,"maximum":5000,"default":1000,"title":"CO2 기준(ppm)"},
                  "duration":{"type":"string","format":"duration","pattern":"%s","default":"PT5M","title":"지속 시간"},
                  "level":{"type":"integer","minimum":1,"maximum":3,"default":3,"title":"환기 단계"}}}"""
                .formatted(DURATION_PATTERN.replace("\\", "\\\\")), ObjectNode.class);
        return new Template(CO2_THEN_VENTILATE, "CO2 높으면 환기", "공간 평균 CO2가 기준을 넘은 상태가 지속되면 환기 장치를 켭니다", "COMFORT",
                Map.of("metrics", List.of("co2"), "capabilities", List.of("Ventilation")), schema,
                preview("CO2 수신", "환기 켜기"));
    }

    /** 갤러리 미리보기 축약 그래프 {nodes:[{id, type, name}], wires:[{from, port, to}]}(API-FLW-20) */
    private static Map<String, Object> preview(String trigger, String action) {
        return Map.of("nodes", List.of(Map.of("id", "n-trigger01", "type", "trigger.telemetry", "name", trigger),
                        Map.of("id", "n-average01", "type", "transform.aggregate", "name", "공간 평균"),
                        Map.of("id", "n-threshold1", "type", "condition.threshold", "name", "기준 초과 지속"),
                        Map.of("id", "n-control001", "type", "action.control", "name", action)),
                "wires", List.of(Map.of("from", "n-trigger01", "port", "out", "to", "n-average01"),
                        Map.of("from", "n-average01", "port", "out", "to", "n-threshold1"),
                        Map.of("from", "n-threshold1", "port", "true", "to", "n-control001")));
    }

    /** 대상 공간 ID(목록·권한 판정용). 장치 ID만 묶은 경우에도 공간이 있어야 한다 */
    public static String spaceId(JsonNode params) {
        JsonNode node = params == null ? null : params.get("spaceId");
        if (node == null || node.isNull() || node.asString("").isBlank()) {
            throw invalid("params.spaceId", "NotNull");
        }
        String value = node.asString("").strip();
        if (!value.matches("\\d{1,18}")) {
            throw invalid("params.spaceId", "Pattern");
        }
        return value;
    }

    /** 매개변수로 정의를 만든다(UC-FLW-01 노드 4개: 수신 → 평균 → 지속 임계값 → 제어) */
    public static JsonNode build(String key, JsonNode params, JsonMapper json) {
        String spaceId = spaceId(params);
        boolean cool = HOT_THEN_COOL.equals(key);
        String metric = cool ? "temperature" : "co2";
        double threshold = number(params, "threshold", cool ? "thresholdC" : "thresholdPpm", cool ? 27 : 1000, cool ? 15 : 600,
                cool ? 40 : 5000);
        String duration = duration(params);
        ObjectNode def = json.createObjectNode();
        def.put("schema", FlowDefinition.SCHEMA);
        ObjectNode mode = def.putObject("mode");
        mode.put("concurrency", "queued");
        mode.put("keyBy", "deviceId");
        mode.put("max", 10);
        def.putArray("variables");
        ArrayNode nodes = def.putArray("nodes");

        ObjectNode trigger = node(nodes, "n-trigger01", "trigger.telemetry", cool ? "온도 수신" : "CO2 수신", 80);
        ObjectNode tc = trigger.putObject("config");
        ObjectNode tt = tc.putObject("target");
        JsonNode sensors = params.get(cool ? "temperatureSensorIds" : "co2SensorIds");
        if (sensors != null && sensors.isArray() && !sensors.isEmpty()) {
            ArrayNode ids = tt.putArray("deviceIds");
            sensors.values().forEach(s -> ids.add(s.asString("")));
        } else {
            tt.put("spaceId", spaceId);
            tt.put("relation", "measures");
            tt.put("includeChildren", false);
        }
        tc.putArray("metrics").add(metric);

        ObjectNode avg = node(nodes, "n-average01", "transform.aggregate", "공간 평균", 320);
        ObjectNode ac = avg.putObject("config");
        ac.put("window", "PT1M");
        ac.put("fn", "avg");
        ac.put("groupBy", "space");
        ac.put("metric", metric);

        ObjectNode thr = node(nodes, "n-threshold1", "condition.threshold", "기준 초과 지속", 560);
        ObjectNode hc = thr.putObject("config");
        hc.put("metric", metric);
        hc.put("op", ">");
        hc.put("value", threshold);
        hc.put("for", duration);
        hc.put("clear", cool ? threshold - 1 : threshold - 100);

        ObjectNode act = node(nodes, "n-control001", "action.control", cool ? "에어컨 냉방" : "환기 켜기", 800);
        ObjectNode cc = act.putObject("config");
        ObjectNode target = cc.putObject("target");
        String deviceAlias = cool ? "airconId" : "ventilatorId";
        String capability = cool ? "Thermostat" : "Ventilation";
        if (params.hasNonNull(deviceAlias) && !params.get(deviceAlias).asString("").isBlank()) {
            target.putArray("deviceIds").add(params.get(deviceAlias).asString(""));
        } else {
            target.put("spaceId", spaceId);
            target.put("relation", "controls");
            target.put("capability", capability);
        }
        cc.put("capability", capability);
        cc.put("command", "set");
        ObjectNode args = cc.putObject("args");
        if (cool) {
            args.put("mode", "cool");
            args.put("targetTemperature", number(params, "targetTemperature", "setpointC", 24, 18, 30));
        } else {
            args.put("mode", "on");
            args.put("level", (int) number(params, "level", "level", 3, 1, 3));
        }
        cc.put("validitySeconds", 600);

        ArrayNode wires = def.putArray("wires");
        wire(wires, "n-trigger01", null, "n-average01");
        wire(wires, "n-average01", null, "n-threshold1");
        wire(wires, "n-threshold1", "true", "n-control001");
        return def;
    }

    private static ObjectNode node(ArrayNode nodes, String id, String type, String name, int x) {
        ObjectNode n = nodes.addObject();
        n.put("id", id);
        n.put("type", type);
        n.put("typeVersion", 1);
        n.put("name", name);
        ObjectNode p = n.putObject("position");
        p.put("x", x);
        p.put("y", 160);
        return n;
    }

    private static void wire(ArrayNode wires, String from, String port, String to) {
        ObjectNode w = wires.addObject();
        w.put("from", from);
        if (port != null) {
            w.put("port", port);
        }
        w.put("to", to);
    }

    private static double number(JsonNode params, String field, String alias, double fallback, double min, double max) {
        JsonNode node = params.hasNonNull(field) ? params.get(field) : params.get(alias);
        if (node == null || node.isNull()) {
            return fallback;
        }
        double value;
        if (node.isNumber()) {
            value = node.asDouble();
        } else {
            try {
                value = Double.parseDouble(node.asString(""));
            } catch (NumberFormatException ex) {
                throw invalid("params." + field, "Type");
            }
        }
        if (value < min || value > max) {
            throw invalid("params." + field, "Range");
        }
        return value;
    }

    private static String duration(JsonNode params) {
        JsonNode node = params.get("duration");
        if ((node == null || node.isNull()) && params.hasNonNull("durationMin")) {
            int minutes = params.get("durationMin").asInt(5);
            if (minutes < 1 || minutes > 1440) {
                throw invalid("params.durationMin", "Range");
            }
            return "PT" + minutes + "M";
        }
        if (node == null || node.isNull()) {
            return "PT5M";
        }
        String value = node.asString("");
        try {
            Duration d = Duration.parse(value);
            if (!value.matches(DURATION_PATTERN) || d.isNegative() || d.isZero() || d.toHours() > 24) {
                throw invalid("params.duration", "Range");
            }
        } catch (DateTimeParseException ex) {
            throw invalid("params.duration", "Pattern");
        }
        return value;
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
