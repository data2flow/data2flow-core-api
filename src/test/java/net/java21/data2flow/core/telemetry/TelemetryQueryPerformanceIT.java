package net.java21.data2flow.core.telemetry;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 차트·원본 조회 시간 확인(NFR-01.06·01.07)의 core 쪽 연기 시험. 정식 측정은 staging k6(TC-NFR-006·007, data2flow-manifests
 * {@code tests/load/performance-02_2·02_3.js})이고, 여기서는 같은 모양의 데이터(기기 1대·측정 항목 1개: 30일 1시간, 1년 1일, 7일 원본 1분)를
 * Testcontainers DB에 넣고 반복 조회의 p95가 기준(2초·3초) 안인지 본다. CI 장비 차이로 실패하지 않게 기준을 그대로 쓰고 첫 조회(예열)는 뺀다.
 */
class TelemetryQueryPerformanceIT extends IntegrationTestSupport {

    long org;
    long admin;
    long sensor;
    Instant now;

    @BeforeEach
    void setUp() {
        org = fx.organization("nfr");
        admin = fx.user(org, "nfr.admin", "ADMIN");
        long room = data.space(org, data.site(org, "본관"), "ROOM", "실습실");
        data.metric(org, "temperature", "℃");
        sensor = data.device(org, data.source(org, "cs"), "a1", "ACTIVE", room, null);
        now = clock.instant();
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry_1h (device_id, metric_key, bucket, organization_id, count, count_all, avg, min, max, sum, last)
                        SELECT :d, 'temperature', CAST(:from AS timestamptz) + i * interval '1 hour', :o, 60, 60, 22, 21, 23, 1320, 22
                          FROM generate_series(0, 719) i""")
                .param("d", sensor).param("o", org).param("from", Pg.ts(now.minus(Duration.ofDays(30)))).update();
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry_1d (device_id, metric_key, bucket, organization_id, count, count_all, avg, min, max, sum, last)
                        SELECT :d, 'temperature', CAST(:from AS timestamptz) + i * interval '1 day', :o, 1440, 1440, 22, 18, 26, 31680, 22
                          FROM generate_series(0, 364) i""")
                .param("d", sensor).param("o", org).param("from", Pg.ts(now.minus(Duration.ofDays(365)))).update();
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry (device_id, metric_key, time, organization_id, value, quality, received_at)
                        SELECT :d, 'temperature', ts, :o, 22 + (i % 7) * 0.1, 0, ts
                          FROM generate_series(0, 10079) i, LATERAL (SELECT CAST(:from AS timestamptz) + i * interval '1 minute' AS ts) x""")
                .param("d", sensor).param("o", org).param("from", Pg.ts(now.minus(Duration.ofDays(7)))).update();
    }

    private long p95Millis(String resolution, Duration span, int expectedPoints) throws Exception {
        List<Long> took = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            long start = System.nanoTime();
            mvc.perform(as(org, admin, get("/core/telemetry/series").param("deviceId", Long.toString(sensor)).param("metrics", "temperature")
                            .param("from", now.minus(span).toString()).param("to", now.toString()).param("resolution", resolution)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.response.series[0].points.length()").value(expectedPoints));
            if (i > 0) {
                took.add((System.nanoTime() - start) / 1_000_000);
            }
        }
        took.sort(Long::compare);
        return took.get((int) Math.ceil(took.size() * 0.95) - 1);
    }

    @Test
    @DisplayName("[NFR-01.06] 기기 1대·측정 항목 1개: 30일 1시간 단위와 1년 1일 단위 차트 조회 p95 ≤ 2초 — TC-NFR-006(core 연기 시험)")
    void chartQueries() throws Exception {
        assertThat(p95Millis("1h", Duration.ofDays(30), 720)).isLessThanOrEqualTo(2000);
        assertThat(p95Millis("1d", Duration.ofDays(365), 365)).isLessThanOrEqualTo(2000);
    }

    @Test
    @DisplayName("[NFR-01.07] 기기 1대·측정 항목 1개 7일 원본(1분, 10,080점 중 첫 10,000점 + 커서) 조회 p95 ≤ 3초 — TC-NFR-007(core 연기 시험)")
    void rawQuery() throws Exception {
        assertThat(p95Millis("raw", Duration.ofDays(7), 10_000)).isLessThanOrEqualTo(3000);
    }
}
