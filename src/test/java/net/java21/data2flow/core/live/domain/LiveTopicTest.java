package net.java21.data2flow.core.live.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** DSH-05.01 API-DSH-20·21 토픽 형식 */
class LiveTopicTest {

    @Test
    @DisplayName("[DSH-05.01] 토픽 이름을 읽는다: home·space·telemetry(d 접두사 허용)·ingest·ingest-messages·alarms·notifications(M4)·이후 마일스톤 토픽")
    void parsesTopics() {
        LiveTopic.Parsed parsed = LiveTopic.parse(
                "home, space:31,telemetry:1042.co2,telemetry:d17.temperature,ingest,ingest-messages?sourceId=3&deviceId=&result=script_error,"
                        + "notifications,alarms,commands:5,analytics:run:9,home");
        assertThat(parsed.invalid()).isEmpty();
        assertThat(parsed.count()).isEqualTo(10);
        assertThat(parsed.valid()).containsExactly(
                new LiveTopic.Home("home"),
                new LiveTopic.Space("space:31", 31),
                new LiveTopic.Telemetry("telemetry:1042.co2", 1042, "co2"),
                new LiveTopic.Telemetry("telemetry:d17.temperature", 17, "temperature"),
                new LiveTopic.Ingest("ingest"),
                new LiveTopic.IngestMessages("ingest-messages?sourceId=3&deviceId=&result=script_error", 3L, null, "SCRIPT_ERROR"),
                new LiveTopic.Notifications("notifications"),
                new LiveTopic.Alarms("alarms"),
                new LiveTopic.Commands("commands:5", 5),
                new LiveTopic.Future("analytics:run:9"));
        assertThat(LiveTopic.parse("sources").valid()).containsExactly(new LiveTopic.Sources("sources"));
        assertThat(LiveTopic.parse("ingest-messages").valid()).containsExactly(new LiveTopic.IngestMessages("ingest-messages", null, null, null));
        assertThat(LiveTopic.parse("ingest-messages?deviceId=7").valid())
                .containsExactly(new LiveTopic.IngestMessages("ingest-messages?deviceId=7", null, 7L, null));
    }

    @Test
    @DisplayName("[DSH-05.01] 형식이 틀린 토픽은 invalid로 모은다, 빈 목록은 0개")
    void rejectsInvalid() {
        LiveTopic.Parsed parsed = LiveTopic.parse("space:abc,telemetry:1.,weather,ingest-messages?sourceId=x,ingest-messages?foo=1,"
                + "ingest-messages?deviceId=a,ingest-messages?result=1!");
        assertThat(parsed.valid()).isEmpty();
        assertThat(parsed.invalid()).hasSize(7);
        assertThat(LiveTopic.parse(null).count()).isZero();
        assertThat(LiveTopic.parse(" , ").count()).isZero();
        String many = IntStream.rangeClosed(1, 201).mapToObj(i -> "space:" + i).collect(Collectors.joining(","));
        assertThat(LiveTopic.parse(many).count()).isEqualTo(201).isGreaterThan(LiveTopic.MAX_TOPICS);
    }
}
