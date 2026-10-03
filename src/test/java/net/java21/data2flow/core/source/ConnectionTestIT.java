package net.java21.data2flow.core.source;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DSC-02.05 저장 전 연결 테스트(API-DSC-57 → ingress API-DSC-51 중계, BR-DSC-07): 결과는 저장하지 않고, 실패 단계를 보여 주고,
 * 조직당 동시 3개 — TC-DSC-087·089. core는 브로커에 직접 붙지 않는다(ingress 대역 서버).
 */
class ConnectionTestIT extends SourceItSupport {

    @Test
    @DisplayName("[DSC-02.05][AT-DSC-01.1] 성공: 단계·미리보기 그대로, ok=true, ingress에 복호화한 비밀값·토픽·기본 15초를 넘기고 아무것도 저장하지 않는다 — TC-DSC-089")
    void success() throws Exception {
        mvc.perform(as(org, integrator, json(post("/core/sources/test"), mqttBody("try", null)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.ok").value(true))
                .andExpect(jsonPath("$.response.steps.length()").value(5))
                .andExpect(jsonPath("$.response.preview[0].topic").value("application/1/device/24e1/event/up"))
                .andExpect(jsonPath("$.response.lossPossible").value(false));
        assertThat(INGRESS.testBodies).hasSize(1);
        String sent = INGRESS.testBodies.getFirst();
        assertThat((String) JsonPath.read(sent, "$.secrets.HEADER_VALUE")).isEqualTo("student:s3cr3t-Pa55");
        assertThat((Integer) JsonPath.read(sent, "$.timeoutSec")).isEqualTo(15);
        assertThat((String) JsonPath.read(sent, "$.config.topics[0].topic")).isEqualTo("application/+/device/+/event/up");
        assertThat((String) JsonPath.read(sent, "$.clientIdBase")).isEqualTo("data2flow-try");
        assertThat((String) JsonPath.read(sent, "$.config.version")).isEqualTo("5.0");
        assertThat(INGRESS.callers).containsOnly("data2flow-core-api");
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.data_sources").query(Long.class).single()).isZero();

        mvc.perform(as(org, integrator, json(post("/core/sources/test").param("timeoutSec", "99"), mqttBody("try", null))))
                .andExpect(status().isOk());
        assertThat((Integer) JsonPath.read(INGRESS.testBodies.get(1), "$.timeoutSec")).isEqualTo(30);
    }

    @Test
    @DisplayName("[DSC-02.05][AT-DSC-01.2][AT-DSC-13.1] 실패는 200 + ok=false·stage(첫 실패 단계), FAILED는 FAIL로, 검증 오류·비밀값 없음은 ingress 호출 전에 400")
    void failures() throws Exception {
        INGRESS.testResponse.set("""
                {"steps":[{"name":"DNS","status":"OK","ms":2},{"name":"TCP","status":"OK","ms":3},{"name":"TLS","status":"OK","ms":4},
                 {"name":"AUTH","status":"FAILED","ms":5,"code":"AUTH_FAILED","detail":"HTTP 401"},{"name":"SUBSCRIBE","status":"SKIPPED","ms":0}],
                 "preview":[],"lossPossible":true}""");
        mvc.perform(as(org, integrator, json(post("/core/sources/test"), mqttBody("bad", null)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.ok").value(false)).andExpect(jsonPath("$.response.stage").value("AUTH"))
                .andExpect(jsonPath("$.response.steps[3].status").value("FAIL"))
                .andExpect(jsonPath("$.response.steps[3].code").value("AUTH_FAILED"));

        mvc.perform(as(org, integrator, json(post("/core/sources/test"),
                        mqttBody("nosecret", null).replace("\"secret\":{\"kind\":\"HEADER\",\"value\":\"student:s3cr3t-Pa55\"},", ""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_SECRET_REQUIRED"));
        mvc.perform(as(org, integrator, json(post("/core/sources/test"), mqttBody("bad", null).replace("wss://broker.test:443/mqtt", "ftp://x"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_CONFIG_INVALID"));
        mvc.perform(as(org, integrator, json(post("/core/sources/test"), "{\"type\":\"WEBHOOK\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("type"));
        assertThat(INGRESS.testBodies).hasSize(1);

        // ingress가 낸 오류 코드는 그대로, 응답 없음·5xx는 503
        INGRESS.testStatus.set(400);
        INGRESS.testResponse.set("{\"header\":{\"isSuccessful\":false,\"resultCode\":\"SOURCE_AUTH_UNSUPPORTED\",\"resultMessage\":\"x\"}}");
        mvc.perform(as(org, integrator, json(post("/core/sources/test"), mqttBody("t", null))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_AUTH_UNSUPPORTED"));
        INGRESS.testResponse.set("not json");
        mvc.perform(as(org, integrator, json(post("/core/sources/test"), mqttBody("t", null))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_CONFIG_INVALID"));
        INGRESS.testStatus.set(429);
        mvc.perform(as(org, integrator, json(post("/core/sources/test"), mqttBody("t", null)))).andExpect(status().isTooManyRequests());
        INGRESS.testStatus.set(502);
        mvc.perform(as(org, integrator, json(post("/core/sources/test"), mqttBody("t", null))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.header.resultCode").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("[DSC-02.05] 저장된 소스 테스트: 저장된 비밀값을 복호화해 쓰고 본문 값이 우선, 없는 소스 404")
    void existingSource() throws Exception {
        long id = createSource(mqttBody("saved", null));
        mvc.perform(as(org, integrator, post("/core/sources/" + id + "/test"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.ok").value(true));
        String sent = INGRESS.testBodies.getFirst();
        assertThat((String) JsonPath.read(sent, "$.secrets.HEADER_VALUE")).isEqualTo("student:s3cr3t-Pa55");
        assertThat((String) JsonPath.read(sent, "$.sourceId")).isEqualTo(Long.toString(id));
        assertThat((String) JsonPath.read(sent, "$.decoderKey")).isEqualTo("chirpstack-v4");
        mvc.perform(as(org, integrator, json(post("/core/sources/" + id + "/test"),
                        "{\"secret\":{\"kind\":\"HEADER\",\"value\":\"other:pw\"},\"topics\":[{\"topic\":\"x/#\",\"qos\":0}]}")))
                .andExpect(status().isOk());
        assertThat((String) JsonPath.read(INGRESS.testBodies.get(1), "$.secrets.HEADER_VALUE")).isEqualTo("other:pw");
        assertThat((Integer) JsonPath.read(INGRESS.testBodies.get(1), "$.config.topics[0].qos")).isZero();
        mvc.perform(as(org, integrator, post("/core/sources/777777/test"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[DSC-02.05][API-DSC-51] 조직당 동시 테스트 3개: 4번째는 429 RATE_LIMITED, 끝나면 다시 가능")
    void concurrencyLimit() throws Exception {
        INGRESS.holdTests();
        List<CompletableFuture<MvcResult>> running = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            running.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return mvc.perform(as(org, integrator, json(post("/core/sources/test"), mqttBody("c", null)))).andReturn();
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }));
        }
        await().atMost(10, TimeUnit.SECONDS).until(() -> INGRESS.waiting.get() == 3);
        mvc.perform(as(org, integrator, json(post("/core/sources/test"), mqttBody("c", null))))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.header.resultCode").value("RATE_LIMITED"));
        INGRESS.release();
        for (CompletableFuture<MvcResult> f : running) {
            assertThat(f.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        mvc.perform(as(org, integrator, json(post("/core/sources/test"), mqttBody("c", null)))).andExpect(status().isOk());
    }
}
