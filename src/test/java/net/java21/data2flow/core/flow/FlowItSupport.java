package net.java21.data2flow.core.flow;

import net.java21.data2flow.core.support.LoopItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 플로우 IT 공통: 실습실(온도 센서 2대 + 에어컨 1대, Thermostat 모델)과 플로우 만들기 도우미 */
abstract class FlowItSupport extends LoopItSupport {

    protected long site;
    protected long lab;
    protected long source;
    protected long airconModel;
    protected long sensor1;
    protected long aircon;

    @BeforeEach
    void flowFixtures() {
        site = data.site(org, "캠퍼스");
        lab = data.space(org, site, "ROOM", "실습실");
        source = data.source(org, "lns");
        airconModel = data.model(org, "AIRCON-T", List.of());
        jdbc.sql("UPDATE data2flow_core.device_models SET kind = 'ACTUATOR', capabilities = CAST(:c AS jsonb) WHERE id = :id")
                .param("c", "[{\"capability\":\"Thermostat\",\"constraints\":{\"targetTemperature\":{\"min\":18,\"max\":30}}}]")
                .param("id", airconModel).update();
        sensor1 = data.device(org, source, "th-1", "ACTIVE", lab, null);
        data.device(org, source, "th-2", "ACTIVE", lab, null);
        aircon = data.device(org, source, "ac-1", "ACTIVE", lab, airconModel);
        jdbc.sql("UPDATE data2flow_core.devices SET kind = 'ACTUATOR' WHERE id = :id").param("id", aircon).update();
    }

    /** "고온이면 냉방" 정의(4노드). threshold로 기준값을 바꿀 수 있다 */
    protected String hotThenCool(long spaceId, double threshold, int target) {
        return """
                {"schema":"data2flow.flow-definition/v1","mode":{"concurrency":"queued","keyBy":"deviceId","max":10},
                 "nodes":[
                  {"id":"n-trigger01","type":"trigger.telemetry","config":{"target":{"spaceId":"%d","relation":"measures"},"metrics":["temperature"]}},
                  {"id":"n-average01","type":"transform.aggregate","config":{"window":"PT1M","fn":"avg","groupBy":"space"}},
                  {"id":"n-threshold1","type":"condition.threshold","config":{"metric":"temperature","op":">","value":%s,"for":"PT5M","clear":26}},
                  {"id":"n-control001","type":"action.control","config":{"target":{"spaceId":"%d","relation":"controls","capability":"Thermostat"},
                    "capability":"Thermostat","command":"set","args":{"mode":"cool","targetTemperature":%d},"validitySeconds":600}}],
                 "wires":[{"from":"n-trigger01","to":"n-average01"},{"from":"n-average01","to":"n-threshold1"},
                          {"from":"n-threshold1","port":"true","to":"n-control001"}]}"""
                .formatted(spaceId, threshold, spaceId, target);
    }

    /** 제어 노드 없는 정의(측정 → 디버그) */
    protected String monitorOnly(long spaceId) {
        return """
                {"schema":"data2flow.flow-definition/v1","nodes":[
                  {"id":"n-trigger01","type":"trigger.telemetry","config":{"target":{"spaceId":"%d"},"metrics":["co2"]}},
                  {"id":"n-debug0001","type":"debug.log","config":{"level":"INFO"}}],
                 "wires":[{"from":"n-trigger01","to":"n-debug0001"}]}""".formatted(spaceId);
    }

    protected String create(long userId, String name, String definition) throws Exception {
        MvcResult r = mvc.perform(as(org, userId, json(post("/core/flows"), "{\"name\":\"" + name + "\",\"definition\":" + definition + "}")))
                .andExpect(status().isCreated()).andReturn();
        return read(r, "$.response.flowId");
    }
}
