package net.java21.data2flow.core.energy;

import net.java21.data2flow.core.support.AnalyticsItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ENE-01.05 대시보드 위젯 energy-kpi·energy-trend(DSH API-DSH-09): API-ENE-10의 totals·series를 쓰고, 하위 공간 상위 N개로 드릴다운한다.
 * 기간은 조직 시간대 날짜(끝 포함), 범위 밖 공간은 WIDGET_DATA_FORBIDDEN
 */
class EnergyWidgetIT extends AnalyticsItSupport {

    static final String METRICS = "/internal/analytics/energy/metrics";

    private long building;
    private long room;

    @BeforeEach
    void setUp() {
        long site = data.site(org, "광주캠퍼스");
        building = data.space(org, site, "BUILDING", "본관");
        room = data.space(org, building, "ROOM", "101");
        STUB.ok("POST", METRICS, 200, """
                {"scope":{"spaceId":%d,"name":"본관","kind":"BUILDING"},
                 "totals":{"kwh":6000.0,"krw":900000.0,"tco2":2.74,"euiKwhPerM2":12.0,"estimatedRatio":0.25},
                 "compare":{"kind":"PREVIOUS_PERIOD","kwh":6500.0,"diffRatio":-0.0769},
                 "series":[{"t":"2026-10-01","kwh":200.0,"krw":30000.0,"tco2":0.09,"estimatedKwh":50.0,"baselineKwh":null}],
                 "children":[{"id":%d,"name":"101","kind":"ROOM","kwh":1000.0,"estimatedRatio":0.0},
                             {"id":1,"name":"102","kind":"ROOM","kwh":3000.0,"estimatedRatio":0.5}],
                 "other":{"kwh":2000.0}}""".formatted(building, room));
    }

    private String preview(String type, String options) {
        return """
                {"widget":{"id":"e","type":"%s","x":0,"y":0,"w":6,"h":4,"targets":[{"kind":"SPACE","spaceId":"%d"}],"options":%s},
                 "timeRange":{"from":"2026-09-30T15:00:00Z","to":"2026-10-02T15:00:00Z"}}""".formatted(type, building, options);
    }

    @Test
    @DisplayName("[ENE-01.05] energy-kpi: 합계·비교·추정 비율과 하위 공간을 사용량 순으로, 기간은 조직 시간대 날짜")
    void kpi() throws Exception {
        mvc.perform(as(org, analyst, post("/core/widgets/preview")).contentType(MediaType.APPLICATION_JSON).content(preview("energy-kpi", "{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.type").value("energy-kpi"))
                .andExpect(jsonPath("$.response.data.scope.spaceId").value(Long.toString(building)))
                .andExpect(jsonPath("$.response.data.from").value("2026-10-01"))
                .andExpect(jsonPath("$.response.data.to").value("2026-10-02"))
                .andExpect(jsonPath("$.response.data.totals.kwh").value(6000.0))
                .andExpect(jsonPath("$.response.data.totals.estimatedRatio").value(0.25))
                .andExpect(jsonPath("$.response.data.compare.diffRatio").value(-0.0769))
                .andExpect(jsonPath("$.response.data.children[0].name").value("102"))
                .andExpect(jsonPath("$.response.data.children[1].id").value(Long.toString(room)));
        var sent = STUB.received("POST", METRICS).getLast();
        assertThat(sent.header("X-ORG-ID")).isEqualTo(Long.toString(org));
        assertThat(sent.body()).contains("\"scopeSpaceId\":" + building, "\"compare\":\"PREVIOUS_PERIOD\"", "\"granularity\":\"DAY\"");
    }

    @Test
    @DisplayName("[ENE-01.05] energy-trend: 기간별 계열, 기준선 옵션이면 BASELINE 비교")
    void trend() throws Exception {
        mvc.perform(as(org, analyst, post("/core/widgets/preview")).contentType(MediaType.APPLICATION_JSON)
                        .content(preview("energy-trend", "{\"baseline\":true,\"measure\":\"krw\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.data.granularity").value("DAY"))
                .andExpect(jsonPath("$.response.data.measure").value("krw"))
                .andExpect(jsonPath("$.response.data.series[0].t").value("2026-10-01"))
                .andExpect(jsonPath("$.response.data.series[0].estimatedKwh").value(50.0));
        assertThat(STUB.received("POST", METRICS).getLast().body()).contains("\"compare\":\"BASELINE\"");
    }

    @Test
    @DisplayName("[ENE-01.05] 범위 밖 공간 대상은 WIDGET_DATA_FORBIDDEN")
    void forbidden() throws Exception {
        long other = data.space(org, data.site(org, "다른캠퍼스"), "BUILDING", "별관");
        data.spaceScope(org, analyst, List.of(other));
        mvc.perform(as(org, analyst, post("/core/widgets/preview")).contentType(MediaType.APPLICATION_JSON).content(preview("energy-kpi", "{}")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("WIDGET_DATA_FORBIDDEN"));
    }
}
