package net.java21.data2flow.core.control;

import net.java21.data2flow.core.support.LoopItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ACT-01.01~01.03·03.01·03.02·06.04, DEV-03.03: 기능 카탈로그(API-ACT-25), 드라이버(API-ACT-30~32), 모델–드라이버 연결(API-ACT-31),
 * 조직 제어 설정·절대 한계(API-ACT-17), action이 읽는 내부 제어 정보(API-ACT-40~43).
 */
class ControlDefinitionIT extends LoopItSupport {

    private static final String HUMIDIFIER = """
            {"name":"custom.Humidifier","attributes":[{"name":"targetHumidity","type":"number","unit":"%","min":30,"max":70}],
             "commands":[{"name":"set","sets":["targetHumidity"],"args":{"type":"object","required":["targetHumidity"],
               "properties":{"targetHumidity":{"type":"number","minimum":30,"maximum":70}}}}]}""";

    @Test
    @DisplayName("[ACT-01.01][ACT-01.02] 표준 7종 목록·상세(set 인자 스키마), 사용자 정의 custom.* 추가·수정, 표준 이름·접두사 위반 — TC-ACT-003·010·011")
    void capabilityCatalog() throws Exception {
        mvc.perform(as(org, viewer, get("/core/capabilities")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(7))
                .andExpect(jsonPath("$.responses[*].name", hasItem("Thermostat")));
        mvc.perform(as(org, viewer, get("/core/capabilities/Thermostat")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.standard").value(true))
                .andExpect(jsonPath("$.response.commands[0].name").value("set"))
                .andExpect(jsonPath("$.response.commands[0].args.properties.targetTemperature.maximum").value(35));
        mvc.perform(as(org, viewer, get("/core/capabilities/custom.None")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("CAPABILITY_NOT_FOUND"));

        mvc.perform(as(org, operator, json(post("/core/capabilities"), HUMIDIFIER))).andExpect(status().isForbidden());
        mvc.perform(as(org, integrator, json(post("/core/capabilities"), HUMIDIFIER)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.name").value("custom.Humidifier"))
                .andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, integrator, json(post("/core/capabilities"), HUMIDIFIER)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DUPLICATED"));
        mvc.perform(as(org, integrator, json(post("/core/capabilities"), HUMIDIFIER.replace("custom.Humidifier", "Switch"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("CAPABILITY_NAME_RESERVED"));
        mvc.perform(as(org, integrator, json(post("/core/capabilities"), HUMIDIFIER.replace("custom.Humidifier", "Humidifier"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("name"));
        mvc.perform(as(org, integrator, json(post("/core/capabilities"), "{\"name\":\"custom.Bad\",\"attributes\":[{\"name\":\"x\"}]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("attributes"));
        mvc.perform(as(org, integrator, json(put("/core/capabilities/custom.Humidifier"), HUMIDIFIER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(2));
        mvc.perform(as(org, integrator, json(put("/core/capabilities/Thermostat"), HUMIDIFIER)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("CAPABILITY_NAME_RESERVED"));
        mvc.perform(as(org, viewer, get("/core/capabilities").param("size", "3").param("page", "3")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(8)).andExpect(jsonPath("$.responses.length()").value(2));
        assertThat(auditCount(org, "CAPABILITY_CREATED")).isEqualTo(1);

        mvc.perform(get("/internal/core/capabilities").param("organizationId", Long.toString(org)).header("X-CALLER-SERVICE", "data2flow-action"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].name").value("custom.Humidifier"));
    }

    @Test
    @DisplayName("[ACT-03.01][ACT-03.02][DEV-03.03] 드라이버 CRUD(비밀값 가림)·모델 연결(기능 불일치 400)·사용 중 삭제 409·연결 확인 중계 — TC-ACT-067·079·080, TC-DEV-101·104·105")
    void driversAndModelLink() throws Exception {
        long thermostatModel = model("AIRCON-1", "[{\"capability\":\"Thermostat\",\"constraints\":{\"targetTemperature\":{\"min\":18,\"max\":30}}}]");
        long dimmerModel = model("LIGHT-1", "[{\"capability\":\"Dimmer\"}]");
        MvcResult created = mvc.perform(as(org, integrator, json(post("/core/drivers"), """
                        {"name":"LG 에어컨","type":"LG_THINQ","config":{"region":"KR"},"secret":{"pat":"thinq-secret-token"}}""")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.hasSecret").value(true))
                .andExpect(jsonPath("$.response.status").value("UNTESTED")).andReturn();
        assertThat(body(created)).doesNotContain("thinq-secret-token");
        long lg = Long.parseLong(read(created, "$.response.driverId"));
        mvc.perform(as(org, operator, get("/core/drivers"))).andExpect(status().isForbidden());
        mvc.perform(as(org, integrator, json(post("/core/drivers"), "{\"name\":\"x\",\"type\":\"FTP\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("type"));
        mvc.perform(as(org, integrator, json(post("/core/drivers"), "{\"name\":\"m\",\"type\":\"MQTT\",\"config\":{}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("config.sourceId"));
        mvc.perform(as(org, integrator, json(post("/core/drivers"), "{\"name\":\"LG 에어컨\",\"type\":\"VIRTUAL\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DUPLICATED"));

        // TC-DEV-101 · TC-ACT-078: 모델 기능(Dimmer)을 드라이버(LG ThinQ)가 지원하지 않으면 연결 거부
        mvc.perform(as(org, integrator, json(put("/core/device-models/" + dimmerModel + "/driver"), "{\"driverId\":\"" + lg + "\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("DRIVER_CAPABILITY_MISMATCH"))
                .andExpect(jsonPath("$.header.resultMessage").value("이 드라이버는 Dimmer를 지원하지 않습니다"));
        mvc.perform(as(org, integrator, json(put("/core/device-models/" + thermostatModel + "/driver"), "{\"driverId\":\"" + lg + "\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.driverId").value(Long.toString(lg)))
                .andExpect(jsonPath("$.response.warnings[0]").value("DRIVER_UNTESTED"));
        mvc.perform(as(org, integrator, get("/core/device-models/" + thermostatModel)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.package.driverId").value(Long.toString(lg)));
        mvc.perform(as(org, integrator, json(put("/core/device-models/999999/driver"), "{\"driverId\":\"" + lg + "\"}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, integrator, json(put("/core/device-models/" + thermostatModel + "/driver"), "{\"driverId\":\"9999999\"}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("DRIVER_NOT_FOUND"));
        mvc.perform(as(org, integrator, delete("/core/drivers/" + lg)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("DRIVER_IN_USE"));
        assertThat(configMessages(org)).anyMatch(m -> m.contains("\"MODEL\"") && m.contains("\"" + thermostatModel + "\""));

        mvc.perform(as(org, integrator, get("/core/drivers").param("type", "lg_thinq")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].deviceCount").value(0));
        mvc.perform(as(org, integrator, json(put("/core/drivers/" + lg), """
                        {"name":"LG 에어컨 2","config":{"region":"KR"},"pollingSec":30,"baseVersion":0,"retry":{"maxAttempts":1}}""")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.pollingSec").value(30)).andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, integrator, json(put("/core/drivers/" + lg), "{\"name\":\"LG\",\"baseVersion\":0}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, integrator, json(put("/core/device-models/" + thermostatModel + "/driver"), "{\"driverId\":null}")))
                .andExpect(status().isOk());
        mvc.perform(as(org, integrator, delete("/core/drivers/" + lg))).andExpect(status().isNoContent());
        assertThat(auditCount(org, "DRIVER_DELETED")).isEqualTo(1);

        // 연결 확인·지표는 action(드라이버 SPI)에 넘긴다
        long virtualDriver = Long.parseLong(read(mvc.perform(as(org, integrator, json(post("/core/drivers"),
                "{\"name\":\"가상\",\"type\":\"VIRTUAL\"}"))).andReturn(), "$.response.driverId"));
        STUB.ok("POST", "/internal/action/drivers/" + virtualDriver + "/healthcheck", 200,
                "{\"ok\":true,\"latencyMs\":3,\"capabilities\":[\"Thermostat\"]}");
        mvc.perform(as(org, integrator, post("/core/drivers/" + virtualDriver + "/healthcheck")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.ok").value(true));
        mvc.perform(as(org, integrator, get("/core/drivers/" + virtualDriver)))
                .andExpect(jsonPath("$.response.status").value("OK"));
        assertThat(STUB.received("POST", ".*/healthcheck").getFirst().header("X-CALLER-SERVICE")).isEqualTo("data2flow-core-api");
        STUB.ok("POST", "/internal/action/drivers/" + virtualDriver + "/healthcheck", 200,
                "{\"ok\":false,\"latencyMs\":0,\"capabilities\":[],\"error\":{\"kind\":\"TIMEOUT\",\"message\":\"응답 없음\"}}");
        mvc.perform(as(org, integrator, post("/core/drivers/" + virtualDriver + "/healthcheck")))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.header.resultCode").value("DRIVER_HEALTHCHECK_FAILED"))
                .andExpect(jsonPath("$.header.resultMessage").value("드라이버에 연결할 수 없습니다: 응답 없음"));
        STUB.ok("GET", "/internal/action/drivers/" + virtualDriver + "/metrics", 200, "{\"status\":\"OK\",\"requests\":5}");
        mvc.perform(as(org, integrator, get("/core/drivers/" + virtualDriver + "/metrics").param("window", "24h")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.requests").value(5));
        mvc.perform(as(org, integrator, get("/core/drivers/" + virtualDriver + "/metrics").param("window", "7d")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[ACT-06.04] 조직 절대 한계: 모델 범위보다 넓으면 400 LIMIT_WIDER_THAN_MODEL, 저장·baseVersion 충돌·설정 변경 메시지 — TC-ACT-119")
    void absoluteLimits() throws Exception {
        model("AIRCON-2", "[{\"capability\":\"Thermostat\",\"constraints\":{\"min\":18,\"max\":30}}]");
        mvc.perform(as(org, admin, get("/core/settings/control")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.defaultValiditySec").value(600));
        mvc.perform(as(org, operator, get("/core/settings/control"))).andExpect(status().isForbidden());
        mvc.perform(as(org, admin, json(put("/core/settings/control"),
                        "{\"absoluteLimits\":{\"Thermostat\":{\"targetTemperature\":{\"min\":16,\"max\":28}}},\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("LIMIT_WIDER_THAN_MODEL"))
                .andExpect(jsonPath("$.errors[0].field").value("absoluteLimits.Thermostat.targetTemperature"));
        mvc.perform(as(org, admin, json(put("/core/settings/control"),
                        "{\"absoluteLimits\":{\"Thermostat\":{\"nope\":{\"min\":18,\"max\":28}}},\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_ATTRIBUTE"));
        mvc.perform(as(org, admin, json(put("/core/settings/control"),
                        "{\"absoluteLimits\":{\"Heater\":{}},\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("CAPABILITY_NOT_SUPPORTED"));
        mvc.perform(as(org, admin, json(put("/core/settings/control"), """
                        {"absoluteLimits":{"Thermostat":{"targetTemperature":{"min":18,"max":28}}},"requireApprovalForControlNodes":true,
                         "defaultValiditySec":900,"baseVersion":0}""")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(1))
                .andExpect(jsonPath("$.response.requireApprovalForControlNodes").value(true))
                .andExpect(jsonPath("$.response.absoluteLimits.Thermostat.targetTemperature.max").value(28.0));
        mvc.perform(as(org, admin, json(put("/core/settings/control"), "{\"minIntervalSec\":5,\"baseVersion\":0}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, admin, json(put("/core/settings/control"), "{\"defaultValiditySec\":10,\"baseVersion\":1}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("defaultValiditySec"));
        assertThat(configMessages(org)).anyMatch(m -> m.contains("\"SETTING\"") && m.contains("\"control\""));
        mvc.perform(get("/internal/core/control-settings").param("organizationId", Long.toString(org)).header("X-CALLER-SERVICE", "action"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.defaultValiditySec").value(900))
                .andExpect(jsonPath("$.response.absoluteLimits.Thermostat.targetTemperature.min").value(18.0));
    }

    @Test
    @DisplayName("[ACT-02.01][SIM-07.03] 내부 제어 정보: 지원 기능·모델 제약·드라이버·절대 한계·샌드박스(API-ACT-40·41), 모델 기능은 카탈로그에 있어야 함(ACT-01.03)")
    void internalControlProfile() throws Exception {
        long model = model("AIRCON-3", "[{\"capability\":\"Thermostat\",\"constraints\":{\"targetTemperature\":{\"min\":18,\"max\":30}}},"
                + "{\"capability\":\"Contact\"}]");
        long site = data.site(org, "캠퍼스");
        long room = data.space(org, site, "ROOM", "실습실");
        long source = data.source(org, "lns");
        long aircon = data.device(org, source, "ac-1", "ACTIVE", room, model);
        mvc.perform(get("/internal/core/devices/" + aircon + "/control-profile").header("X-CALLER-SERVICE", "data2flow-action"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.controllable").value(false))
                .andExpect(jsonPath("$.response.organizationId").value(Long.toString(org)))
                .andExpect(jsonPath("$.response.capabilities.Thermostat.constraints.targetTemperature.max").value(30.0))
                .andExpect(jsonPath("$.response.capabilities.Thermostat.reapplyOnReconnect").value(true))
                .andExpect(jsonPath("$.response.settings.defaultValiditySec").value(600))
                .andExpect(jsonPath("$.response.sandbox").value(false));
        long driver = Long.parseLong(read(mvc.perform(as(org, integrator, json(post("/core/drivers"),
                "{\"name\":\"가상\",\"type\":\"VIRTUAL\"}"))).andReturn(), "$.response.driverId"));
        mvc.perform(as(org, integrator, json(put("/core/device-models/" + model + "/driver"), "{\"driverId\":\"" + driver + "\"}")))
                .andExpect(status().isOk());
        jdbc.sql("UPDATE data2flow_core.spaces SET sandbox = true WHERE id = :id").param("id", site).update();
        mvc.perform(get("/internal/core/devices/" + aircon + "/control-profile").header("X-CALLER-SERVICE", "data2flow-action"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.controllable").value(true))
                .andExpect(jsonPath("$.response.driver.type").value("VIRTUAL")).andExpect(jsonPath("$.response.driver.retry.initialMs").value(1000))
                .andExpect(jsonPath("$.response.sandbox").value(true));
        mvc.perform(get("/internal/core/sim/sandbox-spaces").param("organizationId", Long.toString(org)).header("X-CALLER-SERVICE", "action"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.spaceIds.length()").value(2));
        mvc.perform(get("/internal/core/sim/sandbox-spaces").header("X-CALLER-SERVICE", "action"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.spaceIds.length()").value(2));
        mvc.perform(get("/internal/core/devices/987654321/control-profile").header("X-CALLER-SERVICE", "action"))
                .andExpect(status().isNotFound());

        // ACT-01.03: 카탈로그에 없는 기능 이름으로 모델을 만들 수 없다
        mvc.perform(as(org, integrator, json(post("/core/device-models"), """
                        {"code":"RELAY-9","vendor":"v","name":"릴레이","protocol":"MQTT","kind":"ACTUATOR","capabilities":[{"capability":"Teleport"}]}""")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("CAPABILITY_NOT_SUPPORTED"));
        mvc.perform(as(org, integrator, json(patch("/core/device-models/" + model), "{\"capabilities\":[{\"capability\":\"Switch\"}],\"baseVersion\":0}")))
                .andExpect(status().isOk());
    }

    long model(String code, String capabilities) {
        long id = data.model(org, code, List.of());
        jdbc.sql("UPDATE data2flow_core.device_models SET kind = 'ACTUATOR', capabilities = CAST(:caps AS jsonb) WHERE id = :id")
                .param("caps", capabilities).param("id", id).update();
        return id;
    }
}
