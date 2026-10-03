package net.java21.data2flow.core.ingest;

import net.java21.data2flow.core.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 데이터 품질(API-ING-13)과 수신 공백(API-ING-15, ING-06.05, AT-ING-09.2) 조회 — TC-ING-079(core 조회 부분) */
class DataQualityIT extends IngestITSupport {

    static final Instant T0 = MutableClock.T0;

    long org;
    long operator;
    long lab;
    long labDevice;
    long officeDevice;

    @BeforeEach
    void setUp() {
        org = fx.organization("ingq");
        operator = fx.user(org, "dq.operator", "OPERATOR");
        long site = data.site(org, "본관");
        lab = data.space(org, site, "ROOM", "실습실");
        long office = data.space(org, site, "ROOM", "교무실");
        long source = data.source(org, "chirp");
        long model = data.model(org, "AM107", List.of());
        labDevice = data.device(org, source, "lab-1", "ACTIVE", lab, model);
        officeDevice = data.device(org, source, "office-1", "ACTIVE", office, model);
    }

    @Test
    @DisplayName("[ING-06.05][AT-ING-09.2] 수신 공백 목록(기기·기간), 공간 범위·다른 조직 404, 기간 90일 — API-ING-15")
    void gaps() throws Exception {
        rows.gap(org, labDevice, T0.minus(Duration.ofHours(5)), T0.minus(Duration.ofHours(2)), 180);
        rows.gap(org, officeDevice, T0.minus(Duration.ofHours(4)), T0.minus(Duration.ofHours(3)), 6);
        rows.gap(org, labDevice, T0.minus(Duration.ofDays(20)), T0.minus(Duration.ofDays(19)), 144);
        mvc.perform(as(org, operator, get("/core/ingest/gaps"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[0].deviceId").value(Long.toString(officeDevice)));
        mvc.perform(as(org, operator, get("/core/ingest/gaps").param("deviceId", Long.toString(labDevice))
                        .param("from", T0.minus(Duration.ofDays(30)).toString())))
                .andExpect(jsonPath("$.totalCount").value(2)).andExpect(jsonPath("$.responses[0].estimatedMissing").value(180))
                .andExpect(jsonPath("$.responses[0].from").value("2026-10-02T19:00:00Z"));
        long scoped = fx.user(org, "lab.op", "OPERATOR");
        data.spaceScope(org, scoped, List.of(lab));
        mvc.perform(as(org, scoped, get("/core/ingest/gaps"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, scoped, get("/core/ingest/gaps").param("deviceId", Long.toString(officeDevice)))).andExpect(status().isNotFound());
        mvc.perform(as(org, operator, get("/core/ingest/gaps").param("from", "2026-01-01T00:00:00Z")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ING_QUERY_RANGE_TOO_LARGE"));
        mvc.perform(as(org, operator, get("/core/ingest/gaps").param("from", T0.toString()).param("to", T0.minusSeconds(1).toString())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[ING-06.01] 데이터 품질: 어제(조직 시간대) 기본, 기기·공간·모델별 묶음, 점수 낮은 순·근거 건수, 공간 범위, ANALYST(ANALYTICS_READ) 허용 — API-ING-13")
    void quality() throws Exception {
        LocalDate yesterday = LocalDate.parse("2026-10-02");
        rows.quality(org, labDevice, yesterday, 92, 144, 140);
        rows.quality(org, officeDevice, yesterday, 61, 144, 90);
        rows.quality(org, officeDevice, LocalDate.parse("2026-10-01"), 10, 144, 10);
        rows.gap(org, officeDevice, Instant.parse("2026-10-02T01:00:00Z"), Instant.parse("2026-10-02T03:00:00Z"), 12);
        mvc.perform(as(org, operator, get("/core/ingest/quality"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[0].targetId").value(Long.toString(officeDevice)))
                .andExpect(jsonPath("$.responses[0].targetName").value("기기 office-1"))
                .andExpect(jsonPath("$.responses[0].score").value(61))
                .andExpect(jsonPath("$.responses[0].gaps").value(1))
                .andExpect(jsonPath("$.responses[0].evidence.expected").value(144))
                .andExpect(jsonPath("$.responses[0].evidence.received").value(90))
                .andExpect(jsonPath("$.responses[1].score").value(92));
        mvc.perform(as(org, operator, get("/core/ingest/quality").param("groupBy", "model"))).andExpect(jsonPath("$.responses", hasSize(1)))
                .andExpect(jsonPath("$.responses[0].targetName").value("AM107"))
                .andExpect(jsonPath("$.responses[0].score").value(77))
                .andExpect(jsonPath("$.responses[0].evidence.received").value(230));
        mvc.perform(as(org, operator, get("/core/ingest/quality").param("groupBy", "space"))).andExpect(jsonPath("$.totalCount").value(2));
        mvc.perform(as(org, operator, get("/core/ingest/quality").param("day", "2026-10-01"))).andExpect(jsonPath("$.responses[0].score").value(10));
        mvc.perform(as(org, operator, get("/core/ingest/quality").param("spaceId", Long.toString(lab))))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].score").value(92));
        long analyst = fx.user(org, "dq.analyst", "ANALYST");
        mvc.perform(as(org, analyst, get("/core/ingest/quality"))).andExpect(status().isOk());
        long scoped = fx.user(org, "lab.analyst", "ANALYST");
        data.spaceScope(org, scoped, List.of(lab));
        mvc.perform(as(org, scoped, get("/core/ingest/quality"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, operator, get("/core/ingest/quality").param("groupBy", "floor"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, operator, get("/core/ingest/quality").param("spaceId", "999999"))).andExpect(status().isNotFound());
        long viewer = fx.user(org, "dq.viewer", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/ingest/quality"))).andExpect(status().isOk());
        mvc.perform(as(org, viewer, get("/core/ingest/gaps"))).andExpect(status().isForbidden());
    }
}
