package net.java21.data2flow.core.ingest;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.ingest.PipelineStubServer.Reply;
import net.java21.data2flow.core.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실패 메시지 보관함(ING-07.03, UC-ING-07)과 재처리 작업(UC-ING-08): API-ING-07~12. pipeline 내부 API는 대역 서버 —
 * TC-ING-086·089(core 쪽), AT-ING-07.1·07.2.
 */
class FailureInboxIT extends IngestITSupport {

    static final Instant T0 = MutableClock.T0;

    long org;
    long integrator;
    long operator;
    long source;
    long labDevice;
    long officeDevice;
    long lab;

    @BeforeEach
    void setUp() {
        org = fx.organization("ingf");
        integrator = fx.user(org, "dlq.integrator", "INTEGRATOR");
        operator = fx.user(org, "dlq.operator", "OPERATOR");
        long site = data.site(org, "본관");
        lab = data.space(org, site, "ROOM", "실습실");
        long office = data.space(org, site, "ROOM", "교무실");
        source = data.source(org, "chirp");
        labDevice = data.device(org, source, "lab-1", "ACTIVE", lab, null);
        officeDevice = data.device(org, source, "office-1", "ACTIVE", office, null);
    }

    private long failure(long device, String stage, String code, String status) {
        Instant at = T0.minus(Duration.ofMinutes(30));
        long raw = rows.raw(org, source, device, "DECODE".equals(stage) ? "DECODE_ERROR" : "SCRIPT_ERROR", at, at, "{}", "t", false);
        return rows.dlq(org, raw, stage, code, status, at, null, null);
    }

