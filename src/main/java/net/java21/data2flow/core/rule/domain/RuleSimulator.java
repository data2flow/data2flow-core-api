package net.java21.data2flow.core.rule.domain;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 규칙 시뮬레이션 참조 평가기(RUL-01.11, BR-RUL-23): 과거 측정값에 조건을 적용해 "이 규칙이었다면 언제 발생·해제됐을지"를 계산한다.
 * 알람·알림·상태는 만들지 않는다. 판정은 엔진과 같은 규칙을 따른다: 지속 시간은 측정 시각 기준(BR-RUL-03), 해제 기준(BR-RUL-04),
 * 연속 횟수(BR-RUL-05).
 *
 * <ul>
 *   <li>threshold: 참이 처음 된 측정 시각부터 for가 지나고 연속 repeat번이면 발생. 거짓이면 대기를 버린다. 발생 중에는 해제 기준으로만 해제</li>
 *   <li>rateOfChange: 창 앞쪽 값과의 차이가 delta 이상(방향 up·down·any)이면 발생, 아니면 해제</li>
 *   <li>noData: 이웃한 두 측정 사이가 window보다 길면 그 사이를 한 번의 발생으로 본다(마지막 측정 뒤도)</li>
 *   <li>group: 각 시각의 항목별 최신값으로 AND/OR(항목은 지속 시간 없이), 참이면 발생·거짓이면 해제</li>
 *   <li>공간 집계(spaceAvg·Max·Min): 모든 대상 기기의 값을 1분 단위로 모아 공간 하나(기기 ID -1)로 본다</li>
 * </ul>
 */
public final class RuleSimulator {

    /** 공간 집계 결과의 대상 표시 */
    public static final long SPACE_TARGET = -1L;

    private RuleSimulator() {
    }

    public record Point(Instant t, String metric, double v) {
    }

    /** 발생 구간. 해제되지 않았으면 clearedAt = null */
    public record Episode(long targetId, Instant raisedAt, Instant clearedAt, double triggerValue) {

        public long durationSec(Instant end) {
            Instant e = clearedAt == null ? end : clearedAt;
            return Math.max(0, Duration.between(raisedAt, e).getSeconds());
        }
    }

    public static List<Episode> run(JsonNode condition, Map<Long, List<Point>> seriesByDevice, Instant to) {
        String aggregate = condition.path("aggregate").asString("perDevice");
        Map<Long, List<Point>> series = seriesByDevice;
        if (!"group".equals(condition.path("kind").asString()) && aggregate.startsWith("space")) {
            series = Map.of(SPACE_TARGET, spaceSeries(seriesByDevice, condition.path("metric").asString(""),
                    aggregate.substring("space".length()).toLowerCase(Locale.ROOT)));
        }
        List<Episode> result = new ArrayList<>();
        for (Map.Entry<Long, List<Point>> e : series.entrySet()) {
            List<Point> points = new ArrayList<>(e.getValue());
            points.sort(Comparator.comparing(Point::t));
            result.addAll(switch (condition.path("kind").asString()) {
                case "threshold" -> threshold(e.getKey(), condition, points);
                case "rateOfChange" -> rate(e.getKey(), condition, points);
                case "noData" -> noData(e.getKey(), condition, points, to);
                case "group" -> group(e.getKey(), condition, points);
                default -> List.<Episode>of();
            });
        }
        result.sort(Comparator.comparing(Episode::raisedAt).thenComparing(Episode::targetId));
        return result;
    }

    static List<Point> spaceSeries(Map<Long, List<Point>> byDevice, String metric, String fn) {
        TreeMap<Instant, List<Double>> buckets = new TreeMap<>();
        for (List<Point> points : byDevice.values()) {
            for (Point p : points) {
                if (p.metric().equals(metric)) {
                    Instant bucket = Instant.ofEpochSecond(p.t().getEpochSecond() / 60 * 60);
                    buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(p.v());
                }
            }
        }
        List<Point> out = new ArrayList<>();
        buckets.forEach((t, values) -> {
            double v = switch (fn) {
                case "max" -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                case "min" -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                default -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            };
            out.add(new Point(t.plusSeconds(59), metric, v));
        });
        return out;
    }

    /** 임계값 판정(경계 포함 여부는 연산자대로, 같음 비교는 허용 오차 없음 TC-RUL-005) */
    public static boolean compare(String op, double v, JsonNode c) {
        double value = c.path("value").asDouble();
        return switch (op) {
            case ">" -> v > value;
            case ">=" -> v >= value;
            case "<" -> v < value;
            case "<=" -> v <= value;
            case "==", "=" -> v == value;
            case "!=" -> v != value;
            case "inside" -> v >= c.path("range").path(0).asDouble() && v <= c.path("range").path(1).asDouble();
            case "outside" -> v < c.path("range").path(0).asDouble() || v > c.path("range").path(1).asDouble();
            default -> false;
        };
    }

    /** 발생 중 해제 판정: 해제 기준이 있으면 그것(BR-RUL-04), 없으면 조건이 거짓일 때 */
    static boolean cleared(String op, double v, JsonNode c) {
        if (c.hasNonNull("clear")) {
            double clear = c.get("clear").asDouble();
            return switch (op) {
                case ">", ">=" -> v <= clear;
                case "<", "<=" -> v >= clear;
                default -> !compare(op, v, c);
            };
        }
        return !compare(op, v, c);
    }

