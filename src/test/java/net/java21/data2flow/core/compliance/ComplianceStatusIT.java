package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.compliance.service.ComplianceJobs;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-01.05·02.01 점수·준수 현황 — TC-CMP-032(점수 작업·EVT-CMP-02 한 번), TC-CMP-041(현황판 숫자·공간 범위·기간), TC-CMP-042(CSV·조직),
 * EVT-CMP-04·05 한 번씩. 시계 T0는 2026-10-03T00:00Z(서울 09:00)라 "어제"는 서울 10월 2일이다.
 */
class ComplianceStatusIT extends IntegrationTestSupport {

    /** 서울 2026-10-02 00:00 */
    static final Instant OCT2 = Instant.parse("2026-10-01T15:00:00Z");

    @Autowired
    ComplianceJobs jobs;

    private long org;
    private long other;
    private long admin;
    private long operator;
    private long viewer;
    private long roomA;
    private long roomB;
    private long deviceA;
    private long otherSpace;

    @BeforeEach
    void setUp() {
        org = fx.organization("cmps");
        other = fx.organization("cmps-b");
        admin = fx.user(org, "cmps.admin", "ADMIN");
        operator = fx.user(org, "cmps.operator", "OPERATOR");
        viewer = fx.user(org, "cmps.viewer", "VIEWER");
        long site = data.site(org, "본관");
        roomA = data.space(org, site, "ROOM", "실습실");
        roomB = data.space(org, site, "ROOM", "사무실");
        data.metric(org, "co2", "ppm");
        data.metric(org, "pm2_5", "μg/m³");
        long model = data.model(org, "AM103", List.of("co2", "pm2_5"));
        long source = data.source(org, "cmps-src");
        deviceA = data.device(org, source, "am103-a", "ACTIVE", roomA, model);
        data.device(org, source, "am103-b", "ACTIVE", roomB, model, true);
        otherSpace = data.site(other, "다른 학교");
    }