    private static String ids(List<Long> ids) {
        return ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    @DisplayName("[ING-07.03] 실패 목록: 오류 코드별 묶음·단계별 건수, 개별 목록(소스·기기 이름), 단계 필터, 공간 범위, OPERATOR 조회 가능·VIEWER 403")
    void listFailures() throws Exception {
        for (int i = 0; i < 3; i++) {
            failure(labDevice, "DECODE", "ING_EXTERNAL_ID_MISSING", "OPEN");
        }
        failure(officeDevice, "SCRIPT", "SCRIPT_RUNTIME_ERROR", "OPEN");
        failure(labDevice, "SCRIPT", "SCRIPT_RUNTIME_ERROR", "RESOLVED");
        mvc.perform(as(org, operator, get("/core/ingest/failures").param("groupBy", "errorCode"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.groups", hasSize(2)))
                .andExpect(jsonPath("$.response.groups[0].errorCode").value("ING_EXTERNAL_ID_MISSING"))
                .andExpect(jsonPath("$.response.groups[0].count").value(3))
                .andExpect(jsonPath("$.response.groups[0].sampleMessage").value("ING_EXTERNAL_ID_MISSING 메시지"))
                .andExpect(jsonPath("$.response.countsByStage.DECODE").value(3))
                .andExpect(jsonPath("$.response.countsByStage.SCRIPT").value(1))
                .andExpect(jsonPath("$.response.countsByStage.PUBLISH").value(0));
        mvc.perform(as(org, operator, get("/core/ingest/failures").param("groupBy", "errorCode").param("stage", "SCRIPT")))
                .andExpect(jsonPath("$.response.groups", hasSize(1))).andExpect(jsonPath("$.response.countsByStage.DECODE").value(3));
        mvc.perform(as(org, operator, get("/core/ingest/failures").param("errorCode", "ING_EXTERNAL_ID_MISSING").param("size", "2")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses", hasSize(2))).andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.responses[0].stage").value("DECODE"))
                .andExpect(jsonPath("$.responses[0].sourceName").value("소스 chirp"))
                .andExpect(jsonPath("$.responses[0].deviceName").value("기기 lab-1"))
                .andExpect(jsonPath("$.responses[0].deviceId").value(Long.toString(labDevice)));
        mvc.perform(as(org, operator, get("/core/ingest/failures").param("status", "RESOLVED"))).andExpect(jsonPath("$.totalCount").value(1));
        long scoped = fx.user(org, "lab.operator", "OPERATOR");
        data.spaceScope(org, scoped, List.of(lab));
        mvc.perform(as(org, scoped, get("/core/ingest/failures"))).andExpect(jsonPath("$.totalCount").value(3));
        for (String[] bad : new String[][]{{"stage", "EVENT"}, {"status", "GONE"}, {"groupBy", "stage"}, {"from", "2026-08-01T00:00:00Z"}}) {
            mvc.perform(as(org, operator, get("/core/ingest/failures").param(bad[0], bad[1]))).andExpect(status().isBadRequest());
        }
        long viewer = fx.user(org, "dlq.viewer", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/ingest/failures"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[ING-07.03][AT-ING-07.1] 선택 재처리: pipeline API-ING-22 결과를 RESOLVED·SAME_ERROR·OTHER_ERROR로, 다른 사람이 잡은 항목 LOCKED, 감사 DLQ_REPROCESSED — TC-ING-086")
    void reprocessSelected() throws Exception {
        long resolved = failure(labDevice, "DECODE", "ING_EXTERNAL_ID_MISSING", "OPEN");
        long same = failure(labDevice, "SCRIPT", "SCRIPT_RUNTIME_ERROR", "OPEN");
        long other = failure(labDevice, "SCRIPT", "SCRIPT_RUNTIME_ERROR", "OPEN");
        long lockedRaw = rows.raw(org, source, labDevice, "SCRIPT_ERROR", T0.minusSeconds(60), T0.minusSeconds(60), "{}", "t", false);
        long locked = rows.dlq(org, lockedRaw, "SCRIPT", "SCRIPT_RUNTIME_ERROR", "OPEN", T0.minusSeconds(60), operator, T0.plusSeconds(120));
        PIPELINE.respond(r -> new Reply(200, """
                {"results":[{"id":%d,"outcome":"OK"},{"id":%d,"outcome":"FAILED","errorCode":"SCRIPT_RUNTIME_ERROR"},
                            {"id":%d,"outcome":"FAILED","errorCode":"SCRIPT_TIMEOUT"}],"ok":1,"failed":2,"skipped":0}""".formatted(resolved, same, other)));
        mvc.perform(as(org, integrator, json(post("/core/ingest/failures/reprocess"), "{\"dlqItemIds\":" + ids(List.of(resolved, same, other, locked, 999999L)) + "}"))
                        .header("Idempotency-Key", "rp-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.results[0].outcome").value("RESOLVED"))
                .andExpect(jsonPath("$.response.results[1].outcome").value("SAME_ERROR"))
                .andExpect(jsonPath("$.response.results[2].outcome").value("OTHER_ERROR"))
                .andExpect(jsonPath("$.response.results[2].errorCode").value("SCRIPT_TIMEOUT"))
                .andExpect(jsonPath("$.response.results[3].outcome").value("LOCKED"))
                .andExpect(jsonPath("$.response.results[4].errorCode").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.response.summary.resolved").value(1))
                .andExpect(jsonPath("$.response.summary.sameError").value(1))
                .andExpect(jsonPath("$.response.summary.otherError").value(2))
                .andExpect(jsonPath("$.response.summary.locked").value(1));
        assertThat(PIPELINE.received()).hasSize(1);
        PipelineStubServer.Received sent = PIPELINE.received().getFirst();
        assertThat(sent.path()).isEqualTo("/internal/pipeline/reprocess-items");
        assertThat(sent.callerService()).isEqualTo("data2flow-core-api");
        assertThat(sent.body()).contains("\"organizationId\":" + org).contains("\"kind\":\"DLQ\"").doesNotContain("\"id\":" + locked);
        assertThat(auditCount(org, "DLQ_REPROCESSED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.dlq_item_claims").query(Long.class).single()).isZero();
        // 원본 단건 재처리(kind RAW)
        PIPELINE.respond(r -> new Reply(200, PipelineStubServer.itemResults(r.body(), "OK", null)));
        mvc.perform(as(org, integrator, json(post("/core/ingest/failures/reprocess"), "{\"rawMessageIds\":[\"" + lockedRaw + "\"]}"))
                        .header("Idempotency-Key", "rp-2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.results[0].outcome").value("RESOLVED"));
        assertThat(PIPELINE.received().getLast().body()).contains("\"kind\":\"RAW\"");
        // 다른 사람이 잡은 항목만 요청하면 409
        mvc.perform(as(org, integrator, json(post("/core/ingest/failures/reprocess"), "{\"dlqItemIds\":[\"" + locked + "\"]}"))
                        .header("Idempotency-Key", "rp-3"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("ING_DLQ_ITEM_LOCKED"));
    }

    @Test
    @DisplayName("[ING-07.03] 재처리 입력 검증: 5,001건 ING_DLQ_BATCH_TOO_LARGE, Idempotency-Key 필수, 둘 다·둘 다 없음 400, OPERATOR 403, pipeline 장애 503 후 잡기 해제 — TC-ING-086")
    void reprocessValidation() throws Exception {
        List<Long> many = new ArrayList<>();
        for (long i = 1; i <= 5001; i++) {
            many.add(i);
        }
        mvc.perform(as(org, integrator, json(post("/core/ingest/failures/reprocess"), "{\"dlqItemIds\":" + many + "}")).header("Idempotency-Key", "big"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ING_DLQ_BATCH_TOO_LARGE"))
                .andExpect(jsonPath("$.header.resultMessage").value("한 번에 최대 5,000건까지 재처리할 수 있습니다"));
        long item = failure(labDevice, "DECODE", "ING_DECODE_FAILED", "OPEN");
        mvc.perform(as(org, integrator, json(post("/core/ingest/failures/reprocess"), "{\"dlqItemIds\":[\"" + item + "\"]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        for (String bad : new String[]{"{}", "{\"dlqItemIds\":[\"1\"],\"rawMessageIds\":[\"2\"]}"}) {
            mvc.perform(as(org, integrator, json(post("/core/ingest/failures/reprocess"), bad)).header("Idempotency-Key", "bad-" + bad.length()))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(as(org, operator, json(post("/core/ingest/failures/reprocess"), "{\"dlqItemIds\":[\"" + item + "\"]}")).header("Idempotency-Key", "op"))
                .andExpect(status().isForbidden());
        PIPELINE.respond(r -> new Reply(500, "{}"));
        mvc.perform(as(org, integrator, json(post("/core/ingest/failures/reprocess"), "{\"dlqItemIds\":[\"" + item + "\"]}")).header("Idempotency-Key", "down"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.header.resultCode").value("SERVICE_UNAVAILABLE"));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.dlq_item_claims").query(Long.class).single()).isZero();
        // 공간 범위 밖 항목은 없는 것과 같다
        long officeItem = failure(officeDevice, "DECODE", "ING_DECODE_FAILED", "OPEN");
        long scoped = fx.user(org, "lab.integrator", "INTEGRATOR");
        data.spaceScope(org, scoped, List.of(lab));
        PIPELINE.reset();
        mvc.perform(as(org, scoped, json(post("/core/ingest/failures/reprocess"), "{\"dlqItemIds\":[\"" + officeItem + "\"]}")).header("Idempotency-Key", "sc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.results[0].errorCode").value("RESOURCE_NOT_FOUND"));
        assertThat(PIPELINE.received()).isEmpty();
    }

    @Test
    @DisplayName("[ING-07.03][AT-ING-07.1] 두 사용자가 같은 20건을 동시에 재처리 → 한쪽 성공, 다른 쪽 409 ING_DLQ_ITEM_LOCKED, pipeline 호출 1번 — TC-ING-089")
    void concurrentReprocess() throws Exception {
        List<Long> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(failure(labDevice, "DECODE", "ING_DECODE_FAILED", "OPEN"));
        }
        long second = fx.user(org, "dlq.admin", "ADMIN");
        String body = "{\"dlqItemIds\":" + ids(items) + "}";
        CountDownLatch release = PIPELINE.holdNext();
        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(as(org, integrator, json(post("/core/ingest/failures/reprocess"), body)).header("Idempotency-Key", "c-1")).andReturn();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        await().atMost(Duration.ofSeconds(10)).until(() -> PIPELINE.received().size() == 1);
        mvc.perform(as(org, second, json(post("/core/ingest/failures/reprocess"), body)).header("Idempotency-Key", "c-2"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("ING_DLQ_ITEM_LOCKED"))
                .andExpect(jsonPath("$.header.resultMessage").value("다른 사용자가 처리 중입니다"));
        release.countDown();
        MvcResult done = first.get();
        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(done.getResponse().getContentAsString(), "$.response.summary.resolved")).isEqualTo(20);
        assertThat(PIPELINE.received().stream().filter(r -> r.path().endsWith("/reprocess-items")).count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.dlq_item_claims").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("[ING-07.03][AT-ING-07.2] 10건 중 5건 폐기 → pipeline에 사유와 함께 요청, 감사 DLQ_DISCARDED 1건, 사유 2~200자·5,000건 검사 — TC-ING-086")
    void discard() throws Exception {
        List<Long> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            items.add(failure(labDevice, "DECODE", "ING_DECODE_FAILED", "OPEN"));
        }
        mvc.perform(as(org, integrator, json(post("/core/ingest/failures/discard"),
                        "{\"dlqItemIds\":" + ids(items.subList(0, 5)) + ",\"reason\":\"센서 교체 전 데이터\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.discarded").value(5));
        assertThat(PIPELINE.received().getFirst().path()).isEqualTo("/internal/pipeline/dlq-items/discard");
        assertThat(PIPELINE.received().getFirst().body()).contains("센서 교체 전 데이터").contains("\"requestedBy\":" + integrator);
        assertThat(auditCount(org, "DLQ_DISCARDED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_pipeline.raw_messages WHERE organization_id = :o").param("o", org)
                .query(Long.class).single()).isEqualTo(10);
        mvc.perform(as(org, integrator, json(post("/core/ingest/failures/discard"), "{\"dlqItemIds\":" + ids(items) + ",\"reason\":\"x\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        mvc.perform(as(org, integrator, json(post("/core/ingest/failures/discard"), "{\"dlqItemIds\":[],\"reason\":\"정리\"}")))
                .andExpect(status().isBadRequest());
        List<Long> many = new ArrayList<>();
        for (long i = 1; i <= 5001; i++) {
            many.add(i);
        }
        mvc.perform(as(org, integrator, json(post("/core/ingest/failures/discard"), "{\"dlqItemIds\":" + many + ",\"reason\":\"정리\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ING_DLQ_BATCH_TOO_LARGE"));
        jdbc.sql("UPDATE data2flow_pipeline.dlq_items SET locked_by = :u, locked_until = :t WHERE id = :id")
                .param("u", operator).param("t", net.java21.data2flow.core.common.Pg.ts(T0.plusSeconds(60))).param("id", items.get(9)).update();
        mvc.perform(as(org, integrator, json(post("/core/ingest/failures/discard"), "{\"dlqItemIds\":[\"" + items.get(9) + "\"],\"reason\":\"정리\"}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, operator, json(post("/core/ingest/failures/discard"), "{\"dlqItemIds\":[\"" + items.get(8) + "\"],\"reason\":\"정리\"}")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[ING-07.03][UC-ING-08] 재처리 작업: 미리 보기(건수·디코더·예상 시간), 생성 202 PENDING·감사, 실행 중 409, 취소·끝난 작업 409, 보관 기간·31일 검사")
    void reprocessJobs() throws Exception {
        Instant at = T0.minus(Duration.ofHours(2));
        for (int i = 0; i < 3; i++) {
            rows.raw(org, source, labDevice, i == 0 ? "DECODE_ERROR" : "OK", at, at, "{}", "t", false);
        }
        String window = ",\"from\":\"%s\",\"to\":\"%s\"".formatted(T0.minus(Duration.ofDays(1)), T0);
        mvc.perform(as(org, integrator, json(post("/core/ingest/reprocess-jobs/preview"), "{\"sourceId\":\"" + source + "\"" + window + "}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.total").value(3))
                .andExpect(jsonPath("$.response.byStatus.OK").value(2)).andExpect(jsonPath("$.response.byStatus.DECODE_ERROR").value(1))
                .andExpect(jsonPath("$.response.estimatedSeconds").value(1))
                .andExpect(jsonPath("$.response.decoder.key").value("chirpstack-v4"))
                .andExpect(jsonPath("$.response.scripts", hasSize(0)));
        mvc.perform(as(org, integrator, json(post("/core/ingest/reprocess-jobs"), "{\"sourceId\":\"" + source + "\"" + window + ",\"memo\":\"보정 스크립트\"}"))
                        .header("Idempotency-Key", "job-1"))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/v1/core/ingest/reprocess-jobs/4242"))
                .andExpect(jsonPath("$.response.jobId").value("4242")).andExpect(jsonPath("$.response.status").value("PENDING"))
                .andExpect(jsonPath("$.response.total").value(120));
        assertThat(PIPELINE.received().getLast().body()).contains("보정 스크립트").contains("\"sourceId\":" + source);
        assertThat(auditCount(org, "REPROCESS_STARTED")).isEqualTo(1);
        long running = rows.job(org, source, "RUNNING", 40, null, T0.minus(Duration.ofDays(1)));
        mvc.perform(as(org, integrator, json(post("/core/ingest/reprocess-jobs"), "{\"sourceId\":\"" + source + "\"" + window + "}"))
                        .header("Idempotency-Key", "job-2"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("ING_REPROCESS_ALREADY_RUNNING"));
        mvc.perform(as(org, integrator, post("/core/ingest/reprocess-jobs/" + running + "/cancel"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("CANCELLED")).andExpect(jsonPath("$.response.processed").value(40));
        assertThat(PIPELINE.received().getLast().path()).isEqualTo("/internal/pipeline/reprocess-jobs/" + running + "/cancel");
        long finished = rows.job(org, source, "COMPLETED", 100, null, T0.minus(Duration.ofDays(2)));
        mvc.perform(as(org, integrator, post("/core/ingest/reprocess-jobs/" + finished + "/cancel"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("ING_REPROCESS_NOT_CANCELLABLE"));
        mvc.perform(as(org, integrator, post("/core/ingest/reprocess-jobs/999999/cancel"))).andExpect(status().isNotFound());
        // pipeline이 409를 주면 실행 중 오류로
        jdbc.sql("UPDATE data2flow_pipeline.reprocess_jobs SET status = 'CANCELLED' WHERE id = :id").param("id", running).update();
        PIPELINE.respond(r -> new Reply(409, "{}"));
        mvc.perform(as(org, integrator, json(post("/core/ingest/reprocess-jobs"), "{\"sourceId\":\"" + source + "\"" + window + "}"))
                .header("Idempotency-Key", "job-3"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("ING_REPROCESS_ALREADY_RUNNING"));
        // 검증
        String old = ",\"from\":\"2026-08-01T00:00:00Z\",\"to\":\"2026-08-02T00:00:00Z\"";
        mvc.perform(as(org, integrator, json(post("/core/ingest/reprocess-jobs/preview"), "{\"sourceId\":\"" + source + "\"" + old + "}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ING_REPROCESS_OUT_OF_RETENTION"));
        String wide = ",\"from\":\"2026-09-01T00:00:00Z\",\"to\":\"2026-10-03T00:00:00Z\"";
        mvc.perform(as(org, integrator, json(post("/core/ingest/reprocess-jobs/preview"), "{\"sourceId\":\"" + source + "\"" + wide + "}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ING_QUERY_RANGE_TOO_LARGE"));
        mvc.perform(as(org, integrator, json(post("/core/ingest/reprocess-jobs/preview"), "{\"sourceId\":\"999999\"" + window + "}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, integrator, json(post("/core/ingest/reprocess-jobs/preview"), "{" + window.substring(1) + "}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, integrator, json(post("/core/ingest/reprocess-jobs/preview"),
                        "{\"sourceId\":\"" + source + "\",\"deviceIds\":[\"999999\"]" + window + "}"))).andExpect(status().isNotFound());
        long scoped = fx.user(org, "lab.integrator2", "INTEGRATOR");
        data.spaceScope(org, scoped, List.of(lab));
        mvc.perform(as(org, scoped, json(post("/core/ingest/reprocess-jobs/preview"), "{\"sourceId\":\"" + source + "\"" + window + "}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, scoped, json(post("/core/ingest/reprocess-jobs/preview"),
                        "{\"sourceId\":\"" + source + "\",\"deviceIds\":[\"" + labDevice + "\"]" + window + "}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.total").value(3));
        mvc.perform(as(org, scoped, json(post("/core/ingest/reprocess-jobs/preview"),
                "{\"sourceId\":\"" + source + "\",\"deviceIds\":[\"" + officeDevice + "\"]" + window + "}"))).andExpect(status().isNotFound());
        long scopedJob = rows.job(org, source, "RUNNING", 1, List.of(officeDevice), T0.minus(Duration.ofDays(1)));
        mvc.perform(as(org, scoped, post("/core/ingest/reprocess-jobs/" + scopedJob + "/cancel"))).andExpect(status().isNotFound());
        mvc.perform(as(org, operator, json(post("/core/ingest/reprocess-jobs/preview"), "{\"sourceId\":\"" + source + "\"" + window + "}")))
                .andExpect(status().isForbidden());
    }
}
