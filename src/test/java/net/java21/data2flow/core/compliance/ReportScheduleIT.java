package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.compliance.service.ComplianceJobs;
import net.java21.data2flow.core.compliance.service.ComplianceReportGenerator;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-02.04 정기 발행 — TC-CMP-059(검증), TC-CMP-060(매월 1일 09:00 KST 실행 → r1 발행·알림·EVT-CMP-03, 한 번만), TC-CMP-062(권한·범위).
 * 시계 T0는 2026-10-03T00:00Z(서울 09:00)다.
 */
class ReportScheduleIT extends IntegrationTestSupport {

    static final Instant NOV1 = Instant.parse("2026-11-01T00:00:00Z");
    static final String PATH = "/core/compliance/report-schedules";
    static final String SCHEDULE = """
            {"name":"월간 자가 점검","reportTemplate":{"type":"KR_SELF_CHECK","spaceIds":["%d"]},"cadence":"MONTHLY","dayOfPeriod":%d,
             "publishTime":"09:00","periodRule":"PREVIOUS_MONTH","recipients":{"userIds":[%s],"emails":[%s]},"format":"PDF","enabled":true}""";

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

    @BeforeEach
    void setUp() throws Exception {
        org = fx.organization("cmpr");
        admin = fx.user(org, "cmpr.admin", "ADMIN");
        operator = fx.user(org, "cmpr.operator", "OPERATOR");
        viewer = fx.user(org, "cmpr.viewer", "VIEWER");
        long site = data.site(org, "본관");
        roomA = data.space(org, site, "ROOM", "실습실");
        roomB = data.space(org, site, "ROOM", "사무실");
        data.metric(org, "co2", "ppm");
        long model = data.model(org, "AM103", List.of("co2"));
        long source = data.source(org, "cmpr-src");
        long device = data.device(org, source, "am103-r", "ACTIVE", roomA, model);
        applyProfile(roomA);
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry_1m (device_id, metric_key, bucket, organization_id, count, count_all, avg, min, max,
                               sum, first, last, twa, is_virtual)
                        SELECT :device, 'co2', CAST(:from AS timestamptz) + make_interval(mins => g), :org, 1, 1, 800, 800, 800, 800, 800, 800, 800, false
                          FROM generate_series(0, 1439) g""")
                .param("device", device).param("from", Pg.ts(Instant.parse("2026-10-01T15:00:00Z"))).param("org", org).update();
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

    private static String field(MvcResult r, String name) throws Exception {
        return r.getResponse().getContentAsString().replaceAll("(?s).*\"" + name + "\":\"([^\"]+)\".*", "$1");
    }

    private String schedule(long space, int day, String users, String emails) {
        return SCHEDULE.formatted(space, day, users, emails);
    }

    private long outbox(String kind, String routingKey) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE organization_id = :org AND kind = :kind AND routing_key = :key")
                .param("org", org).param("kind", kind).param("key", routingKey).query(Long.class).single();
    }

    private String outboxPayload(String kind, String routingKey) {
        return jdbc.sql("SELECT payload::text FROM data2flow_core.outboxes WHERE organization_id = :org AND kind = :kind AND routing_key = :key")
                .param("org", org).param("kind", kind).param("key", routingKey).query(String.class).single().replace("\": ", "\":").replace(", ", ",");
    }

    @Test
    @DisplayName("[TC-CMP-059·062] 검증(실행일·수신자·이메일·시각), 권한(VIEWER 403), 범위 밖 공간 404, 버전 충돌")
    void validateAndAuthorize() throws Exception {
        String users = "\"" + operator + "\",\"" + viewer + "\"";
        for (String bad : List.of(schedule(roomA, 32, users, ""), schedule(roomA, 0, users, ""), schedule(roomA, 1, "", ""),
                schedule(roomA, 1, "", "\"not-an-email\""), schedule(roomA, 1, "\"999999\"", ""),
                schedule(roomA, 1, users, "").replace("09:00", "9시"), schedule(roomA, 1, users, "").replace("MONTHLY", "WEEKLY"))) {
            mvc.perform(as(org, operator, json(post(PATH), bad)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("CMP_SCHEDULE_INVALID"));
        }
        String valid = schedule(roomA, 1, users, "\"Audit@Example.com\"");
        mvc.perform(as(org, viewer, json(post(PATH), valid))).andExpect(status().isForbidden());
        MvcResult created = mvc.perform(as(org, operator, json(post(PATH), valid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.nextRunAt").value(NOV1.toString()))
                .andExpect(jsonPath("$.response.recipients.emails[0]").value("audit@example.com"))
                .andExpect(jsonPath("$.response.version").value(0))
                .andReturn();
        String id = field(created, "reportScheduleId");
        mvc.perform(as(org, viewer, get(PATH))).andExpect(status().isOk()).andExpect(jsonPath("$.responses", hasSize(1)));
        mvc.perform(as(org, viewer, post(PATH + "/" + id + "/run-now"))).andExpect(status().isForbidden());

        // 끄면 다음 실행이 없고, 옛 버전으로 고치면 충돌
        String off = valid.replace("\"enabled\":true", "\"enabled\":false,\"version\":0");
        mvc.perform(as(org, operator, json(put(PATH + "/" + id), off)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.nextRunAt").doesNotExist())
                .andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, operator, json(put(PATH + "/" + id), off))).andExpect(status().isConflict());

        long other = fx.organization("cmpr2");
        long otherAdmin = fx.user(other, "cmpr2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get(PATH + "/" + id))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("CMP_SCHEDULE_NOT_FOUND"));
        data.spaceScope(org, operator, List.of(roomB));
        mvc.perform(as(org, operator, get(PATH + "/" + id))).andExpect(status().isNotFound());
        mvc.perform(as(org, operator, get(PATH))).andExpect(jsonPath("$.responses", hasSize(0)));
        mvc.perform(as(org, operator, json(post(PATH), valid))).andExpect(status().isNotFound());

        mvc.perform(as(org, admin, delete(PATH + "/" + id))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get(PATH + "/" + id))).andExpect(status().isNotFound());
        assertThat(auditCount(org, "CMP_SCHEDULE_CREATED")).isEqualTo(1);
        assertThat(auditCount(org, "CMP_SCHEDULE_UPDATED")).isEqualTo(1);
        assertThat(auditCount(org, "CMP_SCHEDULE_DELETED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[TC-CMP-060] 11월 1일 09:00 KST가 되면 10월 리포트 r1을 만들어 발행하고 수신자 2명에게 알리며 EVT-CMP-03을 낸다(한 번만)")
    void runMonthly() throws Exception {
        String users = "\"" + operator + "\",\"" + viewer + "\"";
        String id = field(mvc.perform(as(org, operator, json(post(PATH), schedule(roomA, 1, users, "\"audit@example.com\""))))
                .andExpect(status().isCreated()).andReturn(), "reportScheduleId");

        assertThat(jobs.schedules()).isTrue();
        mvc.perform(as(org, viewer, get(PATH + "/" + id + "/runs"))).andExpect(jsonPath("$.responses", hasSize(0)));

        clock.set(NOV1);
        assertThat(jobs.schedules()).isTrue();
        assertThat(jobs.schedules()).isTrue();
        mvc.perform(as(org, viewer, get(PATH + "/" + id + "/runs")))
                .andExpect(jsonPath("$.responses", hasSize(1)))
                .andExpect(jsonPath("$.responses[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.responses[0].trigger").value("SCHEDULED"))
                .andExpect(jsonPath("$.responses[0].scheduledAt").value(NOV1.toString()))
                .andExpect(jsonPath("$.responses[0].revisionNo").value(1));

        assertThat(generator.runQueued()).isEqualTo(1);
        assertThat(jobs.schedules()).isTrue();
        MvcResult runs = mvc.perform(as(org, viewer, get(PATH + "/" + id + "/runs")))
                .andExpect(jsonPath("$.responses", hasSize(1)))
                .andExpect(jsonPath("$.responses[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.responses[0].attempts").value(1))
                .andExpect(jsonPath("$.responses[0].deliveries", hasSize(3)))
                .andExpect(jsonPath("$.responses[0].deliveries[0].recipient").value("user:" + operator))
                .andExpect(jsonPath("$.responses[0].deliveries[0].status").value("REQUESTED"))
                .andExpect(jsonPath("$.responses[0].deliveries[2].status").value("UNSUPPORTED"))
                .andReturn();
        String reportId = field(runs, "reportId");
        mvc.perform(as(org, viewer, get("/core/compliance/reports/" + reportId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.periodFrom").value("2026-10-01"))
                .andExpect(jsonPath("$.response.periodTo").value("2026-10-31"));
        mvc.perform(as(org, viewer, get("/core/compliance/reports/" + reportId + "/revisions/1")))
                .andExpect(jsonPath("$.response.status").value("PUBLISHED"));
        mvc.perform(as(org, viewer, get(PATH + "/" + id)))
                .andExpect(jsonPath("$.response.nextRunAt").value("2026-12-01T00:00:00Z"))
                .andExpect(jsonPath("$.response.lastRun.status").value("SUCCEEDED"));

        assertThat(outbox("NOTIFY", "notify")).isEqualTo(1);
        String notify = outboxPayload("NOTIFY", "notify");
        assertThat(notify).contains("\"id\":\"" + operator + "\"").contains("\"id\":\"" + viewer + "\"").contains("flow.notify")
                .contains("/compliance/reports/" + reportId + "?rev=1");
        String event = outboxPayload("EVENT", "compliance.report.published");
        assertThat(event).contains("\"userIds\":[" + operator + "," + viewer + "]").contains("audit@example.com");
        assertThat(auditCount(org, "CMP_REPORT_PUBLISHED")).isEqualTo(1);

        // 다시 돌아도 회차는 그대로, 지금 실행은 새 회차(MANUAL)
        assertThat(jobs.schedules()).isTrue();
        clock.set(NOV1.plusSeconds(3600));
        mvc.perform(as(org, operator, post(PATH + "/" + id + "/run-now")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.reportScheduleId").value(id));
        mvc.perform(as(org, viewer, get(PATH + "/" + id + "/runs")))
                .andExpect(jsonPath("$.responses", hasSize(2)))
                .andExpect(jsonPath("$.responses[0].trigger").value("MANUAL"))
                .andExpect(jsonPath("$.responses[0].status").value("PENDING"));
        assertThat(jobs.schedules()).isTrue();
        assertThat(generator.runQueued()).isEqualTo(1);
        assertThat(jobs.schedules()).isTrue();
        mvc.perform(as(org, viewer, get(PATH + "/" + id + "/runs")))
                .andExpect(jsonPath("$.responses[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.responses[0].revisionNo").value(2));
        assertThat(outbox("NOTIFY", "notify")).isEqualTo(2);
    }
}
