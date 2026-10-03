package net.java21.data2flow.core.live.event;

import com.rabbitmq.stream.Address;
import com.rabbitmq.stream.Consumer;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.OffsetSpecification;
import net.java21.data2flow.contracts.message.CanonicalTelemetry;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.MessageFormatException;
import net.java21.data2flow.contracts.messaging.ConsumerGroups;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.live.service.LiveHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 실시간 화면의 텔레메트리 소비(architecture.md §4.2, API-DSH-20): Super Stream {@code data2flow.telemetry}를
 * 그룹 {@code core-live}(로컬은 {@code core-live-<개발자>})로 <b>모든 파티션을 최신부터</b> 읽는다.
 *
 * <ul>
 *   <li>Single Active Consumer가 아니다: 파드마다 모든 메시지를 받아 그 파드에 붙은 연결로 보낸다</li>
 *   <li>오프셋을 저장하지 않는다({@code noTrackingStrategy}, 시작은 {@code next}). 다시 시작하면 그 뒤 메시지부터(손실 허용 — 화면은 최신값만 필요)</li>
 *   <li>RabbitMQ가 없거나 스트림이 아직 없어도 서비스는 뜬다. 뒤에서 {@code stream-retry}(10초)마다 다시 붙는다. 스트림(파티션 0)이 생기기 전에는 붙지 않는다(빈 소비자 방지).
 *       붙은 뒤의 끊김은 클라이언트가 스스로 복구한다</li>
 *   <li>주소: {@code spring.rabbitmq.stream.*}. 브로커가 알려 주는 노드 주소 대신 설정한 주소로만 붙는다(s4 단일 노드, 내부망 5552)</li>
 *   <li>{@code data2flow.core.live.telemetry-enabled=false}면 아무것도 하지 않는다(테스트 기본)</li>
 * </ul>
 */
@Component
public class TelemetryStreamConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelemetryStreamConsumer.class);

    private final RabbitProperties rabbit;
    private final CoreProperties.Live settings;
    private final MessageCodec codec;
    private final LiveHub hub;

    private volatile ScheduledExecutorService retry;
    private volatile Environment environment;
    private volatile Consumer consumer;
    private volatile boolean running;

    public TelemetryStreamConsumer(RabbitProperties rabbit, CoreProperties properties, MessageCodec codec, LiveHub hub) {
        this.rabbit = rabbit;
        this.settings = properties.live();
        this.codec = codec;
        this.hub = hub;
    }

    /** 소비자 그룹 이름(core-live 또는 core-live-<개발자>) */
    public String group() {
        return ConsumerGroups.of(ConsumerGroups.CORE_LIVE, settings.developer());
    }

    /** 스트림에 붙어 있는가 */
    public boolean subscribed() {
        return consumer != null;
    }

    @Override
    public void start() {
        running = true;
        if (!settings.telemetryEnabled()) {
            log.info("실시간 화면 텔레메트리 소비 꺼짐(data2flow.core.live.telemetry-enabled=false)");
            return;
        }
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("live-telemetry-connect").factory());
        retry = s;
        s.execute(this::connect);
    }

    private void connect() {
        if (!running || consumer != null) {
            return;
        }
        RabbitProperties.Stream stream = rabbit.getStream();
        String host = stream.getHost() == null ? rabbit.getHost() : stream.getHost();
        int port = stream.getPort();
        try {
            Environment env = environment;
            if (env == null) {
                env = Environment.builder().host(host).port(port)
                        .virtualHost(stream.getVirtualHost() == null ? rabbit.getVirtualHost() : stream.getVirtualHost())
                        .username(stream.getUsername() == null ? rabbit.getUsername() : stream.getUsername())
                        .password(stream.getPassword() == null ? rabbit.getPassword() : stream.getPassword())
                        .addressResolver(address -> new Address(host, port))
                        .build();
                environment = env;
            }
            if (!env.streamExists(MessagingNames.STREAM_TELEMETRY + "-0")) {
                // pipeline이 첫 발행 때 Super Stream을 만든다. 파티션이 없을 때 붙으면 빈 소비자가 되어 다시 붙지 않으므로 기다린다
                throw new IllegalStateException("Super Stream " + MessagingNames.STREAM_TELEMETRY + "이(가) 아직 없습니다");
            }
            consumer = env.consumerBuilder()
                    .superStream(MessagingNames.STREAM_TELEMETRY)
                    .name(group())
                    .offset(OffsetSpecification.next())
                    .noTrackingStrategy()
                    .messageHandler((context, message) -> handle(message.getBodyAsBinary()))
                    .build();
            log.info("실시간 화면 텔레메트리 소비 시작: {} 그룹 {}", MessagingNames.STREAM_TELEMETRY, group());
        } catch (RuntimeException ex) {
            log.warn("텔레메트리 스트림에 붙지 못했습니다({}). {}초 뒤 다시 시도합니다", ex.toString(), settings.streamRetry().toSeconds());
            ScheduledExecutorService s = retry;
            if (running && s != null && !s.isShutdown()) {
                s.schedule(this::connect, settings.streamRetry().toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    /** 메시지 하나. 형식이 틀리면 버린다(화면용 손실 허용 소비자라 DLQ가 없다) */
    void handle(byte[] body) {
        CanonicalTelemetry telemetry;
        try {
            telemetry = codec.read(body, CanonicalTelemetry.class);
        } catch (MessageFormatException ex) {
            log.debug("data2flow.telemetry 형식 오류라 건너뜁니다: {}", ex.getMessage());
            return;
        }
        try {
            hub.onTelemetry(telemetry);
        } catch (RuntimeException ex) {
            log.warn("실시간 전달 실패(건너뜀): {}", ex.toString());
        }
    }

    @Override
    public void stop() {
        running = false;
        ScheduledExecutorService s = retry;
        if (s != null) {
            s.shutdownNow();
            retry = null;
        }
        Consumer c = consumer;
        consumer = null;
        if (c != null) {
            try {
                c.close();
            } catch (RuntimeException ex) {
                log.debug("텔레메트리 소비자 닫기 실패: {}", ex.toString());
            }
        }
        Environment env = environment;
        environment = null;
        if (env != null) {
            try {
                env.close();
            } catch (RuntimeException ex) {
                log.debug("스트림 환경 닫기 실패: {}", ex.toString());
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
