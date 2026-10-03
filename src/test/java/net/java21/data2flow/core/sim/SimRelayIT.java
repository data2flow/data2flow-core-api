package net.java21.data2flow.core.sim;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.SimFaultLabel;
import net.java21.data2flow.contracts.message.event.SimRunChanged;
import net.java21.data2flow.core.live.service.SimRunStreams;
import net.java21.data2flow.core.support.LoopItSupport;
import net.java21.data2flow.core.support.StubHttpServer.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SIM-04.01·04.02·05.03·08.01·08.02·09.02·11.01: 가상 환경 외부 API 중계(실행·장애·시나리오·프로필·요약·미리 보기·정리 요청),
 * simulator 오류 그대로 전달, 실행 실시간 스트림(API-SIM-31 sim.tick·sim.event·sim.status·sim.throttle).
 */
class SimRelayIT extends LoopItSupport {

    @Autowired
    SimRunStreams streams;

    @Test
    @DisplayName("[SIM-04.02][SIM-11.01][SIM-08.01] 실행 시작 201·상태·제어·가속·리포트 중계, 409 코드별 그대로(SIM_CONCURRENT_RUN_LIMIT), SIM_RUN 없으면 403 — TC-SIM-116·084")
    void runs() throws Exception {
        STUB.ok("POST", "/internal/sim/runs", 201, "{\"runId\":\"41\",\"status\":\"RUNNING\",\"seed\":7,\"accelerationEffective\":60}");
        mvc.perform(as(org, viewer, json(post("/core/sim/runs"), "{\"scenarioId\":\"3\"}"))).andExpect(status().isForbidden());
        mvc.perform(as(org, analyst, json(post("/core/sim/runs"), "{\"scenarioId\":\"3\",\"acceleration\":60}").header("Idempotency-Key", "run-1")))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/core/sim/runs/41"))
                .andExpect(jsonPath("$.response.runId").value("41"));
        assertThat(STUB.received("POST", "/internal/sim/runs").getFirst().header("X-ORG-ID")).isEqualTo(Long.toString(org));
        assertThat(auditCount(org, "SIM_RUN_STARTED")).isEqualTo(1);
        STUB.fail("POST", "/internal/sim/runs", 409, "SIM_CONCURRENT_RUN_LIMIT", null);
        mvc.perform(as(org, analyst, json(post("/core/sim/runs"), "{\"scenarioId\":\"3\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SIM_CONCURRENT_RUN_LIMIT"));
        STUB.ok("GET", "/internal/sim/runs/41", 200, "{\"runId\":\"41\",\"status\":\"RUNNING\",\"progressPct\":10}");
        mvc.perform(as(org, viewer, get("/core/sim/runs/41"))).andExpect(status().isForbidden());
        mvc.perform(as(org, analyst, get("/core/sim/runs/41"))).andExpect(status().isOk()).andExpect(jsonPath("$.response.progressPct").value(10));
        mvc.perform(as(org, analyst, get("/core/sim/runs/99"))).andExpect(status().isNotFound());
        mvc.perform(as(org, analyst, get("/core/sim/runs/..%2Fetc"))).andExpect(status().isNotFound());
        STUB.ok("POST", "/internal/sim/runs/41/pause", 200, "{\"runId\":\"41\",\"status\":\"PAUSED\"}");
        mvc.perform(as(org, analyst, post("/core/sim/runs/41/pause"))).andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("PAUSED"));
        STUB.fail("POST", "/internal/sim/runs/41/resume", 409, "SIM_RUN_STATE_CONFLICT", null);
        mvc.perform(as(org, analyst, post("/core/sim/runs/41/resume"))).andExpect(status().isConflict());
        mvc.perform(as(org, analyst, post("/core/sim/runs/41/explode"))).andExpect(status().isNotFound());
        STUB.ok("PATCH", "/internal/sim/runs/41", 200, "{\"runId\":\"41\",\"accelerationRequested\":30,\"accelerationEffective\":30,\"throttled\":false}");
        mvc.perform(as(org, analyst, json(patch("/core/sim/runs/41"), "{\"acceleration\":30}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.accelerationEffective").value(30));
        STUB.ok("GET", "/internal/sim/runs/41/report", 200, "{\"runId\":\"41\",\"passed\":true,\"total\":2}");
        mvc.perform(as(org, analyst, get("/core/sim/runs/41/report"))).andExpect(jsonPath("$.response.passed").value(true));
        mvc.perform(as(org, analyst, json(patch("/core/sim/runs/41/report"), "{\"retainUntil\":\"2027-01-01T00:00:00Z\"}")))
                .andExpect(status().isForbidden());
        STUB.ok("PATCH", "/internal/sim/runs/41/report", 200, "{\"runId\":\"41\",\"retainUntil\":\"2027-01-01T00:00:00Z\"}");
        mvc.perform(as(org, admin, json(patch("/core/sim/runs/41/report"), "{\"retainUntil\":\"2027-01-01T00:00:00Z\"}")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[SIM-05.03][SIM-08.02][SIM-09.02] 장애 주입(가상 기기만, 아니면 400 SIM_TARGET_NOT_VIRTUAL)·목록·해제, 시나리오 CRUD·복제·내보내기(SIM_SCENARIO_INVALID errors[].field 그대로), 프로필")
    void faultsScenariosProfiles() throws Exception {
        long source = data.source(org, "lns");
        long real = data.device(org, source, "real-1", "ACTIVE", null, null);
        mvc.perform(as(org, analyst, json(post("/core/sim/faults"),
                        "{\"targetType\":\"DEVICE\",\"targetIds\":[\"" + real + "\"],\"kind\":\"STUCK\",\"params\":{},\"durationSec\":600}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SIM_TARGET_NOT_VIRTUAL"));
        long virtual = data.device(org, source, "virt-1", "ACTIVE", null, null, true);
        STUB.ok("POST", "/internal/sim/faults", 201, "{\"faultIds\":[\"5\"]}");
        mvc.perform(as(org, analyst, json(post("/core/sim/faults"),
                        "{\"targetType\":\"DEVICE\",\"targetIds\":[\"" + virtual + "\"],\"kind\":\"STUCK\",\"params\":{},\"durationSec\":600}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.faultIds[0]").value("5"));
        STUB.on("GET", "/internal/sim/faults", r -> new Reply(200, "{\"header\":{\"isSuccessful\":true,\"resultCode\":\"SUCCESS\","
                + "\"resultMessage\":\"SUCCESS\"},\"page\":1,\"size\":20,\"totalPages\":1,\"responses\":[{\"faultId\":\"5\"}],\"totalCount\":1}", Map.of()));
        mvc.perform(as(org, analyst, get("/core/sim/faults").param("runId", "41")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].faultId").value("5"));
        STUB.ok("POST", "/internal/sim/faults/5/cancel", 200, "{\"faultId\":\"5\",\"status\":\"CANCELLED\"}");
        mvc.perform(as(org, analyst, post("/core/sim/faults/5/cancel"))).andExpect(jsonPath("$.response.status").value("CANCELLED"));

        STUB.fail("POST", "/internal/sim/scenarios", 400, "SIM_SCENARIO_INVALID",
                "\"errors\":[{\"field\":\"events[3].at\",\"code\":\"SIM_SCENARIO_INVALID\",\"message\":\"실행 기간 밖\"}]");
        mvc.perform(as(org, integrator, json(post("/core/sim/scenarios"), "{\"name\":\"폭염\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("events[3].at"));
        STUB.ok("POST", "/internal/sim/scenarios", 201, "{\"scenarioId\":\"3\",\"name\":\"폭염\",\"version\":0}");
        mvc.perform(as(org, analyst, json(post("/core/sim/scenarios"), "{\"name\":\"폭염\"}"))).andExpect(status().isForbidden());
        mvc.perform(as(org, integrator, json(post("/core/sim/scenarios"), "{\"name\":\"폭염\"}")))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/core/sim/scenarios/3"));
        STUB.on("GET", "/internal/sim/scenarios", r -> new Reply(200, "{\"header\":{\"isSuccessful\":true,\"resultCode\":\"SUCCESS\","
                + "\"resultMessage\":\"SUCCESS\"},\"page\":1,\"size\":50,\"totalPages\":1,\"responses\":[{\"scenarioId\":\"3\"}],\"totalCount\":1}", Map.of()));
        mvc.perform(as(org, analyst, get("/core/sim/scenarios").param("size", "50"))).andExpect(jsonPath("$.totalCount").value(1));
        assertThat(STUB.received("GET", "/internal/sim/scenarios").getFirst().query()).isEqualTo("size=50");
        STUB.ok("GET", "/internal/sim/scenarios/3", 200, "{\"scenarioId\":\"3\",\"version\":0}");
        mvc.perform(as(org, analyst, get("/core/sim/scenarios/3"))).andExpect(jsonPath("$.response.scenarioId").value("3"));
        STUB.ok("PUT", "/internal/sim/scenarios/3", 200, "{\"scenarioId\":\"3\",\"version\":1}");
        mvc.perform(as(org, integrator, json(put("/core/sim/scenarios/3"), "{\"name\":\"폭염\",\"baseVersion\":0}")))
                .andExpect(jsonPath("$.response.version").value(1));
        STUB.ok("POST", "/internal/sim/scenarios/3/clone", 201, "{\"id\":\"4\"}");
        mvc.perform(as(org, integrator, json(post("/core/sim/scenarios/3/clone"), "{\"name\":\"복제\"}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.id").value("4"));
        STUB.on("DELETE", "/internal/sim/scenarios/4", r -> new Reply(204, "", Map.of()));
        mvc.perform(as(org, integrator, delete("/core/sim/scenarios/4"))).andExpect(status().isNoContent());
        STUB.on("GET", "/internal/sim/scenarios/3/export", r -> new Reply(200, "{\"schemaVersion\":1}",
                Map.of("Content-Disposition", "attachment; filename=\"scenario-3.json\"")));
        mvc.perform(as(org, integrator, get("/core/sim/scenarios/3/export").param("format", "json")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", "attachment; filename=\"scenario-3.json\""))
                .andExpect(jsonPath("$.schemaVersion").value(1));
        mvc.perform(as(org, integrator, get("/core/sim/scenarios/3/export").param("format", "xml"))).andExpect(status().isBadRequest());

        STUB.ok("GET", "/internal/sim/profiles", 200, "[{\"id\":\"8\",\"name\":\"고효율 에어컨\"}]");
        mvc.perform(as(org, analyst, get("/core/sim/profiles"))).andExpect(jsonPath("$.totalCount").value(1));
        STUB.ok("POST", "/internal/sim/profiles", 201, "{\"id\":\"9\",\"name\":\"새 프로필\"}");
        mvc.perform(as(org, integrator, json(post("/core/sim/profiles"), "{\"name\":\"새 프로필\",\"typeId\":\"2\",\"overrides\":{}}")))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/core/sim/profiles/9"));
        STUB.ok("GET", "/internal/sim/profiles/9", 200, "{\"id\":\"9\"}");
        mvc.perform(as(org, analyst, get("/core/sim/profiles/9"))).andExpect(status().isOk());
        STUB.ok("PUT", "/internal/sim/profiles/9", 200, "{\"id\":\"9\",\"version\":1}");
        mvc.perform(as(org, integrator, json(put("/core/sim/profiles/9"), "{\"name\":\"x\",\"typeId\":\"2\",\"overrides\":{},\"baseVersion\":0}")))
                .andExpect(status().isOk());
        STUB.fail("DELETE", "/internal/sim/profiles/9", 409, "SIM_PROFILE_IN_USE", null);
        mvc.perform(as(org, integrator, delete("/core/sim/profiles/9"))).andExpect(status().isConflict());
        assertThat(auditCount(org, "SIM_PROFILE_CHANGED")).isEqualTo(2);
    }

    @Test
    @DisplayName("[SIM-01.02][SIM-07.05] 요약(공간 이름·프리셋 scenarioId 붙임)·카탈로그·미리 보기 중계, 가상 데이터 정리 요청 202(SIM_ADMIN)")
    void overviewPreviewPurge() throws Exception {
        long site = data.site(org, "가상 환경");
        jdbc.sql("UPDATE data2flow_core.spaces SET is_virtual = true WHERE id = :id").param("id", site).update();
        STUB.ok("GET", "/internal/sim/overview", 200, "{\"usage\":{\"devices\":3},\"presets\":[{\"key\":\"heatwave-afternoon\",\"state\":\"PREPARED\"}],"
                + "\"spaces\":[{\"spaceId\":\"" + site + "\",\"current\":{\"temperature\":28}},{\"spaceId\":\"999999\"}]}");
        STUB.ok("GET", "/internal/sim/presets", 200, "[{\"key\":\"heatwave-afternoon\",\"scenarioId\":\"77\"}]");
        mvc.perform(as(org, analyst, get("/core/sim/overview")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.spaces.length()").value(1))
                .andExpect(jsonPath("$.response.spaces[0].name").value("가상 환경"))
                .andExpect(jsonPath("$.response.presets[0].scenarioId").value("77"));
        STUB.ok("GET", "/internal/sim/catalog", 200, "{\"types\":[],\"kits\":[]}");
        mvc.perform(as(org, analyst, get("/core/sim/catalog").param("category", "SENSOR"))).andExpect(status().isOk());
        assertThat(STUB.received("GET", "/internal/sim/catalog").getFirst().query()).isEqualTo("category=SENSOR");
        STUB.ok("POST", "/internal/sim/preview", 200, "{\"series\":{\"temperature\":[{\"t\":\"2026-10-03T00:00:00Z\",\"v\":24}]}}");
        mvc.perform(as(org, analyst, json(post("/core/sim/preview"), "{\"spaceId\":\"" + site + "\",\"hours\":6}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.series.temperature[0].v").value(24));
        mvc.perform(as(org, integrator, json(post("/core/sim/data/purge"), "{\"runIds\":[\"41\"]}"))).andExpect(status().isForbidden());
        mvc.perform(as(org, admin, json(post("/core/sim/data/purge"), "{}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(post("/core/sim/data/purge"), "{\"from\":\"2026-10-02T00:00:00Z\",\"to\":\"2026-10-01T00:00:00Z\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(post("/core/sim/data/purge"),
                        "{\"from\":\"2026-10-01T00:00:00Z\",\"to\":\"2026-10-02T00:00:00Z\",\"spaceIds\":[\"" + site + "\"]}")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.jobId").isNotEmpty());
        assertThat(auditCount(org, "SIM_DATA_PURGED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[SIM-04.02][API-SIM-31] 실행 스트림: 연결 직후 sim.status, 1초 틱 sim.tick(공간 현재값)·새 실행 기록 sim.event, 장애 라벨·가속 변경(sim.throttle), 끝 상태면 닫음. 없는 실행 404")
    void runStream() throws Exception {
        STUB.ok("GET", "/internal/sim/runs/41", 200, "{\"runId\":\"41\",\"status\":\"RUNNING\",\"simClock\":\"2026-08-10T03:00:00Z\","
                + "\"progressPct\":5,\"accelerationEffective\":60,\"lastEvents\":[{\"simAt\":\"2026-08-10T03:00:00Z\",\"type\":\"START\",\"message\":\"시작\"}]}");
        mvc.perform(as(org, viewer, get("/core/stream/sim/runs/41").accept(MediaType.TEXT_EVENT_STREAM))).andExpect(status().isForbidden());
        mvc.perform(as(org, analyst, get("/core/stream/sim/runs/99").accept(MediaType.TEXT_EVENT_STREAM))).andExpect(status().isNotFound());
        MvcResult stream = mvc.perform(as(org, analyst, get("/core/stream/sim/runs/41").accept(MediaType.TEXT_EVENT_STREAM)))
                .andExpect(request().asyncStarted()).andReturn();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(body(stream)).contains("event:sim.status"));
        assertThat(streams.size()).isEqualTo(1);

        STUB.ok("GET", "/internal/sim/runs/41", 200, "{\"runId\":\"41\",\"status\":\"RUNNING\",\"simClock\":\"2026-08-10T03:10:00Z\","
                + "\"progressPct\":7,\"accelerationEffective\":30,\"lastEvents\":[{\"simAt\":\"2026-08-10T03:00:00Z\",\"type\":\"START\",\"message\":\"시작\"},"
                + "{\"simAt\":\"2026-08-10T03:05:00Z\",\"type\":\"OCCUPANCY\",\"message\":\"30명 입실\"}]}");
        STUB.ok("GET", "/internal/sim/spaces", 200, "[{\"spaceId\":\"12\",\"current\":{\"temperature\":28.4,\"co2\":900}},{\"spaceId\":\"13\",\"current\":null}]");
        streams.tick();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(body(stream)).contains("event:sim.tick")
                .contains("\"temperature\":28.4").contains("30명 입실").contains("event:sim.throttle").contains("THROUGHPUT_LIMIT"));
        assertThat(body(stream)).doesNotContain("시작");

        streams.onEvent(new DomainEvent<>(1, UUID.randomUUID(), EventType.SIM_FAULT_STARTED.routingKey(), org, clock.instant(), null,
                new SimFaultLabel(org, 41L, 5, "STUCK", "DEVICE", "17", clock.instant(), null, Map.of())));
        streams.pingAll();
        streams.onEvent(new DomainEvent<>(1, UUID.randomUUID(), EventType.SIM_RUN_COMPLETED.routingKey(), org, clock.instant(), null,
                new SimRunChanged(org, 41, 3L, "COMPLETED", clock.instant(), 30, false, null, clock.instant())));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(body(stream)).contains("FAULT_STARTED")
                .contains("event:ping").contains("\"status\":\"COMPLETED\""));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(streams.size()).isZero());
    }
}
