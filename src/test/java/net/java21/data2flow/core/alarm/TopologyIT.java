package net.java21.data2flow.core.alarm;

import net.java21.data2flow.contracts.alarm.AlarmKeys;
import net.java21.data2flow.contracts.alarm.AlarmSourceType;
import net.java21.data2flow.contracts.capability.ExpectedEffect;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.AlarmSignal;
import net.java21.data2flow.contracts.message.event.CommandNoEffect;
import net.java21.data2flow.contracts.message.event.DeviceConnectivityChanged;
import net.java21.data2flow.contracts.message.event.DriverCircuitChanged;
import net.java21.data2flow.contracts.message.event.GatewayConnectivityChanged;
import net.java21.data2flow.contracts.message.event.OscillationBlocked;
import net.java21.data2flow.contracts.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class TopologyIT extends AlarmItSupport {

    long gateway() {
        long id = jdbc.sql("""
                        INSERT INTO data2flow_core.gateways (organization_id, source_id, gateway_eui, name, space_id, last_seen_at, status)
                        VALUES (:org, :source, 'GW1', '1층 게이트웨이', :space, :seen, 'ONLINE') RETURNING id""")
                .param("org", org).param("source", source).param("space", lab)
                .param("seen", java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(20)))).query(Long.class).single();
        for (long d : new long[]{sensor1, sensor2}) {
            jdbc.sql("INSERT INTO data2flow_pipeline.device_state (device_id, organization_id, best_gateway_eui) VALUES (:d, :org, 'GW1')")
                    .param("d", d).param("org", org).update();
        }
        return id;
    }

    void noData(long deviceId, Instant at) {
        AlarmSignal s = AlarmSignal.raise(AlarmKeys.flow(flowId, "n-nodata", Long.toString(deviceId)), AlarmSourceType.FLOW, null, flowId, 1,
                "n-nodata", net.java21.data2flow.contracts.alarm.AlarmSeverity.MINOR, "무수신", deviceId, null, null, null, null, at,
                UUID.randomUUID().toString());
        deliver(EventType.ALARM_SIGNAL, org, s, at);
    }

    long alarm(String key) {
        return jdbc.sql("SELECT id FROM data2flow_core.alarms WHERE organization_id = :org AND alarm_key = :k").param("org", org).param("k", key)
                .query(Long.class).single();
    }

    @Test
    @DisplayName("[RUL-04.01][DEV-09.01][TC-RUL-084][TC-RUL-086] 게이트웨이 오프라인(1분 작업 → EVT-DEV-08) → MAJOR 시스템 알람 하나에 아래 기기 무수신 알람이 하위(PARENT)로 묶이고, 복구되면 함께 해제")
    void gatewayGroupsChildren() throws Exception {
        long gw = gateway();
        noData(sensor1, clock.instant());
        assertThat(jobs.minute()).isTrue();
        assertThat(events(org, "gateway.connectivity.changed")).hasSize(1);
        assertThat(jdbc.sql("SELECT status FROM data2flow_core.gateways WHERE id = :id").param("id", gw).query(String.class).single())
                .isEqualTo("OFFLINE");
        deliver(EventType.GATEWAY_CONNECTIVITY_CHANGED, org, new GatewayConnectivityChanged(gw, "GW1", DeviceConnectivityChanged.Connectivity.ONLINE,
                DeviceConnectivityChanged.Connectivity.OFFLINE, clock.instant().minus(Duration.ofMinutes(20))), clock.instant());
        long parent = alarm("system:GATEWAY_OFFLINE:" + gw);
        long child1 = alarmId(sensor1);
        noData(sensor2, clock.instant().plusSeconds(5));
        long child2 = alarmId(sensor2);
        for (long c : new long[]{child1, child2}) {
            var row = jdbc.sql("SELECT status, parent_alarm_id, suppressed_reason FROM data2flow_core.alarms WHERE id = :id").param("id", c)
                    .query((rs, n) -> new Object[]{rs.getString(1), rs.getLong(2), rs.getString(3)}).single();
            assertThat(row).containsExactly("SUPPRESSED", parent, "PARENT");
        }
        mvc.perform(as(org, operator, get("/core/alarms/" + parent))).andExpect(jsonPath("$.response.alarm.childCount").value(2))
                .andExpect(jsonPath("$.response.alarm.severity").value("MAJOR"))
                .andExpect(jsonPath("$.response.alarm.title").value("게이트웨이 오프라인: 1층 게이트웨이"));
        mvc.perform(as(org, operator, get("/core/alarms/" + child2))).andExpect(jsonPath("$.response.alarm.parentAlarmId").value(Long.toString(parent)));
        // 다시 수신 → 작업이 ONLINE 전환, 처리기가 부모 해제 → 하위도 PARENT_CLEARED
        jdbc.sql("UPDATE data2flow_core.gateways SET last_seen_at = :t WHERE id = :id").param("t", java.sql.Timestamp.from(clock.instant()))
                .param("id", gw).update();
        clock.advance(Duration.ofMinutes(1));
        jobs.minute();
        assertThat(events(org, "gateway.connectivity.changed")).hasSize(2);
        deliver(EventType.GATEWAY_CONNECTIVITY_CHANGED, org, new GatewayConnectivityChanged(gw, "GW1", DeviceConnectivityChanged.Connectivity.OFFLINE,
                DeviceConnectivityChanged.Connectivity.ONLINE, clock.instant()), clock.instant());
        assertThat(alarmStatus(parent)).isEqualTo("CLEARED");
        assertThat(jdbc.sql("SELECT clear_reason FROM data2flow_core.alarms WHERE id = :id").param("id", child1).query(String.class).single())
                .isEqualTo("PARENT_CLEARED");
    }

    @Test
    @DisplayName("[ACT-02.08][TC-ACT-075] 진동 차단 EVT-ACT-08 → WARNING system:OSCILLATION:{기기}:{능력} 하나(반복은 재발생)")
    void oscillation() {
        OscillationBlocked o = new OscillationBlocked(UUID.randomUUID(), sensor1, lab, "switch", "setState", 6, 600,
                CommandSource.flow(flowId, 1, "n-ctl", UUID.randomUUID().toString()), clock.instant());
        deliver(EventType.CONTROL_OSCILLATION_BLOCKED, org, o, clock.instant());
        deliver(EventType.CONTROL_OSCILLATION_BLOCKED, org, new OscillationBlocked(UUID.randomUUID(), sensor1, lab, "switch", "setState", 7, 600,
                CommandSource.user(operator), clock.instant().plusSeconds(60)), clock.instant().plusSeconds(60));
        var row = jdbc.sql("SELECT severity, occurrence_count, title FROM data2flow_core.alarms WHERE organization_id = :org AND alarm_key = :k")
                .param("org", org).param("k", "system:OSCILLATION:" + sensor1 + ":switch")
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getInt(2), rs.getString(3)}).single();
        assertThat(row[0]).isEqualTo("WARNING");
        assertThat(row[1]).isEqualTo(2);
        assertThat((String) row[2]).contains("switch");
    }

    @Test
    @DisplayName("[ACT-03.04][TC-ACT-085] 드라이버 서킷 열림 → MAJOR 알람, 닫힘 → 자동 해제")
    void driverCircuit() {
        deliver(EventType.DRIVER_CIRCUIT_OPENED, org, new DriverCircuitChanged(7, "MQTT", 0.6, clock.instant()), clock.instant());
        long id = alarm("system:DRIVER_CIRCUIT_OPEN:7");
        assertThat(alarmStatus(id)).isEqualTo("ACTIVE");
        deliver(EventType.DRIVER_CIRCUIT_CLOSED, org, new DriverCircuitChanged(7, "MQTT", 0.0, clock.instant().plusSeconds(60)),
                clock.instant().plusSeconds(60));
        assertThat(alarmStatus(id)).isEqualTo("CLEARED");
    }

    @Test
    @DisplayName("[ACT-06.02][TC-IAM-174] 효과 없음 EVT-ACT-04 → 감사 COMMAND_NO_EFFECT(대상 기기, 원인 명령 ID)")
    void noEffectAudit() {
        UUID command = UUID.randomUUID();
        deliver(EventType.COMMAND_NO_EFFECT, org, new CommandNoEffect(command, sensor1, lab, "thermostat",
                new CommandNoEffect.Expected("temperature", ExpectedEffect.Direction.DOWN, 30), CommandNoEffect.Observed.between(26.0, 26.1),
                clock.instant()), clock.instant());
        assertThat(auditCount(org, "COMMAND_NO_EFFECT")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.audit_logs WHERE organization_id = :org AND action = 'COMMAND_NO_EFFECT'"
                        + " AND target_id = :d AND detail::text LIKE :c").param("org", org).param("d", Long.toString(sensor1))
                .param("c", "%" + command + "%").query(Long.class).single()).isEqualTo(1);
    }
}
