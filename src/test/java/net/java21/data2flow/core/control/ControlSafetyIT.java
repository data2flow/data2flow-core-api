package net.java21.data2flow.core.control;

import net.java21.data2flow.contracts.message.ActionRequest;
import net.java21.data2flow.core.alarm.AlarmItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControlSafetyIT extends AlarmItSupport {

    String interlock(String name) {
        return """
                {"name":"%s","spaceId":"%d","condition":{"kind":"metric","metric":"co2","deviceId":"%d","op":">","value":1500,"staleAfterSec":600},
                 "forbid":{"capability":"window","argsMatch":{"state":"OPEN"}},"message":"CO2가 높아 창문을 열 수 없습니다"}"""
                .formatted(name, lab, sensor1);
    }

    @Test
    @DisplayName("[ACT-04.01][TC-ACT-104] 인터락 CRUD(INTERLOCK_MANAGE), 설정 변경 INTERLOCK, 내부 API-ACT-44는 기기 공간 위 인터락, 조건 오류 400 INTERLOCK_INVALID")
    void interlocks() throws Exception {
        MvcResult r = mvc.perform(as(org, admin, json(post("/core/interlocks"), interlock("창문 잠금")))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.spaceName").value("실습실")).andReturn();
        String id = read(r, "$.response.interlockId");
        assertThat(configMessages(org)).anyMatch(m -> m.contains("\"INTERLOCK\""));
        mvc.perform(get("/internal/core/devices/" + sensor1 + "/interlocks")).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].forbid.capability").value("window"))
                .andExpect(jsonPath("$.responses[0].staleAfterSec").value(600));
        mvc.perform(get("/internal/core/devices/" + sensor3 + "/interlocks")).andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(as(org, operator, json(post("/core/interlocks"), interlock("x")))).andExpect(status().isForbidden());
        mvc.perform(as(org, admin, json(post("/core/interlocks"), interlock("y").replace("\"kind\":\"metric\"", "\"kind\":\"weather\""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INTERLOCK_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value("인터락 설정이 올바르지 않습니다"));
        mvc.perform(as(org, admin, json(put("/core/interlocks/" + id), interlock("창문 잠금").replace("\"message\"", "\"enabled\":false,\"message\""))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.enabled").value(false));
        mvc.perform(get("/internal/core/devices/" + sensor1 + "/interlocks")).andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(as(org, viewer, get("/core/interlocks"))).andExpect(status().isForbidden());
        mvc.perform(as(org, admin, get("/core/interlocks"))).andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.page").value(1));
        STUB.ok("GET", "/internal/action/interlocks/" + id + "/blocks", 200, "{\"count\":2}");
        mvc.perform(as(org, admin, get("/core/interlocks/" + id + "/blocks"))).andExpect(jsonPath("$.response.count").value(2));
        mvc.perform(as(org, admin, delete("/core/interlocks/" + id))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get("/core/interlocks/" + id))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[ACT-04.01][TC-ACT-105] 내부 API-ACT-45 측정값: 기기 마지막 값·공간 평균, 없으면 404, 둘 다 없으면 400")
    void metricValues() throws Exception {
        data.telemetry(org, sensor1, "co2", clock.instant().minusSeconds(60), 1200, 0, false);
        data.telemetry(org, sensor2, "co2", clock.instant().minusSeconds(30), 800, 0, false);
        mvc.perform(get("/internal/core/metric-values").param("metric", "co2").param("deviceId", Long.toString(sensor1)))
                .andExpect(jsonPath("$.response.value").value(1200.0));
        mvc.perform(get("/internal/core/metric-values").param("metric", "co2").param("spaceId", Long.toString(lab)))
                .andExpect(jsonPath("$.response.value").value(1000.0));
        mvc.perform(get("/internal/core/metric-values").param("metric", "co2").param("deviceId", Long.toString(sensor3)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/core/metric-values").param("metric", "co2")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[ACT-04.02][TC-ACT-110][TC-ACT-112] 비상 정지: OPERATOR 시작 201·EVT-ACT 시작·설정 변경, 같은 범위 다시 409 EMERGENCY_STOP_ACTIVE, 해제는 EMERGENCY_RELEASE, 내부 API-ACT-46")
    void emergencyStops() throws Exception {
        MvcResult r = mvc.perform(as(org, operator, json(post("/core/emergency-stops"), "{\"scope\":{\"type\":\"ORG\"},\"reason\":\"화재 경보\"}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.reason").value("화재 경보")).andExpect(jsonPath("$.response.active").value(true))
                .andExpect(jsonPath("$.response.startedBy.userId").value(Long.toString(operator))).andReturn();
        String id = read(r, "$.response.emergencyStopId");
        assertThat(events(org, "control.emergency.started")).hasSize(1);
        assertThat(configMessages(org)).anyMatch(m -> m.contains("\"EMERGENCY_STOP\""));
        mvc.perform(as(org, operator, json(post("/core/emergency-stops"), "{\"scope\":{\"type\":\"ORG\"},\"reason\":\"또\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("EMERGENCY_STOP_ACTIVE"))
                .andExpect(jsonPath("$.header.resultMessage").value("이미 비상 정지 중입니다"));
        mvc.perform(as(org, viewer, get("/core/emergency-stops").param("active", "true"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(get("/internal/core/emergency-stops").param("organizationId", Long.toString(org))).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].organizationId").value(org));
        mvc.perform(as(org, operator, json(post("/core/emergency-stops/" + id + "/release"), "{\"note\":\"해제\"}"))).andExpect(status().isForbidden());
        mvc.perform(as(org, admin, json(post("/core/emergency-stops/" + id + "/release"), "{\"note\":\"진화 완료\"}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.releaseNote").value("진화 완료"));
        mvc.perform(as(org, admin, json(post("/core/emergency-stops/" + id + "/release"), "{}"))).andExpect(status().isConflict());
        assertThat(events(org, "control.emergency.released")).hasSize(1);
        assertThat(auditCount(org, "EMERGENCY_STOP_RELEASED")).isEqualTo(1);
        mvc.perform(as(org, operator, json(post("/core/emergency-stops"), "{\"scope\":{\"type\":\"SPACE\",\"spaceId\":\"" + lab
                + "\"},\"reason\":\"실습실\"}"))).andExpect(status().isCreated());
        mvc.perform(as(org, operator, json(post("/core/emergency-stops"), "{\"scope\":{\"type\":\"CITY\"},\"reason\":\"x\"}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[ACT-05.01][TC-ACT-091] 장면 CRUD·실행(action 202 중계)·미리 보기, 항목 101개 400 SCENE_ITEM_LIMIT_EXCEEDED, 내부 API-ACT-47")
    void scenes() throws Exception {
        String scene = """
                {"name":"수업 시작","spaceId":"%d","items":[{"target":{"deviceId":"%d"},"capability":"switch","desired":{"state":"ON"}},
                 {"target":{"spaceId":"%d"},"capability":"thermostat","desired":{"setpoint":24}}]}""".formatted(lab, sensor1, lab);
        MvcResult r = mvc.perform(as(org, admin, json(post("/core/scenes"), scene))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.itemCount").value(2)).andReturn();
        String id = read(r, "$.response.sceneId");
        mvc.perform(get("/internal/core/scenes/" + id)).andExpect(jsonPath("$.response.organizationId").value(org))
                .andExpect(jsonPath("$.response.items[1].target.relation").value("controls"))
                .andExpect(jsonPath("$.response.items[0].desired.state").value("ON"));
        mvc.perform(get("/internal/core/scenes/999999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SCENE_NOT_FOUND"));
        STUB.ok("POST", "/internal/action/scenes/" + id + "/run", 202, "{\"sceneRunId\":\"r1\"}");
        mvc.perform(as(org, operator, post("/core/scenes/" + id + "/run").header("Idempotency-Key", "k1"))).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.response.sceneRunId").value("r1"));
        assertThat(STUB.received("POST", "/internal/action/scenes/" + id + "/run").getFirst().body()).contains("MANUAL");
        STUB.ok("POST", "/internal/action/scenes/" + id + "/preview", 200, "{\"items\":[]}");
        mvc.perform(as(org, viewer, post("/core/scenes/" + id + "/preview"))).andExpect(status().isOk());
        mvc.perform(as(org, viewer, post("/core/scenes/" + id + "/run"))).andExpect(status().isForbidden());
        StringJoiner many = new StringJoiner(",");
        for (int i = 0; i < 101; i++) {
            many.add("{\"target\":{\"deviceId\":\"" + sensor1 + "\"},\"capability\":\"switch\",\"desired\":{\"state\":\"ON\"}}");
        }
        mvc.perform(as(org, admin, json(post("/core/scenes"), "{\"name\":\"많음\",\"items\":[" + many + "]}"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("SCENE_ITEM_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.header.resultMessage").value("장면 항목은 100개까지입니다"));
        mvc.perform(as(org, admin, json(put("/core/scenes/" + id), scene.replace("수업 시작", "수업 시작 2")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.name").value("수업 시작 2"));
        mvc.perform(as(org, viewer, get("/core/scenes"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, admin, delete("/core/scenes/" + id))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get("/core/scenes/" + id))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[ACT-02.06][TC-ACT-057][TC-ACT-059] 일괄 제어 action 중계(202·멱등 키 전달), 501대 400 COMMAND_BULK_LIMIT_EXCEEDED, 다른 조직 기기 404")
    void bulk() throws Exception {
        STUB.ok("POST", "/internal/action/bulk", 202, "{\"bulkJobId\":\"b1\",\"accepted\":2}");
        mvc.perform(as(org, operator, json(post("/core/commands/bulk"), "{\"target\":{\"deviceIds\":[\"" + sensor1 + "\",\"" + sensor2
                        + "\"]},\"capability\":\"switch\",\"command\":\"setState\",\"args\":{\"state\":\"OFF\"}}").header("Idempotency-Key", "bk")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.bulkJobId").value("b1"));
        StringJoiner many = new StringJoiner(",");
        for (int i = 0; i < 501; i++) {
            many.add("\"" + (i + 1) + "\"");
        }
        mvc.perform(as(org, operator, json(post("/core/commands/bulk"), "{\"target\":{\"deviceIds\":[" + many + "]},\"capability\":\"switch\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("COMMAND_BULK_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.header.resultMessage").value("한 번에 500대까지 제어할 수 있습니다"));
        mvc.perform(as(org, operator, json(post("/core/commands/bulk"), "{\"target\":{\"deviceIds\":[\"999999\"]},\"capability\":\"switch\"}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, viewer, json(post("/core/commands/bulk"), "{\"target\":{\"deviceIds\":[]}}"))).andExpect(status().isForbidden());
        STUB.ok("GET", "/internal/action/bulk-jobs/b1", 200, "{\"status\":\"DONE\"}");
        mvc.perform(as(org, viewer, get("/core/command-bulk-jobs/b1"))).andExpect(jsonPath("$.response.status").value("DONE"));
        STUB.ok("GET", "/internal/action/devices/" + sensor1 + "/runtime", 200, "{\"onSec\":3600}");
        mvc.perform(as(org, viewer, get("/core/devices/" + sensor1 + "/runtime"))).andExpect(jsonPath("$.response.onSec").value(3600));
    }

    @Test
    @DisplayName("[ACT-02.07][TC-ACT-063][TC-ACT-060] 예약: 5필드 cron 다음 실행 시각(서울), 때가 되면 실행기가 SCHEDULE 출처 행동 요청을 한 번만, 잘못된 cron 400 SCHEDULE_INVALID")
    void schedules() throws Exception {
        // T0 = 2026-10-03(토) 09:00 서울. 매일 09:05
        MvcResult r = mvc.perform(as(org, admin, json(post("/core/control-schedules"), """
                        {"name":"아침 환기","target":{"deviceId":"%d","capability":"switch","command":"setState","args":{"state":"ON"}},
                         "kind":"RECURRING","cron":"5 9 * * *","skipHolidays":true}""".formatted(sensor1))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.nextRunAt").value("2026-10-03T00:05:00Z"))
                .andExpect(jsonPath("$.response.timezone").value("Asia/Seoul")).andReturn();
        String id = read(r, "$.response.controlScheduleId");
        mvc.perform(as(org, admin, json(post("/core/control-schedules"), """
                        {"name":"틀림","target":{"deviceId":"%d","capability":"switch","command":"setState"},"kind":"RECURRING","cron":"* * *"}"""
                        .formatted(sensor1)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("SCHEDULE_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value("일정이 올바르지 않습니다"));
        assertThat(jobs.minute()).isTrue();
        assertThat(commandRequests()).isEmpty();
        clock.advance(Duration.ofMinutes(6));
        jobs.minute();
        jobs.minute();
        List<String> sent = commandRequests();
        assertThat(sent).hasSize(1);
        ActionRequest req = codec.read(sent.getFirst().getBytes(StandardCharsets.UTF_8), ActionRequest.class);
        assertThat(req.source().type().name()).isEqualTo("SCHEDULE");
        assertThat(req.organizationId()).isEqualTo(org);
        mvc.perform(as(org, admin, get("/core/control-schedules"))).andExpect(jsonPath("$.responses[0].nextRunAt").value("2026-10-04T00:05:00Z"))
                .andExpect(jsonPath("$.responses[0].lastRun.status").value("SENT"))
                .andExpect(jsonPath("$.responses[0].lastRun.holidayCheck").value("WORKDAY"));
        mvc.perform(as(org, admin, post("/core/control-schedules/" + id + "/disable"))).andExpect(jsonPath("$.response.enabled").value(false));
        clock.advance(Duration.ofDays(1));
        jobs.minute();
        assertThat(commandRequests()).hasSize(1);
        MvcResult scene = mvc.perform(as(org, admin, json(post("/core/scenes"), """
                {"name":"퇴근","items":[{"target":{"deviceId":"%d"},"capability":"switch","desired":{"state":"OFF"}}]}""".formatted(sensor1))))
                .andReturn();
        mvc.perform(as(org, admin, json(post("/core/control-schedules"), """
                        {"name":"한 번","target":{"sceneId":"%s"},"kind":"ONCE","at":"%s"}""".formatted(read(scene, "$.response.sceneId"),
                        clock.instant().plusSeconds(30))))).andExpect(status().isCreated());
        clock.advance(Duration.ofMinutes(1));
        jobs.minute();
        assertThat(commandRequests()).hasSize(2).last().asString().contains("\"SCENE\"");
        mvc.perform(as(org, operator, json(post("/core/control-schedules"), "{}"))).andExpect(status().isForbidden());
        mvc.perform(as(org, admin, delete("/core/control-schedules/" + id))).andExpect(status().isNoContent());
    }

    List<String> commandRequests() {
        return jdbc.sql("SELECT payload::text FROM data2flow_core.outboxes WHERE organization_id = :org AND kind = 'COMMAND' ORDER BY id")
                .param("org", org).query(String.class).list();
    }
}