    /** 1분 집계 n행(값 v, 정상 품질 1건씩) */
    private void minutes(long device, String metric, Instant from, int n, double v) {
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry_1m (device_id, metric_key, bucket, organization_id, count, count_all, avg, min, max,
                               sum, first, last, twa, is_virtual)
                        SELECT :device, :metric, CAST(:from AS timestamptz) + make_interval(mins => g), :org, 1, 1, :v, :v, :v, :v, :v, :v, :v, false
                          FROM generate_series(0, :n - 1) g""")
                .param("device", device).param("metric", metric).param("from", Pg.ts(from)).param("org", org).param("n", n).param("v", v)
                .update();
    }

    private long events(String routingKey) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.outboxes WHERE organization_id = :org AND kind = 'EVENT' AND routing_key = :key""")
                .param("org", org).param("key", routingKey).query(Long.class).single();
    }

    private long systemProfile(String code) {
        return jdbc.sql("SELECT id FROM data2flow_core.compliance_profiles WHERE code = :code").param("code", code).query(Long.class).single();
    }

    private String createProfile() throws Exception {
        String body = """
                {"name":"실습실 기준","version":{"sourceTitle":"내부 지침","effectiveDate":"2026-09-01","streak":{"days":3,"dailyRatio":95},
                 "metrics":[{"metricKey":"co2","evalWindow":"INSTANT","upper":1000,"unit":"ppm","requirement":"LIMIT"}]}}""";
        String content = mvc.perform(as(org, admin, json(post("/core/compliance/profiles"), body))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return content.replaceAll("(?s).*\"profileId\":\"(\\d+)\".*", "$1");
    }

    private void apply(String profileId, long spaceId, String facility) throws Exception {
        String body = "{\"profileId\":\"" + profileId + "\",\"targetType\":\"SPACE\",\"targetSpaceId\":\"" + spaceId + "\""
                + (facility == null ? "" : ",\"facilityType\":\"" + facility + "\"") + "}";
        mvc.perform(as(org, admin, json(post("/core/compliance/applications"), body))).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("[TC-CMP-041·042] 충족 1200·이탈 120·결측 120분 → 90.91%, 이탈 2시간, 이탈 구간 1건, 연속 46일이 깨져 EVT-CMP-05 한 번")
    void dailyStatus() throws Exception {
        String profileId = createProfile();
        apply(profileId, roomA, null);
        minutes(deviceA, "co2", OCT2, 1200, 800);
        minutes(deviceA, "co2", OCT2.plus(Duration.ofMinutes(1200)), 120, 1200);
        jdbc.sql("""
                        INSERT INTO data2flow_core.compliance_streaks (space_id, profile_id, organization_id, current_days, started_on, evaluated_on,
                               updated_at)
                        VALUES (:space, :profile, :org, 46, DATE '2026-08-17', DATE '2026-10-01', now())""")
                .param("space", roomA).param("profile", Long.parseLong(profileId)).param("org", org).update();

        assertThat(jobs.track()).isTrue();
        assertThat(jobs.track()).isTrue();
        assertThat(events("compliance.excursion.started")).isEqualTo(1);
        assertThat(events("compliance.excursion.ended")).isEqualTo(1);
        assertThat(events("compliance.streak.broken")).isEqualTo(1);

        mvc.perform(as(org, viewer, get("/core/compliance/status/spaces/" + roomA + "?profileId=" + profileId + "&from=2026-10-02&to=2026-10-03")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.daily", hasSize(2)))
                .andExpect(jsonPath("$.response.daily[0].day").value("2026-10-02"))
                .andExpect(jsonPath("$.response.daily[0].metMinutes").value(1200))
                .andExpect(jsonPath("$.response.daily[0].exceededMinutes").value(120))
                .andExpect(jsonPath("$.response.daily[0].missingMinutes").value(120))
                .andExpect(jsonPath("$.response.daily[0].ratio").value(90.91))
                .andExpect(jsonPath("$.response.daily[1].missingMinutes").value(540))
                .andExpect(jsonPath("$.response.daily[1].qualityWarning").value(true))
                .andExpect(jsonPath("$.response.excursions", hasSize(1)))
                .andExpect(jsonPath("$.response.excursions[0].metricKey").value("co2"))
                .andExpect(jsonPath("$.response.excursions[0].startedAt").value("2026-10-02T11:00:00Z"))
                .andExpect(jsonPath("$.response.excursions[0].endedAt").value("2026-10-02T13:00:00Z"))
                .andExpect(jsonPath("$.response.excursions[0].peakValue").value(1200))
                .andExpect(jsonPath("$.response.excursions[0].hasAction").value(false));

        mvc.perform(as(org, viewer, get("/core/compliance/status?from=2026-10-02&to=2026-10-02")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.rows", hasSize(1)))
                .andExpect(jsonPath("$.response.rows[0].space.name").value("실습실"))
                .andExpect(jsonPath("$.response.rows[0].profile.name").value("실습실 기준"))
                .andExpect(jsonPath("$.response.rows[0].ratio").value(90.91))
                .andExpect(jsonPath("$.response.rows[0].missingRatio").value(8.33))
                .andExpect(jsonPath("$.response.rows[0].qualityWarning").value(false))
                .andExpect(jsonPath("$.response.rows[0].exceededByMetric[0].metricKey").value("co2"))
                .andExpect(jsonPath("$.response.rows[0].exceededByMetric[0].hours").value(2.0))
                .andExpect(jsonPath("$.response.rows[0].streak.currentDays").value(0))
                .andExpect(jsonPath("$.response.rows[0].streak.requiredDays").value(3))
                .andExpect(jsonPath("$.response.rows[0].streak.brokenOn").value("2026-10-02"))
                .andExpect(jsonPath("$.response.rows[0].streak.brokenMetric").value("co2"))
                .andExpect(jsonPath("$.response.summary.avgRatio").value(90.91))
                .andExpect(jsonPath("$.response.summary.exceededHours").value(2.0))
                .andExpect(jsonPath("$.response.summary.qualityWarnings").value(0));

        // 사무실 범위 OPERATOR에게는 실습실 줄이 없고, 실습실을 고르면 404
        data.spaceScope(org, operator, List.of(roomB));
        mvc.perform(as(org, operator, get("/core/compliance/status?from=2026-10-02&to=2026-10-02")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.rows", hasSize(0)));
        mvc.perform(as(org, operator, get("/core/compliance/status?spaceId=" + roomA + "&from=2026-10-02&to=2026-10-02")))
                .andExpect(status().isNotFound());
        // 401일은 거부, 다른 조직 공간은 404
        mvc.perform(as(org, viewer, get("/core/compliance/status?from=2025-09-01&to=2026-10-06")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("CMP_REPORT_PERIOD_INVALID"));
        mvc.perform(as(org, viewer, get("/core/compliance/status.csv?spaceId=" + otherSpace + "&from=2026-10-02&to=2026-10-02")))
                .andExpect(status().isNotFound());

        byte[] csv = mvc.perform(as(org, viewer, get("/core/compliance/status.csv?from=2026-10-02&to=2026-10-02")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿spaceId,spaceName,spacePath,profileId,profileName,versionNo,ratio,exceededHours,missingRatio,");
        assertThat(text).contains("\"실습실\"").contains("\"90.91\"").contains("\"2.0\"");
    }

    @Test
    @DisplayName("[TC-CMP-032·029] 국내법(일반) 실습실 CO2 1200·PM2.5 10 → 75점, 같은 5분은 한 번만 저장·EVT-CMP-02. 가중치 변경은 baseVersion으로")
    void score() throws Exception {
        apply(Long.toString(systemProfile("KR_IAQ_PUBLIC_FACILITY")), roomA, "GENERAL");
        Instant t0 = MutableClock.T0;
        minutes(deviceA, "co2", t0.minus(Duration.ofMinutes(5)), 5, 1200);
        minutes(deviceA, "pm2_5", t0.minus(Duration.ofMinutes(5)), 5, 10);

        assertThat(jobs.score()).isTrue();
        assertThat(jobs.score()).isTrue();
        assertThat(events("compliance.score.updated")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT score FROM data2flow_core.ieq_scores WHERE space_id = :s").param("s", roomA).query(Integer.class).list())
                .containsExactly(75);
        // 실제 기기가 없는 사무실(가상 기기만)은 점수를 내지 않는다
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.ieq_scores WHERE space_id = :s").param("s", roomB).query(Long.class).single())
                .isZero();

        clock.set(t0.plus(Duration.ofMinutes(5)));
        assertThat(jobs.score()).isTrue();
        assertThat(events("compliance.score.updated")).isEqualTo(2);

        mvc.perform(as(org, viewer, get("/core/compliance/spaces/" + roomA + "/score?include=inputs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.score").value(75))
                .andExpect(jsonPath("$.response.grade").value("MODERATE"))
                .andExpect(jsonPath("$.response.basis").value("PROFILE"))
                .andExpect(jsonPath("$.response.topPenaltyMetric").value("co2"))
                .andExpect(jsonPath("$.response.indices.co2").value(3))
                .andExpect(jsonPath("$.response.indices.pm2_5").value(5))
                .andExpect(jsonPath("$.response.indices.temperature").value(nullValue()))
                .andExpect(jsonPath("$.response.partial").value(true))
                .andExpect(jsonPath("$.response.formulaVersion").value("IEQ-1"))
                .andExpect(jsonPath("$.response.inputs[?(@.metricKey=='co2')].avg5m").value(1200.0));
        mvc.perform(get("/internal/core/compliance/spaces/" + roomA + "/score"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.score").value(75));
        mvc.perform(as(org, viewer, get("/core/compliance/spaces/" + roomA + "/score/history?step=5m&from=2026-10-02T23:00:00Z&to=2026-10-03T01:00:00Z")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.points", hasSize(2)));
        mvc.perform(as(org, viewer, get("/core/compliance/spaces/" + otherSpace + "/score"))).andExpect(status().isNotFound());

        mvc.perform(as(org, viewer, get("/core/compliance/score-formula")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.weights", hasSize(6)))
                .andExpect(jsonPath("$.response.gradeBands[0].grade").value("GOOD"))
                .andExpect(jsonPath("$.response.version").value(0));
        mvc.perform(as(org, operator, json(put("/core/compliance/score-weights"), "{\"weights\":{\"co2\":2},\"baseVersion\":0}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, admin, json(put("/core/compliance/score-weights"), "{\"weights\":{\"co2\":-1},\"baseVersion\":0}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(put("/core/compliance/score-weights"), "{\"weights\":{\"co2\":2},\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, admin, json(put("/core/compliance/score-weights"), "{\"weights\":{\"co2\":3},\"baseVersion\":0}")))
                .andExpect(status().isConflict());
        assertThat(auditCount(org, "CMP_SCORE_WEIGHTS_CHANGED")).isEqualTo(1);
        // CO2 가중치 2: round(100 × (2·0.5 + 1·1) / 3) = 67
        mvc.perform(as(org, viewer, get("/core/compliance/spaces/" + roomA + "/score")))
                .andExpect(jsonPath("$.response.score").value(67));
    }
}
