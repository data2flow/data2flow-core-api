package net.java21.data2flow.core.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 배포 후 재처리(SCR-03.06, AT-SCR-03.1): 배포 응답의 reprocessSuggestion으로 재처리 작업 생성(API-ING-10 → pipeline API-ING-23 대역) */
class DeployThenReprocessIT extends ScriptItSupport {

    @Test
    @DisplayName("[SCR-03.06][SCR-03.04][AT-SCR-03.1] 배포 응답에 연결 대상·지난 7일 재처리 제안(소스 단위, 기기 연결은 소스별 묶음), 제안 그대로 재처리 작업 생성 — TC-SCR-061")
    void deployThenReprocess() throws Exception {
        long org = fx.organization("dr");
        long integrator = fx.user(org, "dr.integrator", "INTEGRATOR");
        long source = source(org, "dr-src");
        data.metric(org, "temperature", "℃");
        long model = data.model(org, "EM300-TH", List.of("temperature"));
        long d1 = data.device(org, source, "a1", "ACTIVE", null, model);
        long d2 = data.device(org, source, "a2", "ACTIVE", null, model);
        JsonNode created = create(org, integrator, """
                {"name":"보정","kind":"TRANSFORM","bindings":[{"targetType":"MODEL","targetId":"EM300-TH"}]}""");
        String id = created.get("id").asString();
        JsonNode deployed = body(mvc.perform(as(org, integrator, json(post("/core/scripts/" + id + "/deploy"),
                "{\"versionId\":\"" + created.get("draftVersionId").asString() + "\",\"memo\":\"보정 배포\",\"baseActiveVersionId\":null}")))
                .andExpect(status().isOk()).andReturn()).get("response");
        JsonNode suggestion = deployed.get("reprocessSuggestion");
        assertThat(suggestion.get("from").asString()).isEqualTo("2026-09-26T00:00:00Z");
        assertThat(suggestion.get("to").asString()).isEqualTo("2026-10-03T00:00:00Z");
        JsonNode request = suggestion.get("requests").get(0);
        assertThat(request.get("sourceId").asLong()).isEqualTo(source);
        assertThat(request.get("deviceIds")).extracting(JsonNode::asLong).containsExactly(d1, d2);
        assertThat(request.get("memo").asString()).contains("v1");

        // 실행 묶음의 MODEL 연결은 pipeline이 숫자로 맞추도록 모델 ID로 나간다(저장은 모델 코드)
        JsonNode bundle = body(mvc.perform(get("/internal/core/scripts/runtime-bundle?organizationId=" + org)).andReturn()).get("response");
        assertThat(bundle.get("scripts").get(0).get("bindings").get(0).get("targetId").asString()).isEqualTo(Long.toString(model));

        PIPELINE.on("POST", "/internal/pipeline/reprocess-jobs", 202, "{\"jobId\":77,\"status\":\"QUEUED\",\"estimatedCount\":1440}");
        mvc.perform(as(org, integrator, json(post("/core/ingest/reprocess-jobs"), request.toString())).header("Idempotency-Key", "dr-1"))
                .andExpect(status().isAccepted());
        JsonNode sent = JSON.readTree(PIPELINE.receivedContaining("/internal/pipeline/reprocess-jobs").getFirst().body());
        assertThat(sent.get("sourceId").asLong()).isEqualTo(source);
        assertThat(sent.get("deviceIds")).hasSize(2);
        assertThat(sent.get("from").asString()).isEqualTo("2026-09-26T00:00:00Z");
    }
}
