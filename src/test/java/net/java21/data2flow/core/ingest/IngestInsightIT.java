package net.java21.data2flow.core.ingest;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.ReprocessJobFinished;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.ingest.event.ReprocessEventHandler;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ING-01.04 재처리 작업 목록(API-ING-14), ING-06.02 품질 추이·요약, TSD-02.04 공백·완전성(API-TSD-09) */
class IngestInsightIT extends IntegrationTestSupport {

    @Autowired
    ReprocessEventHandler reprocessEvents;

    private long org;
    private long integrator;
    private long analyst;
    private long viewer;
    private long source;
    private long room;
    private long room2;
    private long device;
    private long device2;

    @BeforeEach
    void setUp() {
        org = fx.organization("ins");
        integrator = fx.user(org, "ins.int", "INTEGRATOR");
        analyst = fx.user(org, "ins.analyst", "ANALYST");
        viewer = fx.user(org, "ins.viewer", "VIEWER");
        long site = data.site(org, "본관");
        room = data.space(org, site, "ROOM", "실습실");
        room2 = data.space(org, site, "ROOM", "강의실");
        source = data.source(org, "cs");
        device = data.device(org, source, "d1", "ACTIVE", room, null);
        device2 = data.device(org, source, "d2", "ACTIVE", room2, null);
        jdbc.sql("UPDATE data2flow_core.devices SET expected_interval_sec = 60 WHERE organization_id = :org").param("org", org).update();
    }

    private long job(List<Long> devices, String status, long total, long processed) {
        return jdbc.sql("""
                        INSERT INTO data2flow_pipeline.reprocess_jobs (organization_id, source_id, device_ids, period_from, period_to, status, total,
                               processed, failed, decoder_version, requested_by, memo, created_at)
                        VALUES (:org, :source, CAST(:devices AS bigint[]), :from, :to, :status, :total, :processed, 0, 'chirpstack-v4', :user, '보정',
                                :now) RETURNING id""")
                .param("org", org).param("source", source).param("devices", Pg.bigintArray(devices))
                .param("from", Pg.ts(clock.instant().minus(Duration.ofDays(7)))).param("to", Pg.ts(clock.instant())).param("status", status)
                .param("total", total).param("processed", processed).param("user", integrator).param("now", Pg.ts(clock.instant()))
                .query(Long.class).single();
    }

    @Test
    @DisplayName("[ING-01.04][AT-ING-08.2] 재처리 작업 목록·상세(진행률·요청자·소스 이름), 상태·소스 필터, 공간 범위, OPERATOR 403 — API-ING-14")
    void reprocessJobs() throws Exception {
        long running = job(List.of(device), "RUNNING", 10_000, 4_000);
        clock.advance(Duration.ofMinutes(1));
        jdbc.sql("UPDATE data2flow_pipeline.reprocess_jobs SET status = 'COMPLETED' WHERE id = :id").param("id", running).update();
        long second = job(List.of(device2), "RUNNING", 3, 1);
        mvc.perform(as(org, integrator, get("/core/ingest/reprocess-jobs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[0].jobId").value(Long.toString(second)))
                .andExpect(jsonPath("$.responses[0].progressPercent").value(33.3))
                .andExpect(jsonPath("$.responses[0].sourceName").value("소스 cs"))
                .andExpect(jsonPath("$.responses[1].progressPercent").value(40.0));
        mvc.perform(as(org, integrator, get("/core/ingest/reprocess-jobs").param("status", "running")))
                .andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, integrator, get("/core/ingest/reprocess-jobs/" + running)))
                .andExpect(jsonPath("$.response.status").value("COMPLETED"))
                .andExpect(jsonPath("$.response.deviceIds[0]").value(Long.toString(device)))
                .andExpect(jsonPath("$.response.memo").value("보정"))
                .andExpect(jsonPath("$.response.requestedByName").exists());
        mvc.perform(as(org, integrator, get("/core/ingest/reprocess-jobs").param("status", "DONE"))).andExpect(status().isBadRequest());
        long operator = fx.user(org, "ins.op", "OPERATOR");
        mvc.perform(as(org, operator, get("/core/ingest/reprocess-jobs"))).andExpect(status().isForbidden());
        data.spaceScope(org, integrator, List.of(room));
        mvc.perform(as(org, integrator, get("/core/ingest/reprocess-jobs"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, integrator, get("/core/ingest/reprocess-jobs/" + second))).andExpect(status().isNotFound());
        // EVT-ING-09: 끝나면 감사 REPROCESS_COMPLETED
        reprocessEvents.handle(DomainEvent.of(EventType.INGEST_REPROCESS_FINISHED, org,
                new ReprocessJobFinished(Long.toString(running), ReprocessJobFinished.Status.COMPLETED, source, List.of(device),
                        clock.instant().minus(Duration.ofDays(7)), clock.instant(), 10_000, 10_000, 0, 0, integrator, null, clock.instant()),
                null, clock));
        assertThat(auditCount(org, "REPROCESS_COMPLETED")).isEqualTo(1);
    }

