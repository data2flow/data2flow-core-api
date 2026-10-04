package net.java21.data2flow.core.telemetry;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.AggregatesRecomputed;
import net.java21.data2flow.contracts.message.event.RetentionPurged;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.telemetry.event.TelemetryCacheEventHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 확정된 과거 집계 조회 캐시(TSD-06.04, BR-TSD-20, AT-TSD-13.2·13.3) 통합 시험 — Testcontainers Redis 7.2(BSD, 캐시로만).
 * TC-TSD-154
 */
@TestPropertySource(properties = "data2flow.core.telemetry-cache.enabled=true")
class TelemetryQueryApiIT extends IntegrationTestSupport {

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    TelemetryCacheEventHandler cacheEvents;
    @Autowired
    StringRedisTemplate redis;

    PipelineRows rows;
    long org;
    long admin;
    long room;
    long sensor;
    Instant day0;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        rows = new PipelineRows(jdbc);
        org = fx.organization("tsc");
        admin = fx.user(org, "tsc.admin", "ADMIN");
        room = data.space(org, data.site(org, "본관"), "ROOM", "실습실");
        data.metric(org, "co2", "ppm");
        sensor = data.device(org, data.source(org, "cs"), "a1", "ACTIVE", room, null);
        day0 = clock.instant().minus(Duration.ofDays(3));
        for (int h = 0; h < 48; h++) {
            rows.agg("telemetry_1h", org, sensor, "co2", day0.plus(Duration.ofHours(h)), 60, 60, 600.0 + h, false);
        }
    }

    private String series(String to, String resolution, String expectCache) throws Exception {
        return mvc.perform(as(org, admin, get("/core/telemetry/series").param("deviceId", Long.toString(sensor)).param("metrics", "co2")
                        .param("from", day0.toString()).param("to", to).param("resolution", resolution)))
                .andExpect(status().isOk()).andExpect(header().string("X-Cache", expectCache)).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("[TSD-06.04][AT-TSD-13.2][AT-TSD-13.3] 같은 과거 1h 조회 두 번째는 X-Cache: HIT, 재계산(EVT-TSD-03)되면 무효화되어 새 값 — TC-TSD-154")
    void hitThenInvalidate() throws Exception {
        String to = day0.plus(Duration.ofHours(48)).toString();
        String first = series(to, "1h", "MISS");
        String second = series(to, "1h", "HIT");
        assertThat(second).isEqualTo(first);
        jdbc.sql("UPDATE data2flow_pipeline.telemetry_1h SET avg = 1 WHERE device_id = :d").param("d", sensor).update();
        series(to, "1h", "HIT");
        cacheEvents.handle(DomainEvent.of(EventType.AGGREGATES_RECOMPUTED, org, new AggregatesRecomputed("1h",
                List.of(new AggregatesRecomputed.Item(sensor, "co2", day0, day0.plus(Duration.ofHours(1))))), null, clock));
        String fresh = series(to, "1h", "MISS");
        assertThat((Double) JsonPath.read(fresh, "$.response.series[0].points[0][1]")).isEqualTo(1.0);
        series(to, "1h", "HIT");
        cacheEvents.handle(DomainEvent.of(EventType.RETENTION_PURGED, org, new RetentionPurged("TELEMETRY", day0, day0, 10, false), null, clock));
        series(to, "1h", "MISS");
    }

    @Test
    @DisplayName("[TSD-06.04][BR-TSD-20] 실시간 구간(끝이 now − 2×단위보다 최근)·원본·끝 시각 없음은 항상 MISS, 권한 범위가 다르면 캐시를 나누어 씀 — TC-TSD-153·154")
    void realtimeAndScope() throws Exception {
        String recent = clock.instant().minus(Duration.ofMinutes(90)).toString();
        series(recent, "1h", "MISS");
        series(recent, "1h", "MISS");
        String rawTo = day0.plus(Duration.ofHours(2)).toString();
        series(rawTo, "raw", "MISS");
        series(rawTo, "raw", "MISS");
        String to = day0.plus(Duration.ofHours(48)).toString();
        series(to, "1h", "MISS");
        long limited = fx.user(org, "tsc.viewer", "VIEWER");
        data.spaceScope(org, limited, List.of(room));
        mvc.perform(as(org, limited, get("/core/telemetry/series").param("deviceId", Long.toString(sensor)).param("metrics", "co2")
                .param("from", day0.toString()).param("to", to).param("resolution", "1h"))).andExpect(header().string("X-Cache", "MISS"));
        long other = fx.organization("tsc2");
        long otherAdmin = fx.user(other, "tsc2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/telemetry/series").param("deviceId", Long.toString(sensor)).param("metrics", "co2")
                .param("from", day0.toString()).param("to", to).param("resolution", "1h"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[TSD-06.04][DSH-02.04] 공간 집계·여러 계열·공간 비교도 과거 구간은 캐시, 기기 재계산이면 공간 캐시도 지움 — TC-TSD-154")
    void spaceQueries() throws Exception {
        String to = day0.plus(Duration.ofHours(48)).toString();
        for (String expect : List.of("MISS", "HIT")) {
            mvc.perform(as(org, admin, get("/core/telemetry/space-series").param("spaceId", Long.toString(room)).param("metric", "co2")
                    .param("from", day0.toString()).param("to", to).param("resolution", "1h"))).andExpect(header().string("X-Cache", expect));
            mvc.perform(as(org, admin, json(post("/core/telemetry/query"), "{\"series\":[{\"deviceId\":%d,\"metric\":\"co2\"}],\"from\":\"%s\",\"to\":\"%s\",\"resolution\":\"1h\"}"
                    .formatted(sensor, day0, to)))).andExpect(header().string("X-Cache", expect));
            mvc.perform(as(org, admin, json(post("/core/telemetry/compare-spaces"), "{\"spaceIds\":[\"%d\"],\"metricKey\":\"co2\",\"from\":\"%s\",\"to\":\"%s\",\"resolution\":\"1h\"}"
                    .formatted(room, day0, to)))).andExpect(header().string("X-Cache", expect)).andExpect(jsonPath("$.response.effectiveResolution").value("1h"));
        }
        cacheEvents.handle(DomainEvent.of(EventType.AGGREGATES_RECOMPUTED, org, new AggregatesRecomputed("1h",
                List.of(new AggregatesRecomputed.Item(sensor, "co2", day0, day0.plus(Duration.ofHours(1))))), null, clock));
        mvc.perform(as(org, admin, get("/core/telemetry/space-series").param("spaceId", Long.toString(room)).param("metric", "co2")
                .param("from", day0.toString()).param("to", to).param("resolution", "1h"))).andExpect(header().string("X-Cache", "MISS"));
    }
}
