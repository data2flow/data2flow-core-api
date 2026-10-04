package net.java21.data2flow.core.script;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 수식 파생 측정 항목(SCR-01.06, AT-SCR-06.1·06.3, API-SCR-20·21, pipeline API-SCR-37·38 대역) */
class FormulaMetricIT extends ScriptItSupport {

    static final String COMPILED = "{\"ok\":true,\"compiledJs\":\"U.thi(__m(\\\"temperature\\\"), __m(\\\"humidity\\\"))\","
            + "\"inputs\":[\"temperature\",\"humidity\"],\"windows\":{}}";

    private long org;
    private long integrator;
    private long model;
    private long device;

    @BeforeEach
    void setUp() {
        org = fx.organization("fm");
        integrator = fx.user(org, "fm.integrator", "INTEGRATOR");
        data.metric(org, "temperature", "℃");
        data.metric(org, "humidity", "%");
        model = data.model(org, "EM300-TH", List.of("temperature", "humidity"));
        long src = source(org, "fm-src");
        device = data.device(org, src, "24e124136d151547", "ACTIVE", null, model);
        PIPELINE.on("POST", "/internal/pipeline/formula-metrics/compile", 200, COMPILED);
    }

    private String formula(String key, String target) {
        return "{\"resultKey\":\"" + key + "\",\"displayName\":\"불쾌지수\",\"expression\":\"thi(temperature, humidity)\",\"targetType\":\"MODEL\","
                + "\"targetId\":\"" + target + "\"}";
    }

