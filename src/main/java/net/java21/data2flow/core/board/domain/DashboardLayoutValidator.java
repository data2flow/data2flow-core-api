package net.java21.data2flow.core.board.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.board.domain.WidgetTypes.WidgetType;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 대시보드 배치·변수 검사(DSH-04.01, BR-DSH-18, TC-DSH-029). 웹 편집기와 같은 규칙을 서버에서 한 번 더 본다.
 * <ul>
 *   <li>격자는 24열. 위젯은 {@code x ≥ 0, y ≥ 0, w ≥ 1, h ≥ 1, x + w ≤ 24, h ≤ 48}이고 서로 겹치지 않는다. 최대 40개</li>
 *   <li>위젯 id는 1~40자이고 대시보드 안에서 고유하다</li>
 *   <li>종류는 {@link WidgetTypes}에 있는 것만(없으면 WIDGET_TYPE_UNSUPPORTED). 대상 수·종류가 규칙 밖이면 WIDGET_QUERY_INVALID.
 *       위젯당 대상 시계열은 최대 20개(BR-DSH-18)</li>
 *   <li>대상의 deviceId·spaceId는 ID 문자열(숫자) 또는 정의한 변수 참조 {@code ${이름}}(DSH-04.05)</li>
 *   <li>옵션은 종류별 스키마의 알려진 속성만 형식을 본다(모르는 속성은 그대로 둔다)</li>
 * </ul>
 * 배치 오류는 400 DASHBOARD_LAYOUT_INVALID, {@code errors[{field, code: OVERLAP|OUT_OF_GRID|TOO_MANY_WIDGETS|DUPLICATE_ID|…}]}.
 */
public final class DashboardLayoutValidator {

    public static final int COLUMNS = 24;
    public static final int MAX_WIDGETS = 40;
    public static final int MAX_HEIGHT = 48;
    public static final int MAX_TARGETS = 20;
    public static final int MAX_VARIABLES = 10;

