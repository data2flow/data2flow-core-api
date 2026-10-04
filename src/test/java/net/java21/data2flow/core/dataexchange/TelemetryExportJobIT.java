package net.java21.data2flow.core.dataexchange;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.dataexchange.service.ExportJobRunner;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 비동기 내보내기 작업(BR-TSD-14: 동기 한도를 작게 줘서 비동기 경로를 시험) — AT-TSD-04.2(비동기·완료 알림·서명 링크), 동시 작업 3개 한도,
 * 취소, M5 시연 "기기 1대 1년치 CSV"(원본 5분 간격 105,120행을 조각 단위로 저장·내려받기). TC-TSD-105·108, TC-NFR-041
 */
@TestPropertySource(properties = "data2flow.core.exchange.sync-max-rows=1000")
class TelemetryExportJobIT extends IntegrationTestSupport {

    @Autowired
    ExportJobRunner runner;

    ExchangeTestData td;
    long org;
    long analyst;
    long sensor;

    @BeforeEach
    void setUp() {
        td = new ExchangeTestData(jdbc);
        org = fx.organization("expj");
        analyst = fx.user(org, "expj.analyst", "ANALYST");
        long room = data.space(org, data.site(org, "본관"), "ROOM", "실습실");
        data.metric(org, "temperature", "℃");
        sensor = data.device(org, data.source(org, "cs"), "a1", "ACTIVE", room, null);
    }

    private String query(Instant from, Instant to, String resolution) {
        return "{\"query\":{\"series\":[{\"deviceId\":%d,\"metric\":\"temperature\"}],\"from\":\"%s\",\"to\":\"%s\",\"resolution\":\"%s\"}}"
                .formatted(sensor, from, to, resolution);
    }

    @Test
    @DisplayName("[TSD-04.01][AT-TSD-04.2][NFR-04.02] M5 시연: 1년치 원본 CSV(105,120행)는 비동기 → 완료 이벤트 EVT-TSD-01 → 1시간 서명 링크, 행 수 일치·1MiB 조각 저장 — TC-TSD-105·108, TC-NFR-041")
    void oneYearCsvAsync() throws Exception {
        Instant to = clock.instant();
        Instant from = to.minus(Duration.ofDays(365));
        td.raw(org, sensor, "temperature", from, Duration.ofMinutes(5), 105_120);
        MvcResult r = mvc.perform(as(org, analyst, json(post("/core/exports"), query(from, to, "raw"))))
                .andExpect(status().isAccepted()).andExpect(header().exists("Location")).andReturn();
        String json = r.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(json, "$.response.mode")).isEqualTo("ASYNC");
        assertThat((Integer) JsonPath.read(json, "$.response.estimatedRows")).isEqualTo(105_120);
        long jobId = Long.parseLong(JsonPath.read(json, "$.response.jobId"));
        mvc.perform(as(org, analyst, get("/core/exports/" + jobId))).andExpect(jsonPath("$.response.status").value("QUEUED"));

        assertThat(runner.runQueued()).isEqualTo(1);
        assertThat(td.fileChunks("EXPORT", jobId)).isGreaterThan(1);
        assertThat(td.outbox(org, "export.completed")).isEqualTo(1);
        String payload = td.outboxPayload(org, "export.completed");
        assertThat((String) JsonPath.read(payload, "$.payload.jobId")).isEqualTo(Long.toString(jobId));
        assertThat((Integer) JsonPath.read(payload, "$.payload.rows")).isEqualTo(105_120);
        String detail = mvc.perform(as(org, analyst, get("/core/exports/" + jobId))).andExpect(jsonPath("$.response.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.response.rows").value(105_120)).andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(detail, "$.response.downloadUrl");
        assertThat(url).contains("expires=" + clock.instant().plus(Duration.ofHours(1)).getEpochSecond());
        byte[] file = mvc.perform(as(org, analyst, get(url.replace("/api/v1", "")))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (LineNumberReader lines = new LineNumberReader(new InputStreamReader(new ByteArrayInputStream(file), StandardCharsets.UTF_8))) {
            while (lines.readLine() != null) {
                // 줄 수만 센다
            }
            assertThat(lines.getLineNumber()).isEqualTo(105_121);
        }
    }

    @Test
    @DisplayName("[TSD-04.01][UC-TSD-04] 사용자당 진행 중 작업 3개 넘으면 429 EXPORT_LIMIT_EXCEEDED, 대기 작업 취소 — TC-TSD-103")
    void limitAndCancel() throws Exception {
        Instant to = clock.instant();
        Instant from = to.minus(Duration.ofDays(60));
        td.hourly(org, sensor, "temperature", from, 1440);
        String id = null;
        for (int i = 0; i < 3; i++) {
            String json = mvc.perform(as(org, analyst, json(post("/core/exports"), query(from, to, "1h")))).andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            id = JsonPath.read(json, "$.response.jobId");
        }
        mvc.perform(as(org, analyst, json(post("/core/exports"), query(from, to, "1h")))).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.header.resultCode").value("EXPORT_LIMIT_EXCEEDED"));
        mvc.perform(as(org, analyst, post("/core/exports/" + id + "/cancel"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("CANCELLED"));
        assertThat(runner.runQueued()).isEqualTo(2);
        mvc.perform(as(org, analyst, get("/core/exports/" + id))).andExpect(jsonPath("$.response.status").value("CANCELLED"));
        mvc.perform(as(org, analyst, get("/core/exports"))).andExpect(jsonPath("$.totalCount").value(3));
    }
}
