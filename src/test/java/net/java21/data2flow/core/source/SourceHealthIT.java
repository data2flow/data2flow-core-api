package net.java21.data2flow.core.source;

import net.java21.data2flow.contracts.connector.ConnectionErrorKind;
import net.java21.data2flow.contracts.connector.ConnectorState;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.SourceRuntimeReported;
import net.java21.data2flow.contracts.message.event.SourceStatsReported;
import net.java21.data2flow.contracts.message.event.SourceStatsReported.Producer;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.source.service.SourceJobs;
import net.java21.data2flow.core.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DSC-02.01 연결 상태(EVT-DSC-02 소비 → 대표 상태, EVT-DSC-04), DSC-02.03 지표(EVT-DSC-03 합산, API-DSC-09, 7일 보관),
 * 무수신 EVT-DSC-05, 인스턴스 상태 API-DSC-14, 사용처 API-DSC-11, 무시 목록 API-DSC-13 — TC-DSC-055~058·070~073·096
 */
class SourceHealthIT extends SourceItSupport {

    @Autowired
    SourceJobs jobs;

    private long activeSource(String code) throws Exception {
        return createSource(mqttBody(code, "\"activate\":true"));
    }

    private void report(long source, String instance, ConnectorState state, ConnectionErrorKind kind, Instant at) {
        assertThat(deliver(EventType.SOURCE_RUNTIME_REPORTED, org, new SourceRuntimeReported(source, instance, state, kind,
                kind == null ? null : "실패: " + kind, "data2flow-x-prod-" + instance.charAt(instance.length() - 1),
                state == ConnectorState.CONNECTED ? at : null, 2), at)).isTrue();
    }

