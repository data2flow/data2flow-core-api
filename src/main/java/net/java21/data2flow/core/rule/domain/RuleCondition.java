package net.java21.data2flow.core.rule.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 규칙 조건 형식(RUL domain-model §2.1, rule-condition.schema.json)과 검증(BR-RUL-04 히스테리시스, BR-RUL-21 조건 10개·중첩 2단계).
 *
 * <pre>{@code
 * {"kind":"group","op":"AND","items":[
 *    {"kind":"threshold","metric":"co2","op":">","value":1000,"for":"PT5M","clear":900,"repeat":1,"aggregate":"perDevice"},
 *    {"kind":"threshold","metric":"occupancy","op":"==","value":1}]}
 * }</pre>
 * kind: {@code threshold}(op &gt; &gt;= &lt; &lt;= == != outside inside, inside·outside는 range[min,max]), {@code rateOfChange}(metric, window,
 * delta, direction up|down|any), {@code noData}(window, metric?), {@code anomaly}(minScore, analysisId?), {@code group}(op AND|OR, items).
 * aggregate: perDevice(기본)·spaceAvg·spaceMax·spaceMin·any·all. 위반은 400 RULE_CONDITION_INVALID(errors[].field = condition.…).
 */
public final class RuleCondition {

    public static final Set<String> KINDS = Set.of("threshold", "rateOfChange", "noData", "anomaly", "group");
    public static final Set<String> OPS = Set.of(">", ">=", "<", "<=", "==", "=", "!=", "outside", "inside");
    public static final Set<String> AGGREGATES = Set.of("perDevice", "spaceAvg", "spaceMax", "spaceMin", "any", "all");
    public static final Set<String> DIRECTIONS = Set.of("up", "down", "any");
    static final Pattern METRIC = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    static final Pattern SHORT_DURATION = Pattern.compile("(\\d{1,5})([smhd])");

    private RuleCondition() {
    }

    /** 검증 결과: 쓰인 측정 항목(트리거 대상)과 잎 조건 수 */
    public record Summary(Set<String> metrics, int leaves, boolean spaceTarget, boolean lowerIsWorse) {
    }

