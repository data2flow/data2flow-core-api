package net.java21.data2flow.core.live.event;

import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.core.config.CoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** DSH-05.01 텔레메트리 소비자: RabbitMQ가 없어도 서비스는 뜨고 뒤에서 다시 시도한다, 끌 수 있다 */
class TelemetryStreamConsumerTest {

    private static CoreProperties props(boolean enabled, String developer) {
        CoreProperties.Live live = new CoreProperties.Live(enabled, developer, null, false, null, null, null, null, null, null,
                Duration.ofMinutes(10), null);
        return new CoreProperties(null, null, null, null, null, null, null, null, null, null, null, null, live);
    }

    @Test
    @DisplayName("[DSH-05.01] 스트림에 닿지 못하면 시작은 성공하고 붙지 않은 채 다시 시도를 예약한다, 꺼져 있으면 아무것도 안 한다")
    void startsWithoutBroker() {
        RabbitProperties rabbit = new RabbitProperties();
        rabbit.getStream().setHost("127.0.0.1");
        rabbit.getStream().setPort(1);
        TelemetryStreamConsumer consumer = new TelemetryStreamConsumer(rabbit, props(true, "nhn"), MessageCodec.create(), null);
        assertThat(consumer.group()).isEqualTo("core-live-nhn");
        consumer.start();
        assertThat(consumer.isRunning()).isTrue();
        assertThat(consumer.subscribed()).isFalse();
        consumer.handle("not json".getBytes()); // 형식 오류는 버린다
        consumer.stop();
        assertThat(consumer.isRunning()).isFalse();

        TelemetryStreamConsumer off = new TelemetryStreamConsumer(rabbit, props(false, null), MessageCodec.create(), null);
        assertThat(off.group()).isEqualTo("core-live");
        off.start();
        assertThat(off.subscribed()).isFalse();
        off.stop();
    }
}