    private static final Pattern WIDGET_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Pattern VAR_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,30}");
    private static final Pattern VAR_REF = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_]{0,30})}");
    private static final Pattern ID = Pattern.compile("\\d{1,18}");
    private static final Pattern METRIC = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Set<String> VAR_TYPES = Set.of("SPACE", "DEVICE", "METRIC");
    private static final Set<String> AGGS = Set.of("avg", "min", "max", "sum", "last", "count");

    private DashboardLayoutValidator() {
    }

    /** 변수 이름 집합을 돌려준다. 틀리면 DASHBOARD_LAYOUT_INVALID */
    public static Set<String> validateVariables(JsonNode variables) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        Set<String> names = new HashSet<>();
        if (variables != null && !variables.isNull()) {
            if (!variables.isArray()) {
                throw layout(List.of(new FieldErrorDetail("variables", "TYPE", "array")));
            }
            if (variables.size() > MAX_VARIABLES) {
                errors.add(new FieldErrorDetail("variables", "TOO_MANY_VARIABLES", "0~" + MAX_VARIABLES));
            }
            int i = 0;
            for (JsonNode v : variables.values()) {
                String name = v.path("name").asString("");
                if (!VAR_NAME.matcher(name).matches() || !names.add(name)) {
                    errors.add(new FieldErrorDetail("variables[" + i + "].name", "INVALID", name));
                }
                if (!VAR_TYPES.contains(v.path("type").asString(""))) {
                    errors.add(new FieldErrorDetail("variables[" + i + "].type", "INVALID", String.join("|", VAR_TYPES)));
                }
                i++;
            }
        }
        if (!errors.isEmpty()) {
            throw layout(errors);
        }
        return names;
    }

    /** 배치 전체를 검사한다 */
    public static void validate(JsonNode layout, Set<String> variableNames) {
        if (layout == null || !layout.isObject() || !layout.path("widgets").isArray()) {
            throw layout(List.of(new FieldErrorDetail("layout.widgets", "TYPE", "array")));
        }
        JsonNode widgets = layout.get("widgets");
        if (widgets.size() > MAX_WIDGETS) {
            throw layout(List.of(new FieldErrorDetail("layout.widgets", "TOO_MANY_WIDGETS", "0~" + MAX_WIDGETS)));
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        List<int[]> boxes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int i = 0;
        for (JsonNode w : widgets.values()) {
            String f = "layout.widgets[" + i + "]";
            String id = w.path("id").asString("");
            if (!WIDGET_ID.matcher(id).matches()) {
                errors.add(new FieldErrorDetail(f + ".id", "INVALID", id));
            } else if (!ids.add(id)) {
                errors.add(new FieldErrorDetail(f + ".id", "DUPLICATE_ID", id));
            }
            int x = intOf(w, "x");
            int y = intOf(w, "y");
            int width = intOf(w, "w");
            int height = intOf(w, "h");
            if (x < 0 || y < 0 || width < 1 || height < 1 || x + width > COLUMNS || height > MAX_HEIGHT) {
                errors.add(new FieldErrorDetail(f, "OUT_OF_GRID", "x+w≤" + COLUMNS));
            } else {
                int[] box = {x, y, width, height};
                for (int[] other : boxes) {
                    if (overlap(box, other)) {
                        errors.add(new FieldErrorDetail(f, "OVERLAP", null));
                        break;
                    }
                }
                boxes.add(box);
            }
            i++;
        }
        if (!errors.isEmpty()) {
            throw layout(errors);
        }
        i = 0;
        for (JsonNode w : widgets.values()) {
            validateWidget(w, "layout.widgets[" + i + "]", variableNames);
            i++;
        }
    }

    /** 위젯 하나의 종류·대상·옵션 */
    public static WidgetType validateWidget(JsonNode w, String field, Set<String> variableNames) {
        String type = w.path("type").asString("");
        WidgetType wt = WidgetTypes.find(type).orElseThrow(() -> new BusinessException(BoardErrorCode.WIDGET_TYPE_UNSUPPORTED,
                List.of(new FieldErrorDetail(field + ".type", "UNSUPPORTED", type))));
        List<FieldErrorDetail> errors = new ArrayList<>();
        JsonNode targets = w.get("targets");
        int count = targets == null || targets.isNull() ? 0 : targets.isArray() ? targets.size() : -1;
        if (count < 0 || count < wt.minTargets() || count > Math.min(wt.maxTargets(), MAX_TARGETS)) {
            errors.add(new FieldErrorDetail(field + ".targets", "COUNT", wt.minTargets() + "~" + wt.maxTargets()));
        } else if (count > 0) {
            int t = 0;
            for (JsonNode target : targets.values()) {
                checkTarget(target, field + ".targets[" + t + "]", wt, variableNames, errors);
                t++;
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(BoardErrorCode.WIDGET_QUERY_INVALID, errors);
        }
        JsonNode options = w.get("options");
        if (options != null && !options.isNull()) {
            List<FieldErrorDetail> optionErrors = new ArrayList<>();
            if (!options.isObject()) {
                optionErrors.add(new FieldErrorDetail(field + ".options", "TYPE", "object"));
            } else {
                checkOptions(options, field + ".options", wt, optionErrors);
            }
            if (!optionErrors.isEmpty()) {
                throw layout(optionErrors);
            }
        }
        return wt;
    }

    private static void checkTarget(JsonNode target, String f, WidgetType wt, Set<String> variables, List<FieldErrorDetail> errors) {
        String kind = target.path("kind").asString("");
        if (!wt.kinds().contains(kind)) {
            errors.add(new FieldErrorDetail(f + ".kind", "INVALID", String.join("|", wt.kinds())));
            return;
        }
        if (WidgetTypes.DEVICE_METRIC.equals(kind) || WidgetTypes.DEVICE.equals(kind)) {
            ref(target, "deviceId", f, variables, errors);
        } else {
            ref(target, "spaceId", f, variables, errors);
        }
        if (WidgetTypes.metricKind(kind)) {
            String metric = target.path("metricKey").asString("");
            if (!METRIC.matcher(metric).matches() && !isVariable(metric, variables)) {
                errors.add(new FieldErrorDetail(f + ".metricKey", "REQUIRED", null));
            }
            if (target.hasNonNull("agg") && !AGGS.contains(target.get("agg").asString(""))) {
                errors.add(new FieldErrorDetail(f + ".agg", "INVALID", String.join("|", AGGS)));
            }
        }
    }

    private static void ref(JsonNode target, String name, String f, Set<String> variables, List<FieldErrorDetail> errors) {
        JsonNode node = target.get(name);
        String raw = node == null || node.isNull() ? "" : node.isNumber() ? node.asString() : node.asString("");
        if (!ID.matcher(raw).matches() && !isVariable(raw, variables)) {
            errors.add(new FieldErrorDetail(f + "." + name, "INVALID", raw));
        }
    }

    private static boolean isVariable(String raw, Set<String> variables) {
        var m = VAR_REF.matcher(raw);
        return m.matches() && variables.contains(m.group(1));
    }

    @SuppressWarnings("unchecked")
    private static void checkOptions(JsonNode options, String f, WidgetType wt, List<FieldErrorDetail> errors) {
        Map<String, Object> props = (Map<String, Object>) wt.optionsSchema().get("properties");
        for (Map.Entry<String, Object> e : props.entrySet()) {
            JsonNode value = options.get(e.getKey());
            if (value == null || value.isNull()) {
                continue;
            }
            Map<String, Object> rule = (Map<String, Object>) e.getValue();
            String type = (String) rule.get("type");
            boolean ok = switch (type) {
                case "string" -> value.isString() && (!rule.containsKey("maxLength") || value.asString().length() <= (int) rule.get("maxLength"))
                        && (!rule.containsKey("enum") || ((List<String>) rule.get("enum")).contains(value.asString()));
                case "number" -> value.isNumber();
                case "integer" -> value.isIntegralNumber() && (!rule.containsKey("minimum") || value.intValue() >= (int) rule.get("minimum"))
                        && (!rule.containsKey("maximum") || value.intValue() <= (int) rule.get("maximum"));
                case "boolean" -> value.isBoolean();
                case "array" -> value.isArray();
                case "object" -> value.isObject();
                default -> true;
            };
            if (!ok) {
                errors.add(new FieldErrorDetail(f + "." + e.getKey(), "INVALID", type));
            }
        }
        if (options.path("min").isNumber() && options.path("max").isNumber() && options.get("min").doubleValue() >= options.get("max").doubleValue()) {
            errors.add(new FieldErrorDetail(f + ".max", "ORDER", "> min"));
        }
    }

    static boolean overlap(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    private static int intOf(JsonNode w, String field) {
        JsonNode n = w.get(field);
        return n != null && n.isIntegralNumber() ? n.intValue() : -1;
    }

    private static BusinessException layout(List<FieldErrorDetail> errors) {
        return new BusinessException(BoardErrorCode.DASHBOARD_LAYOUT_INVALID, errors);
    }
}
