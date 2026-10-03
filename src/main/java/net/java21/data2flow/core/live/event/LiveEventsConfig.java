package net.java21.data2flow.core.live.event;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.MessageFormatException;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.core.live.service.LiveHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 실시간 화면용 도메인 이벤트 구독(architecture.md §4.3 "SSE로 보내는 일은 모든 파드가 받아야 한다"): 파드마다 이름이 다른
 * 임시 큐({@code core.live.<무작위>}, exclusive·auto-delete·non-durable)를 {@code data2flow.events}에 묶는다.
 * 업무 반영용 Quorum 큐 {@code core.events}(파드끼리 나눠 받음)와 따로이고, 손실 허용이다(파드가 내려가면 큐도 사라진다).
 *
 * <p>RabbitAdmin이 연결을 맺을 때마다(재연결 포함) 큐와 바인딩을 다시 선언한다. RabbitMQ가 없어도 서비스는 뜨고, 리스너가 뒤에서 다시 붙는다.
 * {@code data2flow.core.live.events-enabled=false}면 큐를 만들지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "data2flow.core.live", name = "events-enabled", havingValue = "true", matchIfMissing = true)
public class LiveEventsConfig {

    /** 화면 갱신에 쓰는 이벤트(홈 요약 다시 계산, 공간 보기 기기 카드) */
    public static final List<EventType> TYPES = List.of(EventType.DEVICE_CHANGED, EventType.DEVICE_CONNECTIVITY_CHANGED,
            EventType.DEVICE_PENDING_CREATED, EventType.SPACE_CHANGED, EventType.GROUP_MEMBERSHIP_CHANGED,
            EventType.SOURCE_CONNECTION_CHANGED, EventType.INGEST_ALERT_RAISED, EventType.INGEST_ALERT_CLEARED);

    private static final Logger log = LoggerFactory.getLogger(LiveEventsConfig.class);

    private final String queueName = "core.live." + UUID.randomUUID();

    @Bean
    Declarables liveEventsDeclarables() {
        Queue queue = new Queue(queueName, false, true, true, Map.of("x-queue-type", "classic"));
        List<Declarable> all = new ArrayList<>(List.of(queue));
        for (EventType type : TYPES) {
            all.add(new Binding(queueName, Binding.DestinationType.QUEUE, MessagingNames.EXCHANGE_EVENTS, type.routingKey(), null));
        }
        return new Declarables(all);
    }

    @Bean
    SimpleMessageListenerContainer liveEventsContainer(ConnectionFactory connectionFactory, MessageCodec codec, LiveHub hub) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queueName);
        container.setAcknowledgeMode(AcknowledgeMode.NONE);
        container.setExclusive(true);
        container.setMissingQueuesFatal(false);
        container.setMessageListener(message -> deliver(codec, hub, message.getBody()));
        return container;
    }

    /** 한 건 전달. 형식 오류·모르는 종류·처리 실패는 버린다(화면용 손실 허용) */
    static void deliver(MessageCodec codec, LiveHub hub, byte[] body) {
        DomainEvent<?> event;
        try {
            event = codec.readEvent(body);
        } catch (MessageFormatException ex) {
            log.debug("실시간 화면 이벤트 형식 오류라 버립니다: {}", ex.getMessage());
            return;
        }
        try {
            hub.onEvent(event);
        } catch (RuntimeException ex) {
            log.warn("실시간 화면 이벤트 전달 실패(버림): {}", ex.toString());
        }
    }
}
