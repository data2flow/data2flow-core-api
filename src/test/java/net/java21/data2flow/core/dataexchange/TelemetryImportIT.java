package net.java21.data2flow.core.dataexchange;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.dataexchange.service.ImportRunner;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.support.StubHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 과거 데이터 가져오기(TSD-04.02, API-TSD-30~32, BR-TSD-15·16) 통합 시험. pipeline 대역이 API-TSD-52를 받아 시험 DB에
 * {@code ON CONFLICT DO NOTHING}으로 넣고(실제 pipeline과 같은 중복 처리), InfluxDB 대역은 문서 형식의 annotated CSV
 * ({@code contracts/influxdb/query-annotated.csv})를 돌려준다. TC-TSD-109·111·112·114, AT-TSD-05.1~05.3
 */
class TelemetryImportIT extends IntegrationTestSupport {

    static final StubHttpServer PIPELINE = new StubHttpServer();
    static final StubHttpServer INFLUX = new StubHttpServer();

    @DynamicPropertySource
    static void pipeline(DynamicPropertyRegistry registry) {
        registry.add("data2flow.core.pipeline-base-url", PIPELINE::baseUrl);
    }

    @Autowired
    ImportRunner runner;
    @Autowired
    JsonMapper json;

    ExchangeTestData td;
    long org;
    long integrator;
    long sensor;

    @BeforeEach
    void setUp() {
        PIPELINE.reset();
        INFLUX.reset();
        td = new ExchangeTestData(jdbc);
        org = fx.organization("imp");
        integrator = fx.user(org, "imp.int", "INTEGRATOR");
        long room = data.space(org, data.site(org, "본관"), "ROOM", "실습실");
        data.metric(org, "co2", "ppm");
        data.metric(org, "temperature", "℃");
        sensor = data.device(org, data.source(org, "cs"), "24e124707c481005", "ACTIVE", room, null);
        PIPELINE.on("POST", "/internal/pipeline/telemetry/bulk-insert", r -> {
            JsonNode body = json.readTree(r.body());
            long inserted = 0;
            for (JsonNode row : body.get("rows")) {
                inserted += jdbc.sql("""
                                INSERT INTO data2flow_pipeline.telemetry (device_id, metric_key, time, organization_id, value, quality, flags, received_at)
                                VALUES (:d, :m, :t, :o, :v, 0, 2, :t) ON CONFLICT DO NOTHING""")
                        .param("d", row.get("deviceId").asLong()).param("m", row.get("metricKey").asString())
                        .param("t", Pg.ts(Instant.parse(row.get("measuredAt").asString()))).param("o", body.get("organizationId").asLong())
                        .param("v", row.get("value").asDouble()).update();
            }
            long skipped = body.get("rows").size() - inserted;
            return new StubHttpServer.Reply(200, StubHttpServer.success("{\"inserted\":" + inserted + ",\"skipped\":" + skipped + "}"), Map.of());
        });
        PIPELINE.ok("POST", "/internal/pipeline/aggregates/mark-dirty", 200, "{\"accepted\":1}");
    }

    /** multipart 요청 + 신원 헤더 */
    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload(MockMultipartFile file) {
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder b = multipart("/core/imports").file(file);
        b.header("X-USER-ID", Long.toString(integrator)).header("X-ORG-ID", Long.toString(org));
        return b;
    }

    private String csvJob(String csv, String mapping, boolean dryRun) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "history.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(upload(file).param("mapping", mapping)
                        .param("dryRun", Boolean.toString(dryRun)).param("originLabel", "아카데미 iot-bucket 2026-09"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.response.id");
    }

    static final String WIDE = "{\"timeColumn\":\"time\",\"deviceColumn\":\"devEui\",\"deviceKey\":\"EXTERNAL_ID\","
            + "\"metricColumns\":[{\"column\":\"co2\",\"metricKey\":\"co2\"},{\"column\":\"temp\",\"metricKey\":\"temperature\"}]}";

