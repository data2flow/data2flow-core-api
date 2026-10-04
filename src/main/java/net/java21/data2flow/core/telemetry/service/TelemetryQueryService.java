package net.java21.data2flow.core.telemetry.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.CursorListApiResponse;
import net.java21.data2flow.contracts.web.CursorParams;
import net.java21.data2flow.core.control.repository.CommandHistoryRepository;
import net.java21.data2flow.core.control.repository.CommandHistoryRepository.StateRow;
import net.java21.data2flow.core.telemetry.domain.AggFunction;
import net.java21.data2flow.core.telemetry.domain.BucketGrid;
import net.java21.data2flow.core.telemetry.domain.FillMode;
import net.java21.data2flow.core.telemetry.domain.FillMode.Gap;
import net.java21.data2flow.core.telemetry.domain.Resolution;
import net.java21.data2flow.core.telemetry.domain.ResolutionPlanner;
import net.java21.data2flow.core.telemetry.domain.ResolutionPlanner.Plan;
import net.java21.data2flow.core.telemetry.domain.SeriesPoint;
import net.java21.data2flow.core.telemetry.domain.TelemetryErrorCode;
import net.java21.data2flow.core.telemetry.domain.TimeCursor;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.CompareSpaceSeries;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.CompareSpacesRequest;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.CompareSpacesResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.GapResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.LatestDeviceResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.LatestMetricResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.LinkQualityResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.QueryRequest;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.QuerySeries;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.RangeResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.RawPointResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.SeriesItem;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.SeriesResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.SpaceSeriesResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.StateIntervalResponse;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository.DeviceInfo;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository.LatestRow;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository.LinkRow;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository.MetricInfo;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository.SpaceDevice;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository.SpaceInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 시계열 조회(TSD-03.01·03.02·03.04·03.05·03.06, TSD-06.01·06.02, TSD-01.02·01.05, NFR-01.05).
 * <ul>
 *   <li>권한: TS_READ(VIEWER 이상). 기기 하나를 콕 집은 조회는 권한 범위 밖이면 404 DEVICE_NOT_FOUND(AT-TSD-01.7),
 *       여러 계열 조회는 범위 밖 기기를 빼고 {@code excludedDeviceCount}로만 알린다(BR-TSD-13)</li>
 *   <li>가상 데이터(TSD-03.05, BR-TSD-12): 실제 공간 조회가 기본이므로 {@code virtual} 기본값은 false(가상 공간 개념은 SIM 단계에서 붙는다)</li>
 *   <li>품질(TSD-03.04): normal(기본) = 0·4, all = 0~4, 예보(5)는 {@code includeForecast=true}일 때만(domain-model §2.12)</li>
 *   <li>시각은 UTC. {@code tz}는 응답 표시용으로만 돌려주고, 1일 구간은 pipeline이 사이트 자정에 맞춰 저장한 값을 그대로 쓴다(BR-TSD-05·21)</li>
 * </ul>
 * 조회 캐시(TSD-06.04, BR-TSD-20)는 M2에 넣지 않았다. 캐시를 붙이면 EVT-TSD-03 {@code aggregates.recomputed}로 무효화한다.
 */
@Service
public class TelemetryQueryService {

    static final int MAX_METRICS = 20;
    static final int MAX_SERIES = 50;
    static final int MAX_LATEST_DEVICES = 500;
    static final Duration LINK_MAX_SPAN = Duration.ofDays(90);
    private static final Pattern METRIC_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final TelemetryQueryRepository repository;
    private final CommandHistoryRepository actuatorHistory;
    private final RoleChecker roleChecker;
    private final JsonMapper json;
    private final Clock clock;

    public TelemetryQueryService(TelemetryQueryRepository repository, CommandHistoryRepository actuatorHistory, RoleChecker roleChecker,
                                 JsonMapper json, Clock clock) {
        this.repository = repository;
        this.actuatorHistory = actuatorHistory;
        this.roleChecker = roleChecker;
        this.json = json;
        this.clock = clock;
    }

    /** API-TSD-02 쿼리 */
    public record SeriesQuery(Long deviceId, List<String> metrics, Instant from, Instant to, String resolution, String agg, String fill,
                              String quality, Boolean includeForecast, Boolean virtual, String tz) {
    }

    /** API-TSD-03 쿼리 */
    public record SpaceQuery(Long spaceId, String metric, Boolean includeDescendants, String func, Instant from, Instant to,
                             String resolution, List<Long> models, List<String> tags, Boolean virtual, String tz) {
    }