    /** 검증하고 요약을 돌려준다. 위반이 있으면 RULE_CONDITION_INVALID */
    public static Summary validate(JsonNode condition) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        Set<String> metrics = new LinkedHashSet<>();
        int[] leaves = {0};
        boolean[] space = {false};
        boolean[] lower = {false};
        if (condition == null || !condition.isObject()) {
            errors.add(new FieldErrorDetail("condition", "NotNull", null));
        } else {
            walk(condition, "condition", 1, errors, metrics, leaves, space, lower);
        }
        if (leaves[0] > RuleLimits.MAX_CONDITIONS) {
            errors.add(new FieldErrorDetail("condition", "LIMIT", "조건은 " + RuleLimits.MAX_CONDITIONS + "개까지입니다"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(RuleErrorCode.RULE_CONDITION_INVALID, errors, detail(errors));
        }
        return new Summary(metrics, leaves[0], space[0], lower[0]);
    }

    private static String detail(List<FieldErrorDetail> errors) {
        FieldErrorDetail first = errors.getFirst();
        return first.message() == null ? first.field() : first.field() + " " + first.message();
    }

    private static void walk(JsonNode n, String path, int depth, List<FieldErrorDetail> errors, Set<String> metrics, int[] leaves,
                             boolean[] space, boolean[] lower) {
        String kind = n.path("kind").asString("");
        if (!KINDS.contains(kind)) {
            errors.add(new FieldErrorDetail(path + ".kind", "INVALID", kind));
            return;
        }
        if ("group".equals(kind)) {
            if (depth > RuleLimits.MAX_DEPTH) {
                errors.add(new FieldErrorDetail(path, "DEPTH", "중첩은 " + RuleLimits.MAX_DEPTH + "단계까지입니다"));
                return;
            }
            String op = n.path("op").asString("").toUpperCase(Locale.ROOT);
            if (!op.equals("AND") && !op.equals("OR")) {
                errors.add(new FieldErrorDetail(path + ".op", "INVALID", op));
            }
            JsonNode items = n.path("items");
            if (!items.isArray() || items.isEmpty()) {
                errors.add(new FieldErrorDetail(path + ".items", "NotEmpty", null));
                return;
            }
            int i = 0;
            for (JsonNode item : items.values()) {
                walk(item, path + ".items[" + i++ + "]", depth + 1, errors, metrics, leaves, space, lower);
            }
            return;
        }
        leaves[0]++;
        String aggregate = n.path("aggregate").asString("perDevice");
        if (!AGGREGATES.contains(aggregate)) {
            errors.add(new FieldErrorDetail(path + ".aggregate", "INVALID", aggregate));
        } else if (!"perDevice".equals(aggregate)) {
            space[0] = true;
        }
        switch (kind) {
            case "threshold" -> threshold(n, path, errors, metrics, lower);
            case "rateOfChange" -> {
                metric(n, path, errors, metrics, true);
                duration(n, "window", path, errors, true);
                if (!n.path("delta").isNumber() || n.path("delta").asDouble() <= 0) {
                    errors.add(new FieldErrorDetail(path + ".delta", "Positive", null));
                }
                String direction = n.path("direction").asString("any");
                if (!DIRECTIONS.contains(direction)) {
                    errors.add(new FieldErrorDetail(path + ".direction", "INVALID", direction));
                }
            }
            case "noData" -> {
                metric(n, path, errors, metrics, false);
                Duration window = duration(n, "window", path, errors, true);
                if (window != null && window.toMinutes() < 1) {
                    errors.add(new FieldErrorDetail(path + ".window", "Min", "1분 이상"));
                }
            }
            case "anomaly" -> {
                metric(n, path, errors, metrics, false);
                if (!n.path("minScore").isNumber() || n.path("minScore").asDouble() <= 0) {
                    errors.add(new FieldErrorDetail(path + ".minScore", "Positive", null));
                }
            }
            default -> errors.add(new FieldErrorDetail(path + ".kind", "INVALID", kind));
        }
    }

    private static void threshold(JsonNode n, String path, List<FieldErrorDetail> errors, Set<String> metrics, boolean[] lower) {
        metric(n, path, errors, metrics, true);
        String op = n.path("op").asString("");
        if (!OPS.contains(op)) {
            errors.add(new FieldErrorDetail(path + ".op", "INVALID", op));
            return;
        }
        if (op.equals("inside") || op.equals("outside")) {
            JsonNode range = n.path("range");
            if (!range.isArray() || range.size() != 2 || !range.get(0).isNumber() || !range.get(1).isNumber()
                    || range.get(0).asDouble() > range.get(1).asDouble()) {
                errors.add(new FieldErrorDetail(path + ".range", "INVALID", "[min, max]"));
            }
        } else if (!n.path("value").isNumber()) {
            errors.add(new FieldErrorDetail(path + ".value", "NotNull", null));
        }
        duration(n, "for", path, errors, false);
        if (n.has("repeat") && (!n.get("repeat").isIntegralNumber() || n.get("repeat").asInt() < 1 || n.get("repeat").asInt() > 100)) {
            errors.add(new FieldErrorDetail(path + ".repeat", "Range", "1~100"));
        }
        if (n.hasNonNull("clear")) {
            if (!n.get("clear").isNumber()) {
                errors.add(new FieldErrorDetail(path + ".clear", "INVALID", null));
                return;
            }
            if (!hysteresisOk(op, n.path("value").asDouble(), n.get("clear").asDouble())) {
                errors.add(new FieldErrorDetail(path + ".clear", "HYSTERESIS", "해제 기준은 발생 기준보다 덜 엄격해야 합니다"));
            }
        }
        if (op.startsWith("<")) {
            lower[0] = true;
        }
    }

    /**
     * BR-RUL-04: 해제 기준은 발생 기준보다 같은 방향으로 덜 엄격해야 한다. {@code >}·{@code >=}는 clear ≤ value, {@code <}·{@code <=}는
     * clear ≥ value. 같음·범위 연산자에는 해제 기준을 쓰지 않는다.
     */
    public static boolean hysteresisOk(String op, double value, double clear) {
        return switch (op) {
            case ">", ">=" -> clear <= value;
            case "<", "<=" -> clear >= value;
            default -> false;
        };
    }

    private static void metric(JsonNode n, String path, List<FieldErrorDetail> errors, Set<String> metrics, boolean required) {
        String metric = n.path("metric").asString(null);
        if (metric == null || metric.isBlank()) {
            if (required) {
                errors.add(new FieldErrorDetail(path + ".metric", "NotBlank", null));
            }
            return;
        }
        if (!METRIC.matcher(metric).matches()) {
            errors.add(new FieldErrorDetail(path + ".metric", "INVALID", metric));
            return;
        }
        metrics.add(metric);
    }

    private static Duration duration(JsonNode n, String field, String path, List<FieldErrorDetail> errors, boolean required) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            if (required) {
                errors.add(new FieldErrorDetail(path + "." + field, "NotNull", null));
            }
            return null;
        }
        Duration d = parseDuration(v.asString(""));
        if (d == null || d.isNegative() || d.isZero() || d.compareTo(Duration.ofDays(7)) > 0) {
            errors.add(new FieldErrorDetail(path + "." + field, "INVALID", "ISO-8601 기간(예: PT5M), 7일 이하"));
            return null;
        }
        return d;
    }

    /** ISO-8601({@code PT5M}) 또는 줄임({@code 5m}·{@code 30s}·{@code 2h}·{@code 1d}). 틀리면 null */
    public static Duration parseDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var m = SHORT_DURATION.matcher(raw.strip());
        if (m.matches()) {
            long v = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "s" -> Duration.ofSeconds(v);
                case "m" -> Duration.ofMinutes(v);
                case "h" -> Duration.ofHours(v);
                default -> Duration.ofDays(v);
            };
        }
        try {
            return Duration.parse(raw.strip());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 간단한 요약 문장(API-RUL-01 conditionSummary): {@code co2 > 1000 (5분)} */
    public static String summary(JsonNode n) {
        if (n == null || !n.isObject()) {
            return "";
        }
        String kind = n.path("kind").asString("");
        return switch (kind) {
            case "group" -> {
                List<String> parts = new ArrayList<>();
                for (JsonNode item : n.path("items").values()) {
                    String s = summary(item);
                    parts.add(item.path("kind").asString("").equals("group") ? "(" + s + ")" : s);
                }
                yield String.join(" " + n.path("op").asString("AND").toUpperCase(Locale.ROOT) + " ", parts);
            }
            case "threshold" -> {
                String op = n.path("op").asString("");
                String base = op.equals("inside") || op.equals("outside")
                        ? n.path("metric").asString("") + " " + op + " [" + num(n.path("range").path(0)) + ", " + num(n.path("range").path(1)) + "]"
                        : n.path("metric").asString("") + " " + op + " " + num(n.path("value"));
                Duration d = parseDuration(n.path("for").asString(null));
                yield d == null ? base : base + " (" + human(d) + ")";
            }
            case "rateOfChange" -> n.path("metric").asString("") + " Δ" + num(n.path("delta")) + " / "
                    + human(parseDuration(n.path("window").asString("PT10M")));
            case "noData" -> "noData " + human(parseDuration(n.path("window").asString("PT30M")));
            case "anomaly" -> "anomaly ≥ " + num(n.path("minScore"));
            default -> kind;
        };
    }

    static String num(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) {
            return "";
        }
        double d = v.asDouble();
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    static String human(Duration d) {
        if (d == null) {
            return "";
        }
        if (d.toDays() > 0 && d.toHours() % 24 == 0) {
            return d.toDays() + "d";
        }
        if (d.toHours() > 0 && d.toMinutes() % 60 == 0) {
            return d.toHours() + "h";
        }
        if (d.toMinutes() > 0 && d.getSeconds() % 60 == 0) {
            return d.toMinutes() + "m";
        }
        return d.getSeconds() + "s";
    }
}
