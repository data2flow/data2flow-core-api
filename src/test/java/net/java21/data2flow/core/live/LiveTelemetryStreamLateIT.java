package net.java21.data2flow.core.live;

import com.rabbitmq.stream.Address;
import com.rabbitmq.stream.Environment;
import net.java21.data2flow.contracts.messaging.SuperStreamSpec;
import net.java21.data2flow.core.live.event.TelemetryStreamConsumer;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DSH-05.01: core가 pipeline보다 먼저 떠서 {@code data2flow.telemetry}가 아직 없으면 붙지 않고 기다렸다가, 스트림이 생기면 붙는다
 * (빈 파티션 목록으로 붙어 영영 아무것도 받지 못하던 문제, M2 시연에서 발견).
 */
class LiveTelemetryStreamLateIT extends IntegrationTestSupport {

    private static final int STREAM_PORT = 5552;

    static final GenericContainer<?> STREAM_RABBIT = new GenericContainer<>("rabbitmq:3.13-management")
            .withExposedPorts(STREAM_PORT, 5672)
            .withCopyToContainer(Transferable.of("[rabbitmq_management,rabbitmq_stream]."), "/etc/rabbitmq/enabled_plugins")
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    static {
        STREAM_RABBIT.start();
    }

    @DynamicPropertySource
    static void stream(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.stream.host", STREAM_RABBIT::getHost);
        registry.add("spring.rabbitmq.stream.port", () -> STREAM_RABBIT.getMappedPort(STREAM_PORT));
        registry.add("spring.rabbitmq.stream.virtual-host", () -> "/");
        registry.add("spring.rabbitmq.stream.username", () -> "guest");
        registry.add("spring.rabbitmq.stream.password", () -> "guest");
        registry.add("data2flow.core.live.telemetry-enabled", () -> "true");
        registry.add("data2flow.core.live.stream-retry", () -> "1s");
        registry.add("data2flow.core.live.developer", () -> "late");
    }

    @Autowired
    TelemetryStreamConsumer consumer;

    @Test
    @DisplayName("[DSH-05.01] 텔레메트리 Super Stream이 없으면 붙지 않고 다시 시도하다가, 생기면 붙는다")
    void waitsForSuperStream() {
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).until(() -> !consumer.subscribed());
        String host = STREAM_RABBIT.getHost();
        int port = STREAM_RABBIT.getMappedPort(STREAM_PORT);
        try (Environment env = Environment.builder().host(host).port(port).username("guest").password("guest")
                .addressResolver(address -> new Address(host, port)).build()) {
            SuperStreamSpec spec = SuperStreamSpec.TELEMETRY;
            env.streamCreator().name(spec.name()).maxAge(spec.maxAge()).superStream().partitions(spec.partitions()).creator().create();
        }
        await().atMost(Duration.ofSeconds(30)).until(consumer::subscribed);
        assertThat(consumer.subscribed()).isTrue();
    }
}
