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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCR-03.02 테스트 실행(core 쪽, API-SCR-08 {@code POST /core/scripts/test-run} → pipeline API-SCR-31): 입력 만들기·크기 한도·권한 밖 원본 404·
 * 분당 60회·아무것도 저장하지 않음 — TC-SCR-045(경로는 API-SCR-08), TC-SCR-042·043의 core 계약.
 */
class ScriptTestRunIT extends ScriptItSupport {

    private long org;
    private long integrator;
    private long source;
    private long roomA;
    private long roomB;
    private long deviceA;
    private long deviceB;

    @BeforeEach
    void setUp() {
        org = fx.organization("tr");
        integrator = fx.user(org, "lee.integrator", "INTEGRATOR");
        source = source(org, "chirpstack-s3");
        long site = data.site(org, "본관");
        roomA = data.space(org, site, "ROOM", "실습실 A");
        roomB = data.space(org, site, "ROOM", "실습실 B");
        deviceA = data.device(org, source, "24e124136d151606", "ACTIVE", roomA, null);
        deviceB = data.device(org, source, "24e124136d151607", "ACTIVE", roomB, null);
    }

    private long raw(long orgId, Long deviceId, String payload, String encoding) {
        return jdbc.sql("""
                        INSERT INTO data2flow_pipeline.raw_messages (organization_id, source_id, device_id, source_type, topic, payload,
                            payload_encoding, ingress_instance, dedup_key, stream_partition, stream_offset, status, received_at)
                        VALUES (:org, :source, :device, 'MQTT_SUBSCRIBE', 'application/1/device/24e124136d151606/event/up', :payload,
                                :enc, 'ingress-0', md5(random()::text), 0, 1, 'OK', :at) RETURNING id""")
                .param("org", orgId).param("source", source).param("device", deviceId)
                .param("payload", payload.getBytes(StandardCharsets.UTF_8)).param("enc", encoding)
                .param("at", Pg.ts(Instant.parse("2026-10-03T00:00:00Z"))).query(Long.class).single();
    }

