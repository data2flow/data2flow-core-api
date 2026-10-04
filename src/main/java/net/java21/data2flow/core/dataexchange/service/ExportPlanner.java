package net.java21.data2flow.core.dataexchange.service;

import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.dataexchange.domain.ExportPlan;
import net.java21.data2flow.core.dataexchange.domain.ExportPlan.PlanSeries;
import net.java21.data2flow.core.dataexchange.repository.ExportDataRepository;
import net.java21.data2flow.core.telemetry.domain.AggFunction;
import net.java21.data2flow.core.telemetry.domain.Resolution;
import net.java21.data2flow.core.telemetry.domain.ResolutionPlanner;
import net.java21.data2flow.core.telemetry.domain.TelemetryErrorCode;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.QueryRequest;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.QuerySeries;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository.DeviceInfo;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository.MetricInfo;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository.SpaceDevice;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository.SpaceInfo;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 조회 조건(API-TSD-04 본문)을 지금 사용자 권한 범위로 확정한 내보내기 계획으로 바꾼다(TSD-04.01, AT-TSD-04.5). 권한 밖·없는 기기와 공간은
 * 조용히 뺀다(BR-TSD-13과 같음). 원본 31일·10,000점 제한은 화면 조회 규칙이라 내보내기에는 쓰지 않고 보관 기간만 본다.
 * auto 단위는 공간 계열이 없으면 원본(보관 기간 안일 때), 아니면 보관된 가장 작은 집계 단위다.
 */
@Component
public class ExportPlanner {

    static final int MAX_SERIES = 50;
    private static final Pattern METRIC_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    private final RoleChecker roleChecker;
    private final TelemetryQueryRepository telemetry;
    private final ExportDataRepository data;
    private final Clock clock;

    public ExportPlanner(RoleChecker roleChecker, TelemetryQueryRepository telemetry, ExportDataRepository data, Clock clock) {
        this.roleChecker = roleChecker;
        this.telemetry = telemetry;
        this.data = data;
        this.clock = clock;
    }

