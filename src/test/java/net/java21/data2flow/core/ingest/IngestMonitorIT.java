package net.java21.data2flow.core.ingest;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수집 모니터(ING-01.02, ING-07.04, OPS-01.02, OPS-01.05): API-ING-01·02·04, API-OPS-02·05 — TC-ING-015, TC-OPS-009·016·017.
 */
class IngestMonitorIT extends IngestITSupport {

    static final Instant T0 = MutableClock.T0;

    long org;
    long admin;
    long operator;
    long source;
    long device;

    @BeforeEach
    void setUp() {
        org = fx.organization("ingm");
        admin = fx.user(org, "ing.admin", "ADMIN");
        operator = fx.user(org, "ing.operator", "OPERATOR");
        long site = data.site(org, "본관");
        source = data.source(org, "chirp");
        device = data.device(org, source, "24e1", "ACTIVE", site, null);
        jdbc.sql("""
                        INSERT INTO data2flow_core.source_runtimes (source_id, instance_id, organization_id, state, reported_at)
                        VALUES (:s, 'ingress-0', :o, 'CONNECTED', :t)""")
                .param("s", source).param("o", org).param("t", Pg.ts(T0)).update();
    }

    /** 최근 2분 안 OK 10건(지연 100~1000ms), 디코딩 실패 2, 스크립트 실패 1, 처리 대기 1건(90초 전) */
    private void traffic() {
        Instant at = T0.minus(Duration.ofMinutes(2));
        for (int i = 1; i <= 10; i++) {
            rows.raw(org, source, device, "OK", at, at.plusMillis(100L * i), "{\"t\":" + i + "}", "app/1/device/24e1/event/up", false);
        }
        rows.raw(org, source, device, "DECODE_ERROR", at, at.plusMillis(50), "{broken", "t", false);
        rows.raw(org, source, null, "DECODE_ERROR", at, at.plusMillis(50), "{broken", "t", false);
        rows.raw(org, source, device, "SCRIPT_ERROR", at, at.plusMillis(50), "{}", "t", false);
        rows.raw(org, source, device, "RECEIVED", T0.minusSeconds(90), null, "{}", "t", false);
    }

