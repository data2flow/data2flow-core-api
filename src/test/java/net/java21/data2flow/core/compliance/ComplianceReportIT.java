package net.java21.data2flow.core.compliance;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.compliance.domain.ReportHash;
import net.java21.data2flow.core.compliance.domain.ReportModels;
import net.java21.data2flow.core.compliance.service.ComplianceJobs;
import net.java21.data2flow.core.compliance.service.ComplianceReportGenerator;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-02.02·02.04·02.06 자가 점검 리포트 — TC-CMP-045(생성·초안·수치), TC-CMP-046(같은 조건은 판 +1), TC-CMP-049(발행·EVT-CMP-03),
 * TC-CMP-051(해시 검증·변조), TC-CMP-052(범위 밖 404, BR-CMP-23), TC-CMP-053(검증 오류), TC-CMP-054(생성 실패·초안 정리).
 * 시계 T0는 2026-10-03T00:00Z(서울 09:00)라 "어제"는 서울 10월 2일이다.
 */
class ComplianceReportIT extends IntegrationTestSupport {

    /** 서울 2026-10-02 00:00 */
    static final Instant OCT2 = Instant.parse("2026-10-01T15:00:00Z");
    static final String REQUEST = "{\"type\":\"KR_SELF_CHECK\",\"spaceIds\":[\"%d\"],\"periodFrom\":\"%s\",\"periodTo\":\"%s\"}";

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
    void setUp() {
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
        deviceA = data.device(org, source, "am103-a", "ACTIVE", roomA, model);
        data.device(org, source, "am103-b", "ACTIVE", roomB, model, true);
    }

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

