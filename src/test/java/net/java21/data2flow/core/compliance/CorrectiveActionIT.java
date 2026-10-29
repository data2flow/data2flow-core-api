package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.compliance.service.ComplianceJobs;
import net.java21.data2flow.core.compliance.service.ComplianceReportGenerator;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.support.MutableClock;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-02.05 개선 조치 — TC-CMP-064(검증·작업 지시 연결), TC-CMP-066(리포트 개선 조치 장), TC-CMP-067(권한·다른 조직 404),
 * AT-CMP-10.2(24시간 뒤 자동 결과). 시계 T0는 2026-10-03T00:00Z(서울 09:00)다.
 * 서울 10월 2일 20:00~22:00(11:00Z~13:00Z)에 CO2 1200ppm 이탈이 있고, 21:00에 조치한다.
 */
class CorrectiveActionIT extends IntegrationTestSupport {

    /** 서울 2026-10-02 00:00 */
    static final Instant OCT2 = Instant.parse("2026-10-01T15:00:00Z");
    static final Instant EXCURSION_START = OCT2.plus(Duration.ofMinutes(1200));
    static final Instant ACTED = OCT2.plus(Duration.ofMinutes(1260));
    static final String ACTION = """
            {"cause":"VENTILATION","actionText":"환기 장치 필터 교체","actedAt":"%s","linkedWorkOrderIds":[%s],"linkedAlarmIds":[%s]}""";

    @Autowired
    ComplianceJobs jobs;
    @Autowired
    ComplianceReportGenerator generator;

    private long org;
    private long admin;
    private long operator;
    private long viewer;
    private long roomA;
    private long roomB;
    private long deviceA;

    @BeforeEach
    void setUp() throws Exception {
        org = fx.organization("cmpa");
        admin = fx.user(org, "cmpa.admin", "ADMIN");
        operator = fx.user(org, "cmpa.operator", "OPERATOR");
        viewer = fx.user(org, "cmpa.viewer", "VIEWER");
        long site = data.site(org, "본관");
        roomA = data.space(org, site, "ROOM", "실습실");
        roomB = data.space(org, site, "ROOM", "사무실");
        data.metric(org, "co2", "ppm");
        long model = data.model(org, "AM103", List.of("co2"));
        long source = data.source(org, "cmpa-src");
        deviceA = data.device(org, source, "am103-a", "ACTIVE", roomA, model);
        applyProfile(roomA);
        minutes(deviceA, OCT2, 1200, 800);
        minutes(deviceA, EXCURSION_START, 120, 1200);
        assertThat(jobs.track()).isTrue();
    }

