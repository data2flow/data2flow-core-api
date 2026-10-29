package net.java21.data2flow.core.energy;

import net.java21.data2flow.core.support.AnalyticsItSupport;
import net.java21.data2flow.core.support.StubHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ENE-01.03 지표·하위 단위(API-ENE-10·11) — TC-ENE-019: core는 권한 범위로 자른 공간 트리와 상위 계량기 여부를 analytics 롤업(API-ENE-85)에 넘긴다.
 * 범위가 제한된 사용자에게는 허용 공간만 넘어가 상위 합계도 그 범위로 계산되고, 범위 밖 공간은 404다.
 */
class EnergyMetricsIT extends AnalyticsItSupport {

    static final String PATH = "/internal/analytics/energy/metrics";

    private long site;
    private long building;
    private long room1;
    private long room2;
    private long otherSite;

    @BeforeEach
    void setUp() {
        site = data.site(org, "광주캠퍼스");
        building = data.space(org, site, "BUILDING", "본관");
        room1 = data.space(org, building, "ROOM", "101");
        room2 = data.space(org, building, "ROOM", "102");
        otherSite = data.site(org, "서울캠퍼스");
        jdbc.sql("UPDATE data2flow_core.spaces SET area_m2 = 40 WHERE id = :id").param("id", room1).update();
        data.metric(org, "energy", "kWh");
        long model = data.model(org, "METER", List.of("energy"));
        long source = data.source(org, "ene-src");
        long meter = data.device(org, source, "meter-1", "ACTIVE", building, model);
        jdbc.sql("""
                        INSERT INTO data2flow_core.energy_sources (organization_id, space_id, kind, device_id, metric_key, cumulative,
                               covers_descendants, enabled, created_by)
                        VALUES (:org, :space, 'METERED', :device, 'energy', true, true, true, :user)""")
                .param("org", org).param("space", building).param("device", meter).param("user", admin).update();
        STUB.ok("POST", PATH, 200, """
                {"scope":{"spaceId":%d,"name":"본관","kind":"BUILDING","areaM2":40.0},
                 "totals":{"kwh":650.0,"otherCommonKwh":50.0,"estimatedRatio":0.0},"compare":null,"series":[],
                 "children":[{"id":%d,"name":"101","kwh":100.0,"krw":null,"diffRatio":0.5},
                             {"id":%d,"name":"102","kwh":500.0,"krw":75000.0,"diffRatio":null}],
                 "other":{"kwh":50.0}}""".formatted(building, room1, room2));
    }

    private StubHttpServer.Received last() {
        List<StubHttpServer.Received> sent = STUB.received("POST", PATH);
        return sent.getLast();
    }

    @Test
    @DisplayName("[TC-ENE-019] 지표: 건물과 하위 공간·상위 계량기 여부를 넘기고 ID는 문자열로 돌려준다")
    void metrics() throws Exception {
        mvc.perform(as(org, viewer, get("/core/energy/metrics?scopeSpaceId=" + building + "&from=2026-09-01&to=2026-09-30&compare=previous_period")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.scope.spaceId").value(Long.toString(building)))
                .andExpect(jsonPath("$.response.totals.kwh").value(650.0))
                .andExpect(jsonPath("$.response.totals.otherCommonKwh").value(50.0))
                .andExpect(jsonPath("$.response.children").doesNotExist());
        String body = last().body();
        assertThat(body).contains("\"scopeSpaceId\":" + building, "\"granularity\":\"DAY\"", "\"compare\":\"PREVIOUS_PERIOD\"",
                "{\"id\":" + building + ",\"parentId\":" + site + ",\"name\":\"본관\",\"kind\":\"BUILDING\",\"areaM2\":null,\"coversDescendants\":true}",
                "{\"id\":" + room1 + ",\"parentId\":" + building + ",\"name\":\"101\",\"kind\":\"ROOM\",\"areaM2\":40.0,\"coversDescendants\":false}",
                "\"id\":" + room2);
        assertThat(body).doesNotContain("\"id\":" + site + ",", "\"id\":" + otherSite + ",");

        // 포트폴리오: 조직 전체 트리
        mvc.perform(as(org, viewer, get("/core/energy/metrics?from=2026-09-01&to=2026-09-30"))).andExpect(status().isOk());
        assertThat(last().body()).contains("\"scopeSpaceId\":null", "\"id\":" + site + ",", "\"id\":" + otherSite + ",");
    }

    @Test
    @DisplayName("[TC-ENE-019] 공간 범위가 제한된 사용자는 허용 공간만 넘기고, 범위 밖 공간은 404")
    void restrictedScope() throws Exception {
        data.spaceScope(org, viewer, List.of(room1));
        mvc.perform(as(org, viewer, get("/core/energy/metrics?from=2026-09-01&to=2026-09-30"))).andExpect(status().isOk());
        String body = last().body();
        assertThat(body).contains("\"id\":" + room1 + ",").doesNotContain("\"id\":" + room2 + ",", "\"id\":" + building + ",",
                "\"id\":" + site + ",", "\"id\":" + otherSite + ",");
        int before = STUB.received("POST", PATH).size();
        mvc.perform(as(org, viewer, get("/core/energy/metrics?scopeSpaceId=" + building + "&from=2026-09-01&to=2026-09-30")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        mvc.perform(as(org, viewer, get("/core/energy/metrics?scopeSpaceId=abc&from=2026-09-01&to=2026-09-30")))
                .andExpect(status().isNotFound());
        assertThat(STUB.received("POST", PATH)).hasSize(before);
        mvc.perform(get("/core/energy/metrics?from=2026-09-01&to=2026-09-30")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("API-ENE-11 하위 단위: 정렬·페이지·기타, 장비별은 아직 400, analytics 400은 그대로 전한다")
    void breakdown() throws Exception {
        String base = "/core/energy/breakdown?scopeSpaceId=" + building + "&from=2026-09-01&to=2026-09-30";
        mvc.perform(as(org, viewer, get(base))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.items[0].id").value(Long.toString(room2)))
                .andExpect(jsonPath("$.response.items[1].id").value(Long.toString(room1)))
                .andExpect(jsonPath("$.response.other.kwh").value(50.0))
                .andExpect(jsonPath("$.response.totalCount").value(2));
        assertThat(last().body()).contains("\"compare\":\"PREVIOUS_PERIOD\"");
        mvc.perform(as(org, viewer, get(base + "&sort=DIFF"))).andExpect(jsonPath("$.response.items[0].id").value(Long.toString(room1)));
        mvc.perform(as(org, viewer, get(base + "&sort=KRW&size=1&page=2"))).andExpect(jsonPath("$.response.items.length()").value(1))
                .andExpect(jsonPath("$.response.items[0].id").value(Long.toString(room1))).andExpect(jsonPath("$.response.page").value(2));
        mvc.perform(as(org, viewer, get(base + "&by=DEVICE"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, viewer, get(base + "&sort=NAME"))).andExpect(status().isBadRequest());

        STUB.fail("POST", PATH, 400, "INVALID_REQUEST", "\"errors\":[{\"field\":\"to\",\"code\":\"Invalid\",\"message\":\"기간\"}]");
        mvc.perform(as(org, viewer, get("/core/energy/metrics?from=2026-09-30&to=2026-09-01"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
        STUB.fail("POST", PATH, 500, "INTERNAL_ERROR", null);
        mvc.perform(as(org, viewer, get("/core/energy/metrics?from=2026-09-01&to=2026-09-30"))).andExpect(status().isServiceUnavailable());
        mvc.perform(as(org, viewer, post("/core/energy/metrics"))).andExpect(status().is4xxClientError());
    }
}
