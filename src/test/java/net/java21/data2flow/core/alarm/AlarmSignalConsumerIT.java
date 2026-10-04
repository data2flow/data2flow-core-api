package net.java21.data2flow.core.alarm;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.AlarmSignal;
import net.java21.data2flow.contracts.message.event.AlarmStateChanged;
import net.java21.data2flow.contracts.test.message.MessageFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class AlarmSignalConsumerIT extends AlarmItSupport {

    @Test
    @DisplayName("[RUL-02.03][TC-RUL-047] alarm.signal RAISE 3건(같은 키) → 열린 알람 1행·occurrence 3·최고값, CLEAR → CLEARED + alarm.cleared, 다시 RAISE → 새 행")
    void upsertAndClear() {
        assertThat(raise(sensor1, 1050, clock.instant())).isTrue();
        assertThat(raise(sensor1, 1200, clock.instant().plusSeconds(60))).isTrue();
        assertThat(raise(sensor1, 1100, clock.instant().plusSeconds(120))).isTrue();
        long id = alarmId(sensor1);
        var row = jdbc.sql("SELECT occurrence_count, trigger_value, peak_value, last_value, status, space_id FROM data2flow_core.alarms WHERE id = :id")
                .param("id", id).query((rs, n) -> new Object[]{rs.getInt(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getString(5),
                        rs.getLong(6)}).single();
        assertThat(row).containsExactly(3, 1050.0, 1200.0, 1100.0, "ACTIVE", lab);
        assertThat(count("SELECT count(*) FROM data2flow_core.alarms WHERE organization_id = :org")).isEqualTo(1);
        assertThat(events(org, "alarm.raised")).hasSize(1);
        assertThat(events(org, "alarm.reraised")).hasSize(2);
        clear(sensor1, 880, clock.instant().plusSeconds(300));
        assertThat(alarmStatus(id)).isEqualTo("CLEARED");
        String cleared = events(org, "alarm.cleared").getFirst();
        DomainEvent<?> event = codec.readEvent(cleared.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AlarmStateChanged changed = (AlarmStateChanged) event.payload();
        assertThat(changed.alarm().id()).isEqualTo(id);
        assertThat(changed.alarm().clearReason().name()).isEqualTo("AUTO");
        assertThat(changed.alarm().space().path()).endsWith("/" + lab);
        raise(sensor1, 1300, clock.instant().plusSeconds(600));
        assertThat(alarmId(sensor1)).isNotEqualTo(id);
        assertThat(count("SELECT count(*) FROM data2flow_core.alarms WHERE organization_id = :org")).isEqualTo(2);
        // 열린 알람이 없는 키의 해제는 아무 일도 하지 않는다
        clear(sensor2, 800, clock.instant());
        assertThat(count("SELECT count(*) FROM data2flow_core.alarms WHERE organization_id = :org AND device_id = " + sensor2)).isZero();
    }

    @Test
    @DisplayName("[RUL-02.03][TC-RUL-047] 소비자 2개가 같은 키를 동시에 처리해도 부분 UNIQUE로 열린 알람은 1행(BR-RUL-02)")
    void concurrentConsumers() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            double value = 1100 + i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                try {
                    return raise(sensor2, value, clock.instant());
                } catch (RuntimeException ex) {
                    // 직렬화 충돌로 롤백된 메시지는 큐가 다시 보낸다(최소 1회)
                    return raise(sensor2, value, clock.instant());
                }
            }, pool));
        }
        start.countDown();
        for (CompletableFuture<Boolean> f : futures) {
            f.get(java.util.concurrent.TimeUnit.SECONDS.toMillis(30), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        pool.shutdown();
        assertThat(count("SELECT count(*) FROM data2flow_core.alarms WHERE organization_id = :org AND status = 'ACTIVE'")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT occurrence_count FROM data2flow_core.alarms WHERE organization_id = :org").param("org", org)
                .query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    @DisplayName("[RUL-02.01][TC-RUL-038] flow-engine 골든 픽스처 alarm-signal-raise를 읽어 알람 속성(심각도·출처·대상·값·기준) 복원")
    void contractFixture() {
        DomainEvent<?> fixture = codec.readEvent(MessageFixtures.domainEventJson("alarm-signal-raise"));
        assertThat(fixture.eventType()).isEqualTo(EventType.ALARM_SIGNAL);
        AlarmSignal s = (AlarmSignal) fixture.payload();
        AlarmSignal mine = new AlarmSignal(s.signal(), "flow:" + flowId + ":n-alarm-1:" + sensor1, s.sourceType(), null, flowId, s.flowVersion(),
                s.nodeId(), s.severity(), s.title(), sensor1, lab, s.metric(), s.value(), s.threshold(), clock.instant(), s.triggerMessageId());
        deliver(EventType.ALARM_SIGNAL, org, mine, clock.instant());
        var a = jdbc.sql("SELECT severity, source_type, title, metric_key, trigger_value, threshold::text FROM data2flow_core.alarms WHERE organization_id = :org")
                .param("org", org).query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        Double.toString(rs.getDouble(5)), rs.getString(6))).single();
        assertThat(a.subList(0, 5)).containsExactly("MAJOR", "RULE", "실습실 고온(27℃ 초과 5분)", "temperature", "27.6");
        assertThat(a.get(5)).contains("\"raise\": 27.0").contains("\"clear\": 26.0");
    }

    @Test
    @DisplayName("[RUL-04.02][TC-RUL-088] 30분 안 6번째 발생에 FLAPPING_ON(alarm.flapping), 30분 변화가 없으면 1분 작업이 FLAPPING_OFF")
    void flapping() {
        for (int i = 0; i < 6; i++) {
            raise(sensor1, 1100, clock.instant().plus(Duration.ofMinutes(i * 2L)));
            clear(sensor1, 800, clock.instant().plus(Duration.ofMinutes(i * 2L + 1)));
        }
        assertThat(events(org, "alarm.flapping")).hasSize(1);
        clock.advance(Duration.ofMinutes(10));
        raise(sensor1, 1100, clock.instant());
        long id = alarmId(sensor1);
        assertThat(jdbc.sql("SELECT flapping FROM data2flow_core.alarms WHERE id = :id").param("id", id).query(Boolean.class).single()).isTrue();
        clock.advance(Duration.ofMinutes(31));
        assertThat(jobs.minute()).isTrue();
        assertThat(jdbc.sql("SELECT flapping FROM data2flow_core.alarms WHERE id = :id").param("id", id).query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.alarm_events WHERE alarm_id = :id AND type = 'FLAPPING_OFF'").param("id", id)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("[RUL-04.03][TC-RUL-089] 같은 공간 5분 안 서로 다른 출처 알람 2건 → 공간 이벤트 1건 아래 묶임")
    void spaceEvent() {
        raise(sensor1, 1100, clock.instant());
        raise(net.java21.data2flow.contracts.alarm.AlarmKeys.flow(java.util.UUID.randomUUID().toString(), "n-hot", Long.toString(sensor2)),
                net.java21.data2flow.contracts.alarm.AlarmSourceType.FLOW, null, net.java21.data2flow.contracts.alarm.AlarmSeverity.MINOR,
                sensor2, null, 29, clock.instant().plusSeconds(120));
        assertThat(count("SELECT count(*) FROM data2flow_core.space_events WHERE organization_id = :org")).isEqualTo(1);
        assertThat(count("SELECT count(DISTINCT space_event_id) FROM data2flow_core.alarms WHERE organization_id = :org AND space_event_id IS NOT NULL"))
                .isEqualTo(1);
        assertThat(count("SELECT alarm_count FROM data2flow_core.space_events WHERE organization_id = :org")).isEqualTo(2);
    }
}
