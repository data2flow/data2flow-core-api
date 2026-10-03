package net.java21.data2flow.core.script;

import net.java21.data2flow.contracts.message.ConfigChangedMessage;
import net.java21.data2flow.contracts.message.MessageCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배포 반영 경로(core 쪽): SCR-03.04 EVT-SCR-01·실행 묶음(API-SCR-32)·적용 보고(API-SCR-34), SCR-02.04 자동 비활성(API-SCR-33)과
 * 사람의 해제(API-SCR-17) — TC-SCR-051·054의 core 계약, TC-SCR-036의 API-SCR-33 수신 쪽.
 */
class ScriptRuntimeIT extends ScriptItSupport {

    @Autowired
    MessageCodec codec;

    private long org;
    private long integrator;
    private long source;

    @BeforeEach
    void setUp() {
        org = fx.organization("rt");
        integrator = fx.user(org, "lee.integrator", "INTEGRATOR");
        source = source(org, "chirpstack-s3");
    }

    /** DECODE 스크립트를 소스에 연결하고 v1을 배포한다 → {scriptId, versionId} */
    private String[] deployedDecode(String name, String policy) throws Exception {
        JsonNode created = create(org, integrator, """
                {"name":"%s","kind":"DECODE","bindings":[{"targetType":"SOURCE","targetId":"%d","failurePolicy":"%s"}]}"""
                .formatted(name, source, policy));
        String id = created.get("id").asString();
        String v1 = created.get("draftVersionId").asString();
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + id + "/deploy"),
                "{\"versionId\":\"" + v1 + "\",\"memo\":\"첫 배포\",\"baseActiveVersionId\":null}"))).andExpect(status().isOk());
        return new String[]{id, v1};
    }

    private List<ConfigChangedMessage> configMessages() {
        return jdbc.sql("SELECT payload::text FROM data2flow_core.outboxes WHERE organization_id = :o AND exchange = 'data2flow.config' ORDER BY id")
                .param("o", org).query(String.class).list().stream()
                .map(p -> codec.read(p.getBytes(StandardCharsets.UTF_8), ConfigChangedMessage.class)).toList();
    }

    @Test
    @DisplayName("[SCR-03.04][SCR-01.01][AT-SCR-03.1] 배포 → EVT-SCR-01(SCRIPT) 아웃박스 + 실행 묶음에 ACTIVE 코드·연결·정책, sinceVersion 같으면 204 — TC-SCR-051·054(core)")
    void deployPublishesAndBundles() throws Exception {
        long bundle0 = body(mvc.perform(get("/internal/core/scripts/runtime-bundle")).andExpect(status().isOk()).andReturn())
                .get("response").get("bundleVersion").asLong();
        String[] s = deployedDecode("Milesight 디코더", "FAIL_CLOSED");
        List<ConfigChangedMessage> messages = configMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().entityType()).isEqualTo(ConfigChangedMessage.EntityType.SCRIPT);
        assertThat(messages.getFirst().id()).isEqualTo(s[0]);
        assertThat(messages.getFirst().op()).isEqualTo(ConfigChangedMessage.Op.UPSERT);

        JsonNode b = body(mvc.perform(get("/internal/core/scripts/runtime-bundle?organizationId=" + org)
                        .header("X-CALLER-SERVICE", "data2flow-pipeline"))
                .andExpect(status().isOk()).andReturn()).get("response");
        long version = b.get("bundleVersion").asLong();
        assertThat(version).isGreaterThan(bundle0);
        JsonNode script = b.get("scripts").get(0);
        assertThat(script.get("scriptId").asString()).isEqualTo(s[0]);
        assertThat(script.get("versionId").asString()).isEqualTo(s[1]);
        assertThat(script.get("kind").asString()).isEqualTo("DECODE");
        assertThat(script.get("code").asString()).contains("function decode(input, ctx)");
        assertThat(script.get("codeSha256").asString()).hasSize(64);
        assertThat(script.get("bindings").get(0).get("targetType").asString()).isEqualTo("SOURCE");
        assertThat(script.get("bindings").get(0).get("targetId").asString()).isEqualTo(Long.toString(source));
        assertThat(script.get("bindings").get(0).get("failurePolicy").asString()).isEqualTo("FAIL_CLOSED");
        assertThat(b.get("modules").size()).isZero();
        mvc.perform(get("/internal/core/scripts/runtime-bundle?organizationId=" + org + "&sinceVersion=" + version))
                .andExpect(status().isNoContent());
        mvc.perform(get("/internal/core/scripts/runtime-bundle?sinceVersion=" + version)).andExpect(status().isNoContent());

        // 비활성화하면 묶음에서 빠지고(다음 메시지부터 건너뜀) 버전이 오른다
        mvc.perform(as(org, integrator, post("/core/scripts/" + s[0] + "/disable"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("DISABLED"))
                .andExpect(jsonPath("$.response.impact.bindings").value(1));
        mvc.perform(get("/internal/core/scripts/runtime-bundle?organizationId=" + org + "&sinceVersion=" + version))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.scripts.length()").value(0));
        mvc.perform(as(org, integrator, post("/core/scripts/" + s[0] + "/disable"))).andExpect(status().isOk());
        mvc.perform(as(org, integrator, post("/core/scripts/" + s[0] + "/enable"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("ENABLED"))
                .andExpect(jsonPath("$.response.impact").doesNotExist());
        assertThat(auditCount(org, "SCRIPT_DISABLED")).isEqualTo(1);
        assertThat(auditCount(org, "SCRIPT_ENABLED")).isEqualTo(1);
        assertThat(configMessages()).hasSize(3);
    }

    @Test
    @DisplayName("[SCR-03.04][AT-SCR-03.1] 인스턴스 적용 보고(API-SCR-34) 204·멱등 → 상세 activeVersion.applied 2/2, 없는 스크립트 404")
    void deployAcks() throws Exception {
        String[] s = deployedDecode("적용 보고", "FAIL_OPEN");
        for (String instance : List.of("pipeline-0", "pipeline-1", "pipeline-1")) {
            mvc.perform(json(post("/internal/core/scripts/deploy-acks"), """
                            {"instance":"%s","scriptId":"%s","versionId":"%s","appliedAt":"2026-10-03T00:00:03Z"}""".formatted(instance, s[0], s[1]))
                            .header("X-CALLER-SERVICE", "data2flow-pipeline"))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(as(org, integrator, get("/core/scripts/" + s[0]))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.activeVersion.applied.reported").value(2))
                .andExpect(jsonPath("$.response.activeVersion.applied.total").value(2))
                .andExpect(jsonPath("$.response.activeVersion.applied.instances[0].name").value("pipeline-0"));
        // 다음 배포의 total은 최근 24시간에 보고한 인스턴스 수(2)
        JsonNode v2 = body(mvc.perform(as(org, integrator, json(put("/core/scripts/" + s[0] + "/draft"),
                "{\"code\":" + str("function decode(input, ctx) { return { externalId: 'a', metrics: [] }; }") + ",\"baseVersionNo\":1}")))
                .andExpect(status().isOk()).andReturn()).get("response");
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + s[0] + "/deploy"),
                        "{\"versionId\":\"" + v2.get("versionId").asString() + "\",\"memo\":\"두 번째\",\"baseActiveVersionId\":\"" + s[1] + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.applied.reported").value(0))
                .andExpect(jsonPath("$.response.applied.total").value(2))
                .andExpect(jsonPath("$.response.testResult.failed").value(0));
        clock.advance(Duration.ofHours(25));
        mvc.perform(as(org, integrator, get("/core/scripts/" + s[0]))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.activeVersion.applied.total").value(0));

        mvc.perform(json(post("/internal/core/scripts/deploy-acks"),
                        "{\"instance\":\"pipeline-0\",\"scriptId\":\"999999\",\"versionId\":\"" + s[1] + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_NOT_FOUND"));
        mvc.perform(json(post("/internal/core/scripts/deploy-acks"),
                        "{\"instance\":\"pipeline-0\",\"scriptId\":\"" + s[0] + "\",\"versionId\":\"999999\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/internal/core/scripts/deploy-acks"), "{\"scriptId\":\"" + s[0] + "\",\"versionId\":\"1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[SCR-02.04][BR-SCR-11][BR-SCR-16] 자동 비활성(API-SCR-33) → AUTO_DISABLED·묶음에서 제외·감사, 재요청 멱등, 사람이 enable로 해제(감사 previousStatus)")
    void autoDisableAndRelease() throws Exception {
        String[] s = deployedDecode("자동 비활성", "FAIL_OPEN");
        mvc.perform(json(post("/internal/core/scripts/" + s[0] + "/auto-disable"),
                        "{\"reason\":\"ERROR_RATE\",\"observedRate\":0.52,\"window\":\"10m\",\"versionId\":\"" + s[1] + "\"}")
                        .header("X-CALLER-SERVICE", "data2flow-pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("AUTO_DISABLED"))
                .andExpect(jsonPath("$.response.autoDisabledAt").value("2026-10-03T00:00:00Z"));
        mvc.perform(json(post("/internal/core/scripts/" + s[0] + "/auto-disable"), "{\"reason\":\"ERROR_RATE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("AUTO_DISABLED"));
        mvc.perform(json(post("/internal/core/scripts/" + s[0] + "/auto-disable"), "{\"reason\":\"BORED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/internal/core/scripts/999999/auto-disable"), "{\"reason\":\"TIMEOUT_RATE\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/core/scripts/runtime-bundle?organizationId=" + org)).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.scripts.length()").value(0));
        assertThat(auditCount(org, "SCRIPT_AUTO_DISABLED")).isEqualTo(1);
        mvc.perform(as(org, integrator, get("/core/scripts?status=AUTO_DISABLED"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, integrator, get("/core/scripts/" + s[0]))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.autoDisabledReason").value("ERROR_RATE 52.0% / 10m"));

        mvc.perform(as(org, integrator, post("/core/scripts/" + s[0] + "/enable"))).andExpect(status().isOk());
        String detail = jdbc.sql("SELECT detail::text FROM data2flow_core.audit_logs WHERE organization_id = :o AND action = 'SCRIPT_ENABLED'")
                .param("o", org).query(String.class).single();
        assertThat(detail).contains("AUTO_DISABLED");
        mvc.perform(get("/internal/core/scripts/runtime-bundle?organizationId=" + org)).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.scripts.length()").value(1));
        // 다른 조직의 묶음에는 섞이지 않는다
        long other = fx.organization("other");
        mvc.perform(get("/internal/core/scripts/runtime-bundle?organizationId=" + other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.scripts.length()").value(0))
                .andExpect(jsonPath("$.response.bundleVersion").value(0));
    }

    @Test
    @DisplayName("[SCR-03.04] 배포한 스크립트 삭제는 연결이 없을 때만 → EVT-SCR-01 DELETE")
    void deleteDeployedPublishesDelete() throws Exception {
        String[] s = deployedDecode("삭제", "FAIL_OPEN");
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + s[0] + "/bindings"), "{\"bindings\":[]}"))).andExpect(status().isOk());
        jdbc.sql("UPDATE data2flow_core.data_sources SET decoder_key = 'script', decode_script_id = :s WHERE id = :id")
                .param("s", Long.parseLong(s[0])).param("id", source).update();
        mvc.perform(as(org, integrator, delete("/core/scripts/" + s[0])))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_IN_USE"));
        jdbc.sql("UPDATE data2flow_core.data_sources SET decoder_key = 'chirpstack-v4', decode_script_id = NULL WHERE id = :id")
                .param("id", source).update();
        mvc.perform(as(org, integrator, delete("/core/scripts/" + s[0])))
                .andExpect(status().isNoContent());
        List<ConfigChangedMessage> messages = configMessages();
        assertThat(messages.getLast().op()).isEqualTo(ConfigChangedMessage.Op.DELETE);
    }
}