    /** 조회 공통 옵션 */
    private record Options(FillMode fill, String qualities, boolean allQualities, boolean virtual) {
    }

    // ------------------------------------------------------------------ API-TSD-01

    /** API-TSD-01 현재값(device_state.latest 기준, NFR-01.05). 기기 목록(≤500) 또는 공간(하위 포함 기본) */
    @Transactional(readOnly = true)
    public List<LatestDeviceResponse> latest(List<Long> deviceIds, Long spaceId, Boolean includeDescendants, List<String> metrics,
                                             Boolean virtual) {
        roleChecker.require(Permission.TS_READ);
        long orgId = roleChecker.currentUser().organizationId();
        boolean withVirtual = Boolean.TRUE.equals(virtual);
        List<LatestRow> rows;
        if (spaceId != null) {
            SpaceInfo space = repository.findSpace(orgId, spaceId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            roleChecker.requireSpace(space.id(), CommonErrorCode.RESOURCE_NOT_FOUND);
            rows = repository.findLatestBySpace(orgId, space, !Boolean.FALSE.equals(includeDescendants), withVirtual);
        } else if (deviceIds != null && !deviceIds.isEmpty()) {
            if (deviceIds.size() > MAX_LATEST_DEVICES) {
                throw invalid("deviceIds");
            }
            rows = repository.findLatestByIds(orgId, new LinkedHashSet<>(deviceIds), withVirtual);
        } else {
            throw invalid("deviceIds");
        }
        SpaceScope scope = roleChecker.spaceScope();
        List<String> wanted = metrics == null ? List.of() : metrics.stream().filter(Objects::nonNull).map(String::strip).toList();
        Map<String, String> units = repository.findUnits(orgId);
        List<LatestDeviceResponse> result = new ArrayList<>();
        for (LatestRow row : rows) {
            if (!scope.unrestricted() && !scope.includes(row.spaceId())) {
                continue;
            }
            result.add(new LatestDeviceResponse(Long.toString(row.deviceId()), row.name(), row.virtual(),
                    row.connectivity() == null ? "UNKNOWN" : row.connectivity(), row.lastSeenAt(), latestMetrics(row.latest(), wanted, units)));
        }
        return result;
    }

    private List<LatestMetricResponse> latestMetrics(String latestJson, List<String> wanted, Map<String, String> units) {
        if (latestJson == null || latestJson.isBlank()) {
            return List.of();
        }
        Map<String, Object> latest = new TreeMap<>(json.readValue(latestJson, MAP));
        List<LatestMetricResponse> metrics = new ArrayList<>();
        for (Map.Entry<String, Object> entry : latest.entrySet()) {
            if (!wanted.isEmpty() && !wanted.contains(entry.getKey()) || !(entry.getValue() instanceof Map<?, ?> v)) {
                continue;
            }
            Double value = v.get("v") instanceof Number n ? n.doubleValue() : null;
            Instant measuredAt = v.get("t") instanceof String t ? parseInstant(t) : null;
            Integer quality = v.get("q") instanceof Number q ? q.intValue() : null;
            String unit = v.get("unit") instanceof String u ? u : units.get(entry.getKey());
            metrics.add(new LatestMetricResponse(entry.getKey(), value, unit, measuredAt, quality));
        }
        return metrics;
    }

    // ------------------------------------------------------------------ API-TSD-02

    /** API-TSD-02 기기 한 대의 시계열(측정 항목 ≤20) */
    @Transactional(readOnly = true)
    public SeriesResponse series(SeriesQuery q) {
        roleChecker.require(Permission.TS_READ);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (q.deviceId() == null) {
            throw invalid("deviceId");
        }
        List<String> metrics = metricKeys(q.metrics());
        Instant now = clock.instant();
        Instant to = q.to() == null ? now : q.to();
        Instant from = requireFrom(q.from(), to);
        String timezone = timezone(q.tz(), orgId, user.userId());
        Resolution requested = resolution(q.resolution());
        AggFunction agg = agg(q.agg());
        Options options = options(q.fill(), q.quality(), q.includeForecast(), q.virtual());
        DeviceInfo device = repository.findDevice(orgId, q.deviceId())
                .orElseThrow(() -> new BusinessException(TelemetryErrorCode.DEVICE_NOT_FOUND));
        roleChecker.requireSpace(device.spaceId(), TelemetryErrorCode.DEVICE_NOT_FOUND);
        Map<String, MetricInfo> definitions = repository.findMetrics(orgId, metrics);
        Plan plan = ResolutionPlanner.plan(requested, from, to, now, true, () -> metrics.stream()
                .mapToLong(m -> repository.countRaw(orgId, device.id(), m, from, to, options.qualities(), options.virtual(),
                        ResolutionPlanner.AUTO_MAX_POINTS + 1))
                .max().orElse(0));
        List<SeriesItem> items = new ArrayList<>();
        for (String metric : metrics) {
            items.add(deviceSeries(orgId, device, metric, definitions.get(metric), agg, plan.resolution(), from, to, options, null, now));
        }
        return new SeriesResponse(plan.resolution().key(), plan.reason(), items.stream().anyMatch(i -> i.nextCursor() != null), timezone,
                0, items);
    }

    // ------------------------------------------------------------------ API-TSD-03

    /** API-TSD-03 공간 집계(TSD-03.02, BR-TSD-11): 하위 공간 포함(기본), ACTIVE·실제 기기만, 구간별 기여 기기 수 */
    @Transactional(readOnly = true)
    public SpaceSeriesResponse spaceSeries(SpaceQuery q) {
        roleChecker.require(Permission.TS_READ);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (q.spaceId() == null) {
            throw invalid("spaceId");
        }
        String metric = metricKeys(q.metric() == null ? null : List.of(q.metric())).getFirst();
        Instant now = clock.instant();
        Instant to = q.to() == null ? now : q.to();
        Instant from = requireFrom(q.from(), to);
        String timezone = timezone(q.tz(), orgId, user.userId());
        Resolution requested = resolution(q.resolution());
        AggFunction func = spaceFunc(q.func());
        boolean virtual = Boolean.TRUE.equals(q.virtual());
        SpaceInfo space = repository.findSpace(orgId, q.spaceId()).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        roleChecker.requireSpace(space.id(), CommonErrorCode.RESOURCE_NOT_FOUND);
        Plan plan = ResolutionPlanner.plan(requested, from, to, now, false, () -> 0);
        SpaceSeries computed = spaceSeries(orgId, space, metric, func, plan.resolution(), from, to, !Boolean.FALSE.equals(q.includeDescendants()),
                virtual, q.models(), q.tags());
        MetricInfo definition = repository.findMetrics(orgId, List.of(metric)).get(metric);
        return new SpaceSeriesResponse(Long.toString(space.id()), metric, definition == null ? null : definition.unit(), func.key(),
                plan.resolution().key(), plan.reason(), timezone, computed.deviceCount(), computed.excluded(), arrays(computed.points()));
    }

    private record SpaceSeries(List<SeriesPoint> points, int deviceCount, int excluded) {
    }

    private SpaceSeries spaceSeries(long orgId, SpaceInfo space, String metric, AggFunction func, Resolution level, Instant from, Instant to,
                                    boolean includeDescendants, boolean virtual, List<Long> models, List<String> tags) {
        SpaceScope scope = roleChecker.spaceScope();
        List<Long> allowed = new ArrayList<>();
        int excluded = 0;
        for (SpaceDevice d : repository.findSpaceDevices(orgId, space, includeDescendants, virtual,
                models == null ? List.of() : models, tags == null ? List.of() : tags)) {
            if (scope.unrestricted() || scope.includes(d.spaceId())) {
                allowed.add(d.deviceId());
            } else {
                excluded++;
            }
        }
        // 합계는 기기별 구간 평균을 더한다(예: 재실 인원 합계). 최소·최대는 기기별 최소·최대에서 고른다
        AggFunction column = func == AggFunction.MIN || func == AggFunction.MAX ? func : AggFunction.AVG;
        List<SeriesPoint> points = repository.findSpaceAggregate(orgId, level, metric, allowed, BucketGrid.lowerBound(level, from), to,
                column, func, virtual);
        return new SpaceSeries(points, allowed.size(), excluded);
    }

    // ------------------------------------------------------------------ API-TSD-04

    /** API-TSD-04 여러 계열(≤50)을 같은 집계 단위·같은 구간 시각으로(TSD-03.03 정렬) */
    @Transactional(readOnly = true)
    public SeriesResponse query(QueryRequest req) {
        roleChecker.require(Permission.TS_READ);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (req == null || req.series() == null || req.series().isEmpty()) {
            throw invalid("series");
        }
        if (req.series().size() > MAX_SERIES) {
            throw new BusinessException(TelemetryErrorCode.TSD_TOO_MANY_SERIES);
        }
        Instant now = clock.instant();
        Instant to = req.to() == null ? now : req.to();
        Instant from = requireFrom(req.from(), to);
        String timezone = timezone(req.tz(), orgId, user.userId());
        Resolution requested = resolution(req.resolution());
        Boolean virtualFlag = req.virtual() != null ? req.virtual() : req.includeVirtual();
        Options options = options(req.fill(), req.quality(), req.includeForecast(), virtualFlag);
        SpaceScope scope = roleChecker.spaceScope();

        List<String> keys = new ArrayList<>();
        for (int i = 0; i < req.series().size(); i++) {
            QuerySeries s = req.series().get(i);
            if (s == null || (s.deviceId() == null) == (s.spaceId() == null)) {
                throw invalid("series[" + i + "]");
            }
            if (s.metric() == null || !METRIC_KEY.matcher(s.metric().strip()).matches()) {
                throw invalid("series[" + i + "].metric");
            }
            keys.add(s.metric().strip());
        }
        Map<String, MetricInfo> definitions = repository.findMetrics(orgId, new LinkedHashSet<>(keys));

        // 대상 확인: 없는 기기·공간과 권한 범위 밖은 결과에서 뺀다(BR-TSD-13)
        List<Long> deviceIds = req.series().stream().map(QuerySeries::deviceId).filter(Objects::nonNull).distinct().toList();
        Map<Long, DeviceInfo> devices = new TreeMap<>();
        for (DeviceInfo d : repository.findDevices(orgId, deviceIds)) {
            if (scope.unrestricted() || scope.includes(d.spaceId())) {
                devices.put(d.id(), d);
            }
        }
        int excluded = (int) deviceIds.stream().filter(id -> !devices.containsKey(id)).count();
        Map<Long, SpaceInfo> spaces = new TreeMap<>();
        for (QuerySeries s : req.series()) {
            if (s.spaceId() != null && !spaces.containsKey(s.spaceId())) {
                repository.findSpace(orgId, s.spaceId()).filter(sp -> scope.unrestricted() || scope.includes(sp.id()))
                        .ifPresent(sp -> spaces.put(sp.id(), sp));
            }
        }
        boolean hasSpace = req.series().stream().anyMatch(s -> s.spaceId() != null && spaces.containsKey(s.spaceId()));
        Plan plan = ResolutionPlanner.plan(requested, from, to, now, !hasSpace, () -> req.series().stream()
                .filter(s -> s.deviceId() != null && devices.containsKey(s.deviceId()))
                .mapToLong(s -> repository.countRaw(orgId, s.deviceId(), s.metric().strip(), from, to, options.qualities(), options.virtual(),
                        ResolutionPlanner.AUTO_MAX_POINTS + 1))
                .max().orElse(0));

        List<SeriesItem> items = new ArrayList<>();
        for (QuerySeries s : req.series()) {
            String metric = s.metric().strip();
            if (s.deviceId() != null) {
                DeviceInfo device = devices.get(s.deviceId());
                if (device != null) {
                    items.add(deviceSeries(orgId, device, metric, definitions.get(metric), agg(s.agg()), plan.resolution(), from, to,
                            options, s.label(), now));
                }
                continue;
            }
            SpaceInfo space = spaces.get(s.spaceId());
            if (space == null) {
                continue;
            }
            AggFunction func = spaceFunc(s.agg());
            SpaceSeries computed = spaceSeries(orgId, space, metric, func, plan.resolution(), from, to, true, options.virtual(), null, null);
            excluded += computed.excluded();
            MetricInfo definition = definitions.get(metric);
            items.add(new SeriesItem(null, Long.toString(space.id()), metric, s.label(), definition == null ? null : definition.unit(),
                    func.key(), false, arrays(computed.points()), List.of(), computed.deviceCount(), null, null));
        }
        return new SeriesResponse(plan.resolution().key(), plan.reason(), items.stream().anyMatch(i -> i.nextCursor() != null), timezone,
                excluded, items);
    }

    /** 기기 계열 하나: 원본(최대 10,000점, 넘으면 커서) 또는 집계(+채우기) */
    private SeriesItem deviceSeries(long orgId, DeviceInfo device, String metric, MetricInfo definition, AggFunction requestedAgg,
                                    Resolution level, Instant from, Instant to, Options options, String label, Instant now) {
        String unit = definition == null ? null : definition.unit();
        List<Gap> gaps = repository.findGaps(orgId, device.id(), from, to);
        List<GapResponse> gapResponses = gaps.stream().map(g -> new GapResponse(g.from(), g.to())).toList();
        String deviceId = Long.toString(device.id());
        if (level == Resolution.RAW) {
            List<SeriesPoint> rows = repository.findRawPoints(orgId, device.id(), metric, from, to, options.qualities(), options.virtual(),
                    null, ResolutionPlanner.MAX_POINTS + 1);
            String nextCursor = null;
            RangeResponse remaining = null;
            if (rows.size() > ResolutionPlanner.MAX_POINTS) {
                rows = rows.subList(0, ResolutionPlanner.MAX_POINTS);
                Instant last = rows.getLast().t();
                nextCursor = TimeCursor.encode(last);
                remaining = new RangeResponse(last, to);
            }
            return new SeriesItem(deviceId, null, metric, label, unit, null, device.virtual(), arrays(rows), gapResponses, null,
                    nextCursor, remaining);
        }
        AggFunction agg = requestedAgg != null ? requestedAgg : defaultAgg(definition);
        if (!agg.allowedFor(definition == null ? null : definition.valueType())) {
            throw new BusinessException(TelemetryErrorCode.TSD_INVALID_AGG, agg.key());
        }
        List<SeriesPoint> points = repository.findAggregatePoints(orgId, level, device.id(), metric, BucketGrid.lowerBound(level, from), to,
                agg, options.allQualities(), options.virtual());
        if (options.fill() != FillMode.NONE) {
            ZoneId zone = zone(device.siteTimezone());
            Instant gridEnd = to.isAfter(now) ? now : to;
            points = options.fill().fill(points, BucketGrid.of(level, from, gridEnd, zone), gaps,
                    Duration.ofSeconds(3L * device.intervalSec()));
        }
        return new SeriesItem(deviceId, null, metric, label, unit, agg.key(), device.virtual(), arrays(points), gapResponses, null, null, null);
    }

    // ------------------------------------------------------------------ 공간 비교(DSH-02.04)

    /** 공간 비교 최대 공간 수(DSH-02.04) */
    static final int MAX_COMPARE_SPACES = 6;

    /**
     * 공간 여러 곳의 같은 측정 항목을 같은 집계 단위로(DSH-02.04, TC-DSH-018·019). 7곳 이상은 400 {@code WIDGET_QUERY_INVALID},
     * 없는 공간·권한 범위 밖 공간이 하나라도 섞이면 404(그 공간이 있는지 드러내지 않음). 원본 단위는 쓸 수 없어 1m 이상으로 고른다.
     */
    @Transactional(readOnly = true)
    public CompareSpacesResponse compareSpaces(CompareSpacesRequest req) {
        roleChecker.require(Permission.TS_READ);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (req == null || req.spaceIds() == null || req.spaceIds().isEmpty()) {
            throw invalid("spaceIds");
        }
        if (req.spaceIds().size() > MAX_COMPARE_SPACES) {
            throw new BusinessException(TelemetryErrorCode.WIDGET_QUERY_INVALID, List.of(new FieldErrorDetail("spaceIds", "Size", "1~6")));
        }
        String metric = metricKeys(req.metricKey() == null ? null : List.of(req.metricKey())).getFirst();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < req.spaceIds().size(); i++) {
            String raw = req.spaceIds().get(i);
            if (raw == null || !raw.strip().matches("\\d{1,18}")) {
                throw invalid("spaceIds[" + i + "]");
            }
            long id = Long.parseLong(raw.strip());
            if (ids.contains(id)) {
                throw invalid("spaceIds[" + i + "]");
            }
            ids.add(id);
        }
        Instant now = clock.instant();
        Instant to = req.to() == null ? now : req.to();
        Instant from = requireFrom(req.from(), to);
        String timezone = timezone(req.tz(), orgId, user.userId());
        Resolution requested = resolution(req.resolution());
        AggFunction func = spaceFunc(req.agg());
        boolean virtual = Boolean.TRUE.equals(req.virtual());
        List<SpaceInfo> spaces = new ArrayList<>();
        for (long id : ids) {
            SpaceInfo space = repository.findSpace(orgId, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            roleChecker.requireSpace(space.id(), CommonErrorCode.RESOURCE_NOT_FOUND);
            spaces.add(space);
        }
        Plan plan = ResolutionPlanner.plan(requested, from, to, now, false, () -> 0);
        Map<Long, String> names = repository.findSpaceNames(orgId, ids);
        List<CompareSpaceSeries> series = new ArrayList<>();
        for (SpaceInfo space : spaces) {
            SpaceSeries computed = spaceSeries(orgId, space, metric, func, plan.resolution(), from, to, true, virtual, null, null);
            series.add(new CompareSpaceSeries(Long.toString(space.id()), names.get(space.id()), computed.deviceCount(), computed.excluded(),
                    arrays(computed.points())));
        }
        MetricInfo definition = repository.findMetrics(orgId, List.of(metric)).get(metric);
        return new CompareSpacesResponse(metric, definition == null ? null : definition.unit(), func.key(), plan.resolution().key(),
                plan.reason(), timezone, series);
    }

    // ------------------------------------------------------------------ 원본 점 커서 목록

    /** 원본 점 넘겨 보기(커서 목록, TSD-06.01): 31일까지, 이어 받기 누락·중복 없음 */
    @Transactional(readOnly = true)
    public CursorListApiResponse<RawPointResponse> rawPoints(Long deviceId, String metric, Instant fromParam, Instant toParam, String quality,
                                                            Boolean includeForecast, Boolean virtual, String cursor, Integer size) {
        roleChecker.require(Permission.TS_READ);
        long orgId = roleChecker.currentUser().organizationId();
        if (deviceId == null) {
            throw invalid("deviceId");
        }
        String key = metricKeys(metric == null ? null : List.of(metric)).getFirst();
        Instant now = clock.instant();
        Instant to = toParam == null ? now : toParam;
        Instant from = requireFrom(fromParam, to);
        ResolutionPlanner.plan(Resolution.RAW, from, to, now, true, () -> 0);
        Options options = options(null, quality, includeForecast, virtual);
        CursorParams page = CursorParams.of(cursor, size);
        Instant after;
        try {
            after = page.cursor() == null ? null : TimeCursor.decode(page.cursor());
        } catch (IllegalArgumentException ex) {
            throw invalid("cursor");
        }
        DeviceInfo device = repository.findDevice(orgId, deviceId).orElseThrow(() -> new BusinessException(TelemetryErrorCode.DEVICE_NOT_FOUND));
        roleChecker.requireSpace(device.spaceId(), TelemetryErrorCode.DEVICE_NOT_FOUND);
        List<SeriesPoint> rows = repository.findRawPoints(orgId, device.id(), key, from, to, options.qualities(), options.virtual(), after,
                page.fetchSize());
        String next = null;
        if (rows.size() > page.size()) {
            rows = rows.subList(0, page.size());
            next = TimeCursor.encode(rows.getLast().t());
        }
        return CursorListApiResponse.of(page.size(), rows.stream().map(p -> new RawPointResponse(p.t(), p.value(), p.third(), key)).toList(), next);
    }

    // ------------------------------------------------------------------ API-TSD-05

    /**
     * API-TSD-05 상태 구간: 상태형 측정 항목(문 열림 등)의 원본을 값이 바뀌는 지점으로 나눈 구간. 구간 시작 값은 기간 직전 값(7일 안)이다.
     * {@code actuator=true}(액추에이터 상태·명령 이력, TSD-01.03·BR-TSD-22)는 원천이 data2flow_action이라 action 서비스가 생기는 M4에서 붙이고,
     * 지금은 빈 목록을 준다.
     */
    @Transactional(readOnly = true)
    public List<StateIntervalResponse> stateIntervals(Long deviceId, String metric, Boolean actuator, Instant fromParam, Instant toParam,
                                                      Boolean virtual) {
        roleChecker.require(Permission.TS_READ);
        long orgId = roleChecker.currentUser().organizationId();
        if (deviceId == null) {
            throw invalid("deviceId");
        }
        Instant now = clock.instant();
        Instant to = toParam == null ? now : toParam;
        Instant from = requireFrom(fromParam, to);
        DeviceInfo device = repository.findDevice(orgId, deviceId).orElseThrow(() -> new BusinessException(TelemetryErrorCode.DEVICE_NOT_FOUND));
        roleChecker.requireSpace(device.spaceId(), TelemetryErrorCode.DEVICE_NOT_FOUND);
        if (Boolean.TRUE.equals(actuator)) {
            return actuatorIntervals(orgId, device.id(), from, to);
        }
        String key = metricKeys(metric == null ? null : List.of(metric)).getFirst();
        ResolutionPlanner.plan(Resolution.RAW, from, to, now, true, () -> 0);
        boolean withVirtual = Boolean.TRUE.equals(virtual);
        MetricInfo definition = repository.findMetrics(orgId, List.of(key)).get(key);
        Map<String, Object> labels = definition == null || definition.enumMap() == null ? Map.of() : json.readValue(definition.enumMap(), MAP);
        List<SeriesPoint> points = new ArrayList<>();
        repository.findLastRawBefore(orgId, device.id(), key, from, withVirtual).ifPresent(p -> points.add(new SeriesPoint(from, p.value(), p.third())));
        points.addAll(repository.findRawPoints(orgId, device.id(), key, from, to, "{0,4}", withVirtual, null, ResolutionPlanner.MAX_POINTS));
        List<StateIntervalResponse> intervals = new ArrayList<>();
        Instant end = to.isAfter(now) ? now : to;
        for (int i = 0; i < points.size(); i++) {
            SeriesPoint start = points.get(i);
            int j = i;
            while (j + 1 < points.size() && Objects.equals(points.get(j + 1).value(), start.value())) {
                j++;
            }
            Instant until = j + 1 < points.size() ? points.get(j + 1).t() : end;
            intervals.add(new StateIntervalResponse(start.t(), until, start.value(), label(labels, start.value()), null));
            i = j;
        }
        return intervals;
    }

    /**
     * TSD-01.03 액추에이터 상태 구간: action의 {@code device_state_history}(속성별 구간). 끝나지 않은 구간은 조회 끝(지금 이전)까지로
     * 자르고, 출처는 {@code {type, id}}(플로우·사용자·예약·장면, BR-TSD-22)로 줄인다.
     */
    private List<StateIntervalResponse> actuatorIntervals(long orgId, long deviceId, Instant from, Instant to) {
        Instant now = clock.instant();
        Instant end = to.isAfter(now) ? now : to;
        List<StateIntervalResponse> result = new ArrayList<>();
        for (StateRow row : actuatorHistory.listStateIntervals(orgId, deviceId, from, to, ResolutionPlanner.MAX_POINTS)) {
            Instant start = row.validFrom().isBefore(from) ? from : row.validFrom();
            Instant until = row.validTo() == null || row.validTo().isAfter(end) ? end : row.validTo();
            Object value = row.value() == null ? null : json.readValue(row.value(), Object.class);
            String label = row.label() != null ? row.label() : row.capability() + "." + row.attribute() + "=" + value;
            result.add(new StateIntervalResponse(start, until, value, label, actuatorSource(row.source()), row.capability(),
                    row.attribute()));
        }
        return result;
    }

    private Map<String, Object> actuatorSource(String raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Object> source = json.readValue(raw, MAP);
        Object type = source.get("type");
        Object id = source.get("flowId") != null ? source.get("flowId") : source.get("scheduleId") != null ? source.get("scheduleId")
                : source.get("sceneRunId") != null ? source.get("sceneRunId") : source.get("userId");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("type", type);
        if (id != null) {
            out.put("id", String.valueOf(id));
        }
        return out;
    }

    private static String label(Map<String, Object> labels, Double value) {
        if (value == null || labels.isEmpty()) {
            return null;
        }
        String key = value == Math.rint(value) ? Long.toString(value.longValue()) : value.toString();
        Object label = labels.get(key);
        return label == null ? null : label.toString();
    }

    // ------------------------------------------------------------------ API-TSD-08

    /**
     * API-TSD-08 통신 품질(TSD-01.02, AT-TSD-15.6): 게이트웨이별 rssi·snr. resolution=raw는 원본, 1m·1h·1d는 게이트웨이별 구간 평균,
     * auto는 1일 이하 raw · 41일 이하 1h · 그 밖 1d. 기간은 보관 기간과 같은 90일까지, 응답은 10,000점까지.
     * 시뮬레이터처럼 통신 정보가 없으면 빈 목록이다(화면에 "통신 정보 없음", AT-TSD-15.5).
     */
    @Transactional(readOnly = true)
    public List<LinkQualityResponse> linkQuality(Long deviceId, Instant fromParam, Instant toParam, String resolution) {
        roleChecker.require(Permission.TS_READ);
        long orgId = roleChecker.currentUser().organizationId();
        if (deviceId == null) {
            throw invalid("deviceId");
        }
        Instant now = clock.instant();
        Instant to = toParam == null ? now : toParam;
        Instant from = requireFrom(fromParam, to);
        Duration span = Duration.between(from, to);
        if (span.compareTo(LINK_MAX_SPAN) > 0) {
            throw new BusinessException(TelemetryErrorCode.TSD_RANGE_TOO_LARGE);
        }
        Resolution level = resolution(resolution);
        if (level == null) {
            level = span.compareTo(Duration.ofDays(1)) <= 0 ? Resolution.RAW
                    : span.compareTo(Duration.ofDays(41)) <= 0 ? Resolution.H1 : Resolution.D1;
        }
        DeviceInfo device = repository.findDevice(orgId, deviceId).orElseThrow(() -> new BusinessException(TelemetryErrorCode.DEVICE_NOT_FOUND));
        roleChecker.requireSpace(device.spaceId(), TelemetryErrorCode.DEVICE_NOT_FOUND);
        List<LinkRow> rows = level == Resolution.RAW
                ? repository.findLinkRaw(orgId, device.id(), from, to, ResolutionPlanner.MAX_POINTS)
                : repository.findLinkAggregated(orgId, device.id(), from, to, switch (level) {
                    case M1 -> "1 minute";
                    case H1 -> "1 hour";
                    default -> "1 day";
                }, ResolutionPlanner.MAX_POINTS);
        return rows.stream().map(r -> new LinkQualityResponse(r.t(), r.gatewayEui(), round2(r.rssi()), round2(r.snr()))).toList();
    }

    private static Double round2(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }

    // ------------------------------------------------------------------ 공통

    private static List<List<Object>> arrays(List<SeriesPoint> points) {
        return points.stream().map(SeriesPoint::toArray).toList();
    }

    private static AggFunction defaultAgg(MetricInfo definition) {
        if (definition == null || definition.aggDefault() == null) {
            return AggFunction.AVG;
        }
        return AggFunction.parse(definition.aggDefault());
    }

    private static AggFunction agg(String raw) {
        try {
            return AggFunction.parse(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(TelemetryErrorCode.TSD_INVALID_AGG, raw);
        }
    }

    /** 공간 집계 함수 avg(기본)·min·max·sum */
    private static AggFunction spaceFunc(String raw) {
        AggFunction func = agg(raw);
        if (func == null) {
            return AggFunction.AVG;
        }
        if (func != AggFunction.AVG && func != AggFunction.MIN && func != AggFunction.MAX && func != AggFunction.SUM) {
            throw new BusinessException(TelemetryErrorCode.TSD_INVALID_AGG, raw);
        }
        return func;
    }

    private static Resolution resolution(String raw) {
        try {
            return Resolution.parse(raw);
        } catch (IllegalArgumentException ex) {
            throw invalid("resolution");
        }
    }

    private static Options options(String fill, String quality, Boolean includeForecast, Boolean virtual) {
        FillMode fillMode;
        try {
            fillMode = FillMode.parse(fill);
        } catch (IllegalArgumentException ex) {
            throw invalid("fill");
        }
        String q = quality == null || quality.isBlank() ? "normal" : quality.strip().toLowerCase(java.util.Locale.ROOT);
        boolean all;
        if ("normal".equals(q)) {
            all = false;
        } else if ("all".equals(q)) {
            all = true;
        } else {
            throw invalid("quality");
        }
        String qualities = (all ? "{0,1,2,3,4" : "{0,4") + (Boolean.TRUE.equals(includeForecast) ? ",5}" : "}");
        return new Options(fillMode, qualities, all, Boolean.TRUE.equals(virtual));
    }

    private static List<String> metricKeys(List<String> raw) {
        List<String> keys = raw == null ? List.of() : raw.stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty())
                .distinct().toList();
        if (keys.isEmpty() || keys.size() > MAX_METRICS) {
            throw invalid("metrics");
        }
        for (String key : keys) {
            if (!METRIC_KEY.matcher(key).matches()) {
                throw invalid("metrics");
            }
        }
        return keys;
    }

    private static Instant requireFrom(Instant from, Instant to) {
        if (from == null || !from.isBefore(to)) {
            throw invalid("from");
        }
        return from;
    }

    /** 표시 시간대(BR-TSD-21): tz 파라미터 → 사용자 설정 → 조직 설정 → Asia/Seoul. 잘못된 tz는 400 */
    private String timezone(String tz, long orgId, long userId) {
        if (tz != null && !tz.isBlank()) {
            try {
                return ZoneId.of(tz.strip()).getId();
            } catch (DateTimeException ex) {
                throw invalid("tz");
            }
        }
        return zone(repository.findTimezone(orgId, userId)).getId();
    }

    private static ZoneId zone(String raw) {
        if (raw == null || raw.isBlank()) {
            return ZoneId.of("Asia/Seoul");
        }
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException ex) {
            return ZoneId.of("Asia/Seoul");
        }
    }

    private static Instant parseInstant(String raw) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeException ex) {
            return null;
        }
    }

    static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
