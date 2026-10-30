package net.java21.data2flow.core.energy;

import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.EnergyVirtualSensorUpdated;
import net.java21.data2flow.contracts.message.event.EnergyVirtualSensorUpdated.Kind;
import net.java21.data2flow.contracts.message.event.EnergyVirtualSensorUpdated.Status;
import net.java21.data2flow.core.support.AnalyticsItSupport;
import net.java21.data2flow.core.support.StubHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ENE-02.01 가상 센서(API-ENE-20·21) — TC-ENE-037: 층고 입력 → ACH 켜기 → EVT-ENE-07·API-ENE-82 reload → 설정 묶음 virtualSensors → EVT-ENE-03 →
 * 마지막 계산 시각·상태 → API-ENE-21 환기량. TC-ENE-038의 권한(OPERATOR 403, VIEWER 조회, 범위 밖 404)도 실제 역할로 확인한다
 */
class VirtualSensorConfigIT extends AnalyticsItSupport {

    static final String RELOAD = "/internal/analytics/energy/virtual-sensors/reload";
    static final String ON = "{\"enabled\":true,\"params\":{\"outdoorCo2Ppm\":430}}";

    @Autowired
    JsonMapper json;

    private long site;
    private long room;
    private long empty;
    private long co2;

    @BeforeEach
    void setUp() {
        site = data.site(org, "광주캠퍼스");
        long building = data.space(org, site, "BUILDING", "본관");
        room = data.space(org, building, "ROOM", "101");
        empty = data.space(org, building, "ROOM", "102");
        data.metric(org, "co2", "ppm");
        long model = data.model(org, "IAQ", List.of("co2"));
        co2 = data.device(org, data.source(org, "iaq"), "iaq-1", "ACTIVE", room, model);
        STUB.on("POST", RELOAD, r -> new StubHttpServer.Reply(204, "", Map.of()));
    }

    private String path(long space) {
        return "/core/energy/spaces/" + space + "/virtual-sensors";
    }

