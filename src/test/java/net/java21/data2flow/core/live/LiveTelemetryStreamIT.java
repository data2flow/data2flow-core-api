package net.java21.data2flow.core.live;

import com.rabbitmq.stream.Address;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.Producer;
import net.java21.data2flow.contracts.message.CanonicalTelemetry;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.contracts.messaging.SuperStreamSpec;
import net.java21.data2flow.core.live.event.TelemetryStreamConsumer;
import net.java21.data2flow.core.live.service.LiveHub;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import net.java21.data2flow.core.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * DSH-05.01 TC-DSH-051: 실제 RabbitMQ 3.13 Super Stream {@code data2flow.telemetry}(12 파티션)에서 그룹 {@code core-live}로 최신부터 받아
 * 권한 필터 뒤 SSE로 보낸다. 공용 RabbitMQ 컨테이너는 스트림 플러그인이 없어 이 IT만 스트림을 켠 컨테이너를 따로 띄운다.
 */
class LiveTelemetryStreamIT extends IntegrationTestSupport {

    private static final int STREAM_PORT = 5552;
    private static final Instant T0 = MutableClock.T0;

    static final GenericContainer<?> STREAM_RABBIT = new GenericContainer<>("rabbitmq:3.13-management")
            .withExposedPorts(STREAM_PORT, 5672)
            .withCopyToContainer(Transferable.of("[rabbitmq_management,rabbitmq_stream]."), "/etc/rabbitmq/enabled_plugins")
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    private static final Environment PRODUCER_ENV;

    static {
        STREAM_RABBIT.start();
        String host = STREAM_RABBIT.getHost();
        int port = STREAM_RABBIT.getMappedPort(STREAM_PORT);
        PRODUCER_ENV = Environment.builder().host(host).port(port).username("guest").password("guest")
                .addressResolver(address -> new Address(host, port)).build();
        SuperStreamSpec spec = SuperStreamSpec.TELEMETRY;
        PRODUCER_ENV.streamCreator().name(spec.name()).maxAge(spec.maxAge()).superStream().partitions(spec.partitions()).creator().create();
    }

    @DynamicPropertySource
    static void stream(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.stream.host", STREAM_RABBIT::getHost);
        registry.add("spring.rabbitmq.stream.port", () -> STREAM_RABBIT.getMappedPort(STREAM_PORT));
        registry.add("spring.rabbitmq.stream.virtual-host", () -> "/");
        registry.add("spring.rabbitmq.stream.username", () -> "guest");
        registry.add("spring.rabbitmq.stream.password", () -> "guest");
        registry.add("data2flow.core.live.telemetry-enabled", () -> "true");
        registry.add("data2flow.core.live.developer", () -> "it");
    }

    @Autowired
    TelemetryStreamConsumer consumer;
    @Autowired
    LiveHub hub;
    @Autowired
    MessageCodec codec;

    @AfterEach
    void close() {
        hub.closeAll();
    }

    @Test
    @DisplayName("[DSH-05.01][AT-DSH-01.3] data2flow.telemetry(그룹 core-live, 비SAC·최신부터) → 범위 안 기기만 point·device-update — TC-DSH-051")
    void telemetryFromSuperStream() throws Exception {
        assertThat(consumer.group()).isEqualTo("core-live-it");
        await().atMost(Duration.ofSeconds(60)).until(consumer::subscribed);

        LiveTestData live = new LiveTestData(jdbc);
        long org = fx.organization("stream");
        long site = data.site(org, "캠퍼스");
        long lab = data.space(org, site, "ROOM", "실습실");
        long hall = data.space(org, site, "ROOM", "강당");
        long source = live.source(org, "campus-lns");
        long labSensor = data.device(org, source, "a1", "ACTIVE", lab, null);
        long hallSensor = data.device(org, source, "h1", "ACTIVE", hall, null);
        long user = fx.user(org, "stream.viewer", "VIEWER");
        data.spaceScope(org, user, List.of(lab));

        MvcResult r = mvc.perform(as(org, user, get("/core/stream/live")
                        .param("topics", "telemetry:" + labSensor + ".co2,telemetry:" + hallSensor + ".co2,space:" + site)
                        .accept(MediaType.TEXT_EVENT_STREAM)))
                .andExpect(request().asyncStarted()).andReturn();

        Producer producer = PRODUCER_ENV.producerBuilder().superStream(MessagingNames.STREAM_TELEMETRY)
                .routing(message -> codec.read(message.getBodyAsBinary(), CanonicalTelemetry.class).routingKey())
                .producerBuilder().build();
        try {
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
                for (long device : List.of(hallSensor, labSensor)) {
                    CanonicalTelemetry t = CanonicalTelemetry.builder().organizationId(org).sourceId(source).externalId("x" + device)
                            .deviceId(device).spaceId(device == labSensor ? lab : hall).measuredAt(T0).receivedAt(T0)
                            .metric(CanonicalTelemetry.Metric.of("co2", device == labSensor ? 812 : 999, "ppm")).rawMessageId(7).build();
                    producer.send(producer.messageBuilder().addData(codec.write(t)).build(), status -> { });
                }
                assertThat(body(r)).contains("event:point").contains("\"v\":812.0").contains("event:device-update");
            });
        } finally {
            producer.close();
        }
        assertThat(body(r)).doesNotContain("999.0").doesNotContain("\"deviceId\":\"" + hallSensor + "\"");
    }

    private static String body(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