    @Test
    @DisplayName("[SCR-03.02][AT-SCR-02.1] 최근 원본 선택 → DECODE 입력(topic·payload JSON·receivedAt·source)과 실제 기기 컨텍스트로 pipeline 실행, 결과 그대로·저장 없음 — TC-SCR-042·043(core)")
    void runWithRecentRaw() throws Exception {
        jdbc.sql("""
                        INSERT INTO data2flow_core.device_attributes (organization_id, device_id, scope, key, value)
                        VALUES (:o, :d, 'SERVER', 'tempOffset', '0.5')""").param("o", org).param("d", deviceA).update();
        data.deviceState(org, deviceA, "ONLINE", Instant.parse("2026-10-02T23:59:00Z"),
                "{\"temperature\":{\"v\":22.4,\"t\":\"2026-10-02T23:59:00Z\",\"q\":0}}");
        long rawId = raw(org, deviceA, "{\"devEui\":\"24e124136d151606\",\"data\":\"AXVoAQJnBgE=\"}", "JSON");
        JsonNode script = create(org, integrator, "{\"name\":\"설정 있는 스크립트\",\"kind\":\"TRANSFORM\",\"templateKey\":\"calibration-offset\"}");
        long versionsBefore = jdbc.sql("SELECT count(*) FROM data2flow_core.script_versions").query(Long.class).single();

        mvc.perform(as(org, integrator, json(post("/core/scripts/test-run"), """
                        {"kind":"DECODE","code":%s,"rawMessageId":"%d","scriptId":"%s"}""".formatted(
                        str("function decode(input, ctx) { return { externalId: 'x', metrics: [] }; }"), rawId, script.get("id").asString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.ok").value(true))
                .andExpect(jsonPath("$.response.diff.added[0].key").value("dew_point"))
                .andExpect(jsonPath("$.response.logs[0].message").value("x 3"))
                .andExpect(jsonPath("$.response.durationMs").value(0.4));

        List<PipelineStubServer.Received> runs = PIPELINE.received("/test-run");
        assertThat(runs).hasSize(1);
        JsonNode sent = JSON.readTree(runs.getFirst().body());
        assertThat(sent.get("organizationId").asString()).isEqualTo(Long.toString(org));
        assertThat(sent.get("rawMessageId").asString()).isEqualTo(Long.toString(rawId));
        assertThat(sent.get("input").get("topic").asString()).startsWith("application/1/device/");
        assertThat(sent.get("input").get("payload").get("data").asString()).isEqualTo("AXVoAQJnBgE=");
        assertThat(sent.get("input").get("payloadEncoding").asString()).isEqualTo("JSON");
        assertThat(sent.get("input").get("receivedAt").asString()).isEqualTo("2026-10-03T00:00:00Z");
        assertThat(sent.get("input").get("source").get("code").asString()).isEqualTo("chirpstack-s3");
        assertThat(sent.get("context").get("device").get("id").asLong()).isEqualTo(deviceA);
        assertThat(sent.get("context").get("device").get("attributes").get("tempOffset").asDouble()).isEqualTo(0.5);
        assertThat(sent.get("context").get("last").get("temperature").get("value").asDouble()).isEqualTo(22.4);
        assertThat(sent.get("context").get("config").get("tempOffset").asInt()).isZero();
        assertThat(runs.getFirst().callerService()).isEqualTo("data2flow-core-api");
        // BR-SCR-08: 버전·원본은 그대로
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.script_versions").query(Long.class).single()).isEqualTo(versionsBefore);

        // 바이너리 원본은 base64로, 직접 입력은 그대로 넘긴다
        long binary = raw(org, null, "\u0001u", "BINARY");
        mvc.perform(as(org, integrator, json(post("/core/scripts/test-run"),
                "{\"kind\":\"DECODE\",\"code\":\"x\",\"rawMessageId\":\"" + binary + "\"}"))).andExpect(status().isOk());
        assertThat(JSON.readTree(PIPELINE.received("/test-run").get(1).body()).get("input").get("payload").asString()).isEqualTo("AXU=");
        mvc.perform(as(org, integrator, json(post("/core/scripts/test-run"), """
                        {"kind":"TRANSFORM","code":"x","input":{"metrics":[{"key":"temperature","value":22.3}]},
                         "context":{"device":{"id":%d},"config":{"tempOffset":1}}}""".formatted(deviceA)))).andExpect(status().isOk());
        JsonNode direct = JSON.readTree(PIPELINE.received("/test-run").get(2).body());
        assertThat(direct.get("input").get("metrics").get(0).get("value").asDouble()).isEqualTo(22.3);
        assertThat(direct.get("context").get("config").get("tempOffset").asInt()).isEqualTo(1);
        assertThat(direct.get("context").get("last").get("temperature").get("measuredAt").asString()).isEqualTo("2026-10-02T23:59:00Z");
    }

    @Test
    @DisplayName("[SCR-03.02][AT-SCR-02.1] 입력 256KB 초과 400, 권한 밖·다른 조직 원본 404, 입력 없음 400, 분당 60회 초과 429, pipeline 장애 503 — TC-SCR-045")
    void guards() throws Exception {
        String big = "{\"kind\":\"TRANSFORM\",\"code\":\"x\",\"input\":{\"blob\":\"" + "a".repeat(256 * 1024) + "\"}}";
        mvc.perform(as(org, integrator, json(post("/core/scripts/test-run"), big)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("input"));
        mvc.perform(as(org, integrator, json(post("/core/scripts/test-run"), "{\"kind\":\"TRANSFORM\",\"code\":\"x\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("input"));
        mvc.perform(as(org, integrator, json(post("/core/scripts/test-run"), "{\"kind\":\"TRANSFORM\",\"code\":\"x\",\"input\":{},\"scriptId\":\"999999\"}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_NOT_FOUND"));

        long scoped = fx.user(org, "kim.scoped", "INTEGRATOR");
        data.spaceScope(org, scoped, List.of(roomA));
        long rawB = raw(org, deviceB, "{}", "JSON");
        long rawA = raw(org, deviceA, "{}", "JSON");
        mvc.perform(as(org, scoped, json(post("/core/scripts/test-run"), "{\"kind\":\"DECODE\",\"code\":\"x\",\"rawMessageId\":\"" + rawB + "\"}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("RESOURCE_NOT_FOUND"));
        mvc.perform(as(org, scoped, json(post("/core/scripts/test-run"), "{\"kind\":\"DECODE\",\"code\":\"x\",\"rawMessageId\":\"" + rawA + "\"}")))
                .andExpect(status().isOk());
        long other = fx.organization("other");
        long otherRaw = raw(other, null, "{}", "JSON");
        mvc.perform(as(org, integrator, json(post("/core/scripts/test-run"), "{\"kind\":\"DECODE\",\"code\":\"x\",\"rawMessageId\":\"" + otherRaw + "\"}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, integrator, json(post("/core/scripts/test-run"), "{\"kind\":\"DECODE\",\"code\":\"x\",\"rawMessageId\":\"abc\"}")))
                .andExpect(status().isBadRequest());

        PIPELINE.down(true);
        mvc.perform(as(org, integrator, json(post("/core/scripts/test-run"), "{\"kind\":\"TRANSFORM\",\"code\":\"x\",\"input\":{}}")))
                .andExpect(status().isServiceUnavailable());
        PIPELINE.down(false);

        long busy = fx.user(org, "park.busy", "INTEGRATOR");
        for (int i = 0; i < 60; i++) {
            mvc.perform(as(org, busy, json(post("/core/scripts/test-run"), "{\"kind\":\"TRANSFORM\",\"code\":\"x\",\"input\":{}}")))
                    .andExpect(status().isOk());
        }
        mvc.perform(as(org, busy, json(post("/core/scripts/test-run"), "{\"kind\":\"TRANSFORM\",\"code\":\"x\",\"input\":{}}")))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.header.resultCode").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
    }
}