    private long outbox(String key) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE organization_id = :org AND routing_key = :key").param("org", org)
                .param("key", key).query(Long.class).single();
    }

    @Test
    @DisplayName("[TC-ENE-037] 층고가 없으면 409, 입력하면 켜지고 EVT-ENE-07·reload, EVT-ENE-03 뒤 API-ENE-21이 환기량을 돌려준다")
    void enableComputeAndRead() throws Exception {
        mvc.perform(as(org, admin, json(put(path(room) + "/ach"), ON))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("ENE_SPACE_VOLUME_REQUIRED"));
        mvc.perform(as(org, admin, json(patch("/core/spaces/" + room), "{\"areaM2\":50,\"ceilingHeightM\":3,\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.ceilingHeightM").value(3.0));

        mvc.perform(as(org, admin, json(put(path(room) + "/ach"), ON))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("OK")).andExpect(jsonPath("$.response.params.outdoorCo2Ppm").value(430.0))
                .andExpect(jsonPath("$.response.volumeM3").value(150.0))
                .andExpect(jsonPath("$.response.inputs[0].deviceIds[0]").value(Long.toString(co2)))
                .andExpect(jsonPath("$.response.lastComputedAt").doesNotExist());
        assertThat(outbox("energy.config.changed")).isEqualTo(1);
        List<StubHttpServer.Received> reloads = STUB.received("POST", RELOAD);
        assertThat(reloads).hasSize(1);
        assertThat(json.readTree(reloads.getFirst().body()).path("spaceId").asString()).isEqualTo(Long.toString(room));

        mvc.perform(get("/internal/core/energy/config?orgId=" + org).header("X-CALLER-SERVICE", "data2flow-analytics")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.virtualSensors", hasSize(1)))
                .andExpect(jsonPath("$.response.virtualSensors[0].spaceId").value(Long.toString(room)))
                .andExpect(jsonPath("$.response.virtualSensors[0].kind").value("ACH"))
                .andExpect(jsonPath("$.response.virtualSensors[0].volumeM3").value(150.0))
                .andExpect(jsonPath("$.response.virtualSensors[0].timezone").isString())
                .andExpect(jsonPath("$.response.virtualSensors[0].inputs[0].deviceIds[0]").value(Long.toString(co2)));
        String orgs = mvc.perform(get("/internal/core/energy/organizations").header("X-CALLER-SERVICE", "data2flow-analytics"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(orgs).contains("\"" + org + "\"");

        Instant at = Instant.parse("2026-10-29T17:00:05Z");
        assertThat(deliver(EventType.ENERGY_VIRTUAL_SENSOR_UPDATED, org, new EnergyVirtualSensorUpdated(room, Kind.ACH, List.of("ach", "ventilation_m3h"),
                Instant.parse("2026-10-28T15:00:00Z"), Instant.parse("2026-10-29T15:00:00Z"), Status.OK, null), at)).isTrue();
        mvc.perform(as(org, viewer, get(path(room)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response[0].status").value("OK")).andExpect(jsonPath("$.response[0].lastComputedAt").value("2026-10-29T17:00:05Z"));

        STUB.ok("GET", "/internal/analytics/energy/spaces/" + room + "/ach", 200, """
                {"items":[{"day":"2026-10-29","ach":2.0,"ciLow":1.9,"ciHigh":2.1,
                  "decayWindow":{"from":"2026-10-29T17:00:00+09:00","to":"2026-10-29T19:00:00+09:00"},"fitR2":0.99}]}""");
        mvc.perform(as(org, viewer, get("/core/energy/spaces/" + room + "/ach")).param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.volumeM3").value(150.0))
                .andExpect(jsonPath("$.response.outdoorCo2Ppm").value(430.0))
                .andExpect(jsonPath("$.response.items[0].day").value("2026-10-29"))
                .andExpect(jsonPath("$.response.items[0].ach").value(2.0))
                .andExpect(jsonPath("$.response.items[0].ciLow").value(1.9))
                .andExpect(jsonPath("$.response.items[0].ventilationM3h").value(300.0))
                .andExpect(jsonPath("$.response.items[0].decayWindow.from").value("2026-10-29T08:00:00Z"));
        assertThat(STUB.received("GET", "/internal/analytics/energy/spaces/" + room + "/ach").getFirst().header("X-ORG-ID")).isEqualTo(Long.toString(org));

        // 끄면 DISABLED, 설정 묶음에서 빠진다
        mvc.perform(as(org, admin, json(put(path(room) + "/ACH"), "{\"enabled\":false}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("DISABLED"));
        mvc.perform(get("/internal/core/energy/config?orgId=" + org).header("X-CALLER-SERVICE", "data2flow-analytics"))
                .andExpect(jsonPath("$.response.virtualSensors", hasSize(0)));
    }

    @Test
    @DisplayName("[TC-ENE-037][TC-ENE-038] CO2 기기 없는 공간 409 INPUT_MISSING, OPERATOR 변경 403, VIEWER 조회 200, 범위 밖 404")
    void permissionsAndInputs() throws Exception {
        jdbc.sql("UPDATE data2flow_core.spaces SET area_m2 = 40, ceiling_height_m = 2.7 WHERE id IN (:a, :b)").param("a", room).param("b", empty).update();
        mvc.perform(as(org, admin, json(patch("/core/spaces/" + room), "{\"ceilingHeightM\":0,\"baseVersion\":0}"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("ceilingHeightM"));
        mvc.perform(as(org, admin, json(put(path(empty) + "/ach"), ON))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("ENE_VIRTUAL_SENSOR_INPUT_MISSING"))
                .andExpect(jsonPath("$.errors[0].code").value("co2"));
        mvc.perform(as(org, operator, json(put(path(room) + "/ach"), ON))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        mvc.perform(as(org, viewer, get(path(empty)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response[0].status").value("DISABLED"))
                .andExpect(jsonPath("$.response[0].inputs[0].available").value(false));
        long other = data.site(org, "서울캠퍼스");
        long scoped = fx.user(org, "ene.scoped.viewer", "VIEWER");
        data.spaceScope(org, scoped, List.of(other));
        mvc.perform(as(org, scoped, get(path(room)))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        mvc.perform(as(org, viewer, get("/core/energy/spaces/" + room + "/ach")).param("from", "2026-10-31").param("to", "2026-10-01"))
                .andExpect(status().isBadRequest());
        assertThat(outbox("energy.config.changed")).isZero();
        assertThat(STUB.received("POST", RELOAD)).isEmpty();
    }
}
