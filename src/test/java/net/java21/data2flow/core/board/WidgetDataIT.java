package net.java21.data2flow.core.board;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DSH-04.06·04.05 위젯 데이터(API-DSH-09): 자동 집계 단위·변수·위젯 종류별 데이터·권한 범위 */
class WidgetDataIT extends IntegrationTestSupport {

    private long org;
    private long admin;
    private long room;
    private long room2;
    private long device;
    private long device2;
    private String dashboard;

    @BeforeEach
    void setUp() throws Exception {
        org = fx.organization("wd");
        admin = fx.user(org, "wd.admin", "ADMIN");
        long site = data.site(org, "본관");
        long building = data.space(org, site, "BUILDING", "실습동");
        room = data.space(org, building, "ROOM", "실습실");
        room2 = data.space(org, building, "ROOM", "강의실");
        long source = data.source(org, "cs");
        data.metric(org, "co2", "ppm");
        data.metric(org, "temperature", "℃");
        device = data.device(org, source, "d1", "ACTIVE", room, null);
        device2 = data.device(org, source, "d2", "ACTIVE", room2, null);
        Instant now = clock.instant();
        // 30일 1시간 집계(ADR-019 자체 집계 표), 하루 1분 집계, 최근 원본
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry_1h (device_id, metric_key, bucket, organization_id, count, count_all, avg, min, max, sum,
                               first, last, last_time)
                        SELECT d, 'co2', g, :org, 60, 60, 600 + d % 7, 500, 900, (600 + d % 7) * 60, 600, 610, g + interval '59 minutes'
                          FROM generate_series(CAST(:from AS timestamptz), CAST(:to AS timestamptz) - interval '1 hour', interval '1 hour') g,
                               unnest(CAST(:devices AS bigint[])) d""")
                .param("org", org).param("from", Pg.ts(now.minus(Duration.ofDays(30)))).param("to", Pg.ts(now))
                .param("devices", Pg.bigintArray(List.of(device, device2))).update();
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry_1m (device_id, metric_key, bucket, organization_id, count, count_all, avg, min, max, sum,
                               first, last, last_time)
                        SELECT :device, 'co2', g, :org, 1, 1, 700, 700, 700, 700, 700, 700, g
                          FROM generate_series(CAST(:from AS timestamptz), CAST(:to AS timestamptz) - interval '1 minute', interval '1 minute') g""")
                .param("org", org).param("device", device).param("from", Pg.ts(now.minus(Duration.ofDays(3)))).param("to", Pg.ts(now)).update();
        for (int i = 1; i <= 30; i++) {
            data.telemetry(org, device, "temperature", now.minus(Duration.ofMinutes(i)), 22 + i / 10.0, i == 5 ? 1 : 0, false);
        }
        data.deviceState(org, device, "ONLINE", now, "{\"co2\":{\"v\":812,\"t\":\"" + now + "\",\"q\":0}}");
        data.deviceState(org, device2, "OFFLINE", now, "{\"co2\":{\"v\":400,\"t\":\"" + now + "\",\"q\":0}}");
        jdbc.sql("""
                        INSERT INTO data2flow_core.alarms (organization_id, alarm_key, source_type, severity, title, status, device_id, space_id,
                               raised_at, last_raised_at)
                        VALUES (:org, 'rule:1:x', 'RULE', 'MAJOR', 'CO2 높음', 'ACTIVE', :device, :room, :now, :now)""")
                .param("org", org).param("device", device).param("room", room).param("now", Pg.ts(now)).update();
        String layout = """
                {"widgets":[
                  {"id":"line30","type":"line","x":0,"y":0,"w":12,"h":6,"targets":[{"kind":"DEVICE_METRIC","deviceId":"%1$d","metricKey":"co2"}]},
                  {"id":"space","type":"area","x":12,"y":0,"w":12,"h":6,"targets":[{"kind":"SPACE_AGGREGATE","spaceId":"${space}","metricKey":"co2"}]},
                  {"id":"stat","type":"stat","x":0,"y":6,"w":6,"h":4,"options":{"sparkline":true},"targets":[{"kind":"DEVICE_METRIC","deviceId":"%1$d","metricKey":"co2"}]},
                  {"id":"gauge","type":"gauge","x":6,"y":6,"w":6,"h":4,"options":{"min":0,"max":2000},"targets":[{"kind":"SPACE_AGGREGATE","spaceId":"%2$d","metricKey":"co2"}]},
                  {"id":"tbl","type":"table","x":12,"y":6,"w":12,"h":4,"targets":[{"kind":"DEVICE_METRIC","deviceId":"%1$d","metricKey":"co2","label":"실습실 CO2"}]},
                  {"id":"status","type":"status-list","x":0,"y":10,"w":12,"h":4,"targets":[{"kind":"SPACE","spaceId":"%2$d"}]},
                  {"id":"alarms","type":"alarm-list","x":12,"y":10,"w":12,"h":4,"targets":[]},
                  {"id":"heat","type":"heatmap","x":0,"y":14,"w":12,"h":6,"options":{"days":7},"targets":[{"kind":"DEVICE_METRIC","deviceId":"%1$d","metricKey":"co2"}]},
                  {"id":"plan","type":"floorplan","x":12,"y":14,"w":12,"h":6,"options":{"metricKey":"co2"},"targets":[{"kind":"SPACE","spaceId":"%3$d"}]},
                  {"id":"memo","type":"markdown","x":0,"y":20,"w":24,"h":2,"options":{"content":"메모"}},
                  {"id":"other","type":"stat","x":0,"y":22,"w":6,"h":4,"targets":[{"kind":"DEVICE_METRIC","deviceId":"%4$d","metricKey":"co2"}]},
                  {"id":"raw","type":"line","x":6,"y":22,"w":18,"h":4,"targets":[{"kind":"DEVICE_METRIC","deviceId":"%1$d","metricKey":"temperature"}]}]}"""
                .formatted(device, building, room, device2);
        String res = mvc.perform(as(org, admin, json(post("/core/dashboards"), """
                        {"name":"실습동","visibility":"ORG","layout":%s,"variables":[{"name":"space","type":"SPACE","default":"%d"}],
                         "timeRange":{"relative":"30d"}}""".formatted(layout, room))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        dashboard = JsonPath.read(res, "$.response.id");
    }

    private String widget(String id) {
        return "/core/dashboards/" + dashboard + "/widgets/" + id + "/data";
    }

    @Test
    @DisplayName("[DSH-04.06][AT-DSH-04.2] 30일 자동 → effectiveResolution=1h, 점 ≤ 2,000(720), 자체 집계 telemetry_1h 사용 — TC-DSH-045")
    void thirtyDaysAuto() throws Exception {
        mvc.perform(as(org, admin, json(post(widget("line30")), "{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.type").value("line"))
                .andExpect(jsonPath("$.response.data.effectiveResolution").value("1h"))
                .andExpect(jsonPath("$.response.data.series[0].points", hasSize(720)))
                .andExpect(jsonPath("$.response.data.series[0].points.length()").value(lessThanOrEqualTo(2000)))
                .andExpect(jsonPath("$.response.data.series[0].unit").value("ppm"))
                .andExpect(jsonPath("$.response.data.series[0].points[0][2]").value(60));
        // 3일 → 5분(1분 집계를 다시 묶음, 864점), 1일 → 1분(1,440점)
        mvc.perform(as(org, admin, json(post(widget("line30")), "{\"timeRange\":{\"relative\":\"3d\"}}")))
                .andExpect(jsonPath("$.response.data.effectiveResolution").value("5m"))
                .andExpect(jsonPath("$.response.data.series[0].points", hasSize(864)))
                .andExpect(jsonPath("$.response.data.series[0].points[0][1]").value(700.0))
                .andExpect(jsonPath("$.response.data.series[0].points[0][2]").value(5));
        mvc.perform(as(org, admin, json(post(widget("line30")), "{\"timeRange\":{\"relative\":\"1d\"}}")))
                .andExpect(jsonPath("$.response.data.effectiveResolution").value("1m"))
                .andExpect(jsonPath("$.response.data.series[0].points", hasSize(1440)));
        mvc.perform(as(org, admin, json(post(widget("line30")), "{\"resolution\":\"RAW\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("WIDGET_QUERY_INVALID"))
                .andExpect(jsonPath("$.errors[0].message").value("1h"));
        // 1시간 원본(30점, 품질 1은 그대로 표시용으로 준다)
        mvc.perform(as(org, admin, json(post(widget("raw")), "{\"timeRange\":{\"relative\":\"1h\"}}")))
                .andExpect(jsonPath("$.response.data.effectiveResolution").value("raw"))
                .andExpect(jsonPath("$.response.data.series[0].points", hasSize(30)))
                .andExpect(jsonPath("$.response.data.series[0].points[?(@[2] == 1)]", hasSize(1)));
    }

    @Test
    @DisplayName("[DSH-04.05][AT-DSH-04.3] 공간 변수 ${space}: 기본값(실습실) → 요청 값(건물)으로 바꾸면 그 공간의 기기 평균, 값이 없으면 400")
    void variables() throws Exception {
        mvc.perform(as(org, admin, json(post(widget("space")), "{}")))
                .andExpect(jsonPath("$.response.data.series[0].label").value("실습실 · co2"))
                .andExpect(jsonPath("$.response.data.series[0].points[0][1]").value(600.0 + device % 7));
        long building = jdbc.sql("SELECT parent_id FROM data2flow_core.spaces WHERE id = :id").param("id", room).query(Long.class).single();
        mvc.perform(as(org, admin, json(post(widget("space")), "{\"variables\":{\"space\":\"" + building + "\"}}")))
                .andExpect(jsonPath("$.response.data.series[0].label").value("실습동 · co2"))
                .andExpect(jsonPath("$.response.data.series[0].points[0][1]").value((600.0 + device % 7 + 600.0 + device2 % 7) / 2));
        mvc.perform(as(org, admin, json(post(widget("space")), "{\"variables\":{\"space\":\"999999\"}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("WIDGET_QUERY_INVALID"));
    }

    @Test
    @DisplayName("[DSH-04.01][AT-DSH-04.1] 위젯 종류별 데이터: 현재값(스파크라인)·게이지(공간 평균)·표·상태 목록·알람 목록·히트맵·평면도·메모")
    void widgetTypes() throws Exception {
        mvc.perform(as(org, admin, json(post(widget("stat")), "{\"timeRange\":{\"relative\":\"1d\"}}")))
                .andExpect(jsonPath("$.response.data.value").value(812.0))
                .andExpect(jsonPath("$.response.data.unit").value("ppm"))
                .andExpect(jsonPath("$.response.data.sparkline", hasSize(1440)));
        mvc.perform(as(org, admin, json(post(widget("gauge")), "{}")))
                .andExpect(jsonPath("$.response.data.value").value(606.0))
                .andExpect(jsonPath("$.response.data.max").value(2000.0));
        mvc.perform(as(org, admin, json(post(widget("tbl")), "{\"timeRange\":{\"relative\":\"7d\"}}")))
                .andExpect(jsonPath("$.response.data.columns[0]").value("target"))
                .andExpect(jsonPath("$.response.data.rows[0][0]").value("실습실 CO2"))
                .andExpect(jsonPath("$.response.data.rows[0][2]").value(812.0))
                .andExpect(jsonPath("$.response.data.rows[0][3]").value(500.0))
                .andExpect(jsonPath("$.response.data.rows[0][4]").value(900.0));
        mvc.perform(as(org, admin, json(post(widget("status")), "{}")))
                .andExpect(jsonPath("$.response.data.items", hasSize(2)))
                .andExpect(jsonPath("$.response.data.items[0].connection").value("ONLINE"))
                .andExpect(jsonPath("$.response.data.items[0].alarms").value(1))
                .andExpect(jsonPath("$.response.data.items[1].connection").value("OFFLINE"));
        mvc.perform(as(org, admin, json(post(widget("alarms")), "{}")))
                .andExpect(jsonPath("$.response.data.items", hasSize(1)))
                .andExpect(jsonPath("$.response.data.items[0].title").value("CO2 높음"));
        mvc.perform(as(org, admin, json(post(widget("heat")), "{}")))
                .andExpect(jsonPath("$.response.data.xLabels", hasSize(24)))
                .andExpect(jsonPath("$.response.data.yLabels", hasSize(7)))
                .andExpect(jsonPath("$.response.data.values[0][0]").value(600.0 + device % 7));
        mvc.perform(as(org, admin, json(post(widget("plan")), "{}")))
                .andExpect(jsonPath("$.response.data.spaceId").value(Long.toString(room)))
                .andExpect(jsonPath("$.response.data.markers", hasSize(0)));
        mvc.perform(as(org, admin, json(post(widget("memo")), "{}")))
                .andExpect(jsonPath("$.response.type").value("markdown"));
        mvc.perform(as(org, admin, json(post(widget("nope")), "{}"))).andExpect(status().isNotFound());
        // 편집 중 미리 보기(대시보드 없이)
        mvc.perform(as(org, admin, json(post("/core/widgets/preview"), """
                        {"widget":{"id":"p","type":"stat","x":0,"y":0,"w":4,"h":4,"targets":[{"kind":"DEVICE_METRIC","deviceId":"%d","metricKey":"co2"}]}}"""
                        .formatted(device))))
                .andExpect(jsonPath("$.response.data.value").value(812.0));
        mvc.perform(as(org, admin, json(post("/core/widgets/preview"), "{}"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[DSH-04.07][BR-DSH-09] ORG 대시보드의 위젯 하나가 범위 밖 공간 데이터를 쓰면 그 위젯만 403 WIDGET_DATA_FORBIDDEN, 나머지 200 — TC-DSH-047")
    void scopedWidget() throws Exception {
        long operator = fx.user(org, "wd.op", "OPERATOR");
        data.spaceScope(org, operator, List.of(room));
        mvc.perform(as(org, operator, json(post(widget("stat")), "{}"))).andExpect(status().isOk());
        mvc.perform(as(org, operator, json(post(widget("other")), "{}"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("WIDGET_DATA_FORBIDDEN"));
        mvc.perform(as(org, operator, json(post(widget("gauge")), "{}"))).andExpect(status().isForbidden());
        mvc.perform(as(org, operator, json(post(widget("alarms")), "{}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.data.items", hasSize(1)));
    }
}