    private void applyProfile(long spaceId) throws Exception {
        String body = """
                {"name":"실습실 기준 %d","version":{"sourceTitle":"내부 지침","effectiveDate":"2026-09-01",
                 "metrics":[{"metricKey":"co2","evalWindow":"INSTANT","upper":1000,"unit":"ppm","requirement":"LIMIT"}],
                 "coverage":[{"kind":"CALIBRATION_INTERVAL","params":{"defaultIntervalDays":180}}]}}""".formatted(spaceId);
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

    /** 파일 링크를 받아 내용을 내려받는다 */
    private byte[] download(long user, String reportId, int revisionNo, String kind) throws Exception {
        MvcResult link = mvc.perform(as(org, user, get("/core/compliance/reports/" + reportId + "/revisions/" + revisionNo + "/files/" + kind)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.url").exists()).andReturn();
        String url = field(link, "url").replace("\\u0026", "&").replace("/api/v1", "");
        return mvc.perform(as(org, user, get(url))).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private MvcResult verify(String name, String type, byte[] content) throws Exception {
        return mvc.perform(multipart("/core/compliance/reports/verify-hash").file(new MockMultipartFile("file", name, type, content))
                .header("X-USER-ID", Long.toString(viewer)).header("X-ORG-ID", Long.toString(org))).andReturn();
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    @DisplayName("[TC-CMP-045·046·049·051] 생성 → 초안(워터마크·수치·해시) → 발행(새 해시·EVT-CMP-03) → 해시 검증, 같은 조건은 판 2")
    void generatePublishVerify() throws Exception {
        applyProfile(roomA);
        minutes(deviceA, "co2", OCT2, 1200, 800);
        minutes(deviceA, "co2", OCT2.plus(Duration.ofMinutes(1200)), 120, 1200);
        assertThat(jobs.track()).isTrue();

        String body = REQUEST.formatted(roomA, "2026-10-02", "2026-10-02");
        mvc.perform(as(org, viewer, json(post("/core/compliance/reports"), body))).andExpect(status().isForbidden());
        MvcResult created = mvc.perform(as(org, operator, json(post("/core/compliance/reports"), body)).header("Idempotency-Key", "rep-001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.response.revisionNo").value(1))
                .andExpect(jsonPath("$.response.status").value("GENERATING"))
                .andReturn();
        String reportId = field(created, "reportId");
        // 같은 키는 같은 응답(판이 늘지 않는다)
        mvc.perform(as(org, operator, json(post("/core/compliance/reports"), body)).header("Idempotency-Key", "rep-001"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.reportId").value(reportId))
                .andExpect(jsonPath("$.response.revisionNo").value(1));

        assertThat(generator.runQueued()).isEqualTo(1);
        mvc.perform(as(org, viewer, get("/core/compliance/reports/" + reportId + "/revisions/1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("DRAFT"))
                .andExpect(jsonPath("$.response.progress").value(100))
                .andExpect(jsonPath("$.response.sha256").value(matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$.response.dataRange.rows").value(1320))
                .andExpect(jsonPath("$.response.dataRange.from").value("2026-10-01T15:00:00Z"))
                .andExpect(jsonPath("$.response.dataRange.excludedVirtualDevices").value(0))
                .andExpect(jsonPath("$.response.profileVersions", hasSize(1)))
                .andExpect(jsonPath("$.response.generatedBy.userId").value(Long.toString(operator)));

        byte[] draft = download(viewer, reportId, 1, "pdf");
        String draftSha = ReportHash.verify(draft).orElseThrow();
        String draftText = text(draft);
        assertThat(draftText).contains("초안").contains("실습실").contains(ReportModels.DISCLAIMER).contains("90.91");
        assertThat(download(viewer, reportId, 1, "preview")).isEqualTo(draft);
        mvc.perform(as(org, operator, get("/core/compliance/reports/" + reportId + "/revisions/1/files/csv"))).andExpect(status().isNotFound());

        mvc.perform(as(org, viewer, post("/core/compliance/reports/" + reportId + "/revisions/1/publish"))).andExpect(status().isForbidden());
        mvc.perform(as(org, operator, post("/core/compliance/reports/" + reportId + "/revisions/1/publish")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.response.publishedBy.userId").value(Long.toString(operator)))
                .andExpect(jsonPath("$.response.publishedAt").value("2026-10-03T00:00:00Z"));
        mvc.perform(as(org, operator, post("/core/compliance/reports/" + reportId + "/revisions/1/publish")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("CMP_REPORT_STATE_CONFLICT"));
        assertThat(events("compliance.report.published")).isEqualTo(1);

        byte[] published = download(viewer, reportId, 1, "pdf");
        String publishedSha = ReportHash.verify(published).orElseThrow();
        assertThat(publishedSha).isNotEqualTo(draftSha);
        assertThat(text(published)).doesNotContain("초안");

        MvcResult matched = verify("report.pdf", "application/pdf", published);
        assertThat(matched.getResponse().getStatus()).isEqualTo(200);
        assertThat(matched.getResponse().getContentAsString()).contains("\"matched\":true").contains("\"reportId\":\"" + reportId + "\"")
                .contains("\"revisionNo\":1").contains("\"status\":\"PUBLISHED\"").contains(publishedSha);
        byte[] tampered = published.clone();
        int at = new String(tampered, java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("/Producer");
        tampered[at + 1] = 'p';
        assertThat(verify("report.pdf", "application/pdf", tampered).getResponse().getContentAsString()).contains("\"matched\":false");
        assertThat(verify("note.txt", "text/plain", "hello".getBytes()).getResponse().getStatus()).isEqualTo(400);

        // 같은 조건으로 다시 만들면 같은 리포트의 판 2(늦게 온 데이터 1건)
        minutes(deviceA, "co2", OCT2.plus(Duration.ofMinutes(1400)), 1, 700);
        mvc.perform(as(org, operator, json(post("/core/compliance/reports"), body)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.reportId").value(reportId))
                .andExpect(jsonPath("$.response.revisionNo").value(2));
        assertThat(generator.runQueued()).isEqualTo(1);
        mvc.perform(as(org, viewer, get("/core/compliance/reports/" + reportId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.revisions", hasSize(2)))
                .andExpect(jsonPath("$.response.latest.revisionNo").value(2))
                .andExpect(jsonPath("$.response.latest.dataRange.lateRowsSincePrevious").value(1));
        mvc.perform(as(org, viewer, get("/core/compliance/reports?status=DRAFT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses", hasSize(1)));

        mvc.perform(as(org, operator, delete("/core/compliance/reports/" + reportId + "/revisions/1")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, operator, delete("/core/compliance/reports/" + reportId + "/revisions/2")))
                .andExpect(status().isNoContent());
        assertThat(auditCount(org, "CMP_REPORT_REQUESTED")).isEqualTo(2);
        assertThat(auditCount(org, "CMP_REPORT_PUBLISHED")).isEqualTo(1);
        assertThat(auditCount(org, "CMP_REPORT_DOWNLOADED")).isEqualTo(3);
        assertThat(auditCount(org, "CMP_REPORT_DELETED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[TC-CMP-052·053] 범위 밖 공간이 든 리포트는 404, 기간·유형·적용·가상 기기 검증")
    void scopeAndValidation() throws Exception {
        applyProfile(roomA);
        MvcResult created = mvc.perform(as(org, admin, json(post("/core/compliance/reports"), REQUEST.formatted(roomA, "2026-10-01", "2026-10-02"))))
                .andExpect(status().isAccepted()).andReturn();
        String reportId = field(created, "reportId");

        data.spaceScope(org, operator, List.of(roomB));
        mvc.perform(as(org, operator, get("/core/compliance/reports/" + reportId)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("CMP_REPORT_NOT_FOUND"));
        mvc.perform(as(org, operator, get("/core/compliance/reports"))).andExpect(jsonPath("$.responses", hasSize(0)));
        mvc.perform(as(org, viewer, get("/core/compliance/reports"))).andExpect(jsonPath("$.responses", hasSize(1)));
        mvc.perform(as(org, operator, json(post("/core/compliance/reports"), REQUEST.formatted(roomA, "2026-10-01", "2026-10-02"))))
                .andExpect(status().isNotFound());

        mvc.perform(as(org, admin, json(post("/core/compliance/reports"), REQUEST.formatted(roomA, "2026-10-01", "2026-10-04"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("CMP_REPORT_PERIOD_INVALID"));
        mvc.perform(as(org, admin, json(post("/core/compliance/reports"), REQUEST.formatted(roomA, "2025-08-01", "2026-10-02"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("CMP_REPORT_PERIOD_INVALID"));
        mvc.perform(as(org, admin, json(post("/core/compliance/reports"),
                        REQUEST.formatted(roomA, "2026-10-01", "2026-10-02").replace("KR_SELF_CHECK", "RESET_SUPPORT"))))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(post("/core/compliance/reports"), REQUEST.formatted(roomB, "2026-10-01", "2026-10-02"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("CMP_PROFILE_NOT_APPLIED"));
        applyProfile(roomB);
        mvc.perform(as(org, admin, json(post("/core/compliance/reports"), REQUEST.formatted(roomB, "2026-10-01", "2026-10-02"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("CMP_VIRTUAL_DATA_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("[TC-CMP-054] 생성 중 공간이 사라지면 FAILED와 사유, FAILED는 지울 수 있고 7일 지난 초안은 정리된다")
    void failureAndHousekeeping() throws Exception {
        applyProfile(roomA);
        MvcResult created = mvc.perform(as(org, admin, json(post("/core/compliance/reports"), REQUEST.formatted(roomA, "2026-10-01", "2026-10-02"))))
                .andExpect(status().isAccepted()).andReturn();
        String reportId = field(created, "reportId");
        jdbc.sql("UPDATE data2flow_core.spaces SET status = 'ARCHIVED' WHERE id = :id").param("id", roomA).update();
        assertThat(generator.runQueued()).isEqualTo(1);
        mvc.perform(as(org, admin, get("/core/compliance/reports/" + reportId + "/revisions/1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("FAILED"))
                .andExpect(jsonPath("$.response.failureReason").value("CMP_REPORT_GENERATION_FAILED: SPACE_NOT_FOUND"));
        mvc.perform(as(org, admin, delete("/core/compliance/reports/" + reportId + "/revisions/1"))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get("/core/compliance/reports/" + reportId))).andExpect(status().isNotFound());

        jdbc.sql("UPDATE data2flow_core.spaces SET status = 'ACTIVE' WHERE id = :id").param("id", roomA).update();
        String second = field(mvc.perform(as(org, admin, json(post("/core/compliance/reports"), REQUEST.formatted(roomA, "2026-10-02", "2026-10-02"))))
                .andExpect(status().isAccepted()).andReturn(), "reportId");
        assertThat(generator.runQueued()).isEqualTo(1);
        assertThat(generator.housekeeping()).isZero();
        jdbc.sql("UPDATE data2flow_core.compliance_report_revisions SET updated_at = updated_at - interval '8 days' WHERE organization_id = :org")
                .param("org", org).update();
        assertThat(generator.housekeeping()).isEqualTo(1);
        mvc.perform(as(org, admin, get("/core/compliance/reports/" + second))).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.file_blobs WHERE organization_id = :org AND purpose = 'CMP_REPORT'")
                .param("org", org).query(Long.class).single()).isZero();
    }
}
