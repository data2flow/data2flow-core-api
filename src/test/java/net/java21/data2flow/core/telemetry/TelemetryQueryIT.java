package net.java21.data2flow.core.telemetry;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 시계열 조회 API 통합 시험(TSD-03.01~03.06, TSD-06.01·06.02, TSD-01.02·01.05, NFR-01.05). 실제 PostgreSQL 18에 pipeline 소유 테이블 행을
 * 넣고 조회 결과·조직 조건을 확인한다 — TC-TSD-054·064·072·080·088·093·097·142·148.
 */
class TelemetryQueryIT extends IntegrationTestSupport {

    static final Instant T0 = MutableClock.T0;

    PipelineRows rows;
    long org;
    long admin;
    long site;
    long floor3;
    long room;
    long source;
    long model;
    long sensor;

    @BeforeEach
    void setUp() {
        rows = new PipelineRows(jdbc);
        org = fx.organization("tsd");
        admin = fx.user(org, "ts.admin", "ADMIN");
        site = data.site(org, "본관");
        long building = data.space(org, site, "BUILDING", "A동");
        floor3 = data.space(org, building, "FLOOR", "3층");
        room = data.space(org, floor3, "ROOM", "실습실");
        source = data.source(org, "chirp");
        data.metric(org, "temperature", "℃");
        data.metric(org, "co2", "ppm");
        model = data.model(org, "AM107", List.of("temperature", "co2"));
        sensor = data.device(org, source, "24e1", "ACTIVE", room, model);
        jdbc.sql("UPDATE data2flow_core.devices SET expected_interval_sec = 60 WHERE id = :id").param("id", sensor).update();
    }