    static List<Episode> threshold(long target, JsonNode c, List<Point> points) {
        String metric = c.path("metric").asString();
        String op = c.path("op").asString();
        Duration hold = c.hasNonNull("for") ? RuleCondition.parseDuration(c.get("for").asString()) : Duration.ZERO;
        int repeat = Math.max(1, c.path("repeat").asInt(1));
        List<Episode> out = new ArrayList<>();
        Instant pendingSince = null;
        int count = 0;
        Episode active = null;
        for (Point p : points) {
            if (!p.metric().equals(metric)) {
                continue;
            }
            if (active != null) {
                if (cleared(op, p.v(), c)) {
                    out.add(new Episode(target, active.raisedAt(), p.t(), active.triggerValue()));
                    active = null;
                    pendingSince = null;
                    count = 0;
                }
                continue;
            }
            if (compare(op, p.v(), c)) {
                if (pendingSince == null) {
                    pendingSince = p.t();
                }
                count++;
                if (count >= repeat && !p.t().isBefore(pendingSince.plus(hold == null ? Duration.ZERO : hold))) {
                    active = new Episode(target, p.t(), null, p.v());
                }
            } else {
                pendingSince = null;
                count = 0;
            }
        }
        if (active != null) {
            out.add(active);
        }
        return out;
    }

    static List<Episode> rate(long target, JsonNode c, List<Point> points) {
        String metric = c.path("metric").asString();
        Duration window = RuleCondition.parseDuration(c.path("window").asString("PT10M"));
        double delta = c.path("delta").asDouble();
        String direction = c.path("direction").asString("any");
        List<Point> mine = points.stream().filter(p -> p.metric().equals(metric)).toList();
        List<Episode> out = new ArrayList<>();
        Episode active = null;
        int start = 0;
        for (Point p : mine) {
            Instant from = p.t().minus(window);
            while (start < mine.size() && mine.get(start).t().isBefore(from)) {
                start++;
            }
            double change = p.v() - mine.get(start).v();
            boolean hit = switch (direction) {
                case "up" -> change >= delta;
                case "down" -> -change >= delta;
                default -> Math.abs(change) >= delta;
            };
            if (hit && active == null) {
                active = new Episode(target, p.t(), null, p.v());
            } else if (!hit && active != null) {
                out.add(new Episode(target, active.raisedAt(), p.t(), active.triggerValue()));
                active = null;
            }
        }
        if (active != null) {
            out.add(active);
        }
        return out;
    }

    static List<Episode> noData(long target, JsonNode c, List<Point> points, Instant to) {
        Duration window = RuleCondition.parseDuration(c.path("window").asString("PT30M"));
        String metric = c.path("metric").asString(null);
        List<Point> mine = metric == null ? points : points.stream().filter(p -> p.metric().equals(metric)).toList();
        List<Episode> out = new ArrayList<>();
        for (int i = 1; i <= mine.size(); i++) {
            Instant prev = mine.get(i - 1).t();
            Instant next = i < mine.size() ? mine.get(i).t() : to;
            if (next.isAfter(prev.plus(window))) {
                out.add(new Episode(target, prev.plus(window), i < mine.size() ? next : null, 0));
            }
        }
        return out;
    }

    static List<Episode> group(long target, JsonNode c, List<Point> points) {
        Map<String, Double> latest = new HashMap<>();
        List<Episode> out = new ArrayList<>();
        Episode active = null;
        for (Point p : points) {
            latest.put(p.metric(), p.v());
            boolean hit = groupTrue(c, latest);
            if (hit && active == null) {
                active = new Episode(target, p.t(), null, p.v());
            } else if (!hit && active != null) {
                out.add(new Episode(target, active.raisedAt(), p.t(), active.triggerValue()));
                active = null;
            }
        }
        if (active != null) {
            out.add(active);
        }
        return out;
    }

    static boolean groupTrue(JsonNode c, Map<String, Double> latest) {
        if ("group".equals(c.path("kind").asString())) {
            boolean and = "AND".equalsIgnoreCase(c.path("op").asString("AND"));
            boolean result = and;
            for (JsonNode item : c.path("items").values()) {
                boolean v = groupTrue(item, latest);
                result = and ? result && v : result || v;
            }
            return result;
        }
        if (!"threshold".equals(c.path("kind").asString())) {
            return false;
        }
        Double v = latest.get(c.path("metric").asString());
        return v != null && compare(c.path("op").asString(), v, c);
    }

    /** 기기별 발생 수·가장 긴 지속(초) */
    public static Map<Long, long[]> byTarget(List<Episode> episodes, Instant end) {
        Map<Long, long[]> m = new LinkedHashMap<>();
        for (Episode e : episodes) {
            long[] acc = m.computeIfAbsent(e.targetId(), k -> new long[2]);
            acc[0]++;
            acc[1] = Math.max(acc[1], e.durationSec(end));
        }
        return m;
    }
}
