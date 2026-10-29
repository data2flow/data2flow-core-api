package net.java21.data2flow.core.energy;

import net.java21.data2flow.core.energy.service.EnergyReportGenerator;
import net.java21.data2flow.core.support.AnalyticsItSupport;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ENE-01.05 월간 에너지 리포트(API-ENE-13) — TC-ENE-030: ANALYST 생성(202) → 생성기가 요청자 신원으로 API-ENE-85(기준선 비교)를 불러 모델·PDF를 만든다 →
 * 상세 READY·model, 파일은 PDF(장 순서). VIEWER는 생성 403·조회 200, 미래 달은 400, analytics가 내려가 있으면 다시 시도한다
 */
class EnergyMonthlyReportIT extends AnalyticsItSupport {

    static final String METRICS = "/internal/analytics/energy/metrics";

    @Autowired
    EnergyReportGenerator generator;
    @Autowired
    JsonMapper json;

    private long building;

    @BeforeEach
    void setUp() {
        long site = data.site(org, "광주캠퍼스");
        building = data.space(org, site, "BUILDING", "본관");
        data.space(org, building, "ROOM", "101");
    }

    private String create(String month) throws Exception {
        String res = mvc.perform(as(org, analyst, post("/core/energy/reports")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeSpaceId\":\"" + building + "\",\"month\":\"" + month + "\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.jobId").exists()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).path("response").path("reportId").asString();
    }

    @Test
    @DisplayName("[TC-ENE-030] 생성 → READY, 모델 장 순서·PDF 본문, 요청자 신원으로 analytics 호출")
    void generate() throws Exception {
        STUB.ok("POST", METRICS, 200, """
                {"scope":{"spaceId":%d},"totals":{"kwh":6000.0,"krw":900000.0,"tco2":2.7384},
                 "compare":{"kind":"BASELINE","kwh":null,"reason":"NO_BASELINE"},
                 "series":[],"children":[{"id":1,"name":"101","kind":"ROOM","kwh":5000.0,"share":0.83}],"other":{"kwh":1000.0}}"""
                .formatted(building));
        String id = create("2026-09");
        mvc.perform(as(org, viewer, get("/core/energy/reports/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("GENERATING"));
        mvc.perform(as(org, viewer, get("/core/energy/reports/" + id + "/file"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("ENE_REPORT_NOT_READY"));

        assertThat(generator.runQueued()).isEqualTo(1);
        var sent = STUB.received("POST", METRICS).getLast();
        assertThat(sent.header("X-USER-ID")).isEqualTo(Long.toString(analyst));
        assertThat(sent.header("X-ORG-ID")).isEqualTo(Long.toString(org));
        assertThat(sent.body()).contains("\"from\":\"2026-09-01\"", "\"to\":\"2026-09-30\"", "\"compare\":\"BASELINE\"", "\"granularity\":\"DAY\"");

        mvc.perform(as(org, viewer, get("/core/energy/reports/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("READY"))
                .andExpect(jsonPath("$.response.month").value("2026-09"))
                .andExpect(jsonPath("$.response.scopeName").value("본관"))
                .andExpect(jsonPath("$.response.sha256").isString())
                .andExpect(jsonPath("$.response.model.chapters[0]").value("METRICS"))
                .andExpect(jsonPath("$.response.model.chapters[3]").value("SAVINGS"))
                .andExpect(jsonPath("$.response.model.metrics.tco2").value(2.74))
                .andExpect(jsonPath("$.response.model.baseline.present").value(false))
                .andExpect(jsonPath("$.response.model.topUsage[0].name").value("101"));
        mvc.perform(as(org, viewer, get("/core/energy/reports?scopeSpaceId=" + building))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].reportId").value(id))
                .andExpect(jsonPath("$.responses[0].model").doesNotExist());

        byte[] pdf = mvc.perform(as(org, viewer, get("/core/energy/reports/" + id + "/file"))).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf")).andReturn().getResponse().getContentAsByteArray();
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        }
        assertThat(text).contains("본관 2026-09 에너지 리포트", "6,000kWh");
        assertThat(text.indexOf("에너지 지표")).isLessThan(text.indexOf("기준선 대비"));
        assertThat(text.indexOf("상위 사용 공간")).isLessThan(text.indexOf("절감 조치 결과"));
    }

    @Test
    @DisplayName("[TC-ENE-030] 권한·검증: VIEWER 생성 403, 미래 달·형식 400")
    void guards() throws Exception {
        mvc.perform(as(org, viewer, post("/core/energy/reports")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scopeSpaceId\":\"" + building + "\",\"month\":\"2026-09\"}")).andExpect(status().isForbidden());
        mvc.perform(as(org, analyst, post("/core/energy/reports")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scopeSpaceId\":\"" + building + "\",\"month\":\"2026-12\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("month"));
        mvc.perform(as(org, analyst, post("/core/energy/reports")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"month\":\"2026-09\",\"format\":\"XLSX\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("format"));
        mvc.perform(as(org, viewer, get("/core/energy/reports/999999"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("ENE_REPORT_NOT_FOUND"));
    }

    @Test
    @DisplayName("[TC-ENE-030] analytics가 내려가 있으면 놓아주고 다음 차례에 만든다")
    void retry() throws Exception {
        STUB.fail("POST", METRICS, 503, "SERVICE_UNAVAILABLE", null);
        String id = create("2026-09");
        generator.runQueued();
        mvc.perform(as(org, viewer, get("/core/energy/reports/" + id))).andExpect(jsonPath("$.response.status").value("GENERATING"));

        STUB.ok("POST", METRICS, 200, """
                {"scope":{"spaceId":%d},"totals":{"kwh":10.0},"compare":null,"series":[],"children":[],"other":{"kwh":0}}""".formatted(building));
        clock.set(clock.instant().plusSeconds(60));
        assertThat(generator.runQueued()).isEqualTo(1);
        mvc.perform(as(org, viewer, get("/core/energy/reports/" + id))).andExpect(jsonPath("$.response.status").value("READY"));
    }
}
