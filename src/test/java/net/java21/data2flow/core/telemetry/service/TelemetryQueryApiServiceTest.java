package net.java21.data2flow.core.telemetry.service;

import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.SeriesResponse;
import net.java21.data2flow.core.telemetry.service.TelemetryQueryService.SeriesQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 집계 조회 캐시 판단(TSD-06.04, BR-TSD-20) — TC-TSD-153 */
class TelemetryQueryApiServiceTest {

    static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");

    TelemetryQueryService queries = mock(TelemetryQueryService.class);
    TelemetryCache cache = mock(TelemetryCache.class);
    RoleChecker roles = mock(RoleChecker.class);
    TelemetryCachedQueries cached;

    @BeforeEach
    void setUp() {
        given(roles.currentUser()).willReturn(new CurrentUser(7, 1));
        given(roles.spaceScope()).willReturn(SpaceScope.all());
        given(cache.enabled()).willReturn(true);
        given(cache.get(anyString())).willReturn(Optional.empty());
        cached = new TelemetryCachedQueries(queries, cache, roles, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SeriesQuery query(Instant to) {
        return new SeriesQuery(5L, List.of("co2"), NOW.minusSeconds(86400 * 3), to, "1h", null, null, null, null, null, "UTC");
    }

    @Test
    @DisplayName("[TSD-06.04][BR-TSD-20] 끝이 now − 2×1h 이전인 1h 결과만 저장, 그 뒤 구간·원본·끝 없음은 저장하지 않음, 적중이면 원래 조회를 부르지 않음 — TC-TSD-153")
    void cacheDecision() {
        given(queries.series(any())).willReturn(new SeriesResponse("1h", "REQUESTED", false, "UTC", 0, List.of()));
        assertThat(cached.series(query(NOW.minusSeconds(7200))).hit()).isFalse();
        verify(cache).put(eq(1L), anyString(), anyString(), eq(List.of(5L)), anyBoolean());

        TelemetryCache other = mock(TelemetryCache.class);
        given(other.enabled()).willReturn(true);
        given(other.get(anyString())).willReturn(Optional.empty());
        TelemetryCachedQueries recent = new TelemetryCachedQueries(queries, other, roles, JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        recent.series(query(NOW.minusSeconds(3600)));
        recent.series(query(null));
        given(queries.series(any())).willReturn(new SeriesResponse("raw", "REQUESTED", false, "UTC", 0, List.of()));
        recent.series(query(NOW.minusSeconds(86400)));
        verify(other, never()).put(anyLong(), anyString(), anyString(), any(), anyBoolean());

        given(cache.get(anyString())).willReturn(Optional.of("{\"resolutionUsed\":\"1h\",\"reason\":\"REQUESTED\",\"truncated\":false,"
                + "\"timezone\":\"UTC\",\"excludedDeviceCount\":0,\"series\":[]}"));
        TelemetryCachedQueries.Cached<SeriesResponse> hit = cached.series(query(NOW.minusSeconds(7200)));
        assertThat(hit.hit()).isTrue();
        assertThat(hit.header()).isEqualTo("HIT");
        assertThat(hit.value().resolutionUsed()).isEqualTo("1h");
    }

    @Test
    @DisplayName("[TSD-06.04] 캐시가 꺼져 있으면 항상 MISS, 키는 조직별 접두사 data2flow:")
    void disabled() {
        given(cache.enabled()).willReturn(false);
        given(queries.series(any())).willReturn(new SeriesResponse("1h", "REQUESTED", false, "UTC", 0, List.of()));
        assertThat(cached.series(query(NOW.minusSeconds(7200))).header()).isEqualTo("MISS");
        verify(cache, never()).get(anyString());
        assertThat(TelemetryCache.key(3, "x")).startsWith("data2flow:core:tsq:3:");
    }
}
