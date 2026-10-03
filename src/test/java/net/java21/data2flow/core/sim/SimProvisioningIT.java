package net.java21.data2flow.core.sim;

import net.java21.data2flow.core.support.LoopItSupport;
import net.java21.data2flow.core.support.StubHttpServer.Reply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SIM-01.01·01.02·03.02·04.05·07.03·09.01·09.07·11.01: 가상 공간·기기·키트·프리셋 기준 정보 사가(core 먼저 → simulator, 실패하면 되돌림),
 * 샌드박스, simulator 내부 API(API-SIM-35 맥락·정리 요청), 하트비트 카나리 기기(ING-07.05).
 */
class SimProvisioningIT extends LoopItSupport {

    static final String CATALOG = """
            {"types":[{"id":"1","key":"th-sensor","name":"온습도 센서","category":"SENSOR","capabilities":[],"linkedModelCode":"EM300-TH"},
                      {"id":"2","key":"aircon","name":"에어컨","category":"ACTUATOR","capabilities":["Thermostat","Switch"]},
                      {"id":"3","key":"co2-sensor","name":"CO2 센서","category":"SENSOR","capabilities":[]},
                      {"id":"4","key":"pir-sensor","name":"재실 센서","category":"SENSOR","capabilities":[]}],
             "kits":[{"key":"classroom-standard","name":"표준 강의실 키트","items":[{"typeKey":"th-sensor","count":2,"namePrefix":"온습도","relation":"MEASURES"},
                      {"typeKey":"aircon","count":1,"namePrefix":"에어컨","relation":"CONTROLS"}],"suggestedFlowTemplates":["hot-then-cool"]}]}""";

    @BeforeEach
    void simulator() {
        STUB.ok("GET", "/internal/sim/catalog", 200, CATALOG);
        STUB.on("PUT", "/internal/sim/spaces/\\d+", r -> new Reply(200, net.java21.data2flow.core.support.StubHttpServer.success(
                "{\"spaceId\":\"" + r.path().replaceAll("\\D", "") + "\",\"preset\":\"CLASSROOM\",\"physics\":{\"areaM2\":66},\"version\":0}"), Map.of()));
        STUB.on("GET", "/internal/sim/spaces/\\d+", r -> new Reply(200, net.java21.data2flow.core.support.StubHttpServer.success(
                "{\"spaceId\":\"" + r.path().replaceAll("\\D", "") + "\",\"preset\":\"CLASSROOM\",\"physics\":{\"areaM2\":66},\"current\":{\"temperature\":29.1}}"),
                Map.of()));
        STUB.ok("POST", "/internal/sim/devices/batch-create", 201, "{\"devices\":[]}");
    }

    private long createSpace(String name) throws Exception {
        MvcResult r = mvc.perform(as(org, integrator, json(post("/core/sim/spaces"),
                        "{\"name\":\"" + name + "\",\"preset\":\"CLASSROOM\",\"physics\":{\"areaM2\":66,\"heightM\":3}}")))
                .andExpect(status().isCreated()).andReturn();
        return Long.parseLong(read(r, "$.response.spaceId"));
    }

