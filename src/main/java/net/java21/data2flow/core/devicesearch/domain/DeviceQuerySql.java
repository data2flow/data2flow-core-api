package net.java21.data2flow.core.devicesearch.domain;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.device.domain.SqlCondition;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 검색식 AST → SQL 조건(BR-DEV-35). 기기 별칭 {@code d}와 조직 매개변수 {@code :org}만 쓰므로 기기 목록·동적 그룹 어디에든 붙일 수 있다.
 * 값은 모두 바인드 매개변수({@code :dq0…})로 넘겨 SQL 주입이 없다. 사용자 권한 범위 조건은 호출하는 목록 쿼리가 따로 붙인다.
 */
public final class DeviceQuerySql {

    private static final String STATE = "(SELECT %s FROM data2flow_pipeline.device_state st WHERE st.device_id = d.id AND st.organization_id = d.organization_id)";

    private final Map<String, Object> params = new LinkedHashMap<>();
    private final Instant now;
    private int seq;

    private DeviceQuerySql(Instant now) {
        this.now = now;
    }

    /** now가 null이면 기간 값은 DB 시각 기준({@code now() - interval}, 동적 그룹 재계산용) */
    public static SqlCondition toSql(DeviceQuery query, Instant now) {
        DeviceQuerySql g = new DeviceQuerySql(now);
        String sql = g.node(query);
        return new SqlCondition(sql, g.params);
    }

    private String node(DeviceQuery q) {
        return switch (q) {
            case DeviceQuery.Or or -> join(or.items(), " OR ");
            case DeviceQuery.And and -> join(and.items(), " AND ");
            case DeviceQuery.Not not -> "NOT (" + node(not.item()) + ")";
            case DeviceQuery.Cmp cmp -> cmp(cmp);
        };
    }

    private String join(List<DeviceQuery> items, String sep) {
        List<String> parts = new ArrayList<>();
        for (DeviceQuery i : items) {
            parts.add("(" + node(i) + ")");
        }
        return String.join(sep, parts);
    }

    private String cmp(DeviceQuery.Cmp c) {
        String f = c.field();
        if (f.startsWith("metric.")) {
            return metric(c, f.substring("metric.".length()));
        }
        if (f.startsWith("attr.")) {
            return attribute(c, f.substring("attr.".length()));
        }
        return switch (f) {
            case "name" -> text("d.name", c);
            case "externalid" -> text("d.external_id", c);
            case "status" -> upperIn("d.status", c);
            case "kind" -> upperIn("d.kind", c);
            case "connectivity" -> upperIn("coalesce(" + STATE.formatted("st.connectivity") + ", 'UNKNOWN')", c);
            case "model" -> exists(c, "SELECT 1 FROM data2flow_core.device_models m WHERE m.id = d.model_id AND m.organization_id = d.organization_id"
                    + " AND (upper(m.code) = upper(%1$s) OR m.name = %1$s OR CAST(m.id AS text) = %1$s)");
            case "source" -> exists(c, "SELECT 1 FROM data2flow_core.data_sources s WHERE s.id = d.source_id AND s.organization_id = d.organization_id"
                    + " AND (s.code = %1$s OR s.name = %1$s OR CAST(s.id AS text) = %1$s)");
            case "tag" -> exists(c, "SELECT 1 FROM data2flow_core.device_tags t WHERE t.device_id = d.id AND lower(t.tag) = lower(%1$s)");
            case "group" -> exists(c, "SELECT 1 FROM data2flow_core.device_group_members gm JOIN data2flow_core.device_groups g"
                    + " ON g.id = gm.group_id AND g.organization_id = gm.organization_id"
                    + " WHERE gm.device_id = d.id AND gm.organization_id = d.organization_id AND (g.name = %1$s OR CAST(g.id AS text) = %1$s)");
            case "space" -> space(c);
            case "battery" -> number(STATE.formatted("st.battery"), c);
            case "rssi" -> number(STATE.formatted("st.rssi"), c);
            case "snr" -> number(STATE.formatted("st.snr"), c);
            case "lastseen" -> time(STATE.formatted("st.last_seen_at"), c);
            case "virtual" -> bool("d.is_virtual", c);
            default -> throw new DeviceQueryException(c.column(), "모르는 필드: " + f);
        };
    }

    /** 공간: = 은 그 공간만, in 은 하위 공간 포함(이름 또는 ID) */
    private String space(DeviceQuery.Cmp c) {
        String any = anyOf(c, "p.name = %1$s OR CAST(p.id AS text) = %1$s");
        String inner = "in".equals(c.op())
                ? "d.space_id IN (SELECT ch.id FROM data2flow_core.spaces ch JOIN data2flow_core.spaces p ON ch.path LIKE p.path || '%%'"
                  + " AND p.organization_id = ch.organization_id WHERE ch.organization_id = :org AND (" + any + "))"
                : "d.space_id IN (SELECT p.id FROM data2flow_core.spaces p WHERE p.organization_id = :org AND (" + any + "))";
        inner = inner.replace("%%", "%");
        return "!=".equals(c.op()) ? "(d.space_id IS NULL OR NOT " + inner + ")" : inner;
    }

    private String exists(DeviceQuery.Cmp c, String template) {
        List<String> ors = new ArrayList<>();
        for (int i = 0; i < c.values().size(); i++) {
            ors.add("EXISTS (" + template.formatted(bind(stringValue(c, i))) + ")");
        }
        String any = String.join(" OR ", ors);
        return "!=".equals(c.op()) ? "NOT (" + any + ")" : "(" + any + ")";
    }

