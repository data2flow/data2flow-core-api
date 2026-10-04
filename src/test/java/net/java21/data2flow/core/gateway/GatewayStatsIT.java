package net.java21.data2flow.core.gateway;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-05.02 게이트웨이 수신 분포(API-DEV-61, AT-DEV-11.3) */
class GatewayStatsIT extends IntegrationTestSupport {

    private void link(long org, long device, String eui, Instant t, int rssi, double snr) {
        jdbc.sql("INSERT INTO data2flow_pipeline.link_qualities (device_id, gateway_eui, time, organization_id, rssi, snr) VALUES (:d, :g, :t, :o, :r, :s)")
                .param("d", device).param("g", eui).param("t", Pg.ts(t)).param("o", org).param("r", rssi).param("s", snr).update();
    }

    @Test
    @DisplayName("[DEV-05.02][AT-DEV-11.3] 기기 9대가 두 게이트웨이로 수신: 게이트웨이별 수신 기기 수, 기기별 평균 rssi·snr, 최적 게이트웨이 비율, 시간별 업링크, rssi 분포 — TC-DEV-151·153")
    void stats() throws Exception {
        long org = fx.organization("gs");
        long admin = fx.user(org, "gs.admin", "ADMIN");
        long source = data.source(org, "cs");
        long room = data.space(org, data.site(org, "본관"), "ROOM", "실습실");
        mvc.perform(json(post("/internal/core/gateways/touch"),
                "{\"items\":[{\"sourceId\":%d,\"gatewayEui\":\"gw-a\"},{\"sourceId\":%d,\"gatewayEui\":\"gw-b\"}]}".formatted(source, source)));
        Instant t0 = clock.instant().minus(Duration.ofHours(2));
        for (int i = 0; i < 9; i++) {
            long d = data.device(org, source, "d" + i, "ACTIVE", room, null);
            for (int k = 0; k < 4; k++) {
                Instant t = t0.plus(Duration.ofMinutes(10L * k));
                // 기기 0~5는 gw-a가 더 세고, 6~8은 gw-b만 받는다. 기기 0은 4번 중 1번 gw-b가 더 세다
                if (i < 6) {
                    link(org, d, "gw-a", t, i == 0 && k == 0 ? -110 : -80, 7.5);
                    link(org, d, "gw-b", t, -100, 2.5);
                } else {
                    link(org, d, "gw-b", t, -95, 1.0);
                }
            }
        }
        String gwA = JsonPath.read(mvc.perform(as(org, admin, get("/core/gateways").param("q", "")))
                .andReturn().getResponse().getContentAsString(), "$.responses[0].id");
        mvc.perform(as(org, admin, get("/core/gateways/" + gwA + "/stats")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.deviceCount").value(6))
                .andExpect(jsonPath("$.response.devices", hasSize(6)))
                .andExpect(jsonPath("$.response.devices[0].name").value("기기 d0"))
                .andExpect(jsonPath("$.response.devices[0].share").value(0.75))
                .andExpect(jsonPath("$.response.devices[0].avgRssi").value(-87.5))
                .andExpect(jsonPath("$.response.devices[1].share").value(1.0))
                .andExpect(jsonPath("$.response.devices[1].avgSnr").value(7.5))
                .andExpect(jsonPath("$.response.uplinksByHour[0].count").value(24))
                .andExpect(jsonPath("$.response.rssiHistogram[0].fromDbm").value(-110));
        String gwB = JsonPath.read(mvc.perform(as(org, admin, get("/core/gateways"))).andReturn().getResponse().getContentAsString(),
                "$.responses[1].id");
        mvc.perform(as(org, admin, get("/core/gateways/" + gwB + "/stats")))
                .andExpect(jsonPath("$.response.deviceCount").value(9))
                .andExpect(jsonPath("$.response.devices[0].share").value(0.25))
                .andExpect(jsonPath("$.response.devices[8].share").value(1.0));
        mvc.perform(as(org, admin, get("/core/gateways/" + gwA + "/stats").param("from", "2026-08-01T00:00:00Z")))
                .andExpect(status().isBadRequest());
        long viewer = fx.user(org, "gs.v", "VIEWER");
        data.spaceScope(org, viewer, List.of(room));
        mvc.perform(as(org, viewer, get("/core/gateways/" + gwA + "/stats"))).andExpect(status().isNotFound());
    }
}