    @Test
    @DisplayName("[ING-01.02][ING-07.04][AT-ING-05.1] 요약: 분당 수신·지연 p50/p95·처리 대기·오늘 실패·소스별 결과 건수, lag 90초 WARNING — TC-ING-015")
    void summary() throws Exception {
        traffic();
        long raw = rows.raw(org, source, device, "DECODE_ERROR", T0.minusSeconds(30), T0.minusSeconds(29), "{", "t", false);
        rows.dlq(org, raw, "DECODE", "ING_DECODE_FAILED", "OPEN", T0.minusSeconds(60), null, null);
        rows.dlq(org, raw, "DECODE", "ING_DECODE_FAILED", "RESOLVED", T0.minus(Duration.ofHours(10)), null, null);
        String body = mvc.perform(as(org, operator, get("/core/ingest/summary"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.window").value("1h"))
                .andExpect(jsonPath("$.response.perMinute").value(3.0))
                .andExpect(jsonPath("$.response.streamLagSec").value(90))
                .andExpect(jsonPath("$.response.failuresToday").value(1))
                .andExpect(jsonPath("$.response.heartbeat").doesNotExist())
                .andExpect(jsonPath("$.response.sources", hasSize(1)))
                .andExpect(jsonPath("$.response.sources[0].sourceId").value(Long.toString(source)))
                .andExpect(jsonPath("$.response.sources[0].type").value("MQTT_SUBSCRIBE"))
                .andExpect(jsonPath("$.response.sources[0].connection").value("CONNECTED"))
                .andExpect(jsonPath("$.response.sources[0].counts.OK").value(10))
                .andExpect(jsonPath("$.response.sources[0].counts.DECODE_ERROR").value(3))
                .andExpect(jsonPath("$.response.sources[0].counts.SCRIPT_ERROR").value(1))
                .andExpect(jsonPath("$.response.sources[0].counts.DUPLICATE").value(0))
                .andExpect(jsonPath("$.response.sources[0].lastReceivedAt").value("2026-10-02T23:59:30Z"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(body, "$.response.latencyP50Ms")).isBetween(50, 600);
        assertThat(JsonPath.<Integer>read(body, "$.response.latencyP95Ms")).isBetween(800, 1000);
        assertThat(JsonPath.<List<String>>read(body, "$.response.alerts[*].code")).containsExactly("INGEST_LAG_HIGH");
        assertThat(JsonPath.<String>read(body, "$.response.alerts[0].level")).isEqualTo("WARNING");
        assertThat(JsonPath.<String>read(body, "$.response.alerts[0].message")).isEqualTo("수신한 메시지 처리가 밀리고 있습니다");
        // lag 기준을 낮추면 CRITICAL(BR-ING-16, API-ING-04)
        mvc.perform(as(org, admin, json(put("/core/ingest/alert-thresholds"),
                "{\"lagWarnSec\":30,\"lagCriticalSec\":60,\"heartbeatCriticalSec\":30,\"baseVersion\":0}"))).andExpect(status().isOk());
        mvc.perform(as(org, operator, get("/core/ingest/summary?window=24h").header("Accept-Language", "en")))
                .andExpect(jsonPath("$.response.alerts[0].level").value("CRITICAL"))
                .andExpect(jsonPath("$.response.alerts[0].message").value("Processing of received messages is falling behind"));
        mvc.perform(as(org, operator, get("/core/ingest/summary?window=2h"))).andExpect(status().isBadRequest());
        long viewer = fx.user(org, "ing.viewer", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/ingest/summary"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
    }

    @Test
    @DisplayName("[OPS-01.05][AT-OPS-02.1][AT-OPS-02.2][BR-OPS-02] 활성 소스가 5분 수신 0건이면 CRITICAL INGEST_STOPPED, 모두 멈춤(PAUSED)·기준 끔이면 없음, DLQ 증가 경고 — TC-OPS-016")
    void ingestStoppedAlarm() throws Exception {
        jdbc.sql("UPDATE data2flow_core.source_runtimes SET state = 'DISCONNECTED' WHERE source_id = :s").param("s", source).update();
        rows.raw(org, source, device, "OK", T0.minus(Duration.ofMinutes(6)), T0.minus(Duration.ofMinutes(6)), "{}", "t", false);
        mvc.perform(as(org, operator, get("/core/ingest/summary"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.alerts[0].code").value("INGEST_STOPPED"))
                .andExpect(jsonPath("$.response.alerts[0].level").value("CRITICAL"))
                .andExpect(jsonPath("$.response.alerts[0].causeHints[0]").value("SOURCE_DISCONNECTED"))
                .andExpect(jsonPath("$.response.perMinute").value(0.0));
        // 기준을 10분으로 늘리면 6분 전 수신이 창 안에 들어 경보 없음
        mvc.perform(as(org, admin, json(put("/core/ops/thresholds"), "{\"items\":[{\"key\":\"INGEST_ZERO_MINUTES\",\"value\":10}],\"baseVersion\":0}")))
                .andExpect(status().isOk());
        mvc.perform(as(org, operator, get("/core/ingest/summary"))).andExpect(jsonPath("$.response.alerts", hasSize(0)));
        mvc.perform(as(org, admin, json(put("/core/ops/thresholds"),
                "{\"items\":[{\"key\":\"INGEST_ZERO_MINUTES\",\"value\":5,\"enabled\":false}],\"baseVersion\":1}"))).andExpect(status().isOk());
        mvc.perform(as(org, operator, get("/core/ingest/summary"))).andExpect(jsonPath("$.response.alerts", hasSize(0)));
        mvc.perform(as(org, admin, json(put("/core/ops/thresholds"),
                "{\"items\":[{\"key\":\"INGEST_ZERO_MINUTES\",\"enabled\":true}],\"baseVersion\":2}"))).andExpect(status().isOk());
        jdbc.sql("UPDATE data2flow_core.data_sources SET lifecycle = 'PAUSED' WHERE id = :s").param("s", source).update();
        mvc.perform(as(org, operator, get("/core/ingest/summary"))).andExpect(jsonPath("$.response.alerts", hasSize(0)));
        // DLQ 10분 증가 기준(DLQ_GROWTH_PER_10MIN)
        long raw = rows.raw(org, source, device, "SCRIPT_ERROR", T0.minusSeconds(60), T0.minusSeconds(60), "{}", "t", false);
        rows.dlq(org, raw, "SCRIPT", "SCRIPT_RUNTIME_ERROR", "OPEN", T0.minusSeconds(60), null, null);
        rows.dlq(org, raw, "SCRIPT", "SCRIPT_RUNTIME_ERROR", "RESOLVED", T0.minusSeconds(30), null, null);
        mvc.perform(as(org, admin, json(put("/core/ops/thresholds"),
                "{\"items\":[{\"key\":\"DLQ_GROWTH_PER_10MIN\",\"value\":2}],\"baseVersion\":3}"))).andExpect(status().isOk());
        mvc.perform(as(org, operator, get("/core/ingest/summary"))).andExpect(jsonPath("$.response.alerts[0].code").value("DLQ_GROWING"))
                .andExpect(jsonPath("$.response.alerts[0].level").value("WARNING"))
                .andExpect(jsonPath("$.response.failuresToday").value(2));
    }

    @Test
    @DisplayName("[OPS-01.02][ING-01.02] 지표 시계열: 1분 구간별 수신·결과·지연, 빈 구간 0, 24시간 초과 400, 다른 조직 소스 404 — TC-ING-015, TC-OPS-009")
    void metricsSeries() throws Exception {
        traffic();
        String body = mvc.perform(as(org, operator, get("/core/ingest/metrics").param("from", T0.minus(Duration.ofMinutes(5)).toString())
                        .param("to", T0.toString()).param("sourceId", Long.toString(source))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.step").value("1m"))
                .andExpect(jsonPath("$.response.points", hasSize(5)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(body, "$.response.points[*].received")).containsExactly(0, 0, 0, 14, 0);
        assertThat(JsonPath.<Integer>read(body, "$.response.points[3].byStatus.OK")).isEqualTo(10);
        assertThat(JsonPath.<Integer>read(body, "$.response.points[3].byStatus.DECODE_ERROR")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(body, "$.response.points[3].latencyP95Ms")).isGreaterThan(500);
        assertThat(JsonPath.<Object>read(body, "$.response.points[0].latencyP50Ms")).isNull();
        mvc.perform(as(org, operator, get("/core/ingest/metrics").param("from", T0.minus(Duration.ofHours(1)).toString()).param("step", "5m")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.points", hasSize(12)));
        mvc.perform(as(org, operator, get("/core/ingest/metrics").param("from", T0.minus(Duration.ofHours(25)).toString())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("ING_QUERY_RANGE_TOO_LARGE"))
                .andExpect(jsonPath("$.header.resultMessage").value("조회 기간은 최대 31일입니다"));
        mvc.perform(as(org, operator, get("/core/ingest/metrics").param("step", "10m"))).andExpect(status().isBadRequest());
        long other = fx.organization("ingn");
        long otherSource = data.source(other, "other");
        mvc.perform(as(org, operator, get("/core/ingest/metrics").param("sourceId", Long.toString(otherSource)))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[ING-07.04][ING-07.05] 수집 알람 기준: 기본 60/300/30·version 0, 저장(감사 INGEST_THRESHOLD_CHANGED·설정 변경 발행), 검증·baseVersion·ADMIN 전용")
    void alertThresholds() throws Exception {
        mvc.perform(as(org, admin, get("/core/ingest/alert-thresholds"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.lagWarnSec").value(60)).andExpect(jsonPath("$.response.lagCriticalSec").value(300))
                .andExpect(jsonPath("$.response.heartbeatCriticalSec").value(30)).andExpect(jsonPath("$.response.version").value(0));
        mvc.perform(as(org, admin, json(put("/core/ingest/alert-thresholds"),
                        "{\"lagWarnSec\":90,\"lagCriticalSec\":600,\"heartbeatCriticalSec\":45,\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(1))
                .andExpect(jsonPath("$.response.updatedBy").value(Long.toString(admin)))
                .andExpect(jsonPath("$.response.updatedAt").value("2026-10-03T00:00:00Z"));
        mvc.perform(as(org, admin, json(put("/core/ingest/alert-thresholds"),
                        "{\"lagWarnSec\":120,\"lagCriticalSec\":600,\"heartbeatCriticalSec\":45,\"baseVersion\":1}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(2));
        mvc.perform(as(org, admin, json(put("/core/ingest/alert-thresholds"),
                        "{\"lagWarnSec\":120,\"lagCriticalSec\":600,\"heartbeatCriticalSec\":45,\"baseVersion\":1}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        mvc.perform(as(org, admin, json(put("/core/ingest/alert-thresholds"),
                        "{\"lagWarnSec\":300,\"lagCriticalSec\":300,\"heartbeatCriticalSec\":5,\"baseVersion\":2}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("heartbeatCriticalSec"));
        mvc.perform(as(org, admin, json(put("/core/ingest/alert-thresholds"),
                        "{\"lagWarnSec\":300,\"lagCriticalSec\":300,\"heartbeatCriticalSec\":30,\"baseVersion\":2}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lagCriticalSec"));
        mvc.perform(as(org, admin, json(put("/core/ingest/alert-thresholds"), "{\"lagWarnSec\":60,\"lagCriticalSec\":300,\"heartbeatCriticalSec\":30}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("baseVersion"));
        mvc.perform(as(org, operator, get("/core/ingest/alert-thresholds"))).andExpect(status().isForbidden());
        mvc.perform(as(org, operator, json(put("/core/ingest/alert-thresholds"),
                "{\"lagWarnSec\":60,\"lagCriticalSec\":300,\"heartbeatCriticalSec\":30,\"baseVersion\":2}"))).andExpect(status().isForbidden());
        assertThat(auditCount(org, "INGEST_THRESHOLD_CHANGED")).isEqualTo(2);
        long published = jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE organization_id = :o AND payload::text LIKE '%SETTING%'")
                .param("o", org).query(Long.class).single();
        assertThat(published).isEqualTo(2);
    }

    @Test
    @DisplayName("[OPS-01.05][BR-OPS-02] 운영 알람 기준: 6종 기본값, 보낸 항목만 저장·판 번호, 형식·범위 오류 400 SETTING_INVALID {필드} — TC-OPS-016·017")
    void opsThresholds() throws Exception {
        String defaults = mvc.perform(as(org, admin, get("/core/ops/thresholds"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(0)).andExpect(jsonPath("$.response.items", hasSize(6)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(defaults, "$.response.items[?(@.key == 'INGEST_ZERO_MINUTES')].value")).containsExactly(5.0);
        mvc.perform(as(org, admin, json(put("/core/ops/thresholds"),
                        "{\"items\":[{\"key\":\"INGEST_ZERO_MINUTES\",\"value\":15,\"severity\":\"MAJOR\",\"channelIds\":[\"7\"]}],\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(1))
                .andExpect(jsonPath("$.response.items[?(@.key == 'INGEST_ZERO_MINUTES')].channelIds[0]").value("7"))
                .andExpect(jsonPath("$.response.items[?(@.key == 'INGEST_ZERO_MINUTES')].severity").value("MAJOR"));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.ops_thresholds WHERE organization_id = :o").param("o", org)
                .query(Long.class).single()).isEqualTo(6);
        mvc.perform(as(org, admin, json(put("/core/ops/thresholds"), "{\"items\":[{\"key\":\"DISK_FREE_PERCENT\",\"value\":0}],\"baseVersion\":1}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SETTING_INVALID"))
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultMessage").value("설정값이 올바르지 않습니다: items[0].value"));
        for (String bad : new String[]{"{\"items\":[{\"key\":\"NOPE\",\"value\":1}],\"baseVersion\":1}",
                "{\"items\":[{\"key\":\"DISK_FREE_PERCENT\",\"severity\":\"LOUD\"}],\"baseVersion\":1}",
                "{\"items\":[{\"key\":\"DISK_FREE_PERCENT\",\"channelIds\":[\"x\"]}],\"baseVersion\":1}",
                "{\"items\":[{\"key\":\"DISK_FREE_PERCENT\"},{\"key\":\"DISK_FREE_PERCENT\"}],\"baseVersion\":1}",
                "{\"items\":[],\"baseVersion\":1}", "{\"items\":[{\"key\":\"DISK_FREE_PERCENT\",\"value\":10}]}"}) {
            mvc.perform(as(org, admin, json(put("/core/ops/thresholds"), bad))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.resultCode").value("SETTING_INVALID"));
        }
        mvc.perform(as(org, admin, json(put("/core/ops/thresholds"), "{\"items\":[{\"key\":\"DISK_FREE_PERCENT\",\"value\":10}],\"baseVersion\":0}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, operator, get("/core/ops/thresholds"))).andExpect(status().isForbidden());
        assertThat(auditCount(org, "OPS_THRESHOLD_CHANGED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[OPS-01.02][AT-OPS-01.4] 운영 수집 지표: 24h는 5분 구간 288개, 결과별 합계 = 수신 건수, 처리 대기 추이, ADMIN 전용 — TC-OPS-009")
    void opsIngestMetrics() throws Exception {
        traffic();
        String body = mvc.perform(as(org, admin, get("/core/ops/metrics/ingest"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.range").value("24h"))
                .andExpect(jsonPath("$.response.series.receivedPerMin", hasSize(288)))
                .andExpect(jsonPath("$.response.resultCounts.OK").value(10))
                .andExpect(jsonPath("$.response.resultCounts.DECODE_ERROR").value(2))
                .andReturn().getResponse().getContentAsString();
        List<Number> backlog = JsonPath.read(body, "$.response.series.backlog[*][1]");
        assertThat(backlog.stream().mapToLong(Number::longValue).sum()).isEqualTo(1);
        List<Number> perMin = JsonPath.read(body, "$.response.series.receivedPerMin[*][1]");
        assertThat(perMin.stream().mapToDouble(Number::doubleValue).sum() * 5).isEqualTo(14.0);
        mvc.perform(as(org, admin, get("/core/ops/metrics/ingest?range=7d"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.series.latencyP95", hasSize(168)));
        mvc.perform(as(org, admin, get("/core/ops/metrics/ingest?range=1y"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, operator, get("/core/ops/metrics/ingest"))).andExpect(status().isForbidden());
    }
}