    private void minutes(long device, Instant from, int n, double v) {
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry_1m (device_id, metric_key, bucket, organization_id, count, count_all, avg, min, max,
                               sum, first, last, twa, is_virtual)
                        SELECT :device, 'co2', CAST(:from AS timestamptz) + make_interval(mins => g), :org, 1, 1, :v, :v, :v, :v, :v, :v, :v, false
                          FROM generate_series(0, :n - 1) g""")
                .param("device", device).param("from", Pg.ts(from)).param("org", org).param("n", n).param("v", v).update();
    }

    private void applyProfile(long spaceId) throws Exception {
        String body = """
                {"name":"실습실 기준","version":{"sourceTitle":"내부 지침","effectiveDate":"2026-09-01",
                 "metrics":[{"metricKey":"co2","evalWindow":"INSTANT","upper":1000,"unit":"ppm","requirement":"LIMIT"}]}}""";
        String content = mvc.perform(as(org, admin, json(post("/core/compliance/profiles"), body))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String profileId = content.replaceAll("(?s).*\"profileId\":\"(\\d+)\".*", "$1");
        mvc.perform(as(org, admin, json(post("/core/compliance/applications"),
                "{\"profileId\":\"" + profileId + "\",\"targetType\":\"SPACE\",\"targetSpaceId\":\"" + spaceId + "\"}")))
                .andExpect(status().isCreated());
    }

    private long excursionId() {
        return jdbc.sql("SELECT id FROM data2flow_core.compliance_excursions WHERE organization_id = :org AND space_id = :space")
                .param("org", org).param("space", roomA).query(Long.class).single();
    }

    private long workOrder(long orgId) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.work_orders (organization_id, title, type, status, priority, origin, created_by, updated_by,
                               created_at, updated_at)
                        VALUES (:org, '필터 교체', 'REPAIR', 'OPEN', 'NORMAL', 'MANUAL', :by, :by, now(), now()) RETURNING id""")
                .param("org", orgId).param("by", admin).query(Long.class).single();
    }

    private long alarm() {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.alarms (organization_id, alarm_key, source_type, severity, title, status, device_id, space_id,
                               raised_at, last_raised_at)
                        VALUES (:org, 'rule:1:x', 'RULE', 'MAJOR', 'CO2 높음', 'ACTIVE', :device, :room, now(), now()) RETURNING id""")
                .param("org", org).param("device", deviceA).param("room", roomA).query(Long.class).single();
    }

    private static String field(MvcResult r, String name) throws Exception {
        return r.getResponse().getContentAsString().replaceAll("(?s).*\"" + name + "\":\"([^\"]+)\".*", "$1");
    }

    private String reportText(String day) throws Exception {
        String request = "{\"type\":\"KR_SELF_CHECK\",\"spaceIds\":[\"%d\"],\"periodFrom\":\"%s\",\"periodTo\":\"%s\"}".formatted(roomA, day, day);
        String reportId = field(mvc.perform(as(org, admin, json(post("/core/compliance/reports"), request))).andExpect(status().isAccepted())
                .andReturn(), "reportId");
        assertThat(generator.runQueued()).isEqualTo(1);
        MvcResult link = mvc.perform(as(org, viewer, get("/core/compliance/reports/" + reportId + "/revisions/1/files/pdf")))
                .andExpect(status().isOk()).andReturn();
        String url = field(link, "url").replace("\\u0026", "&").replace("/api/v1", "");
        byte[] pdf = mvc.perform(as(org, viewer, get(url))).andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    @DisplayName("[TC-CMP-064·066·067] 검증·권한 → 기록(작업 지시·알람 연결) → 24시간 뒤 자동 결과 → 리포트 개선 조치 장 → 수정·삭제")
    void recordEvaluateReport() throws Exception {
        long excursion = excursionId();
        String path = "/core/compliance/excursions/" + excursion + "/actions";
        long order = workOrder(org);
        long alarm = alarm();
        mvc.perform(as(org, viewer, get("/core/compliance/status/spaces/" + roomA + "?from=2026-10-02&to=2026-10-02")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.excursions[0].hasAction").value(false));

        String valid = ACTION.formatted(ACTED, "\"" + order + "\"", "\"" + alarm + "\"");
        mvc.perform(as(org, viewer, json(post(path), valid))).andExpect(status().isForbidden());
        mvc.perform(as(org, operator, json(post(path), ACTION.formatted(EXCURSION_START.minusSeconds(60), "", ""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("CMP_ACTION_INVALID"));
        mvc.perform(as(org, operator, json(post(path), valid.replace("VENTILATION", "OTHER"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("CMP_ACTION_INVALID"));
        mvc.perform(as(org, operator, json(post(path), valid.replace("\"cause\":\"VENTILATION\",", ""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("CMP_ACTION_INVALID"));
        mvc.perform(as(org, operator, json(post(path), ACTION.formatted(ACTED, "\"" + (order + 1000) + "\"", ""))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("WORKORDER_NOT_FOUND"));
        mvc.perform(as(org, operator, json(post(path), ACTION.formatted(ACTED, "", "\"" + (alarm + 1000) + "\""))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("ALARM_NOT_FOUND"));

        long other = fx.organization("cmpa2");
        long otherOperator = fx.user(other, "cmpa2.operator", "OPERATOR");
        mvc.perform(as(other, otherOperator, json(post(path), ACTION.formatted(ACTED, "", ""))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("CMP_EXCURSION_NOT_FOUND"));

        MvcResult created = mvc.perform(as(org, operator, json(post(path), valid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.excursionId").value(Long.toString(excursion)))
                .andExpect(jsonPath("$.response.excursion.spaceId").value(Long.toString(roomA)))
                .andExpect(jsonPath("$.response.excursion.startedAt").value(EXCURSION_START.toString()))
                .andExpect(jsonPath("$.response.actor.userId").value(Long.toString(operator)))
                .andExpect(jsonPath("$.response.linkedWorkOrderIds[0]").value(Long.toString(order)))
                .andExpect(jsonPath("$.response.linkedAlarmIds[0]").value(Long.toString(alarm)))
                .andExpect(jsonPath("$.response.resultMode").value("AUTO"))
                .andExpect(jsonPath("$.response.resultStatus").value("PENDING"))
                .andReturn();
        String actionId = field(created, "actionId");
        mvc.perform(as(org, viewer, get("/core/compliance/status/spaces/" + roomA + "?from=2026-10-02&to=2026-10-02")))
                .andExpect(jsonPath("$.response.excursions[0].hasAction").value(true));
        mvc.perform(as(org, viewer, get("/core/compliance/actions?from=2026-10-02&to=2026-10-03")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses", hasSize(1)))
                .andExpect(jsonPath("$.responses[0].actionId").value(actionId));
        mvc.perform(as(org, viewer, get("/core/compliance/actions?from=2026-10-02&to=2026-10-03&cause=SENSOR")))
                .andExpect(jsonPath("$.responses", hasSize(0)));
        mvc.perform(as(org, viewer, get("/core/compliance/actions?excursionId=" + excursion))).andExpect(jsonPath("$.responses", hasSize(1)));
        mvc.perform(as(org, viewer, get("/core/compliance/excursions/" + excursion)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.spaceName").value("실습실"))
                .andExpect(jsonPath("$.response.metricKey").value("co2")).andExpect(jsonPath("$.response.actions", hasSize(1)));
        mvc.perform(as(other, otherOperator, get("/core/compliance/excursions/" + excursion))).andExpect(status().isNotFound());
        data.spaceScope(org, operator, List.of(roomB));
        mvc.perform(as(org, operator, get("/core/compliance/actions/" + actionId)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("CMP_ACTION_NOT_FOUND"));
        mvc.perform(as(org, operator, get("/core/compliance/actions?from=2026-10-02&to=2026-10-03"))).andExpect(jsonPath("$.responses", hasSize(0)));

        // AT-CMP-10.2: 조치 뒤 60분 이탈, 1380분 충족이면 95.83%. 24시간이 지나야 계산한다
        minutes(deviceA, EXCURSION_START.plus(Duration.ofMinutes(120)), 1380, 700);
        assertThat(jobs.outcomes()).isTrue();
        mvc.perform(as(org, viewer, get("/core/compliance/actions/" + actionId))).andExpect(jsonPath("$.response.resultStatus").value("PENDING"));
        clock.set(MutableClock.T0.plus(Duration.ofDays(1)));
        assertThat(jobs.outcomes()).isTrue();
        mvc.perform(as(org, viewer, get("/core/compliance/actions/" + actionId)))
                .andExpect(jsonPath("$.response.resultStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.response.resultRatio24h").value(95.83));

        assertThat(reportText("2026-10-02")).contains("환기 장치 필터 교체").contains("조치 후 충족률 96%").doesNotContain("기록된 조치 없음");

        // 조치 시각을 바꾸면 다시 대기, 수동으로 바꾸면 메모가 결과다
        mvc.perform(as(org, admin, json(put("/core/compliance/actions/" + actionId), ACTION.formatted(ACTED.plusSeconds(600), "", ""))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.resultStatus").value("PENDING"))
                .andExpect(jsonPath("$.response.linkedWorkOrderIds", hasSize(0)));
        String manual = "{\"cause\":\"OTHER\",\"causeNote\":\"공조 일정\",\"actionText\":\"운영 시간 조정\",\"actedAt\":\"%s\",\"resultMode\":\"MANUAL\"}"
                .formatted(ACTED);
        mvc.perform(as(org, admin, json(put("/core/compliance/actions/" + actionId), manual)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("CMP_ACTION_INVALID"));
        mvc.perform(as(org, admin, json(put("/core/compliance/actions/" + actionId), manual.replace("\"MANUAL\"", "\"MANUAL\",\"resultNote\":\"다음 날 정상\""))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.resultStatus").value("RECORDED"))
                .andExpect(jsonPath("$.response.causeNote").value("공조 일정"));

        mvc.perform(as(org, viewer, delete("/core/compliance/actions/" + actionId))).andExpect(status().isForbidden());
        mvc.perform(as(org, admin, delete("/core/compliance/actions/" + actionId))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get("/core/compliance/actions/" + actionId))).andExpect(status().isNotFound());
        assertThat(auditCount(org, "CMP_ACTION_RECORDED")).isEqualTo(1);
        assertThat(auditCount(org, "CMP_ACTION_UPDATED")).isEqualTo(2);
        assertThat(auditCount(org, "CMP_ACTION_DELETED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[TC-CMP-065] 조치 뒤 24시간 결측이 10%를 넘으면 확인 불가, includeActions=false 리포트에는 조치를 넣지 않는다")
    void unverifiableAndOptOut() throws Exception {
        long excursion = excursionId();
        String actionId = field(mvc.perform(as(org, operator, json(post("/core/compliance/excursions/" + excursion + "/actions"),
                ACTION.formatted(ACTED, "", "")))).andExpect(status().isCreated()).andReturn(), "actionId");
        clock.set(MutableClock.T0.plus(Duration.ofDays(1)));
        assertThat(jobs.outcomes()).isTrue();
        mvc.perform(as(org, viewer, get("/core/compliance/actions/" + actionId)))
                .andExpect(jsonPath("$.response.resultStatus").value("UNVERIFIABLE"))
                .andExpect(jsonPath("$.response.resultRatio24h").doesNotExist());

        String request = """
                {"type":"KR_SELF_CHECK","spaceIds":["%d"],"periodFrom":"2026-10-02","periodTo":"2026-10-02","options":{"includeActions":false}}"""
                .formatted(roomA);
        String reportId = field(mvc.perform(as(org, operator, json(post("/core/compliance/reports"), request))).andExpect(status().isAccepted())
                .andReturn(), "reportId");
        assertThat(generator.runQueued()).isEqualTo(1);
        String model = jdbc.sql("SELECT model::text FROM data2flow_core.compliance_report_revisions WHERE organization_id = :org AND report_id = :id")
                .param("org", org).param("id", Long.parseLong(reportId)).query(String.class).single();
        assertThat(model).doesNotContain("환기 장치 필터 교체");
    }
}
