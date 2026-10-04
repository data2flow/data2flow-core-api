package net.java21.data2flow.core.flow;

import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.FlowApplyReported;
import net.java21.data2flow.contracts.message.event.FlowStateChanged;
import net.java21.data2flow.core.support.StubHttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FLW-01.01·01.02·01.03·01.06: 생성·초안·검증·적용·버전 비교·롤백·상태 전이·설정 변경, 노드 카탈로그·지표 중계, 엔진 내부 API·보고 이벤트.
 */
class FlowLifecycleIT extends FlowItSupport {

    @Test
    @DisplayName("[FLW-01.06] 초안 저장 → 적용(v2 ACTIVE) → 새 초안 적용(v2 ARCHIVED, v3 ACTIVE), 버전 비교(노드 1 추가·설정 1 변경), 배포 메모 저장 — TC-FLW-022")
    void versionsAndApply() throws Exception {
        String flowId = create(integrator, "고온이면 냉방", hotThenCool(lab, 27, 24));
        mvc.perform(as(org, integrator, get("/core/flows/" + flowId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.flow.status").value("DRAFT"))
                .andExpect(jsonPath("$.response.version.version").value(1)).andExpect(jsonPath("$.response.version.state").value("DRAFT"))
                .andExpect(jsonPath("$.response.version.validation.errors.length()").value(0))
                .andExpect(jsonPath("$.response.overlay.revision").value(0));
        mvc.perform(as(org, integrator, json(put("/core/flows/" + flowId + "/draft"), "{\"baseVersion\":0,\"definition\":" + hotThenCool(lab, 28, 24) + "}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("FLOW_VERSION_CONFLICT"));
        mvc.perform(as(org, integrator, json(put("/core/flows/" + flowId + "/draft"), "{\"baseVersion\":1,\"definition\":" + hotThenCool(lab, 27, 24) + "}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.draftVersion").value(2));
        mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/validate"), "{\"version\":2}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.errors.length()").value(0))
                .andExpect(jsonPath("$.response.changeSummary.added.length()").value(4))
                .andExpect(jsonPath("$.response.risky.controlNodesChanged").value(true))
                .andExpect(jsonPath("$.response.approvalRequired").value(false));
        // 제어 노드 변경은 위험 확인이 있어야 한다
        mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/apply"), "{\"version\":2,\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("acknowledgedRisks"));
        mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/apply"),
                        "{\"version\":2,\"baseVersion\":1,\"acknowledgedRisks\":true}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("FLOW_VERSION_CONFLICT"));
        mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/apply"),
                        "{\"version\":2,\"baseVersion\":0,\"acknowledgedRisks\":true,\"memo\":\"첫 배포\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.appliedVersion").value(2))
                .andExpect(jsonPath("$.response.applyStatus.targetVersion").value(2))
                .andExpect(jsonPath("$.response.applyStatus.converged").value(false));
        assertThat(configMessages(org)).anyMatch(m -> m.contains("\"FLOW\"") && m.contains(flowId) && m.contains("\"version\": 2"));
        mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/apply"),
                        "{\"version\":2,\"baseVersion\":2,\"acknowledgedRisks\":true}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("FLOW_STATE_CONFLICT"));

        String v3 = hotThenCool(lab, 28, 24).replace("\"wires\":[", "\"wires\":[{\"from\":\"n-threshold1\",\"port\":\"false\",\"to\":\"n-debug0001\"},")
                .replace("\"nodes\":[", "\"nodes\":[{\"id\":\"n-debug0001\",\"type\":\"debug.log\",\"config\":{}},");
        mvc.perform(as(org, integrator, json(put("/core/flows/" + flowId + "/draft"), "{\"baseVersion\":2,\"definition\":" + v3 + "}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.draftVersion").value(3));
        mvc.perform(as(org, operator, json(post("/core/flows/" + flowId + "/apply"),
                        "{\"version\":3,\"baseVersion\":2,\"acknowledgedRisks\":false,\"memo\":\"28도로 올림\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.appliedVersion").value(3));
        mvc.perform(as(org, viewer, get("/core/flows/" + flowId + "/versions"))).andExpect(status().isForbidden());
        mvc.perform(as(org, analyst, get("/core/flows/" + flowId + "/versions")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[0].version").value(3)).andExpect(jsonPath("$.responses[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$.responses[0].memo").value("28도로 올림"))
                .andExpect(jsonPath("$.responses[1].state").value("ARCHIVED"))
                .andExpect(jsonPath("$.responses[1].appliedBy.userId").value(Long.toString(integrator)));
        mvc.perform(as(org, analyst, get("/core/flows/" + flowId + "/version-diff").param("from", "2").param("to", "3")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.added[0]").value("n-debug0001"))
                .andExpect(jsonPath("$.response.changed.length()").value(1))
                .andExpect(jsonPath("$.response.changed[0].nodeId").value("n-threshold1"))
                .andExpect(jsonPath("$.response.changed[0].fields[0]").value("config.value"))
                .andExpect(jsonPath("$.response.changed[0].statePolicy").value("KEEP"))
                .andExpect(jsonPath("$.response.wires.added.length()").value(1));
        mvc.perform(as(org, analyst, get("/core/flows/" + flowId + "/version-diff").param("from", "2")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, analyst, get("/core/flows/" + flowId + "/version-diff").param("from", "2").param("to", "99")))
                .andExpect(status().isNotFound());

        // 롤백: v2(ARCHIVED)를 다시 적용
        mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/rollback"), "{\"toVersion\":3}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/rollback"), "{\"toVersion\":2,\"memo\":\"되돌림\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.appliedVersion").value(2));
        mvc.perform(as(org, analyst, get("/core/flows").param("status", "ACTIVE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].activeVersion").value(2)).andExpect(jsonPath("$.responses[0].hasControlNode").value(true))
                .andExpect(jsonPath("$.responses[0].spaceIds[0]").value(Long.toString(lab)));
        assertThat(auditCount(org, "FLOW_APPLIED")).isEqualTo(2);
        assertThat(auditCount(org, "FLOW_ROLLED_BACK")).isEqualTo(1);
    }

    @Test
    @DisplayName("[FLW-01.01][FLW-01.02][BR-FLW-05] 검증 오류(트리거 없음·순환·연결 안 됨·설정 스키마·제어 인자)는 저장은 되고 적용은 400 FLOW_VALIDATION_FAILED(errors[] + response) — TC-FLW-004·011")
    void validationBlocksApply() throws Exception {
        String broken = """
                {"schema":"data2flow.flow-definition/v1","nodes":[
                  {"id":"n-thr00001","type":"condition.threshold","config":{"op":"~"}},
                  {"id":"n-thr00002","type":"condition.threshold","config":{"metric":"t","op":">"}},
                  {"id":"n-control1","type":"action.control","config":{"target":{"spaceId":"999999"},"capability":"Thermostat","command":"set",
                    "args":{"targetTemperature":40},"priority":"MANUAL"}}],
                 "wires":[{"from":"n-thr00001","port":"true","to":"n-thr00002"},{"from":"n-thr00002","port":"true","to":"n-thr00001"},
                          {"from":"n-thr00002","port":"nope","to":"n-control1"}]}""";
        MvcResult r = mvc.perform(as(org, integrator, json(post("/core/flows"), "{\"name\":\"깨진 플로우\",\"definition\":" + broken + "}")))
                .andExpect(status().isCreated()).andReturn();
        String flowId = read(r, "$.response.flowId");
        List<String> codes = read(r, "$.response.validation.errors[*].code");
        assertThat(codes).contains("NO_TRIGGER", "CYCLE", "INVALID_CONFIG", "UNKNOWN_PORT", "TARGET_MISSING");
        mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/apply"),
                        "{\"version\":1,\"baseVersion\":0,\"acknowledgedRisks\":true}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("FLOW_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].code", hasItem("NO_TRIGGER")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("nodes[n-control1].config.priority")));
        // 엔진 컴파일러 검증(API-FLW-84)이 있으면 그 오류를 그대로 싣고 core 업무 검사(대상)를 더한다(ADR-044)
        STUB.ok("POST", "/internal/flow/definitions/validate", 200,
                "{\"errors\":[{\"field\":\"nodes[n-thr00001].config.metric\",\"code\":\"INVALID_CONFIG\",\"message\":\"필수\"}]}");
        mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/validate"), "{}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.errors[0].field").value("nodes[n-thr00001].config.metric"))
                .andExpect(jsonPath("$.response.errors[*].code", hasItem("TARGET_MISSING")))
                .andExpect(jsonPath("$.response.errors[*].code", not(hasItem("NO_TRIGGER"))));
        assertThat(STUB.received("POST", "/internal/flow/definitions/validate").getLast().body()).contains("\"flowId\":\"" + flowId + "\"");
        STUB.reset();
        mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/validate"), "{\"definition\":" + monitorOnly(lab) + "}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.errors.length()").value(0))
                .andExpect(jsonPath("$.response.risky.controlNodesChanged").value(false));
        mvc.perform(as(org, integrator, json(post("/core/flows"), "{\"name\":\"x\",\"definition\":[]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("definition"));
        StringBuilder many = new StringBuilder("{\"schema\":\"data2flow.flow-definition/v1\",\"nodes\":[");
        for (int i = 0; i < 201; i++) {
            many.append(i == 0 ? "" : ",").append("{\"id\":\"n-").append(i).append("\",\"type\":\"debug.log\"}");
        }
        many.append("]}");
        mvc.perform(as(org, integrator, json(post("/core/flows"), "{\"name\":\"많음\",\"definition\":" + many + "}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("FLOW_NODE_LIMIT_EXCEEDED"));
        mvc.perform(as(org, integrator, json(post("/core/flows"), "{\"name\":\"깨진 플로우\",\"definition\":" + monitorOnly(lab) + "}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("FLOW_NAME_DUPLICATED"));
    }

    @Test
    @DisplayName("[FLW-01.06][FLW-08.04] 일시정지·재개·비활성·삭제 상태 전이(허용 밖 409), 이름 바꾸기(PATCH, 중복 409), 목록 필터·정렬")
    void statusAndSettings() throws Exception {
        String flowId = create(operator, "CO2 모니터", monitorOnly(lab));
        String other = create(operator, "다른 플로우", monitorOnly(lab));
        mvc.perform(as(org, operator, post("/core/flows/" + flowId + "/pause"))).andExpect(status().isConflict());
        mvc.perform(as(org, operator, delete("/core/flows/" + other))).andExpect(status().isNoContent());
        mvc.perform(as(org, operator, get("/core/flows/" + other))).andExpect(status().isNotFound());
        mvc.perform(as(org, operator, json(post("/core/flows/" + flowId + "/apply"), "{\"version\":1,\"baseVersion\":0}")))
                .andExpect(status().isOk());
        mvc.perform(as(org, operator, delete("/core/flows/" + flowId))).andExpect(status().isConflict());
        mvc.perform(as(org, operator, json(post("/core/flows/" + flowId + "/pause"), "{\"mode\":\"BUFFER\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("PAUSED"));
        mvc.perform(as(org, operator, post("/core/flows/" + flowId + "/pause"))).andExpect(status().isConflict());
        mvc.perform(as(org, operator, post("/core/flows/" + flowId + "/resume")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("ACTIVE"));
        mvc.perform(as(org, operator, post("/core/flows/" + flowId + "/disable")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("DISABLED"));
        assertThat(auditCount(org, "FLOW_PAUSED")).isEqualTo(1);
        assertThat(auditCount(org, "FLOW_DISABLED")).isEqualTo(1);

        mvc.perform(as(org, operator, json(patch("/core/flows/" + flowId), "{\"name\":\"CO2 감시\",\"purpose\":\"환기 판단\",\"tags\":[\"co2\"]}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.name").value("CO2 감시"))
                .andExpect(jsonPath("$.response.pauseMode").value("BUFFER")).andExpect(jsonPath("$.response.tags[0]").value("co2"));
        create(operator, "세 번째", monitorOnly(lab));
        mvc.perform(as(org, operator, json(patch("/core/flows/" + flowId), "{\"name\":\"세 번째\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("FLOW_NAME_DUPLICATED"));
        mvc.perform(as(org, operator, json(patch("/core/flows/" + flowId), "{\"name\":\"x\",\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("FLOW_VERSION_CONFLICT"));
        mvc.perform(as(org, operator, json(patch("/core/flows/" + flowId), "{\"ownerUserId\":\"99999\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("FLOW_OWNER_INVALID"));
        mvc.perform(as(org, operator, json(patch("/core/flows/" + flowId),
                        "{\"ownerUserId\":\"" + operator + "\",\"relatedSpaceIds\":[\"" + lab + "\"],\"errorRateThreshold\":5}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.ownerUserId").value(Long.toString(operator)));
        mvc.perform(as(org, analyst, get("/core/flows").param("q", "CO2").param("sort", "name,asc")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, analyst, get("/core/flows").param("spaceId", Long.toString(lab)).param("sort", "updatedAt")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(2));
        mvc.perform(as(org, analyst, get("/core/flows").param("sort", "health,sideways"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, analyst, get("/core/flows").param("status", "BOGUS"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, analyst, get("/core/flows/not-a-uuid")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("FLOW_NOT_FOUND"));
    }

    @Test
    @DisplayName("[FLW-01.02][API-FLW-30][API-FLW-14] 노드 카탈로그(설정 스키마·포트·제어 노드 권한), 지표는 flow-engine 내부 API로 중계, 엔진 장애만 503 FLOW_METRICS_UNAVAILABLE — TC-FLW-282")
    void catalogAndMetrics() throws Exception {
        mvc.perform(as(org, viewer, get("/core/flow-nodes"))).andExpect(status().isForbidden());
        mvc.perform(as(org, analyst, get("/core/flow-nodes")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(22))
                .andExpect(jsonPath("$.responses[?(@.type=='action.control')].permissions[0]").value("FLOW_DEPLOY_CONTROL"))
                .andExpect(jsonPath("$.responses[?(@.type=='condition.threshold')].outputs[1].name").value("false"));
        String flowId = create(operator, "지표", monitorOnly(lab));
        STUB.ok("GET", "/internal/flow/flows/" + flowId + "/metrics", 200,
                "{\"summary\":{\"executions\":12,\"errors\":0,\"errorRate\":0.0},\"nodes\":[],\"series\":[]}");
        mvc.perform(as(org, analyst, get("/core/flows/" + flowId + "/metrics").param("window", "24h").param("step", "1h")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.summary.executions").value(12));
        assertThat(STUB.received("GET", ".*/metrics").getFirst().query()).isEqualTo("window=24h&step=1h");
        mvc.perform(as(org, analyst, get("/core/flows/" + flowId + "/metrics").param("window", "30d"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, analyst, get("/core/flows/" + flowId + "/metrics").param("step", "5m"))).andExpect(status().isBadRequest());
        // M4(ADR-051): 엔진 응답(404 포함)은 그대로 중계하고, 엔진 장애(5xx·연결 실패)만 503 FLOW_METRICS_UNAVAILABLE
        String noMetrics = create(operator, "지표 없음", monitorOnly(lab));
        mvc.perform(as(org, analyst, get("/core/flows/" + noMetrics + "/metrics"))).andExpect(status().isNotFound());
        STUB.on("GET", "/internal/flow/flows/" + noMetrics + "/metrics", r -> new StubHttpServer.Reply(500, "", java.util.Map.of()));
        mvc.perform(as(org, analyst, get("/core/flows/" + noMetrics + "/metrics")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.header.resultCode").value("FLOW_METRICS_UNAVAILABLE"));
        STUB.fail("GET", "/internal/flow/flows/" + noMetrics + "/metrics", 400, "INVALID_REQUEST", null);
        mvc.perform(as(org, analyst, get("/core/flows/" + noMetrics + "/metrics"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[FLW-05.01][EVT-FLW-02][EVT-FLW-03] 엔진 내부 API(적용 플로우·버전 정의)와 보고 이벤트: 인스턴스 적용 → converged, 엔진 판정 DEGRADED·복구")
    void engineIntegration() throws Exception {
        String flowId = create(operator, "엔진", monitorOnly(lab));
        mvc.perform(as(org, operator, json(post("/core/flows/" + flowId + "/apply"), "{\"version\":1,\"baseVersion\":0}"))).andExpect(status().isOk());
        String draftOnly = create(operator, "초안만", monitorOnly(lab));
        MvcResult runtime = mvc.perform(get("/internal/core/flows/runtime").param("organizationId", Long.toString(org))
                        .header("X-CALLER-SERVICE", "flow-engine"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.flows.length()").value(1))
                .andExpect(jsonPath("$.response.organizationId").value(Long.toString(org)))
                .andExpect(jsonPath("$.response.flows[0].activeVersion").value(1))
                .andExpect(jsonPath("$.response.flows[0].definition.nodes[0].id").value("n-trigger01"))
                .andExpect(jsonPath("$.response.flows[0].overlay.revision").value(0)).andReturn();
        Number version = read(runtime, "$.response.version");
        mvc.perform(get("/internal/core/flows/runtime").param("organizationId", Long.toString(org)).param("sinceVersion", version.toString())
                        .header("X-CALLER-SERVICE", "flow-engine"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/internal/core/flows/" + flowId + "/runtime").header("X-CALLER-SERVICE", "flow-engine"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("ACTIVE"));
        mvc.perform(get("/internal/core/flows/" + draftOnly + "/runtime").header("X-CALLER-SERVICE", "flow-engine"))
                .andExpect(status().isNotFound());

        assertThat(deliver(EventType.FLOW_APPLY_REPORTED, org, new FlowApplyReported(flowId, "flow-engine-0", 1, 0L, 12, null),
                clock.instant())).isTrue();
        deliver(EventType.FLOW_APPLY_REPORTED, org + 999, new FlowApplyReported(flowId, "flow-engine-9", 1, 0L, 12, null), clock.instant());
        mvc.perform(as(org, operator, get("/core/flows/" + flowId)))
                .andExpect(jsonPath("$.response.applyStatus.instances.length()").value(1))
                .andExpect(jsonPath("$.response.applyStatus.converged").value(true));
        // 원천은 엔진 API-FLW-82(응답하면 그 값)
        STUB.ok("GET", "/internal/flow/flows/" + flowId + "/apply-status", 200, "{\"flowId\":\"" + flowId + "\",\"instances\":["
                + "{\"instanceId\":\"a\",\"appliedVersion\":1,\"reportedAt\":\"2026-10-03T00:00:00Z\"},"
                + "{\"instanceId\":\"b\",\"appliedVersion\":0,\"reportedAt\":\"2026-10-03T00:00:00Z\"}]}");
        mvc.perform(as(org, operator, get("/core/flows/" + flowId)))
                .andExpect(jsonPath("$.response.applyStatus.instances.length()").value(2))
                .andExpect(jsonPath("$.response.applyStatus.converged").value(false));
        STUB.ok("GET", "/internal/flow/node-types", 200, "[]");
        STUB.on("GET", "/internal/flow/node-types", r -> new net.java21.data2flow.core.support.StubHttpServer.Reply(200,
                "{\"header\":{\"isSuccessful\":true,\"resultCode\":\"SUCCESS\",\"resultMessage\":\"SUCCESS\"},\"page\":1,\"size\":20,"
                        + "\"totalPages\":1,\"responses\":[{\"type\":\"trigger.telemetry\",\"typeVersion\":1}],\"totalCount\":1}", java.util.Map.of()));
        mvc.perform(as(org, operator, get("/core/flow-nodes"))).andExpect(jsonPath("$.totalCount").value(1));
        deliver(EventType.FLOW_STATE_CHANGED, org, new FlowStateChanged(flowId, "ACTIVE", "DEGRADED", FlowStateChanged.Reason.DEGRADED,
                new FlowStateChanged.Metrics(0.4, 3), clock.instant()), clock.instant());
        mvc.perform(as(org, operator, get("/core/flows/" + flowId)))
                .andExpect(jsonPath("$.response.flow.status").value("DEGRADED"))
                .andExpect(jsonPath("$.response.flow.statusReason").value("DEGRADED"));
        deliver(EventType.FLOW_STATE_CHANGED, org, new FlowStateChanged(flowId, "DEGRADED", "ACTIVE", FlowStateChanged.Reason.RECOVERED,
                new FlowStateChanged.Metrics(0.0, 3), clock.instant()), clock.instant());
        mvc.perform(as(org, operator, get("/core/flows/" + flowId)))
                .andExpect(jsonPath("$.response.flow.status").value("ACTIVE")).andExpect(jsonPath("$.response.flow.statusReason").doesNotExist());
    }
}
