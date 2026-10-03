package net.java21.data2flow.core.catalog;

import net.java21.data2flow.core.catalog.dto.CatalogDtos.RegisterUnverifiedRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.UnverifiedKey;
import net.java21.data2flow.core.catalog.service.MetricInternalService;
import net.java21.data2flow.core.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DEV-04.01 측정 항목, DEV-04.02 미검증 항목 처리, DEV-04.03 별칭과 pipeline 캐시 갱신, ING-04.02 미검증 자동 등록(core 쪽)
 * — TC-DEV-121·124·128·131·134·137, TC-ING-056
 */
class MetricIT extends CatalogItSupport {

    @Autowired
    MetricInternalService internal;

    private long org;
    private long admin;

    private void setUp() {
        org = fx.organization("metric");
        admin = fx.user(org, "metric.admin", "ADMIN");
        seeder.seed(org);
    }

    private String registerBody(String... keys) {
        StringBuilder items = new StringBuilder();
        for (String key : keys) {
            items.append(items.isEmpty() ? "" : ",").append("{\"key\":\"").append(key).append("\",\"deviceId\":17,\"sampleValue\":320}");
        }
        return "{\"organizationId\":%d,\"keys\":[%s]}".formatted(org, items);
    }

    @Test
    @DisplayName("[DEV-04.01][BR-DEV-17] 생성 201+Location: 대소문자 섞인 키 허용, 형식 400 METRIC_KEY_INVALID, 키·별칭 중복 409, 정의 검사 400 — TC-DEV-121·124")
    void createMetric() throws Exception {
        setUp();
        mvc.perform(as(org, admin, json(post("/core/metrics"), """
                        {"key":"door","displayName":"문 열림","valueType":"ENUM","enumMap":{"open":1,"close":0},"precision":0,
                         "aggDefault":"LAST","stateType":true,"semantic":"door"}""")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/core/metrics/")))
                .andExpect(jsonPath("$.response.key").value("door"))
                .andExpect(jsonPath("$.response.status").value("VERIFIED"))
                .andExpect(jsonPath("$.response.enumMap.open").value(1))
                .andExpect(jsonPath("$.response.stateType").value(true))
                .andExpect(jsonPath("$.response.builtin").value(false))
                .andExpect(jsonPath("$.response.aliases", hasSize(0)));
        mvc.perform(as(org, admin, json(post("/core/metrics"), "{\"key\":\"PM2_5\",\"displayName\":\"초미세먼지\",\"unit\":\"μg/m³\"}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.valueType").value("NUMBER"))
                .andExpect(jsonPath("$.response.precision").value(1)).andExpect(jsonPath("$.response.aggDefault").value("AVG"));
        for (String bad : new String[]{"1abc", "pm2.5", "_x", "a".repeat(65)}) {
            mvc.perform(as(org, admin, json(post("/core/metrics"), "{\"key\":\"" + bad + "\",\"displayName\":\"x\"}")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("METRIC_KEY_INVALID"));
        }
        mvc.perform(as(org, admin, json(post("/core/metrics"), "{\"key\":\"temperature\",\"displayName\":\"x\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("METRIC_KEY_DUPLICATE"));
        mvc.perform(as(org, admin, json(post("/core/metric-aliases"), "{\"alias\":\"temp\",\"metricKey\":\"temperature\"}")))
                .andExpect(status().isCreated());
        mvc.perform(as(org, admin, json(post("/core/metrics"), "{\"key\":\"temp\",\"displayName\":\"x\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("METRIC_KEY_DUPLICATE"));
        mvc.perform(as(org, admin, json(post("/core/metrics"), "{\"key\":\"door2\",\"displayName\":\"x\",\"valueType\":\"ENUM\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("enumMap"));
        mvc.perform(as(org, admin, json(post("/core/metrics"),
                        "{\"key\":\"x1\",\"displayName\":\"x\",\"validMin\":10,\"validMax\":5,\"precision\":7,\"aggDefault\":\"MEDIAN\",\"enumMap\":{\"a\":\"b\"}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(4)));
        assertThat(auditCount(org, "METRIC_CREATED")).isEqualTo(2);
    }

    @Test
    @DisplayName("[DEV-04.01][BR-DEV-18][BR-DEV-14][AT-DEV-10.2] 유효 범위 변경 → 버전·METRIC 설정 변경(이후 수신부터 반영), 기본 항목 키 변경 403, 키 변경 400 — TC-DEV-124")
    void updateMetric() throws Exception {
        setUp();
        long temperature = metricId(org, "temperature");
        long before = configVersion(org, "METRICS");
        mvc.perform(as(org, admin, json(patch("/core/metrics/" + temperature), "{\"validMin\":-10,\"validMax\":50,\"unit\":null,\"baseVersion\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.validMin").value(-10.0)).andExpect(jsonPath("$.response.validMax").value(50.0))
                .andExpect(jsonPath("$.response.unit").value(nullValue()))
                .andExpect(jsonPath("$.response.displayName").value("온도"))
                .andExpect(jsonPath("$.response.builtin").value(true))
                .andExpect(jsonPath("$.response.version").value(1));
        assertThat(configVersion(org, "METRICS")).isEqualTo(before + 1);
        assertThat(configMessages(org, "METRIC", "UPSERT")).isEqualTo(12 + 1);
        assertThat(auditCount(org, "METRIC_UPDATED")).isEqualTo(1);
        mvc.perform(as(org, admin, json(patch("/core/metrics/" + temperature), "{\"key\":\"temp\",\"baseVersion\":1}")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("MODEL_BUILTIN_READONLY"));
        mvc.perform(as(org, admin, json(patch("/core/metrics/" + temperature), "{\"key\":\"temperature\",\"validMin\":60,\"baseVersion\":1}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("MinMax"));
        mvc.perform(as(org, admin, json(patch("/core/metrics/" + temperature), "{\"precision\":2,\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        mvc.perform(as(org, admin, json(post("/core/metrics"), "{\"key\":\"noise\",\"displayName\":\"소음\"}"))).andExpect(status().isCreated());
        mvc.perform(as(org, admin, json(patch("/core/metrics/" + metricId(org, "noise")), "{\"key\":\"noise2\",\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("key"));
        mvc.perform(as(org, admin, json(patch("/core/metrics/" + metricId(org, "noise")),
                        "{\"valueType\":\"ENUM\",\"enumMap\":{\"quiet\":0,\"loud\":1},\"displayName\":7,\"stateType\":\"yes\",\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors", hasSize(2)));
        mvc.perform(as(org, admin, get("/core/metrics/999999"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("METRIC_NOT_FOUND"));
        mvc.perform(as(org, admin, get("/core/metrics?status=NOPE"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, get("/core/metrics?q=LA&status=verified"))).andExpect(jsonPath("$.totalCount").value(3));
    }

    @Test
    @DisplayName("[ING-04.02][AT-ING-04.2] 처음 보는 pm4_0 → UNVERIFIED 1건(동시 20건에도 1건), EVT-ING-04 1회, 이미 있는 키·별칭·형식 오류 구분 — TC-ING-056")
    void registerUnverified() throws Exception {
        setUp();
        clock.set(MutableClock.T0.plusSeconds(90));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Object>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                calls.add(() -> internal.registerUnverified(new RegisterUnverifiedRequest(org, List.of(new UnverifiedKey("pm4_0", 17L, null)))));
            }
            for (Future<Object> f : pool.invokeAll(calls)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.metrics WHERE organization_id = :o AND key = 'pm4_0' AND status = 'UNVERIFIED'")
                .param("o", org).query(Long.class).single()).isEqualTo(1);
        assertThat(events(org, "metric.unverified.registered")).isEqualTo(1);
        String payload = jdbc.sql("SELECT payload::text FROM data2flow_core.outboxes WHERE organization_id = :o AND routing_key = 'metric.unverified.registered'")
                .param("o", org).query(String.class).single();
        assertThat(payload.replace(" ", "")).contains("\"metricKey\":\"pm4_0\"").contains("\"firstDeviceId\":17")
                .contains("\"type\":\"metric.unverified.registered\"");

        mvc.perform(as(org, admin, json(post("/core/metric-aliases"), "{\"alias\":\"temp\",\"metricKey\":\"temperature\"}")))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/internal/core/metrics/register-unverified").header("X-CALLER-SERVICE", "data2flow-pipeline"),
                        registerBody("illuminance", "pm4_0", "temp", "temperature", "bad.key", "illuminance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.registered[*].key", contains("illuminance")))
                .andExpect(jsonPath("$.response.registered[0].status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.response.existing[*].key", contains("pm4_0", "temp", "temperature")))
                .andExpect(jsonPath("$.response.existing[1].aliasOf").value("temperature"))
                .andExpect(jsonPath("$.response.existing[2].status").value("VERIFIED"))
                .andExpect(jsonPath("$.response.invalid", contains("bad.key")));
        mvc.perform(json(post("/internal/core/metrics/register-unverified"), "{\"organizationId\":999999,\"keys\":[{\"key\":\"x\"}]}"))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/internal/core/metrics/register-unverified"), "{\"organizationId\":1,\"keys\":[]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(as(org, admin, get("/core/metrics?status=UNVERIFIED"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[?(@.key=='pm4_0')].firstSeen.deviceId").value("17"))
                .andExpect(jsonPath("$.responses[?(@.key=='pm4_0')].firstSeen.at").value("2026-10-03T00:01:30Z"));
    }

    @Test
    @DisplayName("[DEV-04.02][BR-DEV-16] 미검증 항목: 최근값 예시, 표준 등록 → VERIFIED, 다시 처리하면 409 METRIC_STATE_CONFLICT, 무시·복원 — TC-DEV-128·131")
    void verifyIgnoreRestore() throws Exception {
        setUp();
        internal.registerUnverified(new RegisterUnverifiedRequest(org, List.of(new UnverifiedKey("pm10", 17L, null),
                new UnverifiedKey("noise_db", 18L, null))));
        long source = source(org, "lab");
        long d1 = data.device(org, source, "dev-1", "ACTIVE", null, null);
        long d2 = data.device(org, source, "dev-2", "ACTIVE", null, null);
        data.deviceState(org, d1, "ONLINE", MutableClock.T0, "{\"pm10\":{\"v\":41.5,\"t\":\"2026-10-03T00:00:00Z\",\"q\":2}}");
        data.deviceState(org, d2, "ONLINE", MutableClock.T0.minusSeconds(60), "{\"pm10\":{\"v\":\"bad\"},\"co2\":{\"v\":900}}");
        long pm10 = metricId(org, "pm10");
        mvc.perform(as(org, admin, get("/core/metrics/" + pm10))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.sample", hasSize(1)))
                .andExpect(jsonPath("$.response.sample[0].value").value(41.5))
                .andExpect(jsonPath("$.response.sample[0].deviceId").value(Long.toString(d1)));

        mvc.perform(as(org, admin, json(post("/core/metrics/" + pm10 + "/verify"),
                        "{\"displayName\":\"미세먼지\",\"unit\":\"μg/m³\",\"validMin\":0,\"validMax\":1000,\"precision\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("VERIFIED"))
                .andExpect(jsonPath("$.response.displayName").value("미세먼지"))
                .andExpect(jsonPath("$.response.sample", hasSize(0)));
        mvc.perform(as(org, admin, post("/core/metrics/" + pm10 + "/verify")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("METRIC_STATE_CONFLICT"))
                .andExpect(jsonPath("$.header.resultMessage").value("이미 처리된 측정 항목입니다"));
        mvc.perform(as(org, admin, post("/core/metrics/" + pm10 + "/ignore"))).andExpect(status().isConflict());

        long noise = metricId(org, "noise_db");
        mvc.perform(as(org, admin, post("/core/metrics/" + noise + "/restore"))).andExpect(status().isConflict());
        mvc.perform(as(org, admin, post("/core/metrics/" + noise + "/ignore"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("IGNORED")).andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, admin, get("/core/metrics?status=IGNORED"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, admin, post("/core/metrics/" + noise + "/ignore"))).andExpect(status().isConflict());
        mvc.perform(as(org, admin, post("/core/metrics/" + noise + "/restore"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("UNVERIFIED"));
        assertThat(auditCount(org, "METRIC_VERIFIED")).isEqualTo(1);
        assertThat(auditCount(org, "METRIC_IGNORED")).isEqualTo(1);
        assertThat(auditCount(org, "METRIC_RESTORED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[DEV-04.02][BR-DEV-16][AT-DEV-10.1] 미검증 illuminance 1,000건을 illumination 별칭으로 연결 → 별칭 생성, pipeline 재매핑 요청, 진행률 → SUCCEEDED, 이후 수신 변환(API-DEV-123 aliases) — TC-DEV-124·128")
    void aliasToWithRemap() throws Exception {
        setUp();
        internal.registerUnverified(new RegisterUnverifiedRequest(org, List.of(new UnverifiedKey("illuminance", 21L, null))));
        long source = source(org, "lab");
        long device = data.device(org, source, "am107", "ACTIVE", null, null);
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry (device_id, metric_key, time, organization_id, value, quality, received_at)
                        SELECT :d, 'illuminance', :t0 + make_interval(secs => g), :o, 300 + g % 30, 2, :t0 FROM generate_series(1, 1000) g""")
                .param("d", device).param("t0", java.time.OffsetDateTime.parse("2026-10-03T00:00:00Z")).param("o", org).update();
        long illuminance = metricId(org, "illuminance");
        MvcResult result = mvc.perform(as(org, admin, json(post("/core/metrics/" + illuminance + "/alias-to"), "{\"targetKey\":\"illumination\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.alias.alias").value("illuminance"))
                .andExpect(jsonPath("$.response.alias.metricKey").value("illumination"))
                .andExpect(jsonPath("$.response.alias.metricId").value(Long.toString(metricId(org, "illumination"))))
                .andReturn();
        String jobId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.response.remapJobId");
        assertThat(PIPELINE.received()).hasSize(1);
        assertThat(PIPELINE.received().get(0).callerService()).isEqualTo("data2flow-core-api");
        assertThat(PIPELINE.received().get(0).body())
                .isEqualTo("{\"organizationId\":%d,\"alias\":\"illuminance\",\"targetKey\":\"illumination\"}".formatted(org));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.metrics WHERE organization_id = :o AND key = 'illuminance'")
                .param("o", org).query(Long.class).single()).isZero();
        mvc.perform(as(org, admin, get("/core/metric-remap-jobs/" + jobId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("RUNNING")).andExpect(jsonPath("$.response.total").value(1000))
                .andExpect(jsonPath("$.response.processed").value(0));
        // pipeline이 키를 바꾼 것처럼(같은 시각 값이 있으면 기존 값 유지): 400건 먼저, 그다음 나머지
        jdbc.sql("UPDATE data2flow_pipeline.telemetry SET metric_key = 'illumination' WHERE organization_id = :o AND metric_key = 'illuminance' AND time <= :t")
                .param("o", org).param("t", java.time.OffsetDateTime.parse("2026-10-03T00:06:40Z")).update();
        mvc.perform(as(org, admin, get("/core/metric-remap-jobs/" + jobId)))
                .andExpect(jsonPath("$.response.status").value("RUNNING")).andExpect(jsonPath("$.response.processed").value(400));
        jdbc.sql("UPDATE data2flow_pipeline.telemetry SET metric_key = 'illumination' WHERE organization_id = :o AND metric_key = 'illuminance'")
                .param("o", org).update();
        mvc.perform(as(org, admin, get("/core/metric-remap-jobs/" + jobId)))
                .andExpect(jsonPath("$.response.status").value("SUCCEEDED")).andExpect(jsonPath("$.response.processed").value(1000));
        mvc.perform(as(org, admin, get("/core/metric-remap-jobs/" + jobId)))
                .andExpect(jsonPath("$.response.status").value("SUCCEEDED"));
        mvc.perform(as(org, admin, get("/core/metric-remap-jobs/999999"))).andExpect(status().isNotFound());

        mvc.perform(get("/internal/core/metrics").param("organizationId", Long.toString(org))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.aliases.illuminance").value("illumination"));
        mvc.perform(as(org, admin, get("/core/metrics?key=illumination")))
                .andExpect(jsonPath("$.responses[0].aliases", contains("illuminance")));
        assertThat(configMessages(org, "METRIC", "DELETE")).isEqualTo(1);
        assertThat(configMessages(org, "ALIAS", "UPSERT")).isEqualTo(1);
        assertThat(auditCount(org, "METRIC_ALIASED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[DEV-04.02][DEV-04.03][BR-DEV-17] 별칭 연결 오류: 미검증·별칭 대상 400 METRIC_ALIAS_INVALID, 검증된 항목 409, 과거 변환 끔, pipeline 실패는 FAILED — TC-DEV-128·134")
    void aliasToErrors() throws Exception {
        setUp();
        internal.registerUnverified(new RegisterUnverifiedRequest(org, List.of(new UnverifiedKey("lux", 1L, null),
                new UnverifiedKey("lux2", 1L, null), new UnverifiedKey("lux3", 1L, null))));
        mvc.perform(as(org, admin, json(post("/core/metric-aliases"), "{\"alias\":\"illum\",\"metricKey\":\"illumination\"}")))
                .andExpect(status().isCreated());
        long lux = metricId(org, "lux");
        mvc.perform(as(org, admin, json(post("/core/metrics/" + lux + "/alias-to"), "{\"targetKey\":\"lux2\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("METRIC_ALIAS_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value("별칭은 검증된 측정 항목에만 연결할 수 있습니다"));
        mvc.perform(as(org, admin, json(post("/core/metrics/" + lux + "/alias-to"), "{\"targetKey\":\"illum\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("METRIC_ALIAS_INVALID"));
        mvc.perform(as(org, admin, json(post("/core/metrics/" + metricId(org, "humidity") + "/alias-to"), "{\"targetKey\":\"temperature\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("METRIC_STATE_CONFLICT"));
        mvc.perform(as(org, admin, json(post("/core/metrics/" + lux + "/alias-to"), "{\"targetKey\":\"illumination\",\"remapHistory\":false}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.remapJobId").value(nullValue()));
        assertThat(PIPELINE.received()).isEmpty();

        long lux2 = metricId(org, "lux2");
        MvcResult empty = mvc.perform(as(org, admin, json(post("/core/metrics/" + lux2 + "/alias-to"), "{\"targetKey\":\"illumination\"}")))
                .andExpect(status().isOk()).andReturn();
        String emptyJob = com.jayway.jsonpath.JsonPath.read(empty.getResponse().getContentAsString(), "$.response.remapJobId");
        mvc.perform(as(org, admin, get("/core/metric-remap-jobs/" + emptyJob)))
                .andExpect(jsonPath("$.response.status").value("SUCCEEDED")).andExpect(jsonPath("$.response.total").value(0));
        assertThat(PIPELINE.received()).isEmpty();

        long source = source(org, "lab");
        long device = data.device(org, source, "x", "ACTIVE", null, null);
        data.telemetry(org, device, "lux3", MutableClock.T0, 10, 2, false);
        PIPELINE.failNext(1);
        MvcResult failed = mvc.perform(as(org, admin, json(post("/core/metrics/" + metricId(org, "lux3") + "/alias-to"),
                        "{\"targetKey\":\"illumination\"}"))).andExpect(status().isOk()).andReturn();
        String failedJob = com.jayway.jsonpath.JsonPath.read(failed.getResponse().getContentAsString(), "$.response.remapJobId");
        mvc.perform(as(org, admin, get("/core/metric-remap-jobs/" + failedJob)))
                .andExpect(jsonPath("$.response.status").value("FAILED")).andExpect(jsonPath("$.response.error").isNotEmpty());
    }

    @Test
    @DisplayName("[DEV-04.03][BR-DEV-17] 별칭 관리: magnet_status → door, 표준 키와 겹치면 409, 대상이 별칭·미검증·없음이면 400, 형식 400, 삭제 204·없으면 404 — TC-DEV-134·137")
    void aliases() throws Exception {
        setUp();
        mvc.perform(as(org, admin, json(post("/core/metrics"),
                        "{\"key\":\"door\",\"displayName\":\"문\",\"valueType\":\"ENUM\",\"enumMap\":{\"open\":1,\"close\":0}}")))
                .andExpect(status().isCreated());
        mvc.perform(as(org, admin, json(post("/core/metric-aliases"), "{\"alias\":\"magnet_status\",\"metricKey\":\"door\"}")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/core/metric-aliases/")))
                .andExpect(jsonPath("$.response.alias").value("magnet_status"))
                .andExpect(jsonPath("$.response.metricKey").value("door"))
                .andExpect(jsonPath("$.response.metricId").value(Long.toString(metricId(org, "door"))));
        mvc.perform(as(org, admin, json(post("/core/metric-aliases"), "{\"alias\":\"temperature\",\"metricKey\":\"door\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("METRIC_KEY_DUPLICATE"));
        mvc.perform(as(org, admin, json(post("/core/metric-aliases"), "{\"alias\":\"magnet_status\",\"metricKey\":\"door\"}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, admin, json(post("/core/metric-aliases"), "{\"alias\":\"magnet2\",\"metricKey\":\"magnet_status\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("METRIC_ALIAS_INVALID"));
        mvc.perform(as(org, admin, json(post("/core/metric-aliases"), "{\"alias\":\"magnet2\",\"metricKey\":\"nothing\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(post("/core/metric-aliases"), "{\"alias\":\"magnet-2\",\"metricKey\":\"door\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("METRIC_KEY_INVALID"));
        mvc.perform(as(org, admin, get("/core/metric-aliases"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].alias").value("magnet_status"));
        long aliasId = jdbc.sql("SELECT id FROM data2flow_core.metric_aliases WHERE organization_id = :o").param("o", org).query(Long.class).single();
        mvc.perform(as(org, admin, delete("/core/metric-aliases/" + aliasId))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, delete("/core/metric-aliases/" + aliasId))).andExpect(status().isNotFound());
        assertThat(configMessages(org, "ALIAS", "UPSERT")).isEqualTo(1);
        assertThat(configMessages(org, "ALIAS", "DELETE")).isEqualTo(1);
        assertThat(auditCount(org, "METRIC_ALIAS_CREATED")).isEqualTo(1);
        assertThat(auditCount(org, "METRIC_ALIAS_DELETED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[DEV-04.03][ING-04.03] 내부 API-DEV-123: 측정 항목·별칭 전체와 버전, sinceVersion이 같으면 204, 바뀌면 새 버전, 조직 생략은 배포 조직 — TC-DEV-137")
    void internalSnapshot() throws Exception {
        setUp();
        MvcResult first = mvc.perform(get("/internal/core/metrics").header("X-CALLER-SERVICE", "data2flow-pipeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.organizationId").value(Long.toString(org)))
                .andExpect(jsonPath("$.response.metrics", hasSize(12)))
                .andExpect(jsonPath("$.response.metrics[?(@.key=='LAeq')].decimals").value(1))
                .andExpect(jsonPath("$.response.metrics[?(@.key=='LAeq')].unit").value("dB"))
                .andReturn();
        Integer version = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.response.version");
        assertThat(version).isEqualTo(12);
        mvc.perform(get("/internal/core/metrics").param("organizationId", Long.toString(org)).param("sinceVersion", "12"))
                .andExpect(status().isNoContent());
        mvc.perform(as(org, admin, json(post("/core/metric-aliases"), "{\"alias\":\"temp\",\"metricKey\":\"temperature\"}")))
                .andExpect(status().isCreated());
        mvc.perform(get("/internal/core/metrics").param("sinceVersion", "12")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(13)).andExpect(jsonPath("$.response.aliases.temp").value("temperature"));
        fx.organization("metric-2");
        mvc.perform(get("/internal/core/metrics")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("organizationId"));
        assertThat(Instant.parse("2026-10-03T00:00:00Z")).isEqualTo(MutableClock.T0);
    }
}