    @Test
    @DisplayName("[SCR-01.06][AT-SCR-06.1] 수식 저장: pipeline 컴파일 → 201, 결과 키를 파생 측정 항목으로 등록, 모델 코드는 ID로, 실행 묶음 formulaMetrics(expression·compiledJs)·EVT-SCR-01 — TC-SCR-018(core)")
    void createAndBundle() throws Exception {
        JsonNode created = body(mvc.perform(as(org, integrator, json(post("/core/formula-metrics"), formula("thi", "EM300-TH"))))
                .andExpect(status().isCreated()).andReturn()).get("response");
        assertThat(created.get("targetId").asString()).isEqualTo(Long.toString(model));
        assertThat(created.get("targetName").asString()).isNotBlank();
        assertThat(created.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(JSON.readTree(PIPELINE.receivedContaining("/formula-metrics/compile").getFirst().body()).get("expression").asString())
                .isEqualTo("thi(temperature, humidity)");
        assertThat(jdbc.sql("SELECT semantic FROM data2flow_core.metrics WHERE organization_id = :o AND key = 'thi'").param("o", org)
                .query(String.class).single()).isEqualTo("DERIVED");
        JsonNode bundle = body(mvc.perform(get("/internal/core/scripts/runtime-bundle?organizationId=" + org)).andReturn()).get("response");
        JsonNode f = bundle.get("formulaMetrics").get(0);
        assertThat(f.get("resultKey").asString()).isEqualTo("thi");
        assertThat(f.get("expression").asString()).isEqualTo("thi(temperature, humidity)");
        assertThat(f.get("compiledJs").asString()).startsWith("U.thi(");
        assertThat(f.get("targetType").asString()).isEqualTo("MODEL");
        assertThat(f.get("targetId").asString()).isEqualTo(Long.toString(model));
        assertThat(outboxConfigCount(org)).isGreaterThanOrEqualTo(2); // METRIC + SCRIPT
        assertThat(auditCount(org, "FORMULA_METRIC_CHANGED")).isEqualTo(1);
        mvc.perform(as(org, integrator, get("/core/formula-metrics").param("targetType", "MODEL").param("targetId", "EM300-TH")))
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("[SCR-01.06][AT-SCR-06.3] 결과 키가 이미 있는 측정 항목이면 409 SCRIPT_FORMULA_KEY_CONFLICT, 오타는 400 SCRIPT_FORMULA_INVALID(줄·열), 금지 API가 든 컴파일 결과 거부 — TC-SCR-017")
    void conflictsAndErrors() throws Exception {
        mvc.perform(as(org, integrator, json(post("/core/formula-metrics"), formula("temperature", "EM300-TH"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_FORMULA_KEY_CONFLICT"));
        mvc.perform(as(org, integrator, json(post("/core/formula-metrics"), formula("thi", "EM300-TH")))).andExpect(status().isCreated());
        mvc.perform(as(org, integrator, json(post("/core/formula-metrics"), formula("thi", "EM300-TH"))))
                .andExpect(status().isConflict());
        PIPELINE.on("POST", "/internal/pipeline/formula-metrics/compile", 200,
                "{\"ok\":false,\"error\":{\"code\":\"SCRIPT_FORMULA_INVALID\",\"line\":1,\"col\":1,\"message\":\"알 수 없는 측정 항목: temprature\"}}");
        mvc.perform(as(org, integrator, json(post("/core/formula-metrics"), formula("thi2", "EM300-TH").replace("thi(temperature, humidity)",
                        "temprature * 2"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_FORMULA_INVALID"))
                .andExpect(jsonPath("$.response.line").value(1)).andExpect(jsonPath("$.response.message").value("알 수 없는 측정 항목: temprature"));
        PIPELINE.on("POST", "/internal/pipeline/formula-metrics/compile", 200, "{\"ok\":true,\"compiledJs\":\"fetch('x')\"}");
        mvc.perform(as(org, integrator, json(post("/core/formula-metrics"), formula("thi3", "EM300-TH"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SCRIPT_FORBIDDEN_API"));
        mvc.perform(as(org, integrator, json(post("/core/formula-metrics"), formula("bad key", "EM300-TH")))).andExpect(status().isBadRequest());
        mvc.perform(as(org, integrator, json(post("/core/formula-metrics"), formula("thi4", "NOPE")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("targetId"));
    }

    @Test
    @DisplayName("[SCR-01.06] 수정은 baseVersion(다르면 409 VERSION_CONFLICT), 비활성 전환, 삭제 204(측정 항목은 남김), 미리 보기는 대상 기기로 pipeline API-SCR-38")
    void updatePreviewDelete() throws Exception {
        String id = body(mvc.perform(as(org, integrator, json(post("/core/formula-metrics"), formula("thi", Long.toString(model)))))
                .andReturn()).get("response").get("id").asString();
        mvc.perform(as(org, integrator, json(put("/core/formula-metrics/" + id), "{\"status\":\"DISABLED\",\"baseVersion\":3}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        mvc.perform(as(org, integrator, json(put("/core/formula-metrics/" + id), "{\"status\":\"DISABLED\",\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("DISABLED"))
                .andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, integrator, json(put("/core/formula-metrics/" + id), "{\"status\":\"DISABLED\"}"))).andExpect(status().isBadRequest());
        PIPELINE.on("POST", "/internal/pipeline/formula-metrics/preview", 200,
                "{\"series\":[{\"t\":\"2026-10-02T23:00:00Z\",\"value\":71.2}],\"inputs\":{\"temperature\":[{\"t\":\"2026-10-02T23:00:00Z\",\"value\":24.1}]}}");
        mvc.perform(as(org, integrator, json(post("/core/formula-metrics/preview"),
                        "{\"expression\":\"thi(temperature, humidity)\",\"targetType\":\"MODEL\",\"targetId\":\"" + model + "\",\"hours\":6}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.series[0].value").value(71.2));
        JsonNode sent = JSON.readTree(PIPELINE.receivedContaining("/formula-metrics/preview").getFirst().body());
        assertThat(sent.get("deviceId").asLong()).isEqualTo(device);
        assertThat(sent.get("hours").asInt()).isEqualTo(6);
        PIPELINE.on("POST", "/internal/pipeline/formula-metrics/preview", 400, "SCRIPT_FORMULA_INVALID");
        mvc.perform(as(org, integrator, json(post("/core/formula-metrics/preview"),
                        "{\"expression\":\"x +\",\"targetType\":\"DEVICE\",\"targetId\":\"" + device + "\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_FORMULA_INVALID"));
        mvc.perform(as(org, integrator, json(post("/core/formula-metrics/preview"),
                        "{\"expression\":\"x\",\"targetType\":\"DEVICE\",\"targetId\":\"" + device + "\",\"hours\":30}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, integrator, delete("/core/formula-metrics/" + id))).andExpect(status().isNoContent());
        mvc.perform(as(org, integrator, delete("/core/formula-metrics/" + id))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SCRIPT_FORMULA_NOT_FOUND"));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.metrics WHERE organization_id = :o AND key = 'thi'").param("o", org)
                .query(Long.class).single()).isEqualTo(1);
        // 같은 키로 다시 만들 수 있다(수식이 만든 파생 항목)
        mvc.perform(as(org, integrator, json(post("/core/formula-metrics"), formula("thi", Long.toString(model))))).andExpect(status().isCreated());
        long operator = fx.user(org, "fm.operator", "OPERATOR");
        mvc.perform(as(org, operator, get("/core/formula-metrics"))).andExpect(status().isOk());
        mvc.perform(as(org, operator, json(post("/core/formula-metrics"), formula("x1", Long.toString(model))))).andExpect(status().isForbidden());
    }
}
