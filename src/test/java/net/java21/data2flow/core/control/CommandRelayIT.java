package net.java21.data2flow.core.control;

import net.java21.data2flow.contracts.command.CommandPriority;
import net.java21.data2flow.contracts.command.CommandSource;
import net.java21.data2flow.contracts.command.CommandStatus;
import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.CommandStatusChanged;
import net.java21.data2flow.contracts.message.event.DeviceStateChanged;
import net.java21.data2flow.core.live.service.LiveHub;
import net.java21.data2flow.core.support.LoopItSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ACT-02.01·04.02·04.03, TSD-01.03: 수동 제어 중계(API-ACT-01 → action 내부 API), 명령 조회·취소·컨트롤 정보·섀도·수동 우선 해제,
 * 명령 이력 커서 목록(action 스키마 읽기, 출처 표시 이름), 액추에이터 상태 구간(API-TSD-05), 실시간 command-status·device-update.
 */
class CommandRelayIT extends LoopItSupport {

    private static final String COMMAND_ID = "6f1c2c1e-0f8b-4f8e-9a52-1b2c3d4e5f60";

    @Autowired
    LiveHub hub;

    private long room;
    private long aircon;
    private long otherOrgDevice;

    @BeforeEach
    void setUp() {
        hub.closeAll();
        long site = data.site(org, "캠퍼스");
        room = data.space(org, site, "ROOM", "실습실");
        long source = data.source(org, "lns");
        aircon = data.device(org, source, "ac-1", "ACTIVE", room, null);
        long other = fx.organization("other");
        long otherSource = data.source(other, "lns2");
        otherOrgDevice = data.device(other, otherSource, "x-1", "ACTIVE", null, null);
    }

    @AfterEach
    void tearDown() {
        hub.closeAll();
    }

    private String command(String status) {
        return """
                {"id":"%s","status":"%s","deviceId":"%d","capability":"Thermostat","command":"set","args":{"mode":"cool"},
                 "priority":"MANUAL","source":{"type":"USER","userId":"%d"},"timeline":[{"status":"REQUESTED","at":"2026-10-03T00:00:00Z"}]}"""
                .formatted(COMMAND_ID, status, aircon, operator);
    }

