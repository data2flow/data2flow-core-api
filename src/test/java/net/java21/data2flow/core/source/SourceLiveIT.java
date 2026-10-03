package net.java21.data2flow.core.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DSC-02.06 실시간 원본 메시지(API-DSC-10, SSE {@code /core/stream/sources/{id}/live})가 ingress API-DSC-52 샘플을 중계한다:
 * 필드 이름 바꾸기(size→sizeBytes, rawExcerpt→payload, truncated), maxRate 넘치면 dropped{count}, 토픽 필터 — TC-DSC-095·097
 */
class SourceLiveIT extends SourceItSupport {

    private static String sample(String topic, int size, String raw) {
        return "event: message\ndata: {\"receivedAt\":\"2026-10-03T00:00:01Z\",\"topic\":\"%s\",\"size\":%d,\"rawExcerpt\":\"%s\"}\n\n"
                .formatted(topic, size, raw.replace("\"", "\\\""));
    }

    @Test
    @DisplayName("[DSC-02.06] message 이벤트 중계·필드 이름 통일, maxRate 2 → 나머지는 dropped{count:3}, ingress가 끝나면 스트림도 끝난다 — TC-DSC-097")
    void relayWithRateLimit() throws Exception {
        long id = createSource(mqttBody("live", null));
        StringBuilder events = new StringBuilder(": hello\n\nevent: ping\ndata: {}\n\n");
        for (int i = 0; i < 5; i++) {
            events.append(sample("application/1/device/d" + i + "/event/up", i == 0 ? 9000 : 7, "{\"n\":" + i + "}"));
        }
        INGRESS.liveEvents.set(events.toString());
        MvcResult r = mvc.perform(as(org, integrator, get("/core/stream/sources/" + id + "/live").param("maxRate", "2")))
                .andExpect(request().asyncStarted()).andReturn();
        await().atMost(10, TimeUnit.SECONDS).until(() -> body(r).contains("event:dropped"));
        String body = body(r);
        assertThat(body.split("event:message", -1)).hasSize(3);
        assertThat(body).contains("\"sizeBytes\":9000", "\"payload\":\"{\\\"n\\\":0}\"", "\"truncated\":true", "\"truncated\":false",
                "\"count\":3", "\"topic\":\"application/1/device/d0/event/up\"");
        assertThat(body).doesNotContain("rawExcerpt");
        assertThat(r.getResponse().getContentType()).startsWith("text/event-stream");
        assertThat(INGRESS.livePaths).containsExactly("/internal/ingress/sources/" + id + "/live");
    }

    @Test
    @DisplayName("[DSC-02.06] 토픽 필터(MQTT 와일드카드)는 ingress에 힌트로 넘기고 core도 거른다, 잘못된 필터 400, 없는 소스 404")
    void topicFilter() throws Exception {
        long id = createSource(mqttBody("filter", null));
        INGRESS.liveEvents.set(sample("application/1/device/aa/event/up", 5, "a") + sample("other/topic", 5, "b")
                + sample("application/2/device/bb/event/join", 5, "c") + sample("application/3/device/cc/event/up", 5, "d"));
        MvcResult r = mvc.perform(as(org, admin, get("/core/stream/sources/" + id + "/live").param("topicFilter", "application/+/device/+/event/up")))
                .andExpect(request().asyncStarted()).andReturn();
        await().atMost(10, TimeUnit.SECONDS).until(() -> body(r).split("event:message", -1).length == 3);
        assertThat(body(r)).contains("device/aa", "device/cc").doesNotContain("other/topic", "event/join");
        assertThat(INGRESS.livePaths.getFirst()).contains("topicFilter=application%2F%2B%2Fdevice%2F%2B%2Fevent%2Fup");

        mvc.perform(as(org, admin, get("/core/stream/sources/" + id + "/live").param("topicFilter", "a/#/b")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_CONFIG_INVALID"));
        mvc.perform(as(org, admin, get("/core/stream/sources/123456/live"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[DSC-02.06] ingress가 스트림을 열지 못하면(503) SSE를 바로 끝낸다(브라우저가 다시 연결)")
    void upstreamUnavailable() throws Exception {
        long id = createSource(mqttBody("down", null));
        INGRESS.liveStatus.set(503);
        MvcResult r = mvc.perform(as(org, integrator, get("/core/stream/sources/" + id + "/live")))
                .andExpect(request().asyncStarted()).andReturn();
        r.getAsyncResult(10000);
        assertThat(INGRESS.livePaths).hasSize(1);
        assertThat(body(r)).doesNotContain("event:message");
    }

    private static String body(MvcResult r) {
        try {
            return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        }
    }
}