    private String anyOf(DeviceQuery.Cmp c, String template) {
        List<String> ors = new ArrayList<>();
        for (int i = 0; i < c.values().size(); i++) {
            ors.add("(" + template.formatted(bind(stringValue(c, i))) + ")");
        }
        return String.join(" OR ", ors);
    }

    private String text(String column, DeviceQuery.Cmp c) {
        return switch (c.op()) {
            case "~" -> column + " ILIKE " + bind("%" + like(stringValue(c, 0)) + "%") + " ESCAPE '\\'";
            case "in" -> "lower(" + column + ") IN (" + list(c, v -> v.toLowerCase(Locale.ROOT)) + ")";
            case "!=" -> "lower(" + column + ") <> lower(" + bind(stringValue(c, 0)) + ")";
            default -> "lower(" + column + ") = lower(" + bind(stringValue(c, 0)) + ")";
        };
    }

    private String upperIn(String column, DeviceQuery.Cmp c) {
        String in = column + " IN (" + list(c, v -> v.toUpperCase(Locale.ROOT)) + ")";
        return "!=".equals(c.op()) ? "NOT (" + in + ")" : in;
    }

    private String number(String expr, DeviceQuery.Cmp c) {
        return expr + " " + sqlOp(c.op()) + " " + bind(numberValue(c, 0));
    }

    private String time(String expr, DeviceQuery.Cmp c) {
        String kind = c.kinds().getFirst();
        Instant at;
        if ("DURATION".equals(kind) && now == null) {
            return expr + " " + sqlOp(c.op()) + " now() - CAST(" + bind(duration(c.values().getFirst()).toSeconds() + " seconds") + " AS interval)";
        }
        if ("DURATION".equals(kind)) {
            at = now.minus(duration(c.values().getFirst()));
        } else if ("STRING".equals(kind)) {
            try {
                at = Instant.parse(c.values().getFirst());
            } catch (RuntimeException ex) {
                throw new DeviceQueryException(c.column(), "시각은 ISO-8601이나 기간(24h)입니다");
            }
        } else {
            throw new DeviceQueryException(c.column(), "시각은 ISO-8601이나 기간(24h)입니다");
        }
        return expr + " " + sqlOp(c.op()) + " " + bind(Pg.ts(at));
    }

    private String bool(String column, DeviceQuery.Cmp c) {
        if (!"BOOLEAN".equals(c.kinds().getFirst())) {
            throw new DeviceQueryException(c.column(), "true 또는 false가 필요합니다");
        }
        boolean v = Boolean.parseBoolean(c.values().getFirst());
        return column + ("!=".equals(c.op()) ? " <> " : " = ") + bind(v);
    }

    /** 최근값(device_state.latest) 비교. 숫자 값이면 숫자로, 아니면 글자로 */
    private String metric(DeviceQuery.Cmp c, String key) {
        String k = bind(key);
        if ("NUMBER".equals(c.kinds().getFirst())) {
            String expr = STATE.formatted("CASE WHEN jsonb_typeof(st.latest -> " + k + " -> 'v') = 'number' THEN CAST(st.latest -> " + k
                    + " ->> 'v' AS numeric) END");
            return expr + " " + sqlOp(c.op()) + " " + bind(numberValue(c, 0));
        }
        String expr = STATE.formatted("st.latest -> " + k + " ->> 'v'");
        return scalarText(expr, c);
    }

    private String attribute(DeviceQuery.Cmp c, String key) {
        String k = bind(key);
        String cond;
        if ("NUMBER".equals(c.kinds().getFirst())) {
            cond = "CASE WHEN jsonb_typeof(a.value) = 'number' THEN CAST(a.value #>> '{}' AS numeric) END " + sqlOp(c.op()) + " "
                    + bind(numberValue(c, 0));
        } else {
            cond = scalarText("a.value #>> '{}'", c);
        }
        return "EXISTS (SELECT 1 FROM data2flow_core.device_attributes a WHERE a.device_id = d.id AND a.organization_id = d.organization_id"
                + " AND a.key = " + k + " AND " + cond + ")";
    }

    private String scalarText(String expr, DeviceQuery.Cmp c) {
        return switch (c.op()) {
            case "~" -> expr + " ILIKE " + bind("%" + like(c.values().getFirst()) + "%") + " ESCAPE '\\'";
            case "in" -> expr + " IN (" + list(c, v -> v) + ")";
            case "=", "!=", "<", "<=", ">", ">=" -> expr + " " + sqlOp(c.op()) + " " + bind(c.values().getFirst());
            default -> throw new DeviceQueryException(c.column(), "쓸 수 없는 연산자");
        };
    }

    private String list(DeviceQuery.Cmp c, java.util.function.UnaryOperator<String> f) {
        List<String> binds = new ArrayList<>();
        for (int i = 0; i < c.values().size(); i++) {
            binds.add(bind(f.apply(stringValue(c, i))));
        }
        return String.join(", ", binds);
    }

    private static String stringValue(DeviceQuery.Cmp c, int i) {
        return c.values().get(i);
    }

    private static BigDecimal numberValue(DeviceQuery.Cmp c, int i) {
        if (!"NUMBER".equals(c.kinds().get(i))) {
            throw new DeviceQueryException(c.column(), "숫자가 필요합니다");
        }
        return new BigDecimal(c.values().get(i));
    }

    private static Duration duration(String v) {
        long n = Long.parseLong(v.substring(0, v.length() - 1));
        return switch (v.charAt(v.length() - 1)) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
    }

    private static String sqlOp(String op) {
        return "!=".equals(op) ? "<>" : op;
    }

    private static String like(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String bind(Object value) {
        String name = "dq" + seq++;
        params.put(name, value);
        return ":" + name;
    }
}
