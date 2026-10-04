package net.java21.data2flow.core.flow;

import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.FlowStateChanged;
import net.java21.data2flow.core.alarm.AlarmItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** flow-engine M4 계약(ADR-051): API-FLW-12·13·41·87 중계·제공, API-DEV-122 tags, EVT-FLW-03 알람, API-FLW-80 필드 */
class FlowEngineRelayIT extends AlarmItSupport {

    static final String DEF = "{\"schema\":\"data2flow.flow-definition/v1\",\"nodes\":[],\"wires\":[]}";

    String flow(String status) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO data2flow_core.flows (id, organization_id, name, status, active_version, error_rate_threshold, auto_pause_on_degraded,
                               created_by, updated_by)
                        VALUES (CAST(:id AS uuid), :org, '엔진 시험', :status, 1, 25.00, true, 0, 0)""")
                .param("id", id).param("org", org).param("status", status).update();
        jdbc.sql("""
                        INSERT INTO data2flow_core.flow_versions (flow_id, version_no, organization_id, state, definition, definition_hash, created_by)
                        VALUES (CAST(:id AS uuid), 1, :org, 'ACTIVE', CAST(:def AS jsonb), repeat('0', 64), 0)""")
                .param("id", id).param("org", org).param("def", DEF).update();
        return id;
    }

    @Test
    @DisplayName("[FLW-03.06][API-FLW-87] 과거 텔레메트리: (기기, 측정 시각)마다 표준 텔레메트리 한 건, 측정 시각 순서, 쪽 넘김 커서, 7일 초과 400")
    void history() throws Exception {
        Instant t = clock.instant().minus(Duration.ofHours(1));
        data.telemetry(org, sensor1, "co2", t, 900, 0, false);
        data.telemetry(org, sensor1, "temperature", t, 24.5, 0, false);
        data.telemetry(org, sensor2, "co2", t.plusSeconds(60), 950, 0, false);
        data.telemetry(org, sensor3, "co2", t.plusSeconds(120), 1000, 0, false);
        MvcResult first = mvc.perform(get("/internal/core/telemetry/history").param("organizationId", Long.toString(org))
                        .param("from", t.minusSeconds(1).toString()).param("to", clock.instant().toString()).param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.responses[0].deviceId").value(sensor1))
                .andExpect(jsonPath("$.responses[0].metrics.length()").value(2))
                .andExpect(jsonPath("$.responses[0].metrics[0].unit").value("ppm"))
                .andExpect(jsonPath("$.responses[0].spaceId").value(lab))
                .andExpect(jsonPath("$.responses[1].deviceId").value(sensor2)).andReturn();
        String cursor = read(first, "$.nextCursor");
        mvc.perform(get("/internal/core/telemetry/history").param("organizationId", Long.toString(org)).param("from", t.minusSeconds(1).toString())
                        .param("to", clock.instant().toString()).param("size", "2").param("cursor", cursor))
                .andExpect(jsonPath("$.responses.length()").value(1)).andExpect(jsonPath("$.responses[0].deviceId").value(sensor3))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        mvc.perform(get("/internal/core/telemetry/history").param("organizationId", Long.toString(org)).param("from", t.toString())
                        .param("to", clock.instant().toString()).param("deviceIds", Long.toString(sensor2)))
                .andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(get("/internal/core/telemetry/history").param("organizationId", Long.toString(org))
                .param("from", clock.instant().minus(Duration.ofDays(8)).toString()).param("to", clock.instant().toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[FLW-03.05][API-FLW-12] 시험 실행: rawMessageId는 core가 표준 텔레메트리로 바꿔 엔진에, 없는 원본은 400 FLOW_TEST_INPUT_INVALID")
    void testRun() throws Exception {
        String flowId = flow("ACTIVE");
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry (device_id, metric_key, time, organization_id, value, quality, is_virtual, received_at,
                               raw_message_id) VALUES (:d, 'co2', :t, :org, 1234, 0, false, :t, 77)""")
                .param("d", sensor1).param("t", java.sql.Timestamp.from(clock.instant())).param("org", org).update();
        STUB.ok("POST", "/internal/flow/test-runs", 200, "{\"trace\":{\"result\":\"COMPLETED\"}}");
        mvc.perform(as(org, operator, json(post("/core/flows/" + flowId + "/test-run"), "{\"input\":{\"rawMessageId\":\"77\"}}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.trace.result").value("COMPLETED"));
        String sent = STUB.received("POST", "/internal/flow/test-runs").getFirst().body();
        assertThat(sent).contains("\"rawMessageId\":77").contains("\"key\":\"co2\"").contains("\"version\":1").contains(flowId);
        mvc.perform(as(org, operator, json(post("/core/flows/" + flowId + "/test-run"), "{\"input\":{\"rawMessageId\":\"78\"}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("FLOW_TEST_INPUT_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value("입력 형식이 올바르지 않습니다"));
        mvc.perform(as(org, operator, json(post("/core/flows/" + flowId + "/test-run"), "{}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, viewer, json(post("/core/flows/" + flowId + "/test-run"), "{\"input\":{\"message\":{}}}")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[FLW-03.06][API-FLW-13] 과거 재생: 202 작업 ID(정의 포함 엔진 요청), 7일 초과 400 FLOW_REPLAY_TOO_LARGE, 다른 조직 플로우 작업은 404, 취소 중계")
    void replay() throws Exception {
        String flowId = flow("ACTIVE");
        STUB.ok("POST", "/internal/flow/replays", 202, "{\"jobId\":\"9\",\"status\":\"QUEUED\"}");
        mvc.perform(as(org, operator, json(post("/core/flows/" + flowId + "/replay"), "{\"version\":1,\"from\":\"" + clock.instant().minus(
                        Duration.ofDays(2)) + "\",\"to\":\"" + clock.instant() + "\",\"deviceIds\":[\"" + sensor1 + "\"]}")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.jobId").value("9"));
        assertThat(STUB.received("POST", "/internal/flow/replays").getFirst().body()).contains("\"definition\"").contains("\"deviceIds\"");
        mvc.perform(as(org, operator, json(post("/core/flows/" + flowId + "/replay"), "{\"from\":\"" + clock.instant().minus(Duration.ofDays(8))
                        + "\",\"to\":\"" + clock.instant() + "\"}"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("FLOW_REPLAY_TOO_LARGE"))
                .andExpect(jsonPath("$.header.resultMessage").value("기간을 줄여 주세요"));
        STUB.ok("GET", "/internal/flow/replays/9", 200, "{\"jobId\":\"9\",\"flowId\":\"" + flowId + "\",\"status\":\"RUNNING\"}");
        mvc.perform(as(org, analyst, get("/core/flow-replays/9"))).andExpect(jsonPath("$.response.status").value("RUNNING"));
        STUB.ok("POST", "/internal/flow/replays/9/cancel", 200, "{\"jobId\":\"9\",\"status\":\"CANCELLED\"}");
        mvc.perform(as(org, operator, post("/core/flow-replays/9/cancel"))).andExpect(jsonPath("$.response.status").value("CANCELLED"));
        long other = fx.organization("other");
        mvc.perform(as(other, fx.user(other, "o.admin", "ADMIN"), get("/core/flow-replays/9"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[FLW-04.03][API-FLW-41] 실행 추적 중계: 공간 범위가 제한된 사용자에게는 범위 밖 spaceId 입력·출력을 지우고 masked=true(ADR-048)")
    void traceMasking() throws Exception {
        String flowId = flow("ACTIVE");
        STUB.ok("GET", "/internal/flow/traces/m1", 200, """
                {"messageId":"m1","flowId":"%s","steps":[{"nodeId":"a","input":{"spaceId":"%d","co2":1},"outputs":[{"port":"out","payload":{"spaceId":%d}}]},
                 {"nodeId":"b","input":{"spaceId":"%d"},"outputs":[]}],"result":"COMPLETED"}""".formatted(flowId, lab, lab, classroom));
        mvc.perform(as(org, analyst, get("/core/flows/" + flowId + "/traces/m1"))).andExpect(jsonPath("$.response.steps[0].input.co2").value(1));
        assertThat(STUB.received("GET", "/internal/flow/traces/m1").getFirst().query()).isEqualTo("flowId=" + flowId);
        // 실습실·강의실을 잇는 플로우를 강의실만 볼 수 있는 사용자가 본다
        jdbc.sql("UPDATE data2flow_core.flows SET related_space_ids = CAST(:s AS bigint[]) WHERE id = CAST(:id AS uuid)")
                .param("s", "{" + lab + "," + classroom + "}").param("id", flowId).update();
        data.spaceScope(org, analyst, List.of(classroom));
        mvc.perform(as(org, analyst, get("/core/flows/" + flowId + "/traces/m1")))
                .andExpect(jsonPath("$.response.steps[0].masked").value(true))
                .andExpect(jsonPath("$.response.steps[0].input").doesNotExist())
                .andExpect(jsonPath("$.response.steps[0].outputs[0].masked").value(true))
                .andExpect(jsonPath("$.response.steps[1].input.spaceId").value(Long.toString(classroom)));
    }

    @Test
    @DisplayName("[FLW-05.04][EVT-FLW-03] 엔진이 폭주로 멈추면 PAUSED 저장 + MAJOR 알람, 복구되면 알람 해제. API-FLW-80에 오류율 기준(비율)·자동 정지·catch")
    void engineState() throws Exception {
        String flowId = flow("ACTIVE");
        deliver(EventType.FLOW_STATE_CHANGED, org, new FlowStateChanged(flowId, "ACTIVE", "PAUSED", FlowStateChanged.Reason.RUNAWAY,
                new FlowStateChanged.Metrics(0.0, 1500), clock.instant()), clock.instant());
        assertThat(jdbc.sql("SELECT status FROM data2flow_core.flows WHERE id = CAST(:id AS uuid)").param("id", flowId).query(String.class)
                .single()).isEqualTo("PAUSED");
        long alarm = jdbc.sql("SELECT id FROM data2flow_core.alarms WHERE organization_id = :org AND alarm_key = :k").param("org", org)
                .param("k", "system:FLOW_STATE:" + flowId).query(Long.class).single();
        mvc.perform(as(org, operator, get("/core/alarms/" + alarm))).andExpect(jsonPath("$.response.alarm.severity").value("MAJOR"))
                .andExpect(jsonPath("$.response.alarm.title").value("플로우 자동 정지: 엔진 시험 (RUNAWAY)"));
        mvc.perform(get("/internal/core/flows/runtime").param("organizationId", Long.toString(org)))
                .andExpect(jsonPath("$.response.flows[0].errorRateThreshold").value(0.25))
                .andExpect(jsonPath("$.response.flows[0].autoPauseOnDegraded").value(true));
        deliver(EventType.FLOW_STATE_CHANGED, org, new FlowStateChanged(flowId, "PAUSED", "ACTIVE", FlowStateChanged.Reason.RECOVERED, null,
                clock.instant().plusSeconds(60)), clock.instant().plusSeconds(60));
        assertThat(alarmStatus(alarm)).isEqualTo("CLEARED");
    }

    @Test
    @DisplayName("[DEV-02.03][API-DEV-122] 처리용 기기 정보에 태그(트리거 태그 대상, ADR-051)")
    void deviceTags() throws Exception {
        jdbc.sql("INSERT INTO data2flow_core.device_tags (organization_id, device_id, tag) VALUES (:org, :d, '3층'), (:org, :d, 'hvac')")
                .param("org", org).param("d", sensor1).update();
        mvc.perform(get("/internal/core/devices/" + sensor1 + "/runtime")).andExpect(jsonPath("$.response.tags.length()").value(2));
    }
}