    @Test
    @DisplayName("[DSC-02.01][AT-DSC-04.3] 인스턴스 2개 중 1개만 연결 → 대표 CONNECTED + 일부 연결(1/2), 둘 다 ERROR → ERROR(최근 종류), EVT-DSC-04 — TC-DSC-058")
    void representativeState() throws Exception {
        long id = activeSource("rep");
        Instant t = MutableClock.T0;
        report(id, "ingress-0", ConnectorState.CONNECTED, null, t);
        report(id, "ingress-1", ConnectorState.CONNECTING, null, t);
        mvc.perform(as(org, operator, get("/core/sources"))).andExpect(jsonPath("$.responses[0].state").value("CONNECTED"))
                .andExpect(jsonPath("$.responses[0].stateDetail.connectedInstances").value(1))
                .andExpect(jsonPath("$.responses[0].stateDetail.totalInstances").value(2));
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/runtime"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.state").value("CONNECTED"))
                .andExpect(jsonPath("$.response.instances.length()").value(2))
                .andExpect(jsonPath("$.response.instances[0].clientId").value("data2flow-x-prod-0"))
                .andExpect(jsonPath("$.response.instances[0].stale").value(false));
        mvc.perform(as(org, operator, get("/core/sources/" + id))).andExpect(jsonPath("$.response.clientIds.length()").value(2))
                .andExpect(jsonPath("$.response.runtime[1].state").value("CONNECTING"));

        clock.advance(Duration.ofSeconds(30));
        report(id, "ingress-0", ConnectorState.ERROR, ConnectionErrorKind.TLS, clock.instant());
        clock.advance(Duration.ofSeconds(1));
        report(id, "ingress-1", ConnectorState.ERROR, ConnectionErrorKind.AUTH, clock.instant());
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/runtime"))).andExpect(jsonPath("$.response.state").value("ERROR"))
                .andExpect(jsonPath("$.response.stateDetail.errorKind").value("AUTH"));
        // 늦게 온 오래된 보고는 새 보고를 덮지 않는다
        report(id, "ingress-1", ConnectorState.CONNECTED, null, t);
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/runtime"))).andExpect(jsonPath("$.response.instances[1].state").value("ERROR"));
        assertThat(outbox(org, "source.connection.changed")).extracting(p -> p.replaceAll(".*\"to\":\"([A-Z]+)\".*", "$1"))
                .containsExactly("CONNECTING", "CONNECTED", "CONNECTING", "ERROR");

        // 같은 messageId를 두 번 받아도 한 번만 처리(소비자 중복 제거)
        var event = net.java21.data2flow.contracts.message.DomainEvent.of(EventType.SOURCE_RUNTIME_REPORTED, org,
                new SourceRuntimeReported(id, "ingress-2", ConnectorState.CONNECTED, null, null, "c2", null, 0), null, clock);
        assertThat(consumer.onMessage(codec.write(event))).isTrue();
        assertThat(consumer.onMessage(codec.write(event))).isFalse();
        // 없는 소스·다른 조직 소스 보고는 무시
        assertThat(deliver(EventType.SOURCE_RUNTIME_REPORTED, org, new SourceRuntimeReported(987654, "i", ConnectorState.CONNECTED,
                null, null, null, null, 0), clock.instant())).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.source_runtimes").query(Long.class).single()).isEqualTo(3);
    }

    @Test
    @DisplayName("[DSC-02.01][AT-DSC-04.1] 보고가 90초 넘게 끊긴 인스턴스는 빠지고 1분 점검이 대표 상태를 DISCONNECTED로 바꾼다(EVT-DSC-04)")
    void staleInstancesAndCheckJob() throws Exception {
        long id = activeSource("stale");
        report(id, "ingress-0", ConnectorState.CONNECTED, null, clock.instant());
        clock.advance(Duration.ofSeconds(91));
        assertThat(jobs.checkOnce()).isZero();
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/runtime"))).andExpect(jsonPath("$.response.state").value("DISCONNECTED"))
                .andExpect(jsonPath("$.response.instances[0].stale").value(true))
                .andExpect(jsonPath("$.response.stateDetail.totalInstances").value(0));
        assertThat(outbox(org, "source.connection.changed")).last().asString().contains("\"from\":\"CONNECTED\"", "\"to\":\"DISCONNECTED\"");
    }

    @Test
    @DisplayName("[DSC-02.03] EVT-DSC-03 ingress·pipeline 합산(같은 분), API-DSC-09 구간·0 채움·7일 제한, 목록 분당 수신·디코딩 실패율(0~1) — TC-DSC-071·073")
    void statsAggregation() throws Exception {
        long id = activeSource("stats");
        Instant m0 = MutableClock.T0;
        for (int i = 0; i < 6; i++) {
            Instant minute = m0.plus(Duration.ofMinutes(i));
            deliver(EventType.SOURCE_STATS_1M, org, new SourceStatsReported(id, minute.plusSeconds(7), Producer.INGRESS,
                    Map.of("received", 10L, "bytes", 1200L, "reconnects", i == 2 ? 1L : 0L)), minute);
            deliver(EventType.SOURCE_STATS_1M, org, new SourceStatsReported(id, minute, Producer.PIPELINE,
                    Map.of("accepted", 9L, "decodeErrors", 1L, "dup", 0L, "unknownCounter", 5L)), minute);
        }
        clock.set(m0.plus(Duration.ofMinutes(6)).plusSeconds(20));
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/stats").param("from", "2026-10-03T00:00:00Z")
                        .param("to", "2026-10-03T00:10:00Z").param("bucket", "5m")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.length()").value(2))
                .andExpect(jsonPath("$.response[0].t").value("2026-10-03T00:00:00Z"))
                .andExpect(jsonPath("$.response[0].received").value(50))
                .andExpect(jsonPath("$.response[0].accepted").value(45))
                .andExpect(jsonPath("$.response[0].decodeErrors").value(5))
                .andExpect(jsonPath("$.response[0].reconnects").value(1))
                .andExpect(jsonPath("$.response[0].bytes").value(6000))
                .andExpect(jsonPath("$.response[1].received").value(10));
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/stats").param("from", "2026-10-03T00:00:00Z")
                        .param("to", "2026-10-03T00:03:00Z")))
                .andExpect(jsonPath("$.response.length()").value(3)).andExpect(jsonPath("$.response[2].received").value(10));
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/stats"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(289));
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/stats").param("from", "2026-09-20T00:00:00Z")
                        .param("to", "2026-10-03T00:00:00Z"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/stats").param("from", "2026-10-03T00:00:00Z")
                        .param("to", "2026-10-02T00:00:00Z"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/stats").param("bucket", "2m"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/stats").param("from", "2026-10-01T00:00:00Z")
                        .param("to", "2026-10-03T06:00:00Z"))).andExpect(jsonPath("$.response.length()").value(54));

        mvc.perform(as(org, operator, get("/core/sources"))).andExpect(jsonPath("$.responses[0].ratePerMin").value(10.0))
                .andExpect(jsonPath("$.responses[0].decodeErrorRate1h").value(0.1))
                .andExpect(jsonPath("$.responses[0].lastReceivedAt").value("2026-10-03T00:05:00Z"))
                .andExpect(jsonPath("$.responses[0].rateSeries[59]").value(0))
                .andExpect(jsonPath("$.responses[0].rateSeries[58]").value(10));
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/usage"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.volume7d[0].day").value("2026-10-03"))
                .andExpect(jsonPath("$.response.volume7d[0].count").value(60))
                .andExpect(jsonPath("$.response.flows.length()").value(0));

        // 보관 기간(7일) 지난 지표는 받지 않고, 정리 작업이 지운다
        deliver(EventType.SOURCE_STATS_1M, org, new SourceStatsReported(id, m0.minus(Duration.ofDays(8)), Producer.INGRESS,
                Map.of("received", 1L)), m0);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.source_stat_1m").query(Long.class).single()).isEqualTo(6);
        clock.set(m0.plus(Duration.ofDays(7)).plusSeconds(150));
        assertThat(jobs.purgeOnce()).isEqualTo(3);
    }

    @Test
    @DisplayName("[DSC-02.03][AT-DSC-04.4] 무수신 기준 600초: 마지막 수신 후 601초 → source.no-data 1건(1분 점검), 다시 받으면 source.data-resumed")
    void noData() throws Exception {
        long id = activeSource("quiet");
        Instant m0 = MutableClock.T0;
        deliver(EventType.SOURCE_STATS_1M, org, new SourceStatsReported(id, m0, Producer.INGRESS, Map.of("received", 3L)), m0);
        clock.set(m0.plusSeconds(60 + 600));
        assertThat(jobs.checkOnce()).isZero();
        clock.set(m0.plusSeconds(60 + 601));
        assertThat(jobs.checkOnce()).isEqualTo(1);
        assertThat(jobs.checkOnce()).isZero();
        assertThat(outbox(org, "source.no-data")).hasSize(1).first().asString()
                .contains("\"sourceId\":" + id, "\"thresholdSec\":600", "\"lastReceivedAt\":\"2026-10-03T00:00:00Z\"");
        Instant later = m0.plus(Duration.ofMinutes(12));
        deliver(EventType.SOURCE_STATS_1M, org, new SourceStatsReported(id, later, Producer.INGRESS, Map.of("received", 1L)), later);
        assertThat(outbox(org, "source.data-resumed")).hasSize(1).first().asString().contains("\"lastReceivedAt\":\"2026-10-03T00:12:00Z\"");
        // 한 번도 받지 않은 소스는 활성화 시각부터 잰다
        long never = activeSource("never");
        clock.advance(Duration.ofSeconds(601));
        assertThat(jobs.checkOnce()).isEqualTo(1);
        assertThat(outbox(org, "source.no-data")).hasSize(2);
        assertThat(jdbc.sql("SELECT no_data FROM data2flow_core.source_states WHERE source_id = :id").param("id", never)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("[DSC-02.01][BR-DEV-07] 무시 목록 조회(페이지)·빼기 204 + 감사, 없는 항목 404 — API-DSC-13, TC-DSC-057")
    void ignoreList() throws Exception {
        long id = createSource(mqttBody("ign", null));
        for (String ext : new String[]{"24e1240000000001", "24e1240000000002"}) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.source_ignore_entries (source_id, external_id, organization_id, reason, created_by, created_at)
                            VALUES (:s, :e, :o, 'REJECTED', :u, :t)""")
                    .param("s", id).param("e", ext).param("o", org).param("u", admin).param("t", Pg.ts(clock.instant())).update();
        }
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/ignore-list").param("size", "1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2)).andExpect(jsonPath("$.responses[0].reason").value("REJECTED"))
                .andExpect(jsonPath("$.responses[0].createdBy").value(Long.toString(admin)));
        mvc.perform(as(org, integrator, delete("/core/sources/" + id + "/ignore-list/24e1240000000001"))).andExpect(status().isNoContent());
        mvc.perform(as(org, integrator, delete("/core/sources/" + id + "/ignore-list/24e1240000000001"))).andExpect(status().isNotFound());
        mvc.perform(as(org, operator, get("/core/sources/" + id + "/ignore-list"))).andExpect(jsonPath("$.totalCount").value(1));
        assertThat(auditCount(org, "SOURCE_IGNORE_REMOVED")).isEqualTo(1);
    }
}