    @Test
    @DisplayName("[SIM-01.01][SIM-01.02][AT-SIM-01.1] 가상 공간: DEV 트리에 virtual=true(가상 뿌리 아래) + simulator 물리 설정, 목록 virtual 필터·배지, 가상 공간 아래 실제 공간 거부 — TC-SIM-001·004")
    void virtualSpaces() throws Exception {
        mvc.perform(as(org, viewer, json(post("/core/sim/spaces"), "{\"name\":\"x\"}"))).andExpect(status().isForbidden());
        long demo = createSpace("데모 강의실");
        assertThat(STUB.received("PUT", "/internal/sim/spaces/" + demo).getFirst().body()).contains("\"preset\":\"CLASSROOM\"").contains("areaM2");
        mvc.perform(as(org, integrator, get("/core/sim/spaces/" + demo)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.virtual").value(true))
                .andExpect(jsonPath("$.response.physics.areaM2").value(66)).andExpect(jsonPath("$.response.current.temperature").value(29.1));
        long realSite = data.site(org, "캠퍼스");
        // 실제 공간 아래 가상 공간은 된다
        mvc.perform(as(org, integrator, json(post("/core/sim/spaces"), "{\"name\":\"가상 실습실\",\"parentId\":\"" + realSite + "\"}")))
                .andExpect(status().isCreated());
        mvc.perform(as(org, viewer, get("/core/spaces").param("virtual", "true")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.length()").value(2));
        mvc.perform(as(org, viewer, get("/core/spaces").param("virtual", "false")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].children.length()").value(0));
        mvc.perform(as(org, viewer, get("/core/spaces/" + demo)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.virtual").value(true));
        // 가상 공간 아래 실제 공간은 안 된다
        mvc.perform(as(org, admin, json(post("/core/spaces"), "{\"parentId\":\"" + demo + "\",\"type\":\"ZONE\",\"name\":\"실제 구역\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("VIRTUAL_PARENT"));
        STUB.ok("GET", "/internal/sim/spaces", 200, "[{\"spaceId\":\"" + demo + "\",\"preset\":\"CLASSROOM\",\"current\":{\"co2\":800}}]");
        mvc.perform(as(org, analyst, get("/core/sim/spaces")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.responses[?(@.spaceId=='" + demo + "')].current.co2").value(800));
        mvc.perform(as(org, integrator, json(put("/core/sim/spaces/" + demo), "{\"name\":\"데모 강의실 2\",\"physics\":{\"areaM2\":70}}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.name").value("데모 강의실 2"));
        mvc.perform(as(org, integrator, get("/core/sim/spaces/" + realSite))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SIM_NOT_FOUND"));
        // simulator 실패 → core 공간도 만들지 않음(사가 보상)
        STUB.fail("PUT", "/internal/sim/spaces/\\d+", 400, "SIM_PROPERTY_OUT_OF_RANGE",
                "\"errors\":[{\"field\":\"physics.areaM2\",\"code\":\"SIM_PROPERTY_OUT_OF_RANGE\",\"message\":\"1~10000\"}]");
        mvc.perform(as(org, integrator, json(post("/core/sim/spaces"), "{\"name\":\"잘못된 공간\",\"physics\":{\"areaM2\":-1}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("physics.areaM2"));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.spaces WHERE organization_id = :org AND name = '잘못된 공간'")
                .param("org", org).query(Long.class).single()).isZero();
        STUB.on("DELETE", "/internal/sim/spaces/\\d+", r -> new Reply(204, "", Map.of()));
        mvc.perform(as(org, integrator, delete("/core/sim/spaces/" + demo))).andExpect(status().isNoContent());
        assertThat(auditCount(org, "SIM_SPACE_DELETED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[SIM-09.01][SIM-03.02][SIM-11.01] 가상 기기 배치: DEV 기기(virtual·SIM 소스·모델·가상 드라이버 연결) → simulator batch-create, 실패하면 되돌림, 한도 500 — TC-SIM-114(core 몫)")
    void placeDevices() throws Exception {
        long space = createSpace("배치 공간");
        data.model(org, "EM300-TH", List.of());
        MvcResult r = mvc.perform(as(org, integrator, json(post("/core/sim/devices"),
                        "{\"typeId\":\"2\",\"spaceId\":\"" + space + "\",\"count\":2,\"namePrefix\":\"에어컨\"}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.devices.length()").value(2)).andReturn();
        String deviceId = read(r, "$.response.devices[0].deviceId");
        String sent = STUB.received("POST", "/internal/sim/devices/batch-create").getFirst().body();
        assertThat(sent).contains("\"typeKey\":\"aircon\"").contains("\"externalId\":\"5a1d");
        Map<String, Object> row = jdbc.sql("""
                        SELECT d.is_virtual, d.kind, d.status, m.code, s.type AS source_type,
                               (SELECT dr.type FROM data2flow_core.driver_bindings b JOIN data2flow_core.drivers dr ON dr.id = b.driver_id
                                 WHERE b.model_id = d.model_id) AS driver_type
                          FROM data2flow_core.devices d JOIN data2flow_core.device_models m ON m.id = d.model_id
                          JOIN data2flow_core.data_sources s ON s.id = d.source_id WHERE d.id = :id""")
                .param("id", Long.parseLong(deviceId)).query().singleRow();
        assertThat(row).containsEntry("is_virtual", true).containsEntry("kind", "ACTUATOR").containsEntry("status", "ACTIVE")
                .containsEntry("code", "SIM-AIRCON").containsEntry("source_type", "SIMULATION").containsEntry("driver_type", "VIRTUAL");
        // 센서는 실측 모델(EM300-TH)을 쓴다
        mvc.perform(as(org, integrator, json(post("/core/sim/devices"), "{\"typeId\":\"th-sensor\",\"spaceId\":\"" + space + "\",\"count\":1}")))
                .andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT m.code FROM data2flow_core.devices d JOIN data2flow_core.device_models m ON m.id = d.model_id"
                + " WHERE d.organization_id = :org AND d.kind = 'SENSOR' AND d.space_id = :s").param("org", org).param("s", space)
                .query(String.class).single()).isEqualTo("EM300-TH");
        // 하트비트 카나리 기기도 함께 생긴다(ING-07.05)
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.devices WHERE organization_id = :org AND external_id = '__heartbeat__'")
                .param("org", org).query(Long.class).single()).isEqualTo(1);
        // 실제 공간에는 둘 수 없다
        long realSite = data.site(org, "캠퍼스");
        mvc.perform(as(org, integrator, json(post("/core/sim/devices"), "{\"typeId\":\"2\",\"spaceId\":\"" + realSite + "\",\"count\":1}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SIM_TARGET_NOT_VIRTUAL"));
        mvc.perform(as(org, integrator, json(post("/core/sim/devices"), "{\"typeId\":\"2\",\"spaceId\":\"" + space + "\",\"count\":51}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("count"));
        mvc.perform(as(org, integrator, json(post("/core/sim/devices"), "{\"typeId\":\"99\",\"spaceId\":\"" + space + "\",\"count\":1}")))
                .andExpect(status().isNotFound());
        // simulator 실패 → core 기기도 되돌림
        long before = jdbc.sql("SELECT count(*) FROM data2flow_core.devices WHERE organization_id = :org").param("org", org).query(Long.class).single();
        STUB.fail("POST", "/internal/sim/devices/batch-create", 409, "SIM_DEVICE_QUOTA_EXCEEDED", null);
        mvc.perform(as(org, integrator, json(post("/core/sim/devices"), "{\"typeId\":\"2\",\"spaceId\":\"" + space + "\",\"count\":3}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SIM_DEVICE_QUOTA_EXCEEDED"));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.devices WHERE organization_id = :org").param("org", org).query(Long.class).single())
                .isEqualTo(before);
        // core 한도(500)
        jdbc.sql("""
                        INSERT INTO data2flow_core.devices (organization_id, source_id, external_id, name, status, is_virtual)
                        SELECT :org, (SELECT id FROM data2flow_core.data_sources WHERE organization_id = :org AND type = 'SIMULATION'),
                               'bulk-' || g, 'bulk', 'ACTIVE', true FROM generate_series(1, 497) g""").param("org", org).update();
        mvc.perform(as(org, integrator, json(post("/core/sim/devices"), "{\"typeId\":\"2\",\"spaceId\":\"" + space + "\",\"count\":2}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SIM_DEVICE_QUOTA_EXCEEDED"));

        // 설정 조회·수정·삭제 중계
        STUB.ok("GET", "/internal/sim/devices/" + deviceId, 200, "{\"deviceId\":\"" + deviceId + "\",\"reportIntervalSec\":60}");
        mvc.perform(as(org, analyst, get("/core/sim/devices/" + deviceId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.reportIntervalSec").value(60));
        STUB.ok("PATCH", "/internal/sim/devices/" + deviceId, 200, "{\"deviceId\":\"" + deviceId + "\",\"reportIntervalSec\":30}");
        mvc.perform(as(org, integrator, json(patch("/core/sim/devices/" + deviceId),
                        "{\"reportIntervalSec\":30}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.reportIntervalSec").value(30));
        STUB.on("DELETE", "/internal/sim/devices/" + deviceId, x -> new Reply(204, "", Map.of()));
        mvc.perform(as(org, integrator, delete("/core/sim/devices/" + deviceId).param("purgeData", "true"))).andExpect(status().isNoContent());
        mvc.perform(as(org, analyst, get("/core/sim/devices/" + deviceId))).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.sim_purge_jobs WHERE organization_id = :org").param("org", org)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("[SIM-09.07][SIM-04.05] 키트 배치(새 가상 공간) → simulator kits place, 데모 프리셋 준비 → 공간·기기·시나리오 + hot-then-cool 플로우 초안(재준비는 reused)")
    void kitsAndPresets() throws Exception {
        STUB.ok("POST", "/internal/sim/kits/classroom-standard/place", 201,
                "{\"spaceId\":\"1\",\"devices\":[],\"suggestedFlows\":[{\"templateKey\":\"hot-then-cool\",\"name\":\"고온이면 냉방\",\"bindings\":{}}]}");
        mvc.perform(as(org, integrator, json(post("/core/sim/kits/classroom-standard/place"), "{\"newSpace\":{\"name\":\"키트 강의실\",\"preset\":\"CLASSROOM\"}}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.suggestedFlows[0].templateKey").value("hot-then-cool"));
        String kitBody = STUB.received("POST", "/internal/sim/kits/classroom-standard/place").getFirst().body();
        assertThat(kitBody.split("\"typeKey\"")).hasSize(4);
        mvc.perform(as(org, integrator, json(post("/core/sim/kits/none/place"), "{\"spaceId\":\"1\"}"))).andExpect(status().isNotFound());

        STUB.ok("GET", "/internal/sim/presets", 200, """
                [{"key":"heatwave-afternoon","name":"폭염 오후","spaceName":"데모 강의실(폭염)","spacePreset":"CLASSROOM","state":"NOT_PREPARED",
                  "scenarioId":null,"devices":[{"typeKey":"th-sensor","count":2,"namePrefix":"온습도","relation":"MEASURES"},
                  {"typeKey":"aircon","count":1,"namePrefix":"에어컨","relation":"CONTROLS"}],"flowTemplates":["hot-then-cool"]}]""");
        STUB.on("POST", "/internal/sim/presets/heatwave-afternoon/prepare", r -> {
            String space = r.body().replaceAll("(?s).*\"spaceId\":\"(\\d+)\".*", "$1");
            String aircon = r.body().replaceAll("(?s).*\"deviceId\":(\\d+),\"typeKey\":\"aircon\".*", "$1");
            return new Reply(200, net.java21.data2flow.core.support.StubHttpServer.success("""
                    {"scenarioId":"77","spaceIds":["%s"],"deviceIds":[],"flowTemplates":[{"templateKey":"hot-then-cool",
                     "bindings":{"spaceId":"%s","airconId":"%s","thresholdC":27,"durationMin":5,"setpointC":24}}],"reused":false}"""
                    .formatted(space, space, aircon)), Map.of());
        });
        MvcResult r = mvc.perform(as(org, integrator, post("/core/sim/presets/heatwave-afternoon/prepare")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.scenarioId").value("77"))
                .andExpect(jsonPath("$.response.deviceIds.length()").value(3)).andExpect(jsonPath("$.response.flowIds.length()").value(1))
                .andExpect(jsonPath("$.response.reused").value(false)).andReturn();
        String flowId = read(r, "$.response.flowIds[0]");
        mvc.perform(as(org, integrator, get("/core/flows/" + flowId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.flow.status").value("DRAFT"))
                .andExpect(jsonPath("$.response.version.definition.nodes[3].config.args.targetTemperature").value(24.0))
                .andExpect(jsonPath("$.response.version.validation.errors.length()").value(0));
        STUB.ok("GET", "/internal/sim/presets", 200, "[{\"key\":\"heatwave-afternoon\",\"state\":\"PREPARED\",\"scenarioId\":\"77\"}]");
        STUB.ok("POST", "/internal/sim/presets/heatwave-afternoon/prepare", 200,
                "{\"scenarioId\":\"77\",\"spaceIds\":[\"5\"],\"deviceIds\":[],\"flowTemplates\":[],\"reused\":true}");
        mvc.perform(as(org, integrator, post("/core/sim/presets/heatwave-afternoon/prepare")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.reused").value(true));
        mvc.perform(as(org, integrator, post("/core/sim/presets/nope/prepare"))).andExpect(status().isNotFound());
        mvc.perform(as(org, analyst, get("/core/sim/presets")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses[0].scenarioId").value("77"));
    }

    @Test
    @DisplayName("[SIM-07.03][AT-SIM-13.2] 샌드박스: SIM_ADMIN만, 실제 기기가 있는 공간 트리는 403 SIM_SANDBOX_VIOLATION, 지정·해제는 SIM_SANDBOX 설정 변경 발행 — TC-SIM-081")
    void sandbox() throws Exception {
        long space = createSpace("샌드박스 공간");
        long realSite = data.site(org, "캠퍼스");
        long source = data.source(org, "lns");
        data.device(org, source, "real-1", "ACTIVE", realSite, null);
        mvc.perform(as(org, integrator, json(put("/core/sim/spaces/" + space + "/sandbox"), "{\"sandbox\":true}"))).andExpect(status().isForbidden());
        mvc.perform(as(org, admin, json(put("/core/sim/spaces/" + realSite + "/sandbox"), "{\"sandbox\":true}")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("SIM_SANDBOX_VIOLATION"));
        STUB.ok("PUT", "/internal/sim/spaces/" + space + "/sandbox", 200, "{\"spaceId\":\"" + space + "\",\"sandbox\":true}");
        mvc.perform(as(org, admin, json(put("/core/sim/spaces/" + space + "/sandbox"), "{\"sandbox\":true}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.sandbox").value(true));
        mvc.perform(as(org, admin, json(put("/core/sim/spaces/" + space + "/sandbox"), "{}"))).andExpect(status().isBadRequest());
        assertThat(configMessages(org)).anyMatch(m -> m.contains("\"SIM_SANDBOX\"") && m.contains("\"" + space + "\""));
        assertThat(auditCount(org, "SIM_SANDBOX_CHANGED")).isEqualTo(1);
        mvc.perform(get("/internal/core/sim/sandbox-spaces").param("organizationId", Long.toString(org)).header("X-CALLER-SERVICE", "action"))
                .andExpect(jsonPath("$.response.spaceIds[0]").value(Long.toString(space)));
    }

    @Test
    @DisplayName("[SIM-01.01][ING-07.05] simulator 내부 API: 맥락(API-SIM-35 SIM 소스·가상 기기·공간), 보관 기한 정리 요청(API-SIM-36, 202 jobId)")
    void internalApis() throws Exception {
        long space = createSpace("맥락 공간");
        mvc.perform(as(org, integrator, json(post("/core/sim/devices"), "{\"typeId\":\"3\",\"spaceId\":\"" + space + "\",\"count\":1}")))
                .andExpect(status().isCreated());
        mvc.perform(get("/internal/core/sim/context").param("organizationId", Long.toString(org)).header("X-CALLER-SERVICE", "data2flow-simulator"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.simSourceId").isNotEmpty())
                .andExpect(jsonPath("$.response.devices.length()").value(2))
                .andExpect(jsonPath("$.response.spaces.length()").value(2));
        mvc.perform(json(post("/internal/core/sim/data/purge"), "{\"organizationId\":" + org + ",\"runIds\":[\"11\",\"12\"]}")
                        .header("X-CALLER-SERVICE", "data2flow-simulator"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.jobId").isNotEmpty());
        mvc.perform(json(post("/internal/core/sim/data/purge"), "{\"organizationId\":" + org + ",\"runIds\":[]}")
                        .header("X-CALLER-SERVICE", "data2flow-simulator"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT run_ids FROM data2flow_core.sim_purge_jobs WHERE organization_id = :org AND origin = 'RETENTION'")
                .param("org", org).query((rs, n) -> List.of((String[]) rs.getArray(1).getArray())).single()).containsExactly("11", "12");
    }
}