    private void rawMinutes(long device, String metric, Instant from, int count, int quality) {
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry (device_id, metric_key, time, organization_id, value, quality, received_at)
                        SELECT :device, :metric, ts, :org, 20 + (i % 10), :quality, ts
                          FROM generate_series(0, :count - 1) i, LATERAL (SELECT CAST(:from AS timestamptz) + i * interval '1 minute' AS ts) x""")
                .param("device", device).param("metric", metric).param("org", org).param("quality", quality)
                .param("count", count).param("from", Pg.ts(from)).update();
    }

    private String series(String query) throws Exception {
        return mvc.perform(as(org, admin, get("/core/telemetry/series?" + query))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("[TSD-03.01][AT-TSD-01.1][AT-TSD-01.2] auto: 1분 주기 24시간 → 1h(1m은 1,440점), 30분 → raw(품질 코드 포함) — TC-TSD-051·054")
    void autoResolution() throws Exception {
        rawMinutes(sensor, "temperature", T0.minus(Duration.ofHours(24)), 1440, 0);
        for (int h = 24; h >= 1; h--) {
            rows.agg("telemetry_1h", org, sensor, "temperature", T0.minus(Duration.ofHours(h)), 60, 60, 22.5, false);
        }
        mvc.perform(as(org, admin, get("/core/telemetry/series").param("deviceId", Long.toString(sensor)).param("metrics", "temperature")
                        .param("from", T0.minus(Duration.ofHours(24)).toString()).param("to", T0.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.resolutionUsed").value("1h"))
                .andExpect(jsonPath("$.response.reason").value("AUTO"))
                .andExpect(jsonPath("$.response.series[0].metric").value("temperature"))
                .andExpect(jsonPath("$.response.series[0].unit").value("℃"))
                .andExpect(jsonPath("$.response.series[0].agg").value("avg"))
                .andExpect(jsonPath("$.response.series[0].deviceId").value(Long.toString(sensor)))
                .andExpect(jsonPath("$.response.series[0].points", hasSize(24)))
                .andExpect(jsonPath("$.response.series[0].points[0][0]").value("2026-10-02T00:00:00Z"))
                .andExpect(jsonPath("$.response.series[0].points[0][1]").value(22.5))
                .andExpect(jsonPath("$.response.series[0].points[0][2]").value(60));
        mvc.perform(as(org, admin, get("/core/telemetry/series").param("deviceId", Long.toString(sensor)).param("metrics", "temperature")
                        .param("from", T0.minus(Duration.ofMinutes(30)).toString()).param("to", T0.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.resolutionUsed").value("raw"))
                .andExpect(jsonPath("$.response.series[0].points", hasSize(30)))
                .andExpect(jsonPath("$.response.series[0].points[0][2]").value(0))
                .andExpect(jsonPath("$.response.series[0].agg").value(nullValue()));
        // 원본이 보관 기간(365일) 밖이면 1분·1시간 집계 단위로 올라가고 사유 CAPPED(BR-TSD-08)
        mvc.perform(as(org, admin, get("/core/telemetry/series").param("deviceId", Long.toString(sensor)).param("metrics", "temperature")
                        .param("from", T0.minus(Duration.ofDays(400)).toString()).param("to", T0.minus(Duration.ofDays(399)).toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.resolutionUsed").value("1h"))
                .andExpect(jsonPath("$.response.reason").value("CAPPED"));
    }

    @Test
    @DisplayName("[TSD-03.01][AT-TSD-01.5][BR-TSD-09] 오류: 2년 전 raw → 400 TSD_RESOLUTION_UNAVAILABLE, raw 32일 → TSD_RANGE_TOO_LARGE, 집계 함수·형식 오류 — TC-TSD-024·052")
    void requestErrors() throws Exception {
        String base = "deviceId=" + sensor + "&metrics=temperature";
        mvc.perform(as(org, admin, get("/core/telemetry/series?" + base + "&resolution=raw&from=2024-10-01T00:00:00Z&to=2024-10-02T00:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("TSD_RESOLUTION_UNAVAILABLE"))
                .andExpect(jsonPath("$.header.resultMessage").value("이 기간에는 raw 데이터가 보관되어 있지 않습니다"));
        mvc.perform(as(org, admin, get("/core/telemetry/series?" + base + "&resolution=raw&from=2026-09-01T00:00:00Z&to=2026-10-03T00:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("TSD_RANGE_TOO_LARGE"))
                .andExpect(jsonPath("$.header.resultMessage").value("원본 데이터는 한 번에 31일까지 조회할 수 있습니다. 집계 단위를 고르거나 내보내기를 이용하세요"));
        // 직접 지정한 집계 단위는 10,000점까지(1분 × 10일 = 14,400점)
        mvc.perform(as(org, admin, get("/core/telemetry/series?" + base + "&resolution=1m&from=2026-09-23T00:00:00Z&to=2026-10-03T00:00:00Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("TSD_RANGE_TOO_LARGE"));
        // 1분 집계 보관(90일) 밖
        mvc.perform(as(org, admin, get("/core/telemetry/series?" + base + "&resolution=1m&from=2026-05-01T00:00:00Z&to=2026-05-02T00:00:00Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("TSD_RESOLUTION_UNAVAILABLE"));
        mvc.perform(as(org, admin, get("/core/telemetry/series?" + base + "&resolution=1h&agg=median&from=2026-10-02T00:00:00Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("TSD_INVALID_AGG"));
        jdbc.sql("UPDATE data2flow_core.metrics SET value_type = 'ENUM', enum_map = '{\"0\":\"닫힘\",\"1\":\"열림\"}' WHERE organization_id = :o AND key = 'co2'")
                .param("o", org).update();
        mvc.perform(as(org, admin, get("/core/telemetry/series?deviceId=" + sensor + "&metrics=co2&resolution=1h&agg=avg&from=2026-10-02T00:00:00Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("TSD_INVALID_AGG"))
                .andExpect(jsonPath("$.header.resultMessage").value("이 측정 항목에는 avg를 쓸 수 없습니다"));
        for (String bad : new String[]{"metrics=temperature&from=2026-10-02T00:00:00Z", base + "&from=2026-10-03T00:00:00Z&to=2026-10-02T00:00:00Z",
                base + "&from=2026-10-02T00:00:00Z&resolution=5m", base + "&from=2026-10-02T00:00:00Z&fill=zero",
                base + "&from=2026-10-02T00:00:00Z&quality=bad", base + "&from=2026-10-02T00:00:00Z&tz=Mars/Base",
                "deviceId=" + sensor + "&metrics=bad-key&from=2026-10-02T00:00:00Z", "deviceId=" + sensor + "&from=2026-10-02T00:00:00Z",
                base + "&from=yesterday"}) {
            mvc.perform(as(org, admin, get("/core/telemetry/series?" + bad))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
        }
        mvc.perform(as(org, admin, get("/core/telemetry/series?deviceId=999999&metrics=temperature&from=2026-10-02T00:00:00Z")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_NOT_FOUND"))
                .andExpect(jsonPath("$.header.resultMessage").value("기기를 찾을 수 없습니다"));
    }

    @Test
    @DisplayName("[TSD-03.04][AT-TSD-01.3] quality=normal(기본)은 품질 1 점 제외, all은 포함(점 세 번째 값 1), 예보(5)는 includeForecast일 때만 — TC-TSD-077·080")
    void qualityFilter() throws Exception {
        Instant from = T0.minus(Duration.ofMinutes(10));
        data.telemetry(org, sensor, "temperature", from.plusSeconds(60), 22.0, 0, false);
        data.telemetry(org, sensor, "temperature", from.plusSeconds(120), 61.0, 1, false);
        data.telemetry(org, sensor, "temperature", from.plusSeconds(180), 22.4, 4, false);
        data.telemetry(org, sensor, "temperature", from.plusSeconds(240), 30.0, 5, false);
        String q = "deviceId=" + sensor + "&metrics=temperature&from=" + from + "&to=" + T0;
        List<Object> normal = JsonPath.read(series(q), "$.response.series[0].points[*][1]");
        assertThat(normal).containsExactly(22.0, 22.4);
        String all = series(q + "&quality=all");
        assertThat(JsonPath.<List<Object>>read(all, "$.response.series[0].points[*][2]")).containsExactly(0, 1, 4);
        assertThat(JsonPath.<List<Object>>read(series(q + "&quality=all&includeForecast=true"), "$.response.series[0].points[*][2]"))
                .containsExactly(0, 1, 4, 5);
        // 집계 단위에서 quality=all이면 표본 수는 count_all
        rows.agg("telemetry_1h", org, sensor, "temperature", T0.minus(Duration.ofHours(1)), 50, 60, 22.0, false);
        String agg = series("deviceId=" + sensor + "&metrics=temperature&resolution=1h&from=" + T0.minus(Duration.ofHours(1)) + "&to=" + T0
                + "&quality=all&agg=count");
        assertThat(JsonPath.<List<Object>>read(agg, "$.response.series[0].points[0]")).containsExactly("2026-10-02T23:00:00Z", 60.0, 60);
    }

    @Test
    @DisplayName("[TSD-03.05][BR-TSD-12] 가상 데이터는 기본 제외, virtual=true·includeVirtual=true면 포함하고 계열에 virtual 표시 — TC-TSD-085·088·093")
    void virtualFilter() throws Exception {
        long sim = data.device(org, source, "sim-1", "ACTIVE", room, model, true);
        Instant from = T0.minus(Duration.ofMinutes(10));
        data.telemetry(org, sim, "temperature", from.plusSeconds(60), 99.0, 0, true);
        data.telemetry(org, sensor, "temperature", from.plusSeconds(60), 22.0, 0, false);
        data.telemetry(org, sensor, "temperature", from.plusSeconds(120), 98.0, 0, true);
        String q = "deviceId=" + sim + "&metrics=temperature&from=" + from + "&to=" + T0;
        String hidden = series(q);
        assertThat(JsonPath.<List<Object>>read(hidden, "$.response.series[0].points")).isEmpty();
        assertThat(JsonPath.<Boolean>read(hidden, "$.response.series[0].virtual")).isTrue();
        assertThat(JsonPath.<List<Object>>read(series(q + "&virtual=true"), "$.response.series[0].points[*][1]")).containsExactly(99.0);
        assertThat(JsonPath.<List<Object>>read(series(q + "&includeVirtual=true"), "$.response.series[0].points[*][1]")).containsExactly(99.0);
        // 실제 기기에 섞인 가상 행(SIM 주입)도 기본 제외
        assertThat(JsonPath.<List<Object>>read(series("deviceId=" + sensor + "&metrics=temperature&from=" + from + "&to=" + T0),
                "$.response.series[0].points[*][1]")).containsExactly(22.0);
        // 공간 집계·여러 계열 조회도 같은 기본값
        rows.agg("telemetry_1h", org, sim, "temperature", T0.minus(Duration.ofHours(1)), 60, 60, 50.0, true);
        rows.agg("telemetry_1h", org, sensor, "temperature", T0.minus(Duration.ofHours(1)), 60, 60, 20.0, false);
        String space = "/core/telemetry/space-series?spaceId=" + room + "&metric=temperature&resolution=1h&from=" + T0.minus(Duration.ofHours(1)) + "&to=" + T0;
        mvc.perform(as(org, admin, get(space))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.points[0][1]").value(20.0)).andExpect(jsonPath("$.response.points[0][2]").value(1))
                .andExpect(jsonPath("$.response.deviceCount").value(1));
        mvc.perform(as(org, admin, get(space + "&virtual=true"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.points[0][1]").value(35.0)).andExpect(jsonPath("$.response.points[0][2]").value(2));
        mvc.perform(as(org, admin, json(post("/core/telemetry/query"), """
                        {"series":[{"deviceId":"%d","metric":"temperature"}],"from":"%s","to":"%s","resolution":"1h","virtual":true}"""
                        .formatted(sim, T0.minus(Duration.ofHours(1)), T0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.series[0].virtual").value(true))
                .andExpect(jsonPath("$.response.series[0].points[0][1]").value(50.0));
    }

    @Test
    @DisplayName("[TSD-06.02][AT-TSD-01.4][BR-TSD-10] fill=none(기본) 그대로, previous는 예상 주기×3까지만, linear는 앞뒤 사이, 공백 구간은 채우지 않고 gaps 반환 — TC-TSD-146·148")
    void fillModes() throws Exception {
        Instant base = Instant.parse("2026-10-02T10:00:00Z");
        double[] values = {10, 11, 12, 0, 0, 15, 0, 0, 18, 19};
        for (int i = 0; i < values.length; i++) {
            if (values[i] != 0) {
                rows.agg("telemetry_1m", org, sensor, "temperature", base.plusSeconds(60L * i), 1, 1, values[i], false);
            }
        }
        rows.gap(org, sensor, base.plusSeconds(360), base.plusSeconds(480), 2);
        String q = "deviceId=" + sensor + "&metrics=temperature&resolution=1m&from=" + base + "&to=" + base.plusSeconds(600);
        String none = series(q);
        assertThat(JsonPath.<List<Object>>read(none, "$.response.series[0].points[*][1]")).containsExactly(10.0, 11.0, 12.0, 15.0, 18.0, 19.0);
        assertThat(JsonPath.<String>read(none, "$.response.series[0].gaps[0].from")).isEqualTo("2026-10-02T10:06:00Z");
        String previous = series(q + "&fill=previous");
        assertThat(JsonPath.<List<Object>>read(previous, "$.response.series[0].points[*][0]")).containsExactly("2026-10-02T10:00:00Z",
                "2026-10-02T10:01:00Z", "2026-10-02T10:02:00Z", "2026-10-02T10:03:00Z", "2026-10-02T10:04:00Z", "2026-10-02T10:05:00Z",
                "2026-10-02T10:08:00Z", "2026-10-02T10:09:00Z");
        assertThat(JsonPath.<List<Object>>read(previous, "$.response.series[0].points[3]")).containsExactly("2026-10-02T10:03:00Z", 12.0, 0);
        String linear = series(q + "&fill=linear");
        assertThat(JsonPath.<List<Object>>read(linear, "$.response.series[0].points[*][1]")).containsExactly(10.0, 11.0, 12.0, 13.0, 14.0,
                15.0, 18.0, 19.0);
        // previous는 예상 주기 × 3(60초 × 3)을 넘겨 채우지 않는다
        jdbc.sql("UPDATE data2flow_core.devices SET expected_interval_sec = 10 WHERE id = :id").param("id", sensor).update();
        assertThat(JsonPath.<List<Object>>read(series(q + "&fill=previous"), "$.response.series[0].points[*][1]"))
                .containsExactly(10.0, 11.0, 12.0, 15.0, 18.0, 19.0);
    }

    @Test
    @DisplayName("[TSD-01.05][AT-TSD-01.6][BR-TSD-21] 시각은 UTC, 1d 구간은 사이트 자정(15:00Z), tz → 사용자 설정 → 조직 순서로 표시 시간대 — TC-TSD-023·024")
    void timezone() throws Exception {
        for (int d = 1; d <= 3; d++) {
            rows.agg("telemetry_1d", org, sensor, "temperature", Instant.parse("2026-09-2" + (6 + d) + "T15:00:00Z"), 1440, 1440, 20.0 + d, false);
        }
        String q = "deviceId=" + sensor + "&metrics=temperature&resolution=1d&from=2026-09-27T15:00:00Z&to=2026-09-30T15:00:00Z";
        String seoul = series(q + "&tz=Asia/Seoul");
        assertThat(JsonPath.<String>read(seoul, "$.response.timezone")).isEqualTo("Asia/Seoul");
        assertThat(JsonPath.<List<Object>>read(seoul, "$.response.series[0].points[*][0]"))
                .containsExactly("2026-09-27T15:00:00Z", "2026-09-28T15:00:00Z", "2026-09-29T15:00:00Z");
        assertThat(JsonPath.<String>read(series(q), "$.response.timezone")).isEqualTo("Asia/Seoul");
        jdbc.sql("INSERT INTO data2flow_core.user_dashboard_prefs (organization_id, user_id, time_zone) VALUES (:o, :u, 'Europe/Paris')")
                .param("o", org).param("u", admin).update();
        assertThat(JsonPath.<String>read(series(q), "$.response.timezone")).isEqualTo("Europe/Paris");
        assertThat(JsonPath.<String>read(series(q + "&tz=UTC"), "$.response.timezone")).isEqualTo("UTC");
        // 1d 채우기 격자도 사이트 자정 기준(하루 빠진 날만 채움)
        jdbc.sql("DELETE FROM data2flow_pipeline.telemetry_1d WHERE bucket = '2026-09-28T15:00:00Z'").update();
        assertThat(JsonPath.<List<Object>>read(series(q + "&fill=linear"), "$.response.series[0].points[*][0]"))
                .containsExactly("2026-09-27T15:00:00Z", "2026-09-28T15:00:00Z", "2026-09-29T15:00:00Z");
    }

    @Test
    @DisplayName("[TSD-06.01][BR-TSD-09] 원본 10,000점 초과 → truncated + nextCursor·남은 범위, raw-points 커서로 이어 받으면 누락·중복 0 — TC-TSD-140·145")
    void rawLimitAndCursor() throws Exception {
        Instant from = T0.minus(Duration.ofDays(8));
        rawMinutes(sensor, "temperature", from, 10_005, 0);
        String body = series("deviceId=" + sensor + "&metrics=temperature&resolution=raw&from=" + from + "&to=" + T0);
        assertThat(JsonPath.<Boolean>read(body, "$.response.truncated")).isTrue();
        assertThat(JsonPath.<List<Object>>read(body, "$.response.series[0].points")).hasSize(10_000);
        String cursor = JsonPath.read(body, "$.response.series[0].nextCursor");
        assertThat(JsonPath.<String>read(body, "$.response.series[0].remaining.from")).isEqualTo(from.plusSeconds(60L * 9_999).toString());
        String rest = mvc.perform(as(org, admin, get("/core/telemetry/raw-points").param("deviceId", Long.toString(sensor))
                        .param("metric", "temperature").param("from", from.toString()).param("to", T0.toString()).param("cursor", cursor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50)).andExpect(jsonPath("$.nextCursor").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(rest, "$.responses[*].t")).containsExactly(from.plusSeconds(60L * 10_000).toString(),
                from.plusSeconds(60L * 10_001).toString(), from.plusSeconds(60L * 10_002).toString(), from.plusSeconds(60L * 10_003).toString(),
                from.plusSeconds(60L * 10_004).toString());
        // 커서 목록만으로 30분 구간을 7개씩 넘겨 받기: 30점, 겹침 없음
        Set<String> seen = new HashSet<>();
        List<String> order = new ArrayList<>();
        String next = null;
        do {
            var request = get("/core/telemetry/raw-points").param("deviceId", Long.toString(sensor)).param("metric", "temperature")
                    .param("from", from.toString()).param("to", from.plusSeconds(1800).toString()).param("size", "7");
            if (next != null) {
                request.param("cursor", next);
            }
            String page = mvc.perform(as(org, admin, request)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            for (Object t : JsonPath.<List<Object>>read(page, "$.responses[*].t")) {
                assertThat(seen.add((String) t)).isTrue();
                order.add((String) t);
            }
            next = JsonPath.read(page, "$.nextCursor");
        } while (next != null);
        assertThat(order).hasSize(30).isSorted();
        mvc.perform(as(org, admin, get("/core/telemetry/raw-points").param("deviceId", Long.toString(sensor)).param("metric", "temperature")
                        .param("from", "2026-08-01T00:00:00Z").param("to", T0.toString())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("TSD_RANGE_TOO_LARGE"));
        mvc.perform(as(org, admin, get("/core/telemetry/raw-points").param("deviceId", Long.toString(sensor)).param("metric", "temperature")
                        .param("from", from.toString()).param("cursor", "!!!")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("[TSD-03.02][AT-TSD-02.1][AT-TSD-02.2][BR-TSD-11] 공간 집계: INACTIVE·가상 제외 3대 평균·기여 3, 공백 구간은 기여 수 감소, 하위 공간 포함 — TC-TSD-061·064")
    void spaceAggregate() throws Exception {
        long a = sensor;
        long b = data.device(org, source, "b", "ACTIVE", room, model);
        long c = data.device(org, source, "c", "ACTIVE", room, model);
        long inactive = data.device(org, source, "d", "INACTIVE", room, model);
        Instant h9 = Instant.parse("2026-10-02T09:00:00Z");
        Instant h10 = Instant.parse("2026-10-02T10:00:00Z");
        for (long d : new long[]{a, b, c, inactive}) {
            double v = d == a ? 20 : d == b ? 22 : d == c ? 24 : 100;
            rows.agg("telemetry_1h", org, d, "temperature", h9, 60, 60, v, false);
            if (d != a) {
                rows.agg("telemetry_1h", org, d, "temperature", h10, 60, 60, v + 1, false);
            }
        }
        String q = "/core/telemetry/space-series?metric=temperature&resolution=1h&from=" + h9 + "&to=" + h10.plusSeconds(3600) + "&spaceId=";
        mvc.perform(as(org, admin, get(q + floor3))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.spaceId").value(Long.toString(floor3)))
                .andExpect(jsonPath("$.response.func").value("avg"))
                .andExpect(jsonPath("$.response.unit").value("℃"))
                .andExpect(jsonPath("$.response.deviceCount").value(3))
                .andExpect(jsonPath("$.response.excludedDeviceCount").value(0))
                .andExpect(jsonPath("$.response.points[0][1]").value(22.0)).andExpect(jsonPath("$.response.points[0][2]").value(3))
                .andExpect(jsonPath("$.response.points[1][1]").value(24.0)).andExpect(jsonPath("$.response.points[1][2]").value(2));
        mvc.perform(as(org, admin, get(q + floor3 + "&func=max"))).andExpect(jsonPath("$.response.points[0][1]").value(24.0));
        mvc.perform(as(org, admin, get(q + floor3 + "&func=sum"))).andExpect(jsonPath("$.response.points[0][1]").value(66.0));
        mvc.perform(as(org, admin, get(q + floor3 + "&includeDescendants=false"))).andExpect(jsonPath("$.response.deviceCount").value(0))
                .andExpect(jsonPath("$.response.points", hasSize(0)));
        mvc.perform(as(org, admin, get(q + floor3 + "&func=twa"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("TSD_INVALID_AGG"));
        mvc.perform(as(org, admin, get(q.replace("resolution=1h", "resolution=raw") + floor3))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.resolutionUsed").value("1m")).andExpect(jsonPath("$.response.reason").value("CAPPED"));
        // 모델·태그 조건
        jdbc.sql("INSERT INTO data2flow_core.device_tags (organization_id, device_id, tag) VALUES (:o, :d, 'window')").param("o", org).param("d", b).update();
        mvc.perform(as(org, admin, get(q + floor3 + "&tags=window"))).andExpect(jsonPath("$.response.deviceCount").value(1))
                .andExpect(jsonPath("$.response.points[0][1]").value(22.0));
        mvc.perform(as(org, admin, get(q + floor3 + "&models=" + model))).andExpect(jsonPath("$.response.deviceCount").value(3));
        mvc.perform(as(org, admin, get(q + "999999"))).andExpect(status().isNotFound());
        mvc.perform(as(org, admin, get("/core/telemetry/space-series?metric=temperature&from=" + h9))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[TSD-03.03][TSD-03.06][AT-TSD-03.1] 여러 계열 조회: 같은 단위·같은 구간 시각, 단위별 계열, 50개 초과 TSD_TOO_MANY_SERIES — TC-TSD-069·097")
    void multiSeriesQuery() throws Exception {
        long outdoor = data.device(org, source, "out-1", "ACTIVE", site, null);
        for (int h = 3; h >= 1; h--) {
            Instant bucket = T0.minus(Duration.ofHours(h));
            rows.agg("telemetry_1h", org, sensor, "temperature", bucket, 60, 60, 22.0 + h, false);
            rows.agg("telemetry_1h", org, sensor, "co2", bucket, 60, 60, 800.0 + h, false);
            rows.agg("telemetry_1h", org, outdoor, "temperature", bucket, 60, 60, 10.0 + h, false);
        }
        String body = mvc.perform(as(org, admin, json(post("/core/telemetry/query"), """
                        {"series":[{"deviceId":"%d","metric":"temperature","label":"실내"},{"deviceId":"%d","metric":"co2"},
                                   {"deviceId":"%d","metric":"temperature","agg":"max","label":"외기"},{"spaceId":"%d","metric":"temperature"},
                                   {"deviceId":"999999","metric":"temperature"}],
                         "from":"%s","to":"%s","tz":"Asia/Seoul"}""".formatted(sensor, sensor, outdoor, room, T0.minus(Duration.ofHours(3)), T0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.resolutionUsed").value("1m"))
                .andExpect(jsonPath("$.response.excludedDeviceCount").value(1))
                .andExpect(jsonPath("$.response.series", hasSize(4)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(body, "$.response.series[*].unit")).containsExactly("℃", "ppm", "℃", "℃");
        assertThat(JsonPath.<String>read(body, "$.response.series[0].label")).isEqualTo("실내");
        assertThat(JsonPath.<String>read(body, "$.response.series[3].spaceId")).isEqualTo(Long.toString(room));
        String hourly = mvc.perform(as(org, admin, json(post("/core/telemetry/query"), """
                        {"series":[{"deviceId":"%d","metric":"temperature"},{"deviceId":"%d","metric":"co2"},{"deviceId":"%d","metric":"temperature","agg":"max"},
                                   {"spaceId":"%d","metric":"temperature"}],
                         "from":"%s","to":"%s","resolution":"1h"}""".formatted(sensor, sensor, outdoor, room, T0.minus(Duration.ofHours(3)), T0))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Object> times = JsonPath.read(hourly, "$.response.series[0].points[*][0]");
        assertThat(times).hasSize(3);
        for (int i = 1; i < 4; i++) {
            assertThat(JsonPath.<List<Object>>read(hourly, "$.response.series[" + i + "].points[*][0]")).isEqualTo(times);
        }
        assertThat(JsonPath.<Object>read(hourly, "$.response.series[3].deviceCount")).isEqualTo(1);
        StringBuilder many = new StringBuilder("{\"series\":[");
        for (int i = 0; i < 51; i++) {
            many.append(i == 0 ? "" : ",").append("{\"deviceId\":\"").append(sensor).append("\",\"metric\":\"m").append(i).append("\"}");
        }
        many.append("],\"from\":\"2026-10-02T00:00:00Z\"}");
        mvc.perform(as(org, admin, json(post("/core/telemetry/query"), many.toString()))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("TSD_TOO_MANY_SERIES"))
                .andExpect(jsonPath("$.header.resultMessage").value("한 번에 50개 시계열까지 비교할 수 있습니다"));
        for (String bad : new String[]{"{\"series\":[],\"from\":\"2026-10-02T00:00:00Z\"}",
                "{\"series\":[{\"metric\":\"temperature\"}],\"from\":\"2026-10-02T00:00:00Z\"}",
                "{\"series\":[{\"deviceId\":\"1\",\"spaceId\":\"2\",\"metric\":\"temperature\"}],\"from\":\"2026-10-02T00:00:00Z\"}",
                "{\"series\":[{\"deviceId\":\"1\",\"metric\":\"\"}],\"from\":\"2026-10-02T00:00:00Z\"}"}) {
            mvc.perform(as(org, admin, json(post("/core/telemetry/query"), bad))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
        }
    }

    @Test
    @DisplayName("[NFR-01.05][TSD-03.06] 현재값: device_state.latest 기준, 공간 하위 포함 50대 한 번에, 측정 항목 거르기, 가상 기기 기본 제외 — TC-TSD-094·097")
    void latestValues() throws Exception {
        data.deviceState(org, sensor, "ONLINE", T0.minusSeconds(30),
                "{\"temperature\":{\"v\":23.4,\"t\":\"2026-10-02T23:59:30Z\",\"q\":0},\"co2\":{\"v\":812,\"t\":\"2026-10-02T23:59:30Z\",\"q\":1,\"unit\":\"ppm\"}}");
        long sim = data.device(org, source, "sim-9", "ACTIVE", room, model, true);
        data.deviceState(org, sim, "ONLINE", T0, "{\"temperature\":{\"v\":1,\"t\":\"2026-10-03T00:00:00Z\",\"q\":0}}");
        for (int i = 0; i < 49; i++) {
            data.device(org, source, "bulk-" + i, "ACTIVE", room, model);
        }
        mvc.perform(as(org, admin, get("/core/telemetry/latest").param("deviceIds", sensor + "," + sim).param("metrics", "temperature")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", hasSize(1)))
                .andExpect(jsonPath("$.response[0].deviceId").value(Long.toString(sensor)))
                .andExpect(jsonPath("$.response[0].connectivity").value("ONLINE"))
                .andExpect(jsonPath("$.response[0].lastSeenAt").value("2026-10-02T23:59:30Z"))
                .andExpect(jsonPath("$.response[0].metrics", hasSize(1)))
                .andExpect(jsonPath("$.response[0].metrics[0].key").value("temperature"))
                .andExpect(jsonPath("$.response[0].metrics[0].value").value(23.4))
                .andExpect(jsonPath("$.response[0].metrics[0].unit").value("℃"))
                .andExpect(jsonPath("$.response[0].metrics[0].measuredAt").value("2026-10-02T23:59:30Z"));
        String bySpace = mvc.perform(as(org, admin, get("/core/telemetry/latest").param("spaceId", Long.toString(site))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response", hasSize(50)))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> first = JsonPath.read(bySpace, "$.response[0]");
        assertThat((List<?>) first.get("metrics")).hasSize(2);
        mvc.perform(as(org, admin, get("/core/telemetry/latest").param("spaceId", Long.toString(site)).param("includeDescendants", "false")))
                .andExpect(jsonPath("$.response", hasSize(0)));
        mvc.perform(as(org, admin, get("/core/telemetry/latest").param("spaceId", Long.toString(room)).param("virtual", "true")))
                .andExpect(jsonPath("$.response", hasSize(51)))
                .andExpect(jsonPath("$.response[2].connectivity").value("UNKNOWN"));
        mvc.perform(as(org, admin, get("/core/telemetry/latest"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, get("/core/telemetry/latest").param("spaceId", "999999"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[TSD-01.02][AT-TSD-15.4][AT-TSD-15.6] 통신 품질: 게이트웨이 2대 2행, 7일 resolution=1h는 게이트웨이별 시간 평균, 시뮬레이터는 0행 — TC-TSD-002·007·008")
    void linkQuality() throws Exception {
        Instant t = Instant.parse("2026-10-02T10:10:00Z");
        rows.link(org, sensor, "24e124fffef79304", t, -97, 7.5);
        rows.link(org, sensor, "24e124fffef70001", t, -110, -3.25);
        rows.link(org, sensor, "24e124fffef79304", t.plusSeconds(1200), -99, 6.5);
        mvc.perform(as(org, admin, get("/core/telemetry/link-quality").param("deviceId", Long.toString(sensor))
                        .param("from", "2026-10-02T10:00:00Z").param("to", "2026-10-02T10:15:00Z")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response", hasSize(2)))
                .andExpect(jsonPath("$.response[0].gatewayEui").value("24e124fffef70001"))
                .andExpect(jsonPath("$.response[0].rssi").value(-110.0)).andExpect(jsonPath("$.response[0].snr").value(-3.25));
        mvc.perform(as(org, admin, get("/core/telemetry/link-quality").param("deviceId", Long.toString(sensor))
                        .param("from", "2026-09-26T00:00:00Z").param("to", T0.toString()).param("resolution", "1h")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response", hasSize(2)))
                .andExpect(jsonPath("$.response[1].t").value("2026-10-02T10:00:00Z"))
                .andExpect(jsonPath("$.response[1].gatewayEui").value("24e124fffef79304"))
                .andExpect(jsonPath("$.response[1].rssi").value(-98.0)).andExpect(jsonPath("$.response[1].snr").value(7.0));
        mvc.perform(as(org, admin, get("/core/telemetry/link-quality").param("deviceId", Long.toString(sensor))
                        .param("from", "2026-09-26T00:00:00Z").param("to", T0.toString())))
                .andExpect(jsonPath("$.response", hasSize(2)));
        long sim = data.device(org, source, "sim-l", "ACTIVE", room, model, true);
        mvc.perform(as(org, admin, get("/core/telemetry/link-quality").param("deviceId", Long.toString(sim)).param("from", "2026-10-02T00:00:00Z")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response", hasSize(0)));
        mvc.perform(as(org, admin, get("/core/telemetry/link-quality").param("deviceId", Long.toString(sensor)).param("from", "2026-01-01T00:00:00Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("TSD_RANGE_TOO_LARGE"));
        mvc.perform(as(org, admin, get("/core/telemetry/link-quality").param("deviceId", Long.toString(sensor))
                        .param("from", "2026-10-02T00:00:00Z").param("resolution", "1m"))).andExpect(status().isOk());
        mvc.perform(as(org, admin, get("/core/telemetry/link-quality").param("deviceId", Long.toString(sensor))
                        .param("from", "2026-07-10T00:00:00Z").param("to", "2026-09-30T00:00:00Z"))).andExpect(status().isOk());
        mvc.perform(as(org, admin, get("/core/telemetry/link-quality").param("from", "2026-10-02T00:00:00Z"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[TSD-03.06] 상태 구간: 상태형 항목 값이 바뀌는 지점으로 나누고 직전 값으로 시작, 라벨은 enum_map, actuator=true는 빈 목록(M4)")
    void stateIntervals() throws Exception {
        data.metric(org, "door", null);
        jdbc.sql("UPDATE data2flow_core.metrics SET value_type = 'ENUM', enum_map = '{\"0\":\"닫힘\",\"1\":\"열림\"}' WHERE organization_id = :o AND key = 'door'")
                .param("o", org).update();
        Instant base = Instant.parse("2026-10-02T09:00:00Z");
        data.telemetry(org, sensor, "door", base.minusSeconds(600), 0, 0, false);
        data.telemetry(org, sensor, "door", base.plusSeconds(600), 0, 0, false);
        data.telemetry(org, sensor, "door", base.plusSeconds(1200), 1, 0, false);
        data.telemetry(org, sensor, "door", base.plusSeconds(1800), 1, 0, false);
        data.telemetry(org, sensor, "door", base.plusSeconds(2400), 0, 0, false);
        mvc.perform(as(org, admin, get("/core/telemetry/state-intervals").param("deviceId", Long.toString(sensor)).param("metric", "door")
                        .param("from", base.toString()).param("to", base.plusSeconds(3600).toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response", hasSize(3)))
                .andExpect(jsonPath("$.response[0].from").value("2026-10-02T09:00:00Z"))
                .andExpect(jsonPath("$.response[0].to").value("2026-10-02T09:20:00Z"))
                .andExpect(jsonPath("$.response[0].label").value("닫힘"))
                .andExpect(jsonPath("$.response[1].value").value(1.0)).andExpect(jsonPath("$.response[1].label").value("열림"))
                .andExpect(jsonPath("$.response[2].to").value("2026-10-02T10:00:00Z"));
        mvc.perform(as(org, admin, get("/core/telemetry/state-intervals").param("deviceId", Long.toString(sensor)).param("actuator", "true")
                        .param("from", base.toString()))).andExpect(status().isOk()).andExpect(jsonPath("$.response", hasSize(0)));
        mvc.perform(as(org, admin, get("/core/telemetry/state-intervals").param("deviceId", Long.toString(sensor)).param("from", base.toString())))
                .andExpect(status().isBadRequest());
    }
}
