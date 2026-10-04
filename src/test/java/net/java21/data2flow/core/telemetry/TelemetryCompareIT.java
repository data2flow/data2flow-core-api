package net.java21.data2flow.core.telemetry;

import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 공간 비교(DSH-02.04): 공간 여러 곳의 같은 측정 항목을 같은 집계 단위로 겹쳐 보기 — TC-DSH-019 */
class TelemetryCompareIT extends IntegrationTestSupport {

    PipelineRows rows;
    long org;
    long viewer;
    long a;
    long b;
    long c;
    Instant from;

    @BeforeEach
    void setUp() {
        rows = new PipelineRows(jdbc);
        org = fx.organization("cmp");
        viewer = fx.user(org, "cmp.viewer", "VIEWER");
        long floor = data.space(org, data.site(org, "본관"), "FLOOR", "3층");
        a = data.space(org, floor, "ROOM", "301");
        b = data.space(org, floor, "ROOM", "302");
        c = data.space(org, floor, "ROOM", "303");
        data.metric(org, "co2", "ppm");
        long source = data.source(org, "cs");
        from = clock.instant().minus(Duration.ofDays(7));
        int i = 0;
        for (long space : List.of(a, b, c)) {
            long d1 = data.device(org, source, "s" + i + "a", "ACTIVE", space, null);
            long d2 = data.device(org, source, "s" + i + "b", "ACTIVE", space, null);
            for (int h = 0; h < 7 * 24; h++) {
                rows.agg("telemetry_1h", org, d1, "co2", from.plus(Duration.ofHours(h)), 60, 60, 500.0 + i * 100, false);
                rows.agg("telemetry_1h", org, d2, "co2", from.plus(Duration.ofHours(h)), 60, 60, 700.0 + i * 100, false);
            }
            i++;
        }
    }

    private String body(List<Long> spaces) {
        return "{\"spaceIds\":[%s],\"metricKey\":\"co2\",\"agg\":\"avg\",\"from\":\"%s\",\"to\":\"%s\",\"resolution\":\"1h\"}"
                .formatted(String.join(",", spaces.stream().map(s -> "\"" + s + "\"").toList()), from, clock.instant());
    }

    @Test
    @DisplayName("[DSH-02.04] 공간 3곳 평균 CO2 7일을 1h로 비교: 계열 3개(요청 순서), 같은 구간 시각, 기여 기기 수 2, 단위 ppm — TC-DSH-019")
    void compareThreeSpaces() throws Exception {
        mvc.perform(as(org, viewer, json(post("/core/telemetry/compare-spaces"), body(List.of(c, a, b))))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.effectiveResolution").value("1h"))
                .andExpect(jsonPath("$.response.unit").value("ppm"))
                .andExpect(jsonPath("$.response.series", hasSize(3)))
                .andExpect(jsonPath("$.response.series[0].spaceName").value("303"))
                .andExpect(jsonPath("$.response.series[0].deviceCount").value(2))
                .andExpect(jsonPath("$.response.series[0].points", hasSize(168)))
                .andExpect(jsonPath("$.response.series[0].points[0][1]").value(800.0))
                .andExpect(jsonPath("$.response.series[1].points[0][1]").value(600.0))
                .andExpect(jsonPath("$.response.series[1].points[0][2]").value(2));
    }

    @Test
    @DisplayName("[DSH-02.04] 7곳 이상 400 WIDGET_QUERY_INVALID, 권한 밖 공간이 섞이면 404(존재 노출 없음), 없는 공간 404, 빈 목록 400 — TC-DSH-018·019")
    void validationAndScope() throws Exception {
        mvc.perform(as(org, viewer, json(post("/core/telemetry/compare-spaces"), body(List.of(a, b, c, a + 100, a + 101, a + 102, a + 103)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("WIDGET_QUERY_INVALID"));
        data.spaceScope(org, viewer, List.of(a));
        mvc.perform(as(org, viewer, json(post("/core/telemetry/compare-spaces"), body(List.of(a, b)))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("RESOURCE_NOT_FOUND"));
        mvc.perform(as(org, viewer, json(post("/core/telemetry/compare-spaces"), body(List.of(a))))).andExpect(status().isOk());
        mvc.perform(as(org, viewer, json(post("/core/telemetry/compare-spaces"), body(List.of(999999L))))).andExpect(status().isNotFound());
        mvc.perform(as(org, viewer, json(post("/core/telemetry/compare-spaces"), body(List.of())))).andExpect(status().isBadRequest());
    }
}