    private void quality(long deviceId, LocalDate day, int score, int completeness, long expected, long received, long outOfRange) {
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.data_quality_daily (device_id, day, organization_id, score, completeness, timeliness, validity,
                               stability, expected_count, received_count, late_count, out_of_range_count, suspect_count)
                        VALUES (:device, :day, :org, :score, :completeness, 90, 95, 99, :expected, :received, 2, :oor, 1)""")
                .param("device", deviceId).param("day", day).param("org", org).param("score", score).param("completeness", completeness)
                .param("expected", expected).param("received", received).param("oor", outOfRange).update();
    }

    @Test
    @DisplayName("[ING-06.02][AT-ING-09.4] 품질 추이(일별 점수, 31일 초과 400 ING_QUERY_RANGE_TOO_LARGE)·요약(최하위 10개·문제 유형 분포), ANALYST·VIEWER 허용(ANALYTICS_READ), 공간 범위 — TC-ING-071")
    void qualityTrendAndSummary() throws Exception {
        LocalDate yesterday = LocalDate.of(2026, 10, 2);
        for (int i = 0; i < 5; i++) {
            quality(device, yesterday.minusDays(i), 80 - i, 90, 1440, 1300, 3);
        }
        quality(device2, yesterday, 41, 50, 1440, 720, 10);
        mvc.perform(as(org, analyst, get("/core/ingest/quality/trend").param("groupBy", "device").param("targetId", Long.toString(device))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.points", hasSize(5)))
                .andExpect(jsonPath("$.response.points[4].day").value("2026-10-02"))
                .andExpect(jsonPath("$.response.points[4].score").value(80))
                .andExpect(jsonPath("$.response.from").value("2026-09-03"));
        mvc.perform(as(org, analyst, get("/core/ingest/quality/trend").param("groupBy", "space").param("targetId", Long.toString(room2))))
                .andExpect(jsonPath("$.response.points[0].score").value(41));
        mvc.perform(as(org, analyst, get("/core/ingest/quality/trend").param("targetId", Long.toString(device))
                        .param("from", "2026-08-01").param("to", "2026-10-02")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ING_QUERY_RANGE_TOO_LARGE"));
        mvc.perform(as(org, analyst, get("/core/ingest/quality/trend"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, analyst, get("/core/ingest/quality/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.day").value("2026-10-02"))
                .andExpect(jsonPath("$.response.devices").value(2))
                .andExpect(jsonPath("$.response.bottom10[0].targetId").value(Long.toString(device2)))
                .andExpect(jsonPath("$.response.bottom10[0].score").value(41))
                .andExpect(jsonPath("$.response.distribution.outOfRange").value(13))
                .andExpect(jsonPath("$.response.distribution.late").value(4))
                .andExpect(jsonPath("$.response.distribution.received").value(2020));
        data.spaceScope(org, analyst, List.of(room));
        mvc.perform(as(org, analyst, get("/core/ingest/quality/summary"))).andExpect(jsonPath("$.response.devices").value(1));
        mvc.perform(as(org, analyst, get("/core/ingest/quality/trend").param("targetId", Long.toString(device2))))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, viewer, get("/core/ingest/quality/summary"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("[TSD-02.04][AT-TSD-14.1] 1분 주기 기기의 30분 공백 → 공백 1건 expectedCount 30, 기기별 완전성 %, 공간 조회·범위 밖 404·90일 초과 400 — TC-TSD-046~049")
    void gapsAndCompleteness() throws Exception {
        Instant now = clock.instant();
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.data_gaps (organization_id, device_id, gap_start, gap_end, expected_count)
                        VALUES (:org, :device, :start, :end, 30)""")
                .param("org", org).param("device", device).param("start", Pg.ts(now.minus(Duration.ofMinutes(90))))
                .param("end", Pg.ts(now.minus(Duration.ofMinutes(60)))).update();
        mvc.perform(as(org, viewer, get("/core/telemetry/gaps").param("deviceId", Long.toString(device))
                        .param("from", now.minus(Duration.ofHours(10)).toString()).param("to", now.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.gaps", hasSize(1)))
                .andExpect(jsonPath("$.response.gaps[0].expectedCount").value(30))
                .andExpect(jsonPath("$.response.devices[0].expected").value(600))
                .andExpect(jsonPath("$.response.devices[0].missing").value(30))
                .andExpect(jsonPath("$.response.devices[0].completenessPercent").value(95.0));
        Long site = jdbc.sql("SELECT parent_id FROM data2flow_core.spaces WHERE id = :id").param("id", room).query(Long.class).single();
        mvc.perform(as(org, viewer, get("/core/telemetry/gaps").param("spaceId", Long.toString(site))))
                .andExpect(jsonPath("$.response.devices", hasSize(2)))
                .andExpect(jsonPath("$.response.devices[1].completenessPercent").value(100.0));
        mvc.perform(as(org, viewer, get("/core/telemetry/gaps").param("from", now.minus(Duration.ofDays(91)).toString())))
                .andExpect(status().isBadRequest());
        data.spaceScope(org, viewer, List.of(room2));
        mvc.perform(as(org, viewer, get("/core/telemetry/gaps").param("deviceId", Long.toString(device)))).andExpect(status().isNotFound());
        mvc.perform(as(org, viewer, get("/core/telemetry/gaps"))).andExpect(jsonPath("$.response.devices", hasSize(1)));
        long other = fx.organization("ins2");
        long otherAdmin = fx.user(other, "ins2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/telemetry/gaps").param("deviceId", Long.toString(device)))).andExpect(status().isNotFound());
    }
}