    @Test
    @DisplayName("[TSD-04.02][AT-TSD-05.1][AT-TSD-05.2][BR-TSD-15] CSV: 미리 실행(중복 예상·앞 10행) → 실행, 이미 있는 점은 덮어쓰지 않고 skippedDuplicate, 재계산 요청·EVT-TSD-02 — TC-TSD-109·111·114")
    void csvDryRunThenRun() throws Exception {
        data.telemetry(org, sensor, "co2", Instant.parse("2026-09-01T00:00:00Z"), 999, 0, false);
        String csv = "﻿time,devEui,co2,temp\n2026-09-01T00:00:00Z,24E124707C481005,812,23.4\n"
                + "2026-09-01 09:10:00,24E124707C481005,830,\n";
        String id = csvJob(csv, WIDE, true);
        mvc.perform(as(org, integrator, get("/core/imports/" + id))).andExpect(jsonPath("$.response.status").value("QUEUED"));
        assertThat(runner.runQueued()).isEqualTo(1);
        mvc.perform(as(org, integrator, get("/core/imports/" + id))).andExpect(jsonPath("$.response.status").value("DRY_RUN_DONE"))
                .andExpect(jsonPath("$.response.total").value(3)).andExpect(jsonPath("$.response.skippedDuplicate").value(1))
                .andExpect(jsonPath("$.response.failed").value(0)).andExpect(jsonPath("$.response.sample[2].measuredAt").value("2026-09-01T00:10:00Z"))
                .andExpect(jsonPath("$.response.originLabel").value("아카데미 iot-bucket 2026-09"));
        assertThat(PIPELINE.received("POST", "/internal/pipeline/telemetry/bulk-insert")).isEmpty();
        mvc.perform(as(org, integrator, post("/core/imports/" + id + "/run"))).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.response.status").value("QUEUED")).andExpect(jsonPath("$.response.dryRun").value(false));
        runner.runQueued();
        mvc.perform(as(org, integrator, get("/core/imports/" + id))).andExpect(jsonPath("$.response.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.response.inserted").value(2)).andExpect(jsonPath("$.response.skippedDuplicate").value(1));
        assertThat(jdbc.sql("SELECT value FROM data2flow_pipeline.telemetry WHERE device_id = :d AND metric_key = 'co2' AND time = '2026-09-01T00:00:00Z'")
                .param("d", sensor).query(Double.class).single()).isEqualTo(999);
        StubHttpServer.Received dirty = PIPELINE.received("POST", "/internal/pipeline/aggregates/mark-dirty").getFirst();
        assertThat(dirty.body()).contains("\"reason\":\"IMPORT\"").contains("\"metricKey\":\"co2\"");
        assertThat(dirty.header("X-CALLER-SERVICE")).isEqualTo("data2flow-core-api");
        assertThat(td.outbox(org, "import.completed")).isEqualTo(1);
        mvc.perform(as(org, integrator, post("/core/imports/" + id + "/run"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("IMPORT_STATE_CONFLICT"));
        assertThat(auditCount(org, "TELEMETRY_IMPORT_REQUESTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[TSD-04.02][BR-TSD-16] 잘못된 행은 줄 번호별 오류(시각·기기·값), 나머지는 들어감 → PARTIALLY_FAILED. 머리글에 없는 열 400 IMPORT_FILE_INVALID — TC-TSD-110·114")
    void rowErrorsAndHeader() throws Exception {
        String csv = "time,devEui,metric,value\n2026-09-01T00:00:00Z,24e124707c481005,co2,700\nyesterday,24e124707c481005,co2,1\n"
                + "2026-09-01T00:01:00Z,unknown,co2,1\n2026-09-01T00:02:00Z,24e124707c481005,co2,abc\n2026-09-01T00:03:00Z,24e124707c481005,noise,1\n";
        String mapping = "{\"timeColumn\":\"time\",\"deviceColumn\":\"devEui\",\"metricColumn\":\"metric\",\"valueColumn\":\"value\"}";
        String id = csvJob(csv, mapping, false);
        runner.runQueued();
        mvc.perform(as(org, integrator, get("/core/imports/" + id))).andExpect(jsonPath("$.response.status").value("PARTIALLY_FAILED"))
                .andExpect(jsonPath("$.response.inserted").value(1)).andExpect(jsonPath("$.response.failed").value(4));
        mvc.perform(as(org, integrator, get("/core/imports/" + id + "/errors"))).andExpect(jsonPath("$.totalCount").value(4))
                .andExpect(jsonPath("$.responses[0].lineOrPoint").value("3")).andExpect(jsonPath("$.responses[0].errorCode").value("TIME_INVALID"))
                .andExpect(jsonPath("$.responses[1].errorCode").value("DEVICE_NOT_FOUND"))
                .andExpect(jsonPath("$.responses[2].errorCode").value("VALUE_INVALID"))
                .andExpect(jsonPath("$.responses[3].errorCode").value("METRIC_NOT_FOUND"));
        MockMultipartFile file = new MockMultipartFile("file", "h.csv", "text/csv", "when,dev\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(upload(file).param("mapping", mapping).param("originLabel", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("IMPORT_FILE_INVALID"));
        mvc.perform(upload(file).param("mapping", mapping))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("originLabel"));
        mvc.perform(upload(file).param("mapping", "{\"timeColumn\":\"t\"}").param("originLabel", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("mapping.deviceColumn"));
    }

    @Test
    @DisplayName("[TSD-04.02][AT-TSD-05.3][BR-TSD-16] InfluxDB: 1일 단위 Flux, device_id 태그 → 외부 ID, 매핑 안 된 devEui 2개는 실패(기기 자동 생성 없음), 측정 항목이 아닌 필드(status)는 실패, 토큰은 응답·설정에 없음 — TC-TSD-109·111")
    void influx() throws Exception {
        String sample = new ClassPathResource("contracts/influxdb/query-annotated.csv").getContentAsString(StandardCharsets.UTF_8);
        INFLUX.on("GET", "/ping", r -> new StubHttpServer.Reply(204, "", Map.of()));
        INFLUX.on("POST", "/api/v2/query", r -> new StubHttpServer.Reply(200, sample, Map.of("Content-Type", "text/csv")));
        long devicesBefore = jdbc.sql("SELECT count(*) FROM data2flow_core.devices WHERE organization_id = :o").param("o", org).query(Long.class).single();
        String body = mvc.perform(as(org, integrator, json(post("/core/imports"), ("{\"url\":\"%s\",\"org\":\"academy\",\"bucket\":\"iot-bucket\","
                        + "\"measurement\":\"am107\",\"token\":\"tok-123\",\"from\":\"2026-09-01T00:00:00Z\",\"to\":\"2026-09-02T00:00:00Z\","
                        + "\"tagMapping\":{\"deviceTag\":\"device_id\"},\"dryRun\":false,\"originLabel\":\"iot-bucket\"}").formatted(INFLUX.baseUrl()))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("tok-123");
        String id = JsonPath.read(body, "$.response.id");
        runner.runQueued();
        StubHttpServer.Received q = INFLUX.received("POST", "/api/v2/query").getFirst();
        assertThat(q.header("Authorization")).isEqualTo("Token tok-123");
        assertThat(q.query()).isEqualTo("org=academy");
        assertThat(q.body()).contains("from(bucket: \"iot-bucket\")").contains("range(start: 2026-09-01T00:00:00Z, stop: 2026-09-02T00:00:00Z)")
                .contains("r._measurement == \"am107\"");
        mvc.perform(as(org, integrator, get("/core/imports/" + id))).andExpect(jsonPath("$.response.status").value("PARTIALLY_FAILED"))
                .andExpect(jsonPath("$.response.inserted").value(3)).andExpect(jsonPath("$.response.failed").value(3));
        mvc.perform(as(org, integrator, get("/core/imports/" + id + "/errors")))
                .andExpect(jsonPath("$.responses[0].errorCode").value("DEVICE_NOT_FOUND"))
                .andExpect(jsonPath("$.responses[1].errorCode").value("DEVICE_NOT_FOUND"))
                .andExpect(jsonPath("$.responses[2].errorCode").value("METRIC_NOT_FOUND"));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.devices WHERE organization_id = :o").param("o", org).query(Long.class).single())
                .isEqualTo(devicesBefore);
        assertThat(jdbc.sql("SELECT config::text FROM data2flow_core.import_jobs WHERE id = :id").param("id", Long.parseLong(id))
                .query(String.class).single()).doesNotContain("tok-123");
    }

    @Test
    @DisplayName("[TSD-04.02] InfluxDB 접속 실패 502 IMPORT_SOURCE_UNREACHABLE, OPERATOR 403, 다른 조직 404 — TC-TSD-110·112")
    void unreachableAndPermissions() throws Exception {
        mvc.perform(as(org, integrator, json(post("/core/imports"), "{\"url\":\"http://127.0.0.1:1\",\"org\":\"a\",\"bucket\":\"b\",\"token\":\"t\","
                        + "\"from\":\"2026-09-01T00:00:00Z\",\"to\":\"2026-09-02T00:00:00Z\",\"originLabel\":\"x\"}")))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.header.resultCode").value("IMPORT_SOURCE_UNREACHABLE"));
        mvc.perform(as(org, integrator, json(post("/core/imports"), "{\"url\":\"ftp://x\",\"originLabel\":\"x\"}"))).andExpect(status().isBadRequest());
        long operator = fx.user(org, "imp.op", "OPERATOR");
        mvc.perform(as(org, operator, get("/core/imports"))).andExpect(status().isForbidden());
        String id = csvJob("time,devEui,co2,temp\n", WIDE, true);
        long other = fx.organization("imp2");
        long otherAdmin = fx.user(other, "imp2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/imports/" + id))).andExpect(status().isNotFound());
        mvc.perform(as(org, integrator, get("/core/imports"))).andExpect(jsonPath("$.totalCount").value(1));
    }
}
