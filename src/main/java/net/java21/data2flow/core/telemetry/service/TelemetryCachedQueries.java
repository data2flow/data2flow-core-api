package net.java21.data2flow.core.telemetry.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.telemetry.domain.Resolution;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.CompareSpacesRequest;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.CompareSpacesResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.QueryRequest;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.QuerySeries;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.SeriesResponse;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.SpaceSeriesResponse;
import net.java21.data2flow.core.telemetry.service.TelemetryQueryService.SeriesQuery;
import net.java21.data2flow.core.telemetry.service.TelemetryQueryService.SpaceQuery;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 집계 조회 캐시 앞단(TSD-06.04, BR-TSD-20, AT-TSD-13.2·13.3). 권한 확인 → 캐시 조회 → 없으면 원래 조회 → 끝 시각이 {@code now - 2 × 집계 단위}
 * 보다 과거인 집계 결과만 저장한다. 원본(raw)·실시간 구간·끝 시각을 주지 않은 조회(끝 = 지금)는 저장하지 않는다.
 * 키는 조직·조건·권한 범위(공간 범위 해시)·표시 시간대 기준(tz가 없으면 사용자)으로 만든다.
 */
@Service
public class TelemetryCachedQueries {

    /** 결과와 캐시 적중 여부(응답 헤더 {@code X-Cache}) */
    public record Cached<T>(T value, boolean hit) {
        public String header() {
            return hit ? "HIT" : "MISS";
        }
    }

    private final TelemetryQueryService queries;
    private final TelemetryCache cache;
    private final RoleChecker roleChecker;
    private final JsonMapper json;
    private final Clock clock;

    public TelemetryCachedQueries(TelemetryQueryService queries, TelemetryCache cache, RoleChecker roleChecker, JsonMapper json, Clock clock) {
        this.queries = queries;
        this.cache = cache;
        this.roleChecker = roleChecker;
        this.json = json;
        this.clock = clock;
    }

    /** API-TSD-02 */
    public Cached<SeriesResponse> series(SeriesQuery q) {
        return cached("series", q, q.to(), q.tz(), q.deviceId() == null ? List.of() : List.of(q.deviceId()), false, SeriesResponse.class,
                () -> queries.series(q), SeriesResponse::resolutionUsed);
    }

    /** API-TSD-03 */
    public Cached<SpaceSeriesResponse> spaceSeries(SpaceQuery q) {
        return cached("space-series", q, q.to(), q.tz(), List.of(), true, SpaceSeriesResponse.class, () -> queries.spaceSeries(q),
                SpaceSeriesResponse::resolutionUsed);
    }

    /** API-TSD-04 */
    public Cached<SeriesResponse> query(QueryRequest req) {
        List<Long> devices = req == null || req.series() == null ? List.of()
                : req.series().stream().filter(Objects::nonNull).map(QuerySeries::deviceId).filter(Objects::nonNull).distinct().toList();
        boolean spaceWide = req != null && req.series() != null
                && req.series().stream().anyMatch(s -> s != null && s.spaceId() != null);
        return cached("query", req, req == null ? null : req.to(), req == null ? null : req.tz(), devices, spaceWide, SeriesResponse.class,
                () -> queries.query(req), SeriesResponse::resolutionUsed);
    }

    /** 공간 비교(DSH-02.04) */
    public Cached<CompareSpacesResponse> compareSpaces(CompareSpacesRequest req) {
        return cached("compare-spaces", req, req == null ? null : req.to(), req == null ? null : req.tz(), List.of(), true,
                CompareSpacesResponse.class, () -> queries.compareSpaces(req), CompareSpacesResponse::effectiveResolution);
    }

    private <T> Cached<T> cached(String kind, Object condition, Instant to, String tz, List<Long> deviceIds, boolean spaceWide,
                                 Class<T> type, Supplier<T> loader, java.util.function.Function<T, String> resolutionOf) {
        roleChecker.require(Permission.TS_READ);
        if (!cache.enabled() || to == null) {
            return new Cached<>(loader.get(), false);
        }
        CurrentUser user = roleChecker.currentUser();
        String key = TelemetryCache.key(user.organizationId(), condition(kind, condition, tz, user));
        Optional<String> hit = cache.get(key);
        if (hit.isPresent()) {
            return new Cached<>(json.readValue(hit.get(), type), true);
        }
        T value = loader.get();
        Resolution level = parseLevel(resolutionOf.apply(value));
        if (level != null && level != Resolution.RAW && !to.isAfter(clock.instant().minus(level.bucket().multipliedBy(2)))) {
            cache.put(user.organizationId(), key, json.writeValueAsString(value), deviceIds, spaceWide);
        }
        return new Cached<>(value, false);
    }

    private static Resolution parseLevel(String key) {
        try {
            return Resolution.parse(key);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** 조건 문자열: 종류 + 요청 JSON + 공간 범위 + 시간대 기준 */
    private String condition(String kind, Object condition, String tz, CurrentUser user) {
        SpaceScope scope = roleChecker.spaceScope();
        String scopeKey = scope.unrestricted() ? "*" : new TreeSet<>(scope.allowedSpaceIds()).toString();
        String tzKey = tz == null || tz.isBlank() ? "user:" + user.userId() : tz.strip();
        Map<String, Object> parts = new TreeMap<>();
        parts.put("kind", kind);
        parts.put("q", condition);
        parts.put("scope", scopeKey);
        parts.put("tz", tzKey);
        parts.put("token", user.accessTokenId() == null ? "" : "t");
        List<String> pieces = new ArrayList<>();
        parts.forEach((k, v) -> pieces.add(k + "=" + json.writeValueAsString(v)));
        return String.join("|", pieces);
    }
}
