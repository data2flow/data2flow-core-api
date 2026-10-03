package net.java21.data2flow.core.gateway;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.common.Pg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-05.01 게이트웨이 자동 등록·마지막 수신(BR-DEV-19), API-DEV-60·62·125 */
class GatewayIT extends IntegrationTestSupport {

    private long org;
    private long admin;
    private long source;
    private long room;

    @BeforeEach
    void setUp() {
        org = fx.organization("gw");
        admin = fx.user(org, "gw.admin", "ADMIN");
        source = data.source(org, "cs");
        room = data.space(org, data.site(org, "본관"), "ROOM", "실습실");
    }

    private void touch(String eui, String seenAt) throws Exception {
        mvc.perform(json(post("/internal/core/gateways/touch"),
                        "{\"items\":[{\"sourceId\":%d,\"gatewayEui\":\"%s\",\"seenAt\":\"%s\"},{\"sourceId\":999999,\"gatewayEui\":\"x\"}]}"
                                .formatted(source, eui, seenAt)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("[DEV-05.01][AT-DEV-11.1][BR-DEV-19] 처음 보는 게이트웨이는 이름=EUI로 자동 생성, 마지막 수신은 늦은 값만, 지워도 다시 수신되면 다시 생김 — TC-DEV-146·148")
    void touchCreatesAndUpdates() throws Exception {
        touch("24E124FFFEF79304", "2026-10-02T23:58:00Z");
        touch("24e124fffef79304", "2026-10-02T23:50:00Z");
        String body = mvc.perform(as(org, admin, get("/core/gateways")))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].gatewayEui").value("24e124fffef79304"))
                .andExpect(jsonPath("$.responses[0].name").value("24e124fffef79304"))
                .andExpect(jsonPath("$.responses[0].lastSeenAt").value("2026-10-02T23:58:00Z"))
                .andExpect(jsonPath("$.responses[0].status").value("ONLINE"))
                .andExpect(jsonPath("$.responses[0].source.id").value(Long.toString(source)))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.responses[0].id");
        jdbc.sql("DELETE FROM data2flow_core.gateways WHERE id = :id").param("id", Long.parseLong(id)).update();
        touch("24e124fffef79304", "2026-10-03T00:00:00Z");
        mvc.perform(as(org, admin, get("/core/gateways"))).andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("[DEV-05.01][API-DEV-60·62] 상태는 마지막 수신+기준(기본 600초)으로 계산, 이름·공간·기준 수정, 24시간 수신 기기·업링크 수, 필터 — TC-DEV-147")
    void listAndUpdate() throws Exception {
        touch("gw1", "2026-10-02T23:55:00Z");
        long device = data.device(org, source, "d1", "ACTIVE", room, null);
        data.deviceState(org, device, "ONLINE", clock.instant(), "{}");
        jdbc.sql("UPDATE data2flow_pipeline.device_state SET best_gateway_eui = 'gw1' WHERE device_id = :d").param("d", device).update();
        jdbc.sql("INSERT INTO data2flow_pipeline.link_qualities (device_id, gateway_eui, time, organization_id, rssi) VALUES (:d, 'gw1', :t, :o, -80)")
                .param("d", device).param("t", Pg.ts(clock.instant())).param("o", org).update();
        String id = JsonPath.read(mvc.perform(as(org, admin, get("/core/gateways"))).andReturn().getResponse().getContentAsString(),
                "$.responses[0].id");
        mvc.perform(as(org, admin, get("/core/gateways/" + id)))
                .andExpect(jsonPath("$.response.deviceCount24h").value(1)).andExpect(jsonPath("$.response.uplinks24h").value(1))
                .andExpect(jsonPath("$.response.offlineAfterSec").value(600));
        clock.advance(Duration.ofMinutes(20));
        mvc.perform(as(org, admin, get("/core/gateways").param("status", "OFFLINE"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, admin, json(patch("/core/gateways/" + id), "{\"name\":\"3층 게이트웨이\",\"spaceId\":\"" + room
                        + "\",\"offlineAfterSec\":3600}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("ONLINE"))
                .andExpect(jsonPath("$.response.space.name").value("실습실"));
        mvc.perform(as(org, admin, get("/core/gateways").param("spaceId", Long.toString(room)).param("sourceId", Long.toString(source))))
                .andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, admin, json(patch("/core/gateways/" + id), "{\"offlineAfterSec\":10}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(patch("/core/gateways/" + id), "{\"spaceId\":\"999999\"}"))).andExpect(status().isNotFound());
        mvc.perform(as(org, admin, json(patch("/core/gateways/" + id), "{\"name\":null,\"spaceId\":null}")))
                .andExpect(jsonPath("$.response.name").value("gw1")).andExpect(jsonPath("$.response.space").doesNotExist());
        mvc.perform(as(org, admin, json(patch("/core/gateways/" + id), "{\"name\":\"\"}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, get("/core/gateways").param("status", "BAD"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, get("/core/gateways").param("sourceId", "x"))).andExpect(status().isBadRequest());
        assertThat(auditCount(org, "GATEWAY_UPDATED")).isEqualTo(2);
    }

    @Test
    @DisplayName("[DEV-05.01][BR-DEV-25] 권한: VIEWER 조회 가능·수정 403, 공간 범위 밖·공간 없는 게이트웨이 404, 다른 조직 404 — TC-DEV-149")
    void permissions() throws Exception {
        touch("gw2", "2026-10-02T23:55:00Z");
        String id = JsonPath.read(mvc.perform(as(org, admin, get("/core/gateways"))).andReturn().getResponse().getContentAsString(),
                "$.responses[0].id");
        long viewer = fx.user(org, "gw.viewer", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/gateways/" + id))).andExpect(status().isOk());
        mvc.perform(as(org, viewer, json(patch("/core/gateways/" + id), "{\"name\":\"x\"}"))).andExpect(status().isForbidden());
        data.spaceScope(org, viewer, List.of(room));
        mvc.perform(as(org, viewer, get("/core/gateways/" + id))).andExpect(status().isNotFound());
        mvc.perform(as(org, viewer, get("/core/gateways"))).andExpect(jsonPath("$.totalCount").value(0));
        long other = fx.organization("gw2");
        long otherAdmin = fx.user(other, "gw2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/gateways/" + id))).andExpect(status().isNotFound());
    }
}
