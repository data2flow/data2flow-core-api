package net.java21.data2flow.core.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FLW-01.05 · UC-FLW-01: 플로우 템플릿 "고온이면 냉방"(hot-then-cool)·"CO2 높으면 환기"(co2-then-ventilate) */
class FlowTemplateIT extends FlowItSupport {

    @Test
    @DisplayName("[FLW-01.05][AT-FLW-01.1] 실습실·27℃·5분·24℃ → DRAFT 플로우 4노드(수신 → 평균 → 임계값 → Thermostat 냉방 24), 감사 FLOW_CREATED — TC-FLW-019")
    void hotThenCool() throws Exception {
        mvc.perform(as(org, viewer, get("/core/flow-templates"))).andExpect(status().isForbidden());
        mvc.perform(as(org, operator, get("/core/flow-templates").param("category", "comfort")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[0].key").value("hot-then-cool"))
                .andExpect(jsonPath("$.responses[0].required.capabilities[0]").value("Thermostat"))
                .andExpect(jsonPath("$.responses[0].paramsSchema.properties.threshold.default").value(27));
        MvcResult r = mvc.perform(as(org, operator, json(post("/core/flow-templates/hot-then-cool/instantiate"),
                        "{\"name\":\"실습실 냉방\",\"params\":{\"spaceId\":\"" + lab + "\",\"threshold\":27,\"duration\":\"PT5M\",\"targetTemperature\":24}}")))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.response.draftVersion").value(1)).andExpect(jsonPath("$.response.warnings.length()").value(0))
                .andReturn();
        String flowId = read(r, "$.response.flowId");
        mvc.perform(as(org, operator, get("/core/flows/" + flowId)))
                .andExpect(jsonPath("$.response.flow.status").value("DRAFT"))
                .andExpect(jsonPath("$.response.version.definition.nodes.length()").value(4))
                .andExpect(jsonPath("$.response.version.definition.nodes[0].type").value("trigger.telemetry"))
                .andExpect(jsonPath("$.response.version.definition.nodes[1].config.fn").value("avg"))
                .andExpect(jsonPath("$.response.version.definition.nodes[2].config.for").value("PT5M"))
                .andExpect(jsonPath("$.response.version.definition.nodes[2].config.clear").value(26.0))
                .andExpect(jsonPath("$.response.version.definition.nodes[3].config.args.mode").value("cool"))
                .andExpect(jsonPath("$.response.version.definition.nodes[3].config.args.targetTemperature").value(24.0))
                .andExpect(jsonPath("$.response.version.validation.errors.length()").value(0));
        assertThat(auditCount(org, "FLOW_CREATED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[FLW-01.05][AT-FLW-01.2] 대상 공간에 Thermostat 기기가 없으면 만들되 경고(TARGET_EMPTY \"대상 기기 없음\"), 적용은 막지 않음 — TC-FLW-020")
    void targetEmptyWarning() throws Exception {
        long empty = data.space(org, site, "ROOM", "빈 강의실");
        MvcResult r = mvc.perform(as(org, integrator, json(post("/core/flow-templates/hot-then-cool/instantiate"),
                        "{\"name\":\"빈 방\",\"params\":{\"spaceId\":" + empty + "}}")))
                .andExpect(status().isCreated()).andReturn();
        List<String> codes = read(r, "$.response.warnings[*].code");
        assertThat(codes).contains("TARGET_EMPTY");
        assertThat(body(r)).contains("대상 기기 없음");
        String flowId = read(r, "$.response.flowId");
        mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/apply"),
                        "{\"version\":1,\"baseVersion\":0,\"acknowledgedRisks\":true}")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[FLW-01.05] 모르는 템플릿 404 FLOW_TEMPLATE_NOT_FOUND, 매개변수 오류 400(params.*), 공간 없음 404, simulator 묶음 이름(thresholdPpm·ventilatorId)")
    void templateErrorsAndBindings() throws Exception {
        mvc.perform(as(org, operator, json(post("/core/flow-templates/nope/instantiate"), "{\"name\":\"x\",\"params\":{}}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("FLOW_TEMPLATE_NOT_FOUND"));
        mvc.perform(as(org, operator, json(post("/core/flow-templates/hot-then-cool/instantiate"), "{\"name\":\"x\",\"params\":{}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("params.spaceId"));
        mvc.perform(as(org, operator, json(post("/core/flow-templates/hot-then-cool/instantiate"),
                        "{\"name\":\"x\",\"params\":{\"spaceId\":\"" + lab + "\",\"threshold\":99}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("params.threshold"));
        mvc.perform(as(org, operator, json(post("/core/flow-templates/hot-then-cool/instantiate"),
                        "{\"name\":\"x\",\"params\":{\"spaceId\":\"" + lab + "\",\"duration\":\"5분\"}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("params.duration"));
        mvc.perform(as(org, operator, json(post("/core/flow-templates/hot-then-cool/instantiate"),
                        "{\"name\":\"x\",\"params\":{\"spaceId\":\"99999999\"}}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, operator, json(post("/core/flow-templates/hot-then-cool/instantiate"), "{\"params\":{\"spaceId\":\"" + lab + "\"}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("name"));
        MvcResult r = mvc.perform(as(org, operator, json(post("/core/flow-templates/co2-then-ventilate/instantiate"),
                        "{\"name\":\"환기\",\"params\":{\"spaceId\":\"" + lab + "\",\"thresholdPpm\":1200,\"durationMin\":3,\"ventilatorId\":\"" + aircon
                                + "\",\"co2SensorIds\":[\"" + sensor1 + "\"],\"level\":2}}")))
                .andExpect(status().isCreated()).andReturn();
        String flowId = read(r, "$.response.flowId");
        mvc.perform(as(org, operator, get("/core/flows/" + flowId)))
                .andExpect(jsonPath("$.response.version.definition.nodes[0].config.target.deviceIds[0]").value(Long.toString(sensor1)))
                .andExpect(jsonPath("$.response.version.definition.nodes[2].config.value").value(1200.0))
                .andExpect(jsonPath("$.response.version.definition.nodes[2].config.for").value("PT3M"))
                .andExpect(jsonPath("$.response.version.definition.nodes[3].config.target.deviceIds[0]").value(Long.toString(aircon)))
                .andExpect(jsonPath("$.response.version.definition.nodes[3].config.args.level").value(2));
    }
}
