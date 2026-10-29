package net.java21.data2flow.core.energy;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.EnergyBaselineTrained;
import net.java21.data2flow.contracts.message.event.EnergyBaselineTrained.Status;
import net.java21.data2flow.core.energy.event.EnergyBaselineEventHandler;
import net.java21.data2flow.core.support.AnalyticsItSupport;
import net.java21.data2flow.core.support.StubHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ENE-01.04 기준선(API-ENE-12) — TC-ENE-026: 학습 요청 → analytics API-ENE-81(권한 범위 트리·가상 공간 표시·기온 원천) → EVT-ENE-02 → 적용(이전 ACTIVE는
 * RETIRED) → 지표의 기준선 비교가 활성 버전 모델 키를 넘긴다. TC-ENE-027의 권한(VIEWER 403)과 409 오류도 실제 역할로 확인한다
 */
class BaselineTrainingIT extends AnalyticsItSupport {

    static final String TRAIN = "/internal/analytics/energy/baselines/train";
    static final String METRICS = "/internal/analytics/energy/metrics";

    @Autowired
    EnergyBaselineEventHandler handler;
    @Autowired
    JsonMapper json;

    private long building;
    private long sim;
    private long thermo;
    private int jobs;

    @BeforeEach
    void setUp() {
        long site = data.site(org, "광주캠퍼스");
        building = data.space(org, site, "BUILDING", "본관");
        data.space(org, building, "ROOM", "101");
        sim = data.space(org, building, "ROOM", "가상실");
        jdbc.sql("UPDATE data2flow_core.spaces SET is_virtual = true WHERE id = :id").param("id", sim).update();
        data.metric(org, "temperature", "°C");
        long model = data.model(org, "SENSOR", List.of("temperature"));
        thermo = data.device(org, data.source(org, "weather"), "outdoor-1", "ACTIVE", site, model);
        jobs = 0;
        STUB.on("POST", TRAIN, r -> {
            JsonNode body = json.readTree(r.body());
            jobs++;
            String v = body.path("versionId").asString();
            return new StubHttpServer.Reply(202, StubHttpServer.success("{\"jobId\":\"" + (900 + jobs) + "\",\"versionId\":\"" + v
                    + "\",\"modelKey\":\"bl-" + v + "\"}"), Map.of());
        });
    }

    private String body(String method, String from, String to, boolean temperature) {
        return """
                {"scopeSpaceId":"%d","method":"%s","trainFrom":"%s","trainTo":"%s","params":{"splitWeekday":true}%s}"""
                .formatted(building, method, from, to, temperature ? ",\"temperatureSource\":{\"deviceId\":\"" + thermo + "\"}" : "");
    }

