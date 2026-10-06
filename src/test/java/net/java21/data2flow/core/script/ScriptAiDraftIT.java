package net.java21.data2flow.core.script;

import net.java21.data2flow.core.common.Pg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCR-03.07·AIA-04.01 스크립트 AI 초안(API-SCR-16 {@code POST /core/scripts/ai-draft})의 core 계약: 원본 ID({@code sampleRawMessageIds})를
 * core가 payload로 꺼내 ai 내부 API({@code samples}, design/openapi/ai-internal.yaml)로 넘기고, 초안에 정적 검사·첫 샘플 테스트 실행을 붙인다.
 * M5·M6 시연(e2e/m56-demo.sh)에서 ai가 원본 ID를 몰라 샘플 없이 초안을 만들던 어긋남을 고친 것 — TC-SCR-063.
 */
class ScriptAiDraftIT extends ScriptItSupport {

    private long org;
    private long integrator;
    private long source;
    private long roomA;
    private long roomB;

    @BeforeEach
    void setUp() {
        org = fx.organization("ai-draft");
        integrator = fx.user(org, "lee.integrator", "INTEGRATOR");
        source = source(org, "vendorx-mqtt");
        long site = data.site(org, "본관");
        roomA = data.space(org, site, "ROOM", "실습실 A");
        roomB = data.space(org, site, "ROOM", "실습실 B");
        PIPELINE.on("POST", "/internal/ai/script-drafts", 200,
                "{\"code\":\"function decode(input, ctx) { return { externalId: String(input.payload.sn), metrics: [] }; }\\n\","
                        + "\"explanation\":\"초안입니다\"}");
    }

    private long raw(Long deviceId, String payload) {
        return jdbc.sql("""
                        INSERT INTO data2flow_pipeline.raw_messages (organization_id, source_id, device_id, source_type, topic, payload,
                            payload_encoding, ingress_instance, dedup_key, stream_partition, stream_offset, status, received_at)
                        VALUES (:org, :source, :device, 'MQTT_SUBSCRIBE', 'vendorx/1/VX-01/up', :payload, 'JSON', 'ingress-0',
                                md5(random()::text), 0, 1, 'DECODE_ERROR', :at) RETURNING id""")
                .param("org", org).param("source", source).param("device", deviceId)
                .param("payload", payload.getBytes(StandardCharsets.UTF_8))
                .param("at", Pg.ts(Instant.parse("2026-10-06T01:00:00Z"))).query(Long.class).single();
    }

    @Test
    @DisplayName("[SCR-03.07][AIA-04.01] 원본 ID → ai에 payload 샘플 전달, 응답에 정적 검사·첫 샘플 테스트 실행(원본 입력) — TC-SCR-063")
    void samplesAreResolvedAndDraftIsChecked() throws Exception {
        long rawId = raw(null, "{\"sn\":\"VX-01\",\"env\":{\"t\":231}}");
        mvc.perform(as(org, integrator, json(post("/core/scripts/ai-draft"), """
                        {"kind":"DECODE","requirement":"env.t는 10배 정수","sampleRawMessageIds":["%d"]}""".formatted(rawId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.code").value(startsWith("function decode")))
                .andExpect(jsonPath("$.response.explanation").value("초안입니다"))
                .andExpect(jsonPath("$.response.staticCheck.ok").value(true))
                .andExpect(jsonPath("$.response.testRun.ok").value(true));

        List<PipelineStubServer.Received> drafts = PIPELINE.received("/internal/ai/script-drafts");
        assertThat(drafts).hasSize(1);
        JsonNode sent = JSON.readTree(drafts.getFirst().body());
        assertThat(sent.has("sampleRawMessageIds")).isFalse();
        assertThat(sent.get("samples").get(0).get("sn").asString()).isEqualTo("VX-01");
        assertThat(sent.get("samples").get(0).get("env").get("t").asInt()).isEqualTo(231);
        assertThat(drafts.getFirst().callerService()).isEqualTo("data2flow-core-api");
        // 정적 검사·테스트 실행은 초안 코드로, 테스트 입력은 원본(rawMessageId)으로
        assertThat(PIPELINE.received("/scripts/check")).hasSize(1);
        JsonNode run = JSON.readTree(PIPELINE.received("/test-run").getFirst().body());
        assertThat(run.get("code").asString()).startsWith("function decode");
        assertThat(run.get("rawMessageId").asString()).isEqualTo(Long.toString(rawId));
        assertThat(run.get("input").get("payload").get("sn").asString()).isEqualTo("VX-01");
    }

    @Test
    @DisplayName("[SCR-03.07][AIA-04.01] 샘플 없으면 테스트 실행 없이 정적 검사만, 권한 밖·없는 원본 404, 형식 오류 400, pipeline 장애여도 초안은 돌려줌 — TC-SCR-063")
    void guards() throws Exception {
        mvc.perform(as(org, integrator, json(post("/core/scripts/ai-draft"), "{\"kind\":\"DECODE\",\"requirement\":\"a\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.staticCheck.ok").value(true))
                .andExpect(jsonPath("$.response.testRun").value(nullValue()));
        assertThat(JSON.readTree(PIPELINE.received("/internal/ai/script-drafts").getFirst().body()).get("samples").size()).isZero();
        assertThat(PIPELINE.received("/test-run")).isEmpty();

        long device = data.device(org, source, "vx-02", "ACTIVE", roomB, null);
        long rawB = raw(device, "{\"sn\":\"VX-02\"}");
        long scoped = fx.user(org, "kim.scoped", "INTEGRATOR");
        data.spaceScope(org, scoped, List.of(roomA));
        mvc.perform(as(org, scoped, json(post("/core/scripts/ai-draft"),
                        "{\"kind\":\"DECODE\",\"requirement\":\"a\",\"sampleRawMessageIds\":[\"" + rawB + "\"]}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, integrator, json(post("/core/scripts/ai-draft"),
                        "{\"kind\":\"DECODE\",\"requirement\":\"a\",\"sampleRawMessageIds\":[\"999999999\"]}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, integrator, json(post("/core/scripts/ai-draft"),
                        "{\"kind\":\"DECODE\",\"requirement\":\"a\",\"sampleRawMessageIds\":[\"x\"]}")))
                .andExpect(status().isBadRequest());
        assertThat(PIPELINE.received("/internal/ai/script-drafts")).hasSize(1);

        PIPELINE.down(true);
        // pipeline·ai가 같은 대역이라 down이면 ai도 503 → SCRIPT_AI_UNAVAILABLE
        mvc.perform(as(org, integrator, json(post("/core/scripts/ai-draft"), "{\"kind\":\"DECODE\",\"requirement\":\"a\"}")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.header.resultCode").value("SCRIPT_AI_UNAVAILABLE"));
        PIPELINE.down(false);
    }
}
