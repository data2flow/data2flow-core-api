package net.java21.data2flow.core.alarm;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.NotificationDeliveryResult;
import net.java21.data2flow.contracts.notification.DeliveryStatus;
import net.java21.data2flow.core.live.service.LiveHub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlarmStreamIT extends AlarmItSupport {

    @Autowired
    LiveHub hub;

    @AfterEach
    void close() {
        hub.closeAll();
    }

    MvcResult alarms(long userId) throws Exception {
        return mvc.perform(as(org, userId, get("/core/stream/alarms").accept(MediaType.TEXT_EVENT_STREAM))).andExpect(request().asyncStarted())
                .andReturn();
    }

    MvcResult live(long userId, String topics) throws Exception {
        return mvc.perform(as(org, userId, get("/core/stream/live").param("topics", topics).accept(MediaType.TEXT_EVENT_STREAM)))
                .andExpect(request().asyncStarted()).andReturn();
    }

    /** 아웃박스에 쌓인 이벤트를 실시간 허브에 넣는다(릴레이 → data2flow.events → core.live 대신) */
    void relay(String routingKey) {
        for (String e : events(org, routingKey)) {
            hub.onEvent(codec.readEvent(e.getBytes(StandardCharsets.UTF_8)));
        }
    }

    static int count(MvcResult r, String event) {
        Matcher m = Pattern.compile("(?m)^event:" + Pattern.quote(event) + "$").matcher(body(r));
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    static void awaitCount(MvcResult r, String event, int expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(count(r, event)).isEqualTo(expected));
    }

    @Test
    @DisplayName("[RUL-02.01][AT-RUL-06.1][API-RUL-14] 알람 스트림(API-RUL-14): 발생·확인·해제가 alarm.raised·updated·cleared로, 공간 범위 밖 사용자에게는 가지 않는다")
    void alarmStream() throws Exception {
        data.spaceScope(org, viewer, List.of(classroom));
        MvcResult op = alarms(operator);
        MvcResult limited = alarms(viewer);
        raise(sensor1, 1100, clock.instant());
        relay("alarm.raised");
        awaitCount(op, "alarm.raised", 1);
        assertThat(body(op)).contains("\"title\":\"CO2 높음 1100.0\"");
        mvc.perform(as(org, operator, post("/core/alarms/" + alarmId(sensor1) + "/ack"))).andExpect(status().isOk());
        relay("alarm.acked");
        awaitCount(op, "alarm.updated", 1);
        clear(sensor1, 800, clock.instant().plusSeconds(30));
        relay("alarm.cleared");
        awaitCount(op, "alarm.cleared", 1);
        assertThat(count(limited, "alarm.raised")).isZero();
        assertThat(count(limited, "alarm.cleared")).isZero();
    }

    @Test
    @DisplayName("[DSH-05.01][API-DSH-20] 실시간 alarms·notifications 토픽: alarm 이벤트, 본인 WEB 알림만 notification, 비상 정지는 모든 연결에 emergency-stop")
    void liveTopics() throws Exception {
        MvcResult op = live(operator, "alarms,notifications");
        MvcResult other = live(admin, "notifications");
        raise(sensor1, 1100, clock.instant());
        relay("alarm.raised");
        awaitCount(op, "alarm", 1);
        assertThat(body(op)).contains("\"state\":\"ACTIVE\"").contains("\"severity\":\"MAJOR\"");
        assertThat(count(other, "alarm")).isZero();
        long alarm = alarmId(sensor1);
        hub.onEvent(DomainEvent.of(EventType.NOTIFICATION_DELIVERED, org, new NotificationDeliveryResult(UUID.randomUUID(), alarm, null, "WEB",
                "USER:" + operator, DeliveryStatus.SENT, 1, null, clock.instant()), null, clock));
        hub.onEvent(DomainEvent.of(EventType.NOTIFICATION_DELIVERED, org, new NotificationDeliveryResult(UUID.randomUUID(), alarm, null,
                "TELEGRAM", "USER:" + operator, DeliveryStatus.SENT, 1, null, clock.instant()), null, clock));
        awaitCount(op, "notification", 1);
        assertThat(body(op)).contains("\"link\":\"/alarms/" + alarm + "\"");
        assertThat(count(other, "notification")).isZero();
        mvc.perform(as(org, operator, json(post("/core/emergency-stops"), "{\"scope\":{\"type\":\"ORG\"},\"reason\":\"화재\"}")))
                .andExpect(status().isCreated());
        relay("control.emergency.started");
        awaitCount(op, "emergency-stop", 1);
        awaitCount(other, "emergency-stop", 1);
    }
}
