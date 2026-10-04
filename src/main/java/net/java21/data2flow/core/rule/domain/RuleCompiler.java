package net.java21.data2flow.core.rule.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 규칙 → 표준 플로우 컴파일(BR-RUL-01, ADR-005). 규칙 버전 하나가 플로우 버전 하나가 된다(1:1). 결과는 결정적이다(같은 규칙 = 같은 JSON,
 * 골든 파일 {@code fixtures/rules/compiled/*.json}, TC-RUL-002).
 *
 * <pre>
 * trigger.telemetry(범위마다 1개) → [condition.timeWindow] → [transform.aggregate(공간 집계)] → 조건 노드 ─참→ action.alarm(raise)
 *                                                                                             └거짓→ action.alarm(clear, autoClear일 때)
 * </pre>
 * 조건 노드: threshold → {@code condition.threshold}(for·clear·repeat), rateOfChange → {@code condition.rateOfChange},
 * noData → {@code condition.noData}(timeout → 발생, resumed → 해제), anomaly → {@code detect.anomaly}(anomaly → 발생, normal → 해제),
 * group → {@code condition.composite}(AND/OR 항목을 그대로, 참/거짓). 알림은 플로우에 넣지 않는다: 알람 서비스가 상태 변화마다 정책을 평가해
 * 알림을 요청한다(EVT-RUL-03, core-api). 알람 키는 엔진이 {@code rule:{ruleId}:{기기 ID 또는 space-공간 ID}}로 만든다(AlarmKeys).
 */
public final class RuleCompiler {

    public static final String SCHEMA = "data2flow.flow-definition/v1";
    static final String RAISE = "n-alarm-raise";
    static final String CLEAR = "n-alarm-clear";
    static final String CONDITION = "n-condition";
    static final String TIME = "n-time-window";
    static final String AGGREGATE = "n-aggregate";
    static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private RuleCompiler() {
    }

    /**
     * 컴파일 입력.
     *
     * @param scopeIds 범위 ID(기기 ID·공간 ID·모델 ID·태그, 문자열)
     */
    public record Input(long ruleId, String name, String scopeType, List<String> scopeIds, boolean includeChildren, JsonNode condition,
                        JsonNode timeCondition, String severity, String titleTemplate, boolean autoClear) {
    }

    public static ObjectNode compile(Input in, JsonMapper json) {
        RuleCondition.Summary summary = RuleCondition.validate(in.condition());
        ObjectNode root = json.createObjectNode();
        root.put("schema", SCHEMA);
        ObjectNode mode = root.putObject("mode");
        mode.put("concurrency", "queued");
        mode.put("keyBy", summary.spaceTarget() ? "spaceId" : "deviceId");
        mode.put("max", 10);
        ArrayNode nodes = root.putArray("nodes");
        ArrayNode wires = root.putArray("wires");
        Set<String> metrics = new TreeSet<>(summary.metrics());

        List<String> triggers = triggers(in, metrics, nodes, json);
        String last = null;
        List<String> heads = triggers;
        if (in.timeCondition() != null && in.timeCondition().isObject() && !in.timeCondition().isEmpty()) {
            ObjectNode n = node(nodes, TIME, "condition.timeWindow", "시간 조건");
            ObjectNode cfg = n.putObject("config");
            if (in.timeCondition().path("days").isArray() && !in.timeCondition().path("days").isEmpty()) {
                ArrayNode days = cfg.putArray("days");
                for (JsonNode d : in.timeCondition().path("days").values()) {
                    days.add(DAYS.get(Math.floorMod(d.asInt() - 1, 7)));
                }
            }
            if (in.timeCondition().hasNonNull("spaceSchedule")) {
                ObjectNode schedule = cfg.putObject("spaceSchedule");
                schedule.put("match", in.timeCondition().get("spaceSchedule").asString().toLowerCase(Locale.ROOT));
            } else {
                cfg.put("from", in.timeCondition().path("from").asString("00:00"));
                cfg.put("to", in.timeCondition().path("to").asString("24:00"));
            }
            for (String h : heads) {
                wire(wires, h, null, TIME);
            }
            heads = List.of(TIME);
            last = "true";
        }
        JsonNode c = in.condition();
        String kind = c.path("kind").asString();
        String aggregate = c.path("aggregate").asString("perDevice");
        if (!"group".equals(kind) && aggregate.startsWith("space")) {
            ObjectNode n = node(nodes, AGGREGATE, "transform.aggregate", "공간 집계");
            ObjectNode cfg = n.putObject("config");
            cfg.put("window", "PT1M");
            cfg.put("fn", aggregate.substring("space".length()).toLowerCase(Locale.ROOT));
            cfg.put("groupBy", "space");
            if (c.hasNonNull("metric")) {
                cfg.put("metric", c.get("metric").asString());
            }
            for (String h : heads) {
                wire(wires, h, last, AGGREGATE);
            }
            heads = List.of(AGGREGATE);
            last = null;
        }
        String raisePort;
        String clearPort;
        ObjectNode cond;
        switch (kind) {
            case "threshold" -> {
                cond = node(nodes, CONDITION, "condition.threshold", RuleCondition.summary(c));
                cond.set("config", thresholdConfig(c, json));
                raisePort = "true";
                clearPort = "false";
            }
            case "rateOfChange" -> {
                cond = node(nodes, CONDITION, "condition.rateOfChange", RuleCondition.summary(c));
                ObjectNode cfg = cond.putObject("config");
                cfg.put("metric", c.path("metric").asString());
                cfg.put("window", iso(c.path("window").asString()));
                cfg.set("delta", c.get("delta"));
                cfg.put("direction", c.path("direction").asString("any"));
                raisePort = "true";
                clearPort = "false";
            }
            case "noData" -> {
                cond = node(nodes, CONDITION, "condition.noData", RuleCondition.summary(c));
                ObjectNode cfg = cond.putObject("config");
                copyText(c, cfg, "metric");
                cfg.put("window", iso(c.path("window").asString()));
                raisePort = "timeout";
                clearPort = "resumed";
            }
            case "anomaly" -> {
                cond = node(nodes, CONDITION, "detect.anomaly", RuleCondition.summary(c));
                ObjectNode cfg = cond.putObject("config");
                copyText(c, cfg, "metric");
                cfg.put("method", c.path("method").asString("zscore"));
                cfg.set("threshold", c.get("minScore"));
                copyText(c, cfg, "analysisId");
                raisePort = "anomaly";
                clearPort = "normal";
            }
            default -> {
                cond = node(nodes, CONDITION, "condition.composite", RuleCondition.summary(c));
                ObjectNode cfg = cond.putObject("config");
                cfg.put("op", c.path("op").asString("AND").toUpperCase(Locale.ROOT));
                cfg.set("items", normalize(c.path("items"), json));
                raisePort = "true";
                clearPort = "false";
            }
        }
        for (String h : heads) {
            wire(wires, h, last, CONDITION);
        }
        String targetBy = summary.spaceTarget() ? "space" : "device";
        ObjectNode raise = node(nodes, RAISE, "action.alarm", "알람 발생");
        ObjectNode raiseCfg = raise.putObject("config");
        raiseCfg.put("mode", "raise");
        raiseCfg.put("ruleId", Long.toString(in.ruleId()));
        raiseCfg.put("severity", in.severity());
        raiseCfg.put("title", in.titleTemplate());
        raiseCfg.put("targetBy", targetBy);
        if (summary.lowerIsWorse()) {
            raiseCfg.put("lowerIsWorse", true);
        }
        wire(wires, CONDITION, raisePort, RAISE);
        if (in.autoClear()) {
            ObjectNode clear = node(nodes, CLEAR, "action.alarm", "알람 해제");
            ObjectNode clearCfg = clear.putObject("config");
            clearCfg.put("mode", "clear");
            clearCfg.put("ruleId", Long.toString(in.ruleId()));
            clearCfg.put("targetBy", targetBy);
            wire(wires, CONDITION, clearPort, CLEAR);
        }
        return root;
    }

    private static List<String> triggers(Input in, Set<String> metrics, ArrayNode nodes, JsonMapper json) {
        List<String> ids = new java.util.ArrayList<>();
        switch (in.scopeType()) {
            case "DEVICE", "TAG" -> {
                ObjectNode n = node(nodes, "n-trigger-1", "trigger.telemetry", "측정값");
                ObjectNode cfg = n.putObject("config");
                ObjectNode target = cfg.putObject("target");
                ArrayNode list = target.putArray("DEVICE".equals(in.scopeType()) ? "deviceIds" : "tags");
                in.scopeIds().forEach(list::add);
                metrics(cfg, metrics);
                ids.add("n-trigger-1");
            }
            default -> {
                int i = 1;
                for (String id : in.scopeIds()) {
                    String nodeId = "n-trigger-" + i++;
                    ObjectNode n = node(nodes, nodeId, "trigger.telemetry", "측정값");
                    ObjectNode cfg = n.putObject("config");
                    ObjectNode target = cfg.putObject("target");
                    if ("SPACE".equals(in.scopeType())) {
                        target.put("spaceId", id);
                        target.put("relation", "measures");
                        target.put("includeChildren", in.includeChildren());
                    } else {
                        target.put("modelId", id);
                    }
                    metrics(cfg, metrics);
                    ids.add(nodeId);
                }
            }
        }
        return ids;
    }

    private static void metrics(ObjectNode cfg, Set<String> metrics) {
        if (!metrics.isEmpty()) {
            ArrayNode list = cfg.putArray("metrics");
            metrics.forEach(list::add);
        }
    }

    private static ObjectNode thresholdConfig(JsonNode c, JsonMapper json) {
        ObjectNode cfg = json.createObjectNode();
        cfg.put("metric", c.path("metric").asString());
        String op = c.path("op").asString();
        cfg.put("op", "=".equals(op) ? "==" : op);
        if (c.has("range")) {
            cfg.set("range", c.get("range"));
        } else {
            cfg.set("value", c.get("value"));
        }
        if (c.hasNonNull("for")) {
            cfg.put("for", iso(c.get("for").asString()));
        }
        if (c.hasNonNull("clear")) {
            cfg.set("clear", c.get("clear"));
        }
        if (c.hasNonNull("repeat") && c.get("repeat").asInt() > 1) {
            cfg.put("repeat", c.get("repeat").asInt());
        }
        return cfg;
    }

    /** 묶음 항목: 연산자 '='를 '=='로, 키 순서를 고정한다 */
    private static ArrayNode normalize(JsonNode items, JsonMapper json) {
        ArrayNode out = json.createArrayNode();
        for (JsonNode item : items.values()) {
            if ("group".equals(item.path("kind").asString())) {
                ObjectNode g = out.addObject();
                g.put("kind", "group");
                g.put("op", item.path("op").asString("AND").toUpperCase(Locale.ROOT));
                g.set("items", normalize(item.path("items"), json));
                continue;
            }
            ObjectNode n = out.addObject();
            n.put("kind", item.path("kind").asString());
            for (String field : List.of("metric", "op", "value", "range", "for", "clear", "repeat", "window", "delta", "direction",
                    "minScore", "aggregate")) {
                JsonNode v = item.get(field);
                if (v != null && !v.isNull()) {
                    if ("op".equals(field) && "=".equals(v.asString())) {
                        n.put("op", "==");
                    } else if ("for".equals(field) || "window".equals(field)) {
                        n.put(field, iso(v.asString()));
                    } else {
                        n.set(field, v);
                    }
                }
            }
        }
        return out;
    }

    private static ObjectNode node(ArrayNode nodes, String id, String type, String name) {
        ObjectNode n = nodes.addObject();
        n.put("id", id);
        n.put("type", type);
        n.put("name", name);
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

    /** 기간을 ISO-8601({@code PT5M})로 고정한다(노드 설정 스키마 형식) */
    static String iso(String raw) {
        java.time.Duration d = RuleCondition.parseDuration(raw);
        return d == null ? raw : d.toString();
    }

    private static void copyText(JsonNode from, ObjectNode to, String field) {
        if (from.hasNonNull(field)) {
            to.put(field, from.get(field).asString());
        }
    }
}
