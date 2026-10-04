package net.java21.data2flow.core.board.service;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.board.domain.BoardErrorCode;
import net.java21.data2flow.core.board.domain.DashboardLayoutValidator;
import net.java21.data2flow.core.board.domain.TimeRanges;
import net.java21.data2flow.core.board.domain.TimeRanges.Range;
import net.java21.data2flow.core.board.domain.WidgetResolution;
import net.java21.data2flow.core.board.domain.WidgetTypes;
import net.java21.data2flow.core.board.domain.WidgetTypes.WidgetType;
import net.java21.data2flow.core.board.dto.BoardDtos.AlarmEntry;
import net.java21.data2flow.core.board.dto.BoardDtos.FloorplanData;
import net.java21.data2flow.core.board.dto.BoardDtos.GaugeData;
import net.java21.data2flow.core.board.dto.BoardDtos.HeatmapData;
import net.java21.data2flow.core.board.dto.BoardDtos.ItemsData;
import net.java21.data2flow.core.board.dto.BoardDtos.MarkerData;
import net.java21.data2flow.core.board.dto.BoardDtos.Series;
import net.java21.data2flow.core.board.dto.BoardDtos.SeriesData;
import net.java21.data2flow.core.board.dto.BoardDtos.StatData;
import net.java21.data2flow.core.board.dto.BoardDtos.StatusItem;
import net.java21.data2flow.core.board.dto.BoardDtos.TableData;
import net.java21.data2flow.core.board.dto.BoardDtos.WidgetData;
import net.java21.data2flow.core.board.repository.WidgetDataRepository;
import net.java21.data2flow.core.board.repository.WidgetDataRepository.DeviceRef;
import net.java21.data2flow.core.board.repository.WidgetDataRepository.HeatCell;
import net.java21.data2flow.core.board.repository.WidgetDataRepository.MetricRef;
import net.java21.data2flow.core.board.repository.WidgetDataRepository.Point;
import net.java21.data2flow.core.board.repository.WidgetDataRepository.SpaceRef;
import net.java21.data2flow.core.board.repository.WidgetDataRepository.StateRow;
import net.java21.data2flow.core.board.repository.WidgetDataRepository.Summary;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 위젯 데이터 계산(API-DSH-09·API-DSH-15, DSH-04.06·04.05·06.03). 호출자(로그인 사용자 또는 공유 링크)가 정한 조직과 공간 범위로만 계산한다.
 * <ul>
 *   <li>대상의 {@code ${변수}}는 요청 값 → 변수 기본값 순으로 채운다(DSH-04.05)</li>
 *   <li>권한 범위 밖 대상이 하나라도 있으면 그 위젯만 403 WIDGET_DATA_FORBIDDEN(BR-DSH-09, TC-DSH-047). 없는 대상은 400 WIDGET_QUERY_INVALID</li>
 *   <li>집계 단위는 {@link WidgetResolution}(차트당 점 2,000개 이하, BR-DSH-04). 누락 구간은 점을 만들지 않는다(보간 없음, BR-DSH-05)</li>
 * </ul>
 */
@Service
public class WidgetDataService {