    private String create(String method, boolean temperature) throws Exception {
        String res = mvc.perform(as(org, analyst, post("/core/energy/baselines/versions")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(method, "2026-07-01", "2026-09-30", temperature)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.jobId").exists()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).path("response").path("versionId").asString();
    }

    private void trained(String versionId, Status st, Double r2, String error, Integer needed) {
        handler.handle(DomainEvent.of(EventType.ENERGY_BASELINE_TRAINED, org, new EnergyBaselineTrained(Long.parseLong(versionId), st, r2,
                st == Status.READY ? Map.of("intercept", 50.0, "cdd", 8.0, "hdd", 0.0, "weekend", 0.0) : null,
                st == Status.READY ? "bl-" + versionId : null, null, error, needed, st == Status.READY ? 92 : null), "req-1", clock));
    }

    @Test
    @DisplayName("[TC-ENE-026] 학습 요청 → EVT-ENE-02 READY → 적용, 새 버전 적용 시 이전 ACTIVE는 RETIRED, 지표 비교에 모델 키")
    void trainAndActivate() throws Exception {
        String v1 = create("DEGREE_DAY_REGRESSION", true);
        String sent = STUB.received("POST", TRAIN).getLast().body();
        assertThat(sent).contains("\"versionId\":" + v1, "\"method\":\"DEGREE_DAY_REGRESSION\"", "\"excludeVirtual\":true",
                "\"temperatureSource\":{\"deviceId\":" + thermo + ",\"metricKey\":\"temperature\"}", "\"timezone\":\"Asia/Seoul\"",
                "\"id\":" + sim + ",", "\"virtual\":true", "\"coolingBaseC\":24.0");
        String baselineId = jdbc.sql("SELECT baseline_id FROM data2flow_core.energy_baseline_versions WHERE id = :id").param("id", Long.parseLong(v1))
                .query(Long.class).single().toString();

        mvc.perform(as(org, analyst, post("/core/energy/baselines/versions/" + v1 + "/activate"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("ENE_BASELINE_NOT_READY"));
        trained(v1, Status.READY, 0.9234, null, null);
        trained(v1, Status.FAILED, null, "INTERNAL_ERROR", null);  // 늦은 중복은 무시
        mvc.perform(as(org, viewer, get("/core/energy/baselines/" + baselineId + "/versions"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].status").value("READY")).andExpect(jsonPath("$.responses[0].r2").value(0.923));
        mvc.perform(as(org, analyst, post("/core/energy/baselines/versions/" + v1 + "/activate"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("ACTIVE"));

        String v2 = create("PERIOD_AVERAGE", false);
        trained(v2, Status.READY, 0.5, null, null);
        mvc.perform(as(org, analyst, post("/core/energy/baselines/versions/" + v2 + "/activate"))).andExpect(status().isOk());
        mvc.perform(as(org, viewer, get("/core/energy/baselines/" + baselineId + "/versions"))).andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[0].status").value("ACTIVE")).andExpect(jsonPath("$.responses[1].status").value("RETIRED"));
        mvc.perform(as(org, viewer, get("/core/energy/baselines?scopeSpaceId=" + building))).andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].baselineId").value(baselineId))
                .andExpect(jsonPath("$.responses[0].scopeSpace.name").value("본관"))
                .andExpect(jsonPath("$.responses[0].activeVersion.versionId").value(v2))
                .andExpect(jsonPath("$.responses[0].activeVersion.method").value("PERIOD_AVERAGE"));

        STUB.ok("GET", "/internal/analytics/energy/baselines/bl-" + v2, 200,
                "{\"key\":\"bl-" + v2 + "\",\"residuals\":[{\"date\":\"2026-07-01\",\"actual\":51.0,\"predicted\":50.0}]}");
        mvc.perform(as(org, viewer, get("/core/energy/baselines/" + baselineId + "/versions/" + v2))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.params.splitWeekday").value(true))
                .andExpect(jsonPath("$.response.coefficients.cdd").value(8.0))
                .andExpect(jsonPath("$.response.residuals[0].actual").value(51.0))
                .andExpect(jsonPath("$.response.createdBy.userId").value(Long.toString(analyst)));

        STUB.ok("POST", METRICS, 200, """
                {"scope":{"spaceId":%d},"totals":{"kwh":900.0},"compare":{"kind":"BASELINE","kwh":1000.0,"diffRatio":-0.1,"baselineKey":"bl-%s"},
                 "series":[],"children":[],"other":{"kwh":0}}""".formatted(building, v2));
        mvc.perform(as(org, viewer, get("/core/energy/metrics?scopeSpaceId=" + building + "&from=2026-10-01&to=2026-10-02&compare=BASELINE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.compare.baselineVersionId").value(v2))
                .andExpect(jsonPath("$.response.compare.baselineKey").doesNotExist());
        assertThat(STUB.received("POST", METRICS).getLast().body()).contains("\"baselineKey\":\"bl-" + v2 + "\"");
    }

    @Test
    @DisplayName("[TC-ENE-026] EVT-ENE-02 FAILED는 사유·neededDays를 남기고 적용할 수 없다")
    void failed() throws Exception {
        String v = create("DEGREE_DAY_REGRESSION", true);
        trained(v, Status.FAILED, null, "INSUFFICIENT_DATA", 12);
        Long baselineId = jdbc.sql("SELECT baseline_id FROM data2flow_core.energy_baseline_versions WHERE id = :id").param("id", Long.parseLong(v))
                .query(Long.class).single();
        mvc.perform(as(org, viewer, get("/core/energy/baselines/" + baselineId + "/versions/" + v))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("FAILED")).andExpect(jsonPath("$.response.errorCode").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.response.neededDays").value(12)).andExpect(jsonPath("$.response.residuals.length()").value(0));
        mvc.perform(as(org, analyst, post("/core/energy/baselines/versions/" + v + "/activate"))).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("[TC-ENE-025·027] 25일이면 409 neededDays 5, 회귀에 기온 원천이 없으면 400, VIEWER 학습·적용 403, analytics 장애면 버전을 남기지 않는다")
    void guards() throws Exception {
        mvc.perform(as(org, analyst, post("/core/energy/baselines/versions")).contentType(MediaType.APPLICATION_JSON)
                        .content(body("PERIOD_AVERAGE", "2026-09-01", "2026-09-25", false)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("ENE_BASELINE_INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.errors[0].field").value("neededDays")).andExpect(jsonPath("$.errors[0].message").value("5"));
        mvc.perform(as(org, analyst, post("/core/energy/baselines/versions")).contentType(MediaType.APPLICATION_JSON)
                        .content(body("DEGREE_DAY_REGRESSION", "2026-07-01", "2026-09-30", false)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("temperatureSource"));
        mvc.perform(as(org, viewer, post("/core/energy/baselines/versions")).contentType(MediaType.APPLICATION_JSON)
                .content(body("PERIOD_AVERAGE", "2026-07-01", "2026-09-30", false))).andExpect(status().isForbidden());
        mvc.perform(as(org, viewer, post("/core/energy/baselines/versions/1/activate"))).andExpect(status().isForbidden());
        assertThat(jobs).isZero();

        STUB.fail("POST", TRAIN, 500, "INTERNAL_ERROR", null);
        mvc.perform(as(org, analyst, post("/core/energy/baselines/versions")).contentType(MediaType.APPLICATION_JSON)
                .content(body("PERIOD_AVERAGE", "2026-07-01", "2026-09-30", false))).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.energy_baseline_versions WHERE organization_id = :org").param("org", org)
                .query(Long.class).single()).isZero();
        mvc.perform(as(org, viewer, get("/core/energy/baselines/999999/versions"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("ENE_BASELINE_NOT_FOUND"));
    }
}