    @Test
    @DisplayName("[ACT-02.01][AT-ACT-01.4][AT-ACT-01.5] 명령은 권한·공간 범위를 본 뒤 action으로 넘기고(priority MANUAL·source USER), 202·200을 그대로, 같은 키 재요청은 첫 응답 — TC-ACT-016·028·031")
    void sendCommand() throws Exception {
        STUB.ok("POST", "/internal/action/commands", 202, command("QUEUED"));
        String body = "{\"capability\":\"Thermostat\",\"command\":\"set\",\"args\":{\"mode\":\"cool\"},\"wait\":\"none\"}";
        mvc.perform(as(org, operator, json(post("/core/devices/" + aircon + "/commands"), body).header("Idempotency-Key", "k-1")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.id").value(COMMAND_ID))
                .andExpect(jsonPath("$.response.status").value("QUEUED"));
        var sent = STUB.received("POST", "/internal/action/commands");
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().body()).contains("\"priority\":\"MANUAL\"").contains("\"type\":\"USER\"")
                .contains("\"idempotencyKey\":\"k-1\"");
        assertThat(sent.getFirst().header("Idempotency-Key")).isEqualTo("k-1");
        assertThat(sent.getFirst().header("X-ORG-ID")).isEqualTo(Long.toString(org));
        // 같은 키·같은 본문: 컨트롤러를 다시 부르지 않고 첫 응답
        mvc.perform(as(org, operator, json(post("/core/devices/" + aircon + "/commands"), body).header("Idempotency-Key", "k-1")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.id").value(COMMAND_ID));
        assertThat(STUB.received("POST", "/internal/action/commands")).hasSize(1);
        mvc.perform(as(org, operator, json(post("/core/devices/" + aircon + "/commands"), body.replace("cool", "heat"))
                        .header("Idempotency-Key", "k-1")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(as(org, operator, json(post("/core/devices/" + aircon + "/commands"), body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        // wait=applied → action 200
        STUB.ok("POST", "/internal/action/commands", 200, command("APPLIED"));
        mvc.perform(as(org, operator, json(post("/core/devices/" + aircon + "/commands"), body.replace("none", "applied"))
                        .header("Idempotency-Key", "k-2")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("APPLIED"));
        // 권한: ANALYST 403(감사 ACCESS_DENIED), 다른 조직 기기 404
        mvc.perform(as(org, analyst, json(post("/core/devices/" + aircon + "/commands"), body).header("Idempotency-Key", "k-3")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        assertThat(auditCount(org, "ACCESS_DENIED")).isGreaterThanOrEqualTo(1);
        mvc.perform(as(org, operator, json(post("/core/devices/" + otherOrgDevice + "/commands"), body).header("Idempotency-Key", "k-4")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_NOT_FOUND"));
        mvc.perform(as(org, operator, json(post("/core/devices/" + aircon + "/commands"), "{\"capability\":\"Thermostat\",\"args\":{}}")
                        .header("Idempotency-Key", "k-5")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("command"));
        mvc.perform(as(org, operator, json(post("/core/devices/" + aircon + "/commands"),
                        "{\"capability\":\"Thermostat\",\"command\":\"set\",\"args\":{},\"wait\":\"never\"}").header("Idempotency-Key", "k-6")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("wait"));
        mvc.perform(as(org, operator, json(post("/core/devices/" + aircon + "/commands"),
                        "{\"capability\":\"Thermostat\",\"command\":\"set\",\"args\":{},\"validitySeconds\":5}").header("Idempotency-Key", "k-7")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("validitySeconds"));
        assertThat(auditCount(org, "DEVICE_COMMAND_REQUESTED")).isEqualTo(2);
    }

    @Test
    @DisplayName("[ACT-06.04][ACT-01.03][AT-ACT-01.3] action이 거부한 명령(400 COMMAND_ABSOLUTE_LIMIT + response.commandId)·차단(409)은 그대로 전하고 감사에 남긴다 — TC-ACT-114")
    void rejectedCommandIsRelayed() throws Exception {
        STUB.fail("POST", "/internal/action/commands", 400, "COMMAND_ABSOLUTE_LIMIT",
                "\"response\":{\"commandId\":\"" + COMMAND_ID + "\",\"status\":\"REJECTED\"},"
                        + "\"errors\":[{\"field\":\"args.targetTemperature\",\"code\":\"ABSOLUTE_LIMIT\",\"message\":\"18~28\"}]");
        mvc.perform(as(org, operator, json(post("/core/devices/" + aircon + "/commands"),
                        "{\"capability\":\"Thermostat\",\"command\":\"set\",\"args\":{\"targetTemperature\":29}}").header("Idempotency-Key", "r-1")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("COMMAND_ABSOLUTE_LIMIT"))
                .andExpect(jsonPath("$.response.commandId").value(COMMAND_ID))
                .andExpect(jsonPath("$.errors[0].field").value("args.targetTemperature"));
        assertThat(jdbc.sql("SELECT detail::text FROM data2flow_core.audit_logs WHERE organization_id = :org AND action = 'DEVICE_COMMAND_REQUESTED'")
                .param("org", org).query(String.class).single()).contains("COMMAND_ABSOLUTE_LIMIT").contains(COMMAND_ID);
        STUB.on("POST", "/internal/action/commands", r -> new net.java21.data2flow.core.support.StubHttpServer.Reply(500, "", Map.of()));
        mvc.perform(as(org, operator, json(post("/core/devices/" + aircon + "/commands"),
                        "{\"capability\":\"Thermostat\",\"command\":\"set\",\"args\":{\"mode\":\"cool\"}}").header("Idempotency-Key", "r-2")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.header.resultCode").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("[ACT-04.03][ACT-02.04] 명령 조회·취소(남의 기기 명령은 404 COMMAND_NOT_FOUND), 컨트롤 정보·섀도 중계, 수동 우선 해제 204 — TC-ACT-088·089")
    void commandAndDeviceReads() throws Exception {
        STUB.ok("GET", "/internal/action/commands/" + COMMAND_ID, 200, command("QUEUED"));
        mvc.perform(as(org, viewer, get("/core/commands/" + COMMAND_ID)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("QUEUED"));
        mvc.perform(as(org, viewer, get("/core/commands/not-a-uuid")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("COMMAND_NOT_FOUND"));
        mvc.perform(as(org, viewer, get("/core/commands/" + UUID.randomUUID())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("COMMAND_NOT_FOUND"));
        mvc.perform(as(org, viewer, post("/core/commands/" + COMMAND_ID + "/cancel"))).andExpect(status().isForbidden());
        STUB.fail("POST", "/internal/action/commands/" + COMMAND_ID + "/cancel", 409, "COMMAND_NOT_CANCELLABLE", null);
        mvc.perform(as(org, operator, post("/core/commands/" + COMMAND_ID + "/cancel")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("COMMAND_NOT_CANCELLABLE"));
        STUB.ok("POST", "/internal/action/commands/" + COMMAND_ID + "/cancel", 200, command("CANCELLED"));
        mvc.perform(as(org, operator, post("/core/commands/" + COMMAND_ID + "/cancel")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("CANCELLED"));
        assertThat(auditCount(org, "DEVICE_COMMAND_CANCELLED")).isEqualTo(1);
        // 다른 조직 기기의 명령
        STUB.ok("GET", "/internal/action/commands/" + COMMAND_ID, 200, command("QUEUED").replace("\"deviceId\":\"" + aircon + "\"",
                "\"deviceId\":\"" + otherOrgDevice + "\""));
        mvc.perform(as(org, viewer, get("/core/commands/" + COMMAND_ID))).andExpect(status().isNotFound());

        STUB.ok("GET", "/internal/action/devices/" + aircon + "/control", 200, "{\"controllable\":true,\"capabilities\":[]}");
        mvc.perform(as(org, viewer, get("/core/devices/" + aircon + "/control")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.controllable").value(true));
        STUB.ok("GET", "/internal/action/devices/" + aircon + "/shadow", 200, "{\"desired\":{},\"reported\":{},\"connectivity\":\"ONLINE\"}");
        mvc.perform(as(org, viewer, get("/core/devices/" + aircon + "/shadow")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.connectivity").value("ONLINE"));
        mvc.perform(as(org, viewer, get("/core/devices/" + otherOrgDevice + "/shadow"))).andExpect(status().isNotFound());
        STUB.on("DELETE", "/internal/action/devices/" + aircon + "/manual-override",
                r -> new net.java21.data2flow.core.support.StubHttpServer.Reply(204, "", Map.of()));
        mvc.perform(as(org, operator, delete("/core/devices/" + aircon + "/manual-override").param("capability", "Thermostat")))
                .andExpect(status().isNoContent());
        assertThat(STUB.received("DELETE", ".*/manual-override").getFirst().query()).isEqualTo("capability=Thermostat");
        mvc.perform(as(org, viewer, delete("/core/devices/" + aircon + "/manual-override"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[ACT-04.03][AT-ACT-14.1] 명령 이력 커서 목록: 출처 플로우 이름·버전·노드, 사용자 이름, 타임라인, 필터·공간 범위 — TC-ACT-086")
    void commandHistory() throws Exception {
        UUID flowId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO data2flow_core.flows (id, organization_id, name, status, created_by, updated_by)
                        VALUES (:id, :org, '고온이면 냉방', 'ACTIVE', :u, :u)""")
                .param("id", flowId).param("org", org).param("u", admin).update();
        Instant t = Instant.parse("2026-10-03T00:00:00Z");
        for (int i = 0; i < 3; i++) {
            UUID id = UUID.randomUUID();
            String source = i == 0 ? "{\"type\":\"FLOW\",\"flowId\":\"" + flowId + "\",\"flowVersion\":13,\"nodeId\":\"n-act-1\"}"
                    : "{\"type\":\"USER\",\"userId\":\"" + operator + "\"}";
            jdbc.sql("""
                            INSERT INTO data2flow_action.commands (id, organization_id, idempotency_key, device_id, capability, command, args,
                                   priority, source, status, valid_until, requested_at)
                            VALUES (:id, :org, :key, :device, 'Thermostat', 'set', '{"mode":"cool"}', :priority, CAST(:source AS jsonb), :status,
                                    :until, :at)""")
                    .param("id", id).param("org", org).param("key", "k" + i).param("device", aircon)
                    .param("priority", i == 0 ? "AUTO" : "MANUAL").param("source", source).param("status", i == 2 ? "REJECTED" : "APPLIED")
                    .param("until", java.sql.Timestamp.from(t.plusSeconds(600))).param("at", java.sql.Timestamp.from(t.plusSeconds(i)))
                    .update();
            jdbc.sql("INSERT INTO data2flow_action.command_events (command_id, organization_id, at, to_status) VALUES (:id, :org, :at, 'REQUESTED')")
                    .param("id", id).param("org", org).param("at", java.sql.Timestamp.from(t.plusSeconds(i))).update();
        }
        MvcResult all = mvc.perform(as(org, viewer, get("/core/commands").param("size", "2")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses.length()").value(2))
                .andExpect(jsonPath("$.responses[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.responses[0].deviceName").value("기기 ac-1"))
                .andExpect(jsonPath("$.responses[0].source.userName").value("이름 loop.operator"))
                .andExpect(jsonPath("$.responses[0].timeline[0].status").value("REQUESTED")).andReturn();
        String cursor = read(all, "$.nextCursor");
        mvc.perform(as(org, viewer, get("/core/commands").param("size", "2").param("cursor", cursor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses.length()").value(1))
                .andExpect(jsonPath("$.responses[0].source.flowName").value("고온이면 냉방"))
                .andExpect(jsonPath("$.responses[0].source.flowVersion").value(13))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        // 기기별 이력은 action 내부 API로 넘기고 출처 표시 이름을 붙인다(ADR-043)
        STUB.on("GET", "/internal/action/devices/" + aircon + "/commands", r -> new net.java21.data2flow.core.support.StubHttpServer.Reply(200,
                "{\"header\":{\"isSuccessful\":true,\"resultCode\":\"SUCCESS\",\"resultMessage\":\"SUCCESS\"},\"size\":50,\"responses\":["
                        + "{\"id\":\"" + COMMAND_ID + "\",\"deviceId\":\"" + aircon + "\",\"status\":\"APPLIED\",\"source\":{\"type\":\"FLOW\",\"flowId\":\""
                        + flowId + "\",\"flowVersion\":13,\"nodeId\":\"n-act-1\"}},"
                        + "{\"id\":\"" + UUID.randomUUID() + "\",\"deviceId\":\"" + aircon + "\",\"status\":\"APPLIED\",\"source\":{\"type\":\"USER\",\"userId\":\""
                        + operator + "\"}}],\"nextCursor\":\"c2\"}", Map.of()));
        mvc.perform(as(org, viewer, get("/core/devices/" + aircon + "/commands").param("size", "2").param("status", "applied")
                        .param("cursor", "c1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses[0].source.flowName").value("고온이면 냉방"))
                .andExpect(jsonPath("$.responses[0].deviceName").value("기기 ac-1"))
                .andExpect(jsonPath("$.responses[1].source.userName").value("이름 loop.operator"))
                .andExpect(jsonPath("$.nextCursor").value("c2"));
        assertThat(STUB.received("GET", ".*/devices/" + aircon + "/commands").getFirst().query())
                .contains("status=APPLIED").contains("cursor=c1").contains("size=2");
        mvc.perform(as(org, viewer, get("/core/commands").param("spaceId", Long.toString(room)).param("sourceType", "flow")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses.length()").value(1));
        mvc.perform(as(org, viewer, get("/core/commands").param("status", "REJECTED")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses.length()").value(1));
        mvc.perform(as(org, viewer, get("/core/commands").param("status", "NOPE"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, viewer, get("/core/commands").param("cursor", "%%%"))).andExpect(status().isBadRequest());
        // 공간 범위: 다른 공간만 볼 수 있으면 0건
        long otherRoom = data.space(org, null, "SITE", "별관");
        data.spaceScope(org, viewer, List.of(otherRoom));
        mvc.perform(as(org, viewer, get("/core/commands")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses.length()").value(0));
        mvc.perform(as(org, viewer, get("/core/devices/" + aircon + "/commands"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[TSD-01.03] 액추에이터 상태 구간(actuator=true): 속성 값·라벨·출처 {type,id}, 조회 끝까지 자름 — TC-TSD-012")
    void actuatorStateIntervals() throws Exception {
        Instant t = clock.instant().minus(Duration.ofHours(2));
        jdbc.sql("""
                        INSERT INTO data2flow_action.device_state_history (organization_id, device_id, capability, attribute, value, valid_from,
                               valid_to, source)
                        VALUES (:org, :device, 'Thermostat', 'mode', '"cool"', :from, :to, '{"type":"FLOW","flowId":"f-1"}'),
                               (:org, :device, 'Switch', 'on', 'true', :to, NULL, NULL)""")
                .param("org", org).param("device", aircon).param("from", java.sql.Timestamp.from(t))
                .param("to", java.sql.Timestamp.from(t.plusSeconds(1800))).update();
        mvc.perform(as(org, viewer, get("/core/telemetry/state-intervals").param("deviceId", Long.toString(aircon)).param("actuator", "true")
                        .param("from", t.minusSeconds(60).toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.length()").value(2))
                .andExpect(jsonPath("$.response[0].value").value("cool"))
                .andExpect(jsonPath("$.response[0].label").value("Thermostat.mode=cool"))
                .andExpect(jsonPath("$.response[0].source.type").value("FLOW"))
                .andExpect(jsonPath("$.response[0].source.id").value("f-1"))
                .andExpect(jsonPath("$.response[1].value").value(true))
                .andExpect(jsonPath("$.response[1].to").value(clock.instant().toString()));
    }

    @Test
    @DisplayName("[ACT-04.02][ACT-02.04] 실시간: commands:{deviceId} → command-status, space:{id} → device-update(액추에이터 보고 상태). 다른 기기·권한 밖은 보내지 않음")
    void liveCommandStatus() throws Exception {
        MvcResult stream = mvc.perform(as(org, viewer, get("/core/stream/live")
                        .param("topics", "commands:" + aircon + ",space:" + room).accept(MediaType.TEXT_EVENT_STREAM)))
                .andExpect(request().asyncStarted()).andReturn();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(body(stream)).contains("event:ready"));
        assertThat(body(stream)).doesNotContain("rejected\":[\"commands");
        CommandStatusChanged status = new CommandStatusChanged(UUID.fromString(COMMAND_ID), "k", aircon, room, "Thermostat", "set",
                Map.of("mode", "cool"), CommandStatus.FAILED, "TIMEOUT", "응답 없음", CommandSource.user(operator), CommandPriority.MANUAL,
                clock.instant());
        hub.onEvent(new DomainEvent<>(1, UUID.randomUUID(), EventType.COMMAND_STATUS_FAILED.routingKey(), org, clock.instant(), null, status));
        CommandStatusChanged other = new CommandStatusChanged(UUID.randomUUID(), "k2", aircon + 1000, room, "Thermostat", "set", Map.of(),
                CommandStatus.APPLIED, null, null, CommandSource.user(operator), CommandPriority.MANUAL, clock.instant());
        hub.onEvent(new DomainEvent<>(1, UUID.randomUUID(), EventType.COMMAND_STATUS_APPLIED.routingKey(), org, clock.instant(), null, other));
        DeviceStateChanged state = new DeviceStateChanged(aircon, null, DeviceStateChanged.Connectivity.ONLINE,
                Map.of("Thermostat", Map.of("mode", "cool", "targetTemperature", 24)), List.of(), 7, Map.of(), clock.instant(),
                DeviceStateChanged.Origin.COMMAND);
        hub.onEvent(new DomainEvent<>(1, UUID.randomUUID(), EventType.DEVICE_STATE_CHANGED.routingKey(), org, clock.instant(), null, state));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(body(stream))
                .contains("event:command-status").contains("\"status\":\"FAILED\"").contains("\"reason\":\"TIMEOUT\"")
                .contains("\"userName\":\"이름 loop.operator\"").contains("\"reportedVersion\":7")
                .contains("event:device-update").contains("\"targetTemperature\":24").contains("\"origin\":\"COMMAND\""));
        assertThat(body(stream)).doesNotContain("\"status\":\"APPLIED\"");
    }
}