    private static final Pattern VAR_REF = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_]{0,30})}");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final Set<String> ALL_SEVERITIES = Set.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO");

    private final WidgetDataRepository data;
    private final JsonMapper json;
    private final Clock clock;

    public WidgetDataService(WidgetDataRepository data, JsonMapper json, Clock clock) {
        this.data = data;
        this.json = json;
        this.clock = clock;
    }

    /** 계산에 필요한 호출자 맥락 */
    public record Context(long organizationId, SpaceScope scope, String timezone) {
    }

    /** 한 대상(변수를 채운 뒤) */
    record Target(String kind, Long deviceId, Long spaceId, String metricKey, String agg, String label) {
    }

    /**
     * @param widget         위젯 정의
     * @param variableDefs   대시보드 변수 정의(배열, 없으면 null)
     * @param variableValues 요청 변수 값
     * @param timeRange      요청 범위(없으면 defaultRange)
     * @param resolution     요청 단위(없으면 defaultResolution)
     */
    public WidgetData compute(Context ctx, JsonNode widget, JsonNode variableDefs, Map<String, String> variableValues, JsonNode timeRange,
                              JsonNode defaultRange, String resolution, String defaultResolution) {
        Set<String> names = DashboardLayoutValidator.validateVariables(variableDefs);
        WidgetType type = DashboardLayoutValidator.validateWidget(widget, "widget", names);
        Map<String, String> vars = variables(variableDefs, variableValues);
        Instant now = clock.instant();
        Range range = TimeRanges.resolve(timeRange != null && !timeRange.isNull() ? timeRange : defaultRange, now);
        String res = resolution != null && !resolution.isBlank() ? resolution : defaultResolution;
        List<Target> targets = targets(widget.get("targets"), vars);
        JsonNode options = widget.path("options");
        Object result = switch (type.type()) {
            case "line", "area", "bar" -> series(ctx, targets, range, res);
            case "stat" -> stat(ctx, targets.getFirst(), range, options);
            case "gauge" -> gauge(ctx, targets.getFirst(), options);
            case "heatmap" -> heatmap(ctx, targets.getFirst(), now, options);
            case "table" -> table(ctx, targets, range, options);
            case "status-list" -> statusList(ctx, targets);
            case "alarm-list" -> alarmList(ctx, targets, options);
            case "floorplan" -> floorplan(ctx, targets.getFirst(), options);
            default -> null; // markdown: 데이터 없음(내용은 위젯 옵션)
        };
        return new WidgetData(type.type(), result);
    }

    // ------------------------------------------------------------------ 시계열

    private SeriesData series(Context ctx, List<Target> targets, Range range, String resolution) {
        List<Resolved> resolved = targets.stream().map(t -> resolve(ctx, t)).toList();
        long raw = -1;
        if (range.span().compareTo(WidgetResolution.RAW_AUTO_MAX_SPAN) <= 0) {
            raw = 0;
            for (Resolved r : resolved) {
                raw = Math.max(raw, data.countRaw(ctx.organizationId(), r.deviceIds(), r.target().metricKey(), range.from(), range.to(),
                        WidgetResolution.MAX_POINTS + 1));
            }
        }
        WidgetResolution level = WidgetResolution.choose(resolution, range.span(), raw);
        List<Series> series = new ArrayList<>();
        int index = 0;
        for (Resolved r : resolved) {
            MetricRef metric = metric(ctx, r.target().metricKey());
            String agg = r.target().agg() != null ? r.target().agg() : metric.aggDefault();
            List<Object[]> points = new ArrayList<>();
            for (Point p : data.findSeries(ctx.organizationId(), level, r.deviceIds(), metric.key(), range.from(), range.to(), agg)) {
                if (points.size() >= WidgetResolution.MAX_POINTS) {
                    break;
                }
                points.add(new Object[]{p.t(), p.v(), p.q()});
            }
            series.add(new Series("s" + index++, r.target().label() != null ? r.target().label() : r.name() + " · " + displayName(metric),
                    metric.unit(), r.virtual(), points));
        }
        return new SeriesData(series, level.key(), List.of());
    }

    // ------------------------------------------------------------------ 현재값·게이지

    private StatData stat(Context ctx, Target target, Range range, JsonNode options) {
        Resolved r = resolve(ctx, target);
        MetricRef metric = metric(ctx, target.metricKey());
        Latest latest = latest(ctx, r, metric.key());
        List<Object[]> sparkline = null;
        if (options.path("sparkline").asBoolean(false)) {
            WidgetResolution level = WidgetResolution.choose(null, range.span(), -1);
            sparkline = new ArrayList<>();
            for (Point p : data.findSeries(ctx.organizationId(), level, r.deviceIds(), metric.key(), range.from(), range.to(), "avg")) {
                sparkline.add(new Object[]{p.t(), p.v()});
            }
        }
        return new StatData(latest.value(), metric.unit(), latest.quality(), latest.at(), sparkline);
    }

    private GaugeData gauge(Context ctx, Target target, JsonNode options) {
        Resolved r = resolve(ctx, target);
        MetricRef metric = metric(ctx, target.metricKey());
        Latest latest = latest(ctx, r, metric.key());
        Double min = options.path("min").isNumber() ? options.get("min").doubleValue() : 0d;
        Double max = options.path("max").isNumber() ? options.get("max").doubleValue() : 100d;
        return new GaugeData(latest.value(), metric.unit(), min, max);
    }

    record Latest(Double value, Integer quality, Instant at) {
    }

    /** 기기 하나면 그 값, 여럿(공간)이면 값이 있는 기기의 평균과 가장 늦은 시각 */
    private Latest latest(Context ctx, Resolved r, String metric) {
        double sum = 0;
        int n = 0;
        Integer quality = null;
        Instant at = null;
        for (StateRow s : data.findStates(ctx.organizationId(), r.deviceIds())) {
            Map<String, Object> latest = s.latest() == null ? Map.of() : json.readValue(s.latest(), MAP);
            if (latest.get(metric) instanceof Map<?, ?> v && v.get("v") instanceof Number value) {
                sum += value.doubleValue();
                n++;
                if (v.get("q") instanceof Number q) {
                    quality = quality == null ? q.intValue() : Math.max(quality, q.intValue());
                }
                if (v.get("t") instanceof String t) {
                    Instant parsed = Instant.parse(t);
                    at = at == null || parsed.isAfter(at) ? parsed : at;
                }
            }
        }
        return new Latest(n == 0 ? null : sum / n, quality, at);
    }

    // ------------------------------------------------------------------ 히트맵·표

    private HeatmapData heatmap(Context ctx, Target target, Instant now, JsonNode options) {
        Resolved r = resolve(ctx, target);
        MetricRef metric = metric(ctx, target.metricKey());
        int days = options.path("days").isIntegralNumber() ? options.get("days").intValue() : 7;
        ZoneId zone = zone(ctx.timezone());
        LocalDate today = LocalDate.ofInstant(now, zone);
        Instant from = today.minusDays(days - 1L).atStartOfDay(zone).toInstant();
        List<String> yLabels = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            yLabels.add(today.minusDays(i).toString());
        }
        List<String> xLabels = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            xLabels.add(String.format(Locale.ROOT, "%02d", h));
        }
        List<List<Double>> values = new ArrayList<>();
        Map<String, Double[]> rows = new HashMap<>();
        for (String day : yLabels) {
            Double[] row = new Double[24];
            rows.put(day, row);
        }
        boolean max = "max".equals(options.path("agg").asString("avg"));
        for (HeatCell c : data.findHeatmap(ctx.organizationId(), r.deviceIds(), metric.key(), from, now, zone.getId(), max)) {
            Double[] row = rows.get(c.day());
            if (row != null && c.hour() >= 0 && c.hour() < 24) {
                row[c.hour()] = c.value();
            }
        }
        for (String day : yLabels) {
            values.add(java.util.Arrays.asList(rows.get(day)));
        }
        return new HeatmapData(xLabels, yLabels, values, metric.unit());
    }

    private TableData table(Context ctx, List<Target> targets, Range range, JsonNode options) {
        List<String> columns = new ArrayList<>(List.of("target", "unit"));
        List<String> wanted = new ArrayList<>();
        if (options.path("columns").isArray() && !options.get("columns").isEmpty()) {
            options.get("columns").values().forEach(c -> wanted.add(c.asString("")));
        } else {
            wanted.addAll(List.of("current", "min", "max", "avg", "lastSeen"));
        }
        columns.addAll(wanted);
        List<List<Object>> rows = new ArrayList<>();
        boolean hourly = range.span().compareTo(Duration.ofDays(2)) > 0;
        for (Target t : targets) {
            Resolved r = resolve(ctx, t);
            MetricRef metric = metric(ctx, t.metricKey());
            Summary s = data.findSummary(ctx.organizationId(), r.deviceIds(), metric.key(), range.from(), range.to(), hourly);
            Latest latest = latest(ctx, r, metric.key());
            List<Object> row = new ArrayList<>();
            row.add(t.label() != null ? t.label() : r.name() + " · " + displayName(metric));
            row.add(metric.unit());
            for (String c : wanted) {
                row.add(switch (c) {
                    case "current" -> latest.value();
                    case "min" -> s.min();
                    case "max" -> s.max();
                    case "avg" -> s.avg();
                    case "lastSeen" -> latest.at() != null ? latest.at() : s.lastTime();
                    default -> null;
                });
            }
            rows.add(row);
        }
        return new TableData(columns, rows);
    }

    // ------------------------------------------------------------------ 목록

    private ItemsData statusList(Context ctx, List<Target> targets) {
        Map<Long, DeviceRef> devices = new LinkedHashMap<>();
        for (Target t : targets) {
            Resolved r = resolve(ctx, t);
            r.devices().forEach(d -> devices.putIfAbsent(d.id(), d));
        }
        Map<Long, StateRow> states = new HashMap<>();
        data.findStates(ctx.organizationId(), devices.keySet()).forEach(s -> states.put(s.deviceId(), s));
        List<StatusItem> items = new ArrayList<>();
        for (DeviceRef d : devices.values()) {
            StateRow s = states.get(d.id());
            items.add(new StatusItem(Long.toString(d.id()), d.name(), s == null || s.connectivity() == null ? "UNKNOWN" : s.connectivity(),
                    s == null ? null : s.battery(), s == null ? null : s.rssi(), s == null ? 0 : s.alarms()));
        }
        return new ItemsData(items);
    }

    private ItemsData alarmList(Context ctx, List<Target> targets, JsonNode options) {
        List<String> paths = new ArrayList<>();
        List<Long> deviceIds = new ArrayList<>();
        for (Target t : targets) {
            Resolved r = resolve(ctx, t);
            if (r.space() != null) {
                paths.add(r.space().path());
            } else {
                deviceIds.addAll(r.deviceIds());
            }
        }
        Set<String> severities = strings(options.path("severities"), ALL_SEVERITIES);
        Set<String> states = strings(options.path("states"), Set.of("ACTIVE", "ACKNOWLEDGED"));
        int max = options.path("maxRows").isIntegralNumber() ? options.get("maxRows").intValue() : 10;
        Set<Long> allowed = ctx.scope().unrestricted() ? null : ctx.scope().allowedSpaceIds();
        List<AlarmEntry> items = data.findAlarms(ctx.organizationId(), paths, deviceIds, allowed, severities, states, max).stream()
                .map(a -> new AlarmEntry(Long.toString(a.id()), a.severity(), a.title(), a.status(), a.at())).toList();
        return new ItemsData(items);
    }

    private FloorplanData floorplan(Context ctx, Target target, JsonNode options) {
        Resolved r = resolve(ctx, target);
        long spaceId = r.space().id();
        var fp = data.findFloorplan(ctx.organizationId(), spaceId).orElse(null);
        if (fp == null) {
            return new FloorplanData(Long.toString(spaceId), null, null, null, List.of());
        }
        String metricKey = options.path("metricKey").asString(null);
        var markers = data.findMarkers(ctx.organizationId(), fp.id());
        Map<Long, StateRow> states = new HashMap<>();
        data.findStates(ctx.organizationId(), markers.stream().map(m -> m.deviceId()).toList()).forEach(s -> states.put(s.deviceId(), s));
        String unit = metricKey == null ? null : data.findMetric(ctx.organizationId(), metricKey).map(MetricRef::unit).orElse(null);
        List<MarkerData> out = new ArrayList<>();
        for (var m : markers) {
            StateRow s = states.get(m.deviceId());
            Double value = null;
            if (metricKey != null && s != null && s.latest() != null) {
                Map<String, Object> latest = json.readValue(s.latest(), MAP);
                if (latest.get(metricKey) instanceof Map<?, ?> v && v.get("v") instanceof Number n) {
                    value = n.doubleValue();
                }
            }
            out.add(new MarkerData(Long.toString(m.deviceId()), m.x(), m.y(), value, unit,
                    s == null || s.connectivity() == null ? "UNKNOWN" : s.connectivity()));
        }
        return new FloorplanData(Long.toString(spaceId), "/api/v1/core/spaces/" + spaceId + "/floorplan/image?v=" + fp.version(),
                fp.width(), fp.height(), out);
    }

    // ------------------------------------------------------------------ 대상·변수

    record Resolved(Target target, String name, SpaceRef space, List<DeviceRef> devices, boolean virtual) {
        List<Long> deviceIds() {
            return devices.stream().map(DeviceRef::id).toList();
        }
    }

    /** 대상을 찾고 권한 범위를 본다: 없으면 400 WIDGET_QUERY_INVALID, 범위 밖이면 403 WIDGET_DATA_FORBIDDEN */
    Resolved resolve(Context ctx, Target t) {
        if (t.deviceId() != null) {
            DeviceRef d = data.findDevices(ctx.organizationId(), List.of(t.deviceId())).stream().findFirst()
                    .orElseThrow(() -> missing("deviceId"));
            if (!ctx.scope().unrestricted() && (d.spaceId() == null || !ctx.scope().includes(d.spaceId()))) {
                throw new BusinessException(BoardErrorCode.WIDGET_DATA_FORBIDDEN);
            }
            return new Resolved(t, d.name(), null, List.of(d), d.virtual());
        }
        SpaceRef s = data.findSpace(ctx.organizationId(), t.spaceId()).orElseThrow(() -> missing("spaceId"));
        if (!ctx.scope().includes(s.id())) {
            throw new BusinessException(BoardErrorCode.WIDGET_DATA_FORBIDDEN);
        }
        List<DeviceRef> devices = data.findSpaceDevices(ctx.organizationId(), s.path(), 500).stream()
                .filter(d -> ctx.scope().unrestricted() || d.spaceId() != null && ctx.scope().includes(d.spaceId())).toList();
        return new Resolved(t, s.name(), s, devices, devices.stream().anyMatch(DeviceRef::virtual));
    }

    private MetricRef metric(Context ctx, String key) {
        return data.findMetric(ctx.organizationId(), key).orElse(new MetricRef(key, key, null, "avg"));
    }

    private static String displayName(MetricRef m) {
        return m.name() == null ? m.key() : m.name();
    }

    private List<Target> targets(JsonNode targets, Map<String, String> vars) {
        List<Target> out = new ArrayList<>();
        if (targets == null || !targets.isArray()) {
            return out;
        }
        for (JsonNode t : targets.values()) {
            String kind = t.path("kind").asString("");
            Long deviceId = WidgetTypes.DEVICE_METRIC.equals(kind) || WidgetTypes.DEVICE.equals(kind) ? id(t.get("deviceId"), vars, "deviceId") : null;
            Long spaceId = deviceId == null ? id(t.get("spaceId"), vars, "spaceId") : null;
            String metric = WidgetTypes.metricKind(kind) ? substitute(t.path("metricKey").asString(""), vars, "metricKey") : null;
            out.add(new Target(kind, deviceId, spaceId, metric, t.hasNonNull("agg") ? t.get("agg").asString() : null,
                    t.hasNonNull("label") ? t.get("label").asString() : null));
        }
        return out;
    }

    private static Long id(JsonNode node, Map<String, String> vars, String field) {
        String raw = node == null || node.isNull() ? "" : node.isNumber() ? node.asString() : node.asString("");
        String value = substitute(raw, vars, field);
        if (!value.matches("\\d{1,18}")) {
            throw missing(field);
        }
        return Long.parseLong(value);
    }

    private static String substitute(String raw, Map<String, String> vars, String field) {
        Matcher m = VAR_REF.matcher(raw);
        if (!m.matches()) {
            return raw;
        }
        String value = vars.get(m.group(1));
        if (value == null || value.isBlank()) {
            throw missing(field);
        }
        return value;
    }

    /** 변수 값: 요청 값 → 정의의 기본값 */
    private static Map<String, String> variables(JsonNode defs, Map<String, String> values) {
        Map<String, String> vars = new HashMap<>();
        if (defs != null && defs.isArray()) {
            for (JsonNode d : defs.values()) {
                JsonNode def = d.get("default");
                if (def != null && !def.isNull()) {
                    vars.put(d.path("name").asString(""), def.isNumber() ? def.asString() : def.asString(""));
                }
            }
        }
        if (values != null) {
            values.forEach((k, v) -> {
                if (v != null) {
                    vars.put(k, v);
                }
            });
        }
        return vars;
    }

    private static Set<String> strings(JsonNode node, Set<String> fallback) {
        if (!node.isArray() || node.isEmpty()) {
            return fallback;
        }
        Set<String> out = new TreeSet<>();
        node.values().forEach(v -> out.add(v.asString("").toUpperCase(Locale.ROOT)));
        return out;
    }

    private static ZoneId zone(String tz) {
        try {
            return tz == null ? ZoneId.of("Asia/Seoul") : ZoneId.of(tz);
        } catch (RuntimeException ex) {
            return ZoneId.of("Asia/Seoul");
        }
    }

    private static BusinessException missing(String field) {
        return new BusinessException(BoardErrorCode.WIDGET_QUERY_INVALID, List.of(new FieldErrorDetail("targets." + field, "NOT_FOUND", null)));
    }
}