    /**
     * @param range 정기 실행이면 그 기간(조회 조건의 from·to 대신), 아니면 null
     */
    public ExportPlan plan(QueryRequest q, String layout, boolean includeQuality, String tz, Instant[] range) {
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (q == null || q.series() == null || q.series().isEmpty()) {
            throw invalid("query.series");
        }
        if (q.series().size() > MAX_SERIES) {
            throw new BusinessException(TelemetryErrorCode.TSD_TOO_MANY_SERIES);
        }
        Instant now = clock.instant();
        Instant from = range != null ? range[0] : q.from();
        Instant to = range != null ? range[1] : q.to() == null ? now : q.to();
        if (from == null) {
            throw invalid("query.from");
        }
        if (!from.isBefore(to)) {
            throw invalid("query.to");
        }
        String quality = q.quality() == null || q.quality().isBlank() ? "normal" : q.quality().strip().toLowerCase(Locale.ROOT);
        if (!"normal".equals(quality) && !"all".equals(quality)) {
            throw invalid("query.quality");
        }
        List<Integer> qualities = new ArrayList<>("all".equals(quality) ? List.of(0, 1, 2, 3, 4) : List.of(0, 4));
        if (Boolean.TRUE.equals(q.includeForecast())) {
            qualities.add(5);
        }
        boolean virtual = Boolean.TRUE.equals(q.virtual() != null ? q.virtual() : q.includeVirtual());
        for (int i = 0; i < q.series().size(); i++) {
            QuerySeries s = q.series().get(i);
            if (s == null || (s.deviceId() == null) == (s.spaceId() == null)) {
                throw invalid("query.series[" + i + "]");
            }
            if (s.metric() == null || !METRIC_KEY.matcher(s.metric().strip()).matches()) {
                throw invalid("query.series[" + i + "].metric");
            }
        }
        boolean hasSpace = q.series().stream().anyMatch(s -> s.spaceId() != null);
        Resolution level = resolution(q.resolution(), from, now, hasSpace);
        SpaceScope scope = roleChecker.spaceScope();
        Map<String, MetricInfo> metrics = telemetry.findMetrics(orgId,
                new LinkedHashSet<>(q.series().stream().map(s -> s.metric().strip()).toList()));
        List<Long> deviceIds = q.series().stream().map(QuerySeries::deviceId).filter(Objects::nonNull).distinct().toList();
        Map<Long, DeviceInfo> devices = new HashMap<>();
        for (DeviceInfo d : telemetry.findDevices(orgId, deviceIds)) {
            if (scope.unrestricted() || scope.includes(d.spaceId())) {
                devices.put(d.id(), d);
            }
        }
        Map<Long, SpaceInfo> spaces = new HashMap<>();
        Map<Long, List<Long>> spaceDevices = new HashMap<>();
        for (QuerySeries s : q.series()) {
            if (s.spaceId() == null || spaces.containsKey(s.spaceId())) {
                continue;
            }
            telemetry.findSpace(orgId, s.spaceId()).filter(sp -> scope.unrestricted() || scope.includes(sp.id())).ifPresent(sp -> {
                spaces.put(sp.id(), sp);
                List<Long> ids = new ArrayList<>();
                for (SpaceDevice d : telemetry.findSpaceDevices(orgId, sp, true, virtual, List.of(), List.of())) {
                    if (scope.unrestricted() || scope.includes(d.spaceId())) {
                        ids.add(d.deviceId());
                    }
                }
                spaceDevices.put(sp.id(), ids);
            });
        }
        Set<Long> pathSpaces = new LinkedHashSet<>(spaces.keySet());
        devices.values().forEach(d -> {
            if (d.spaceId() != null) {
                pathSpaces.add(d.spaceId());
            }
        });
        Map<Long, String> paths = data.spacePathNames(orgId, List.copyOf(pathSpaces));
        List<PlanSeries> series = new ArrayList<>();
        for (QuerySeries s : q.series()) {
            String metric = s.metric().strip();
            MetricInfo def = metrics.get(metric);
            String unit = def == null ? null : def.unit();
            if (s.deviceId() != null) {
                DeviceInfo d = devices.get(s.deviceId());
                if (d == null) {
                    continue;
                }
                String agg = null;
                if (level != Resolution.RAW) {
                    AggFunction f = parseAgg(s.agg());
                    if (f == null) {
                        f = def == null || def.aggDefault() == null ? AggFunction.AVG : AggFunction.parse(def.aggDefault());
                    }
                    if (!f.allowedFor(def == null ? null : def.valueType())) {
                        throw new BusinessException(TelemetryErrorCode.TSD_INVALID_AGG, f.key());
                    }
                    agg = f.key();
                }
                series.add(new PlanSeries("DEVICE", d.id(), d.name(), d.spaceId(), d.spaceId() == null ? null : paths.get(d.spaceId()),
                        metric, unit, agg, s.label(), List.of()));
            } else {
                SpaceInfo sp = spaces.get(s.spaceId());
                if (sp == null) {
                    continue;
                }
                AggFunction f = parseAgg(s.agg());
                if (f == null) {
                    f = AggFunction.AVG;
                }
                if (f != AggFunction.AVG && f != AggFunction.MIN && f != AggFunction.MAX && f != AggFunction.SUM) {
                    throw new BusinessException(TelemetryErrorCode.TSD_INVALID_AGG, f.key());
                }
                String path = paths.get(sp.id());
                String name = path == null ? null : path.substring(path.lastIndexOf('/') + 1);
                series.add(new PlanSeries("SPACE", null, name, sp.id(), path, metric, unit, f.key(), s.label(), spaceDevices.get(sp.id())));
            }
        }
        String zone = timezone(tz != null ? tz : q.tz(), orgId, user.userId());
        String unit = data.findTemperatureUnit(orgId, user.userId());
        return new ExportPlan(level.key(), from, to, zone, layout, includeQuality, qualities, virtual, unit, series);
    }

    /** 단위 선택: 지정이면 보관 기간 확인, auto면 원본(공간 계열 없음) → 1m → 1h → 1d 중 보관된 가장 작은 것 */
    static Resolution resolution(String raw, Instant from, Instant now, boolean hasSpace) {
        Resolution requested;
        try {
            requested = Resolution.parse(raw);
        } catch (IllegalArgumentException ex) {
            throw invalid("query.resolution");
        }
        if (requested != null) {
            if (requested == Resolution.RAW && hasSpace) {
                return Resolution.M1;
            }
            if (!ResolutionPlanner.retained(requested, from, now)) {
                throw new BusinessException(TelemetryErrorCode.TSD_RESOLUTION_UNAVAILABLE, requested.key());
            }
            return requested;
        }
        for (Resolution r : Resolution.values()) {
            if (r == Resolution.RAW && hasSpace) {
                continue;
            }
            if (ResolutionPlanner.retained(r, from, now)) {
                return r;
            }
        }
        return Resolution.D1;
    }

    private static AggFunction parseAgg(String raw) {
        try {
            return AggFunction.parse(raw);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(TelemetryErrorCode.TSD_INVALID_AGG, raw);
        }
    }

    private String timezone(String tz, long orgId, long userId) {
        if (tz != null && !tz.isBlank()) {
            try {
                return ZoneId.of(tz.strip()).getId();
            } catch (DateTimeException ex) {
                throw invalid("tz");
            }
        }
        String stored = telemetry.findTimezone(orgId, userId);
        try {
            return stored == null || stored.isBlank() ? "Asia/Seoul" : ZoneId.of(stored).getId();
        } catch (DateTimeException ex) {
            return "Asia/Seoul";
        }
    }

    static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
