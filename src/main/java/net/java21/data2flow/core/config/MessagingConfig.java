package net.java21.data2flow.core.config;

import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.core.messaging.service.CoreEventConsumer;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.ArrayList;
import java.util.List;

/**
 * RabbitMQ 이름(ADR-020, architecture.md §4). 도메인 이벤트 exchange는 durable topic, 설정 변경은 durable fanout이고,
 * 이미 있으면 같은 설정으로 그대로 둔다. 연결은 처음 발행할 때 맺으므로 RabbitMQ가 잠시 없어도 서비스는 뜬다(아웃박스가 나중에 보낸다).
 *
 * <p>이벤트 소비: Quorum 큐 {@code core.events}(DLX {@code data2flow.dlx} → {@code core.events.dlq}, delivery-limit 5)를
 * 처리기({@code CoreEventHandler})가 맡은 라우팅 키로 {@code data2flow.events}에 묶는다. {@code data2flow.core.events.listener-enabled=false}면
 * 큐도 만들지 않는다(로컬: 소비자 없는 큐에 메시지가 쌓이지 않게).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class MessagingConfig {

    @Bean
    TopicExchange data2flowEventsExchange() {
        return new TopicExchange(MessagingNames.EXCHANGE_EVENTS, true, false);
    }

    /** 설정 변경(EVT-DEV-04·DSC-01·SCR-01, ConfigChangedMessage) */
    @Bean
    FanoutExchange data2flowConfigExchange() {
        return new FanoutExchange(MessagingNames.EXCHANGE_CONFIG, true, false);
    }

    /** 서비스에 하나(스레드 안전). 계약 메시지 직렬화·역직렬화 */
    @Bean
    MessageCodec messageCodec() {
        return MessageCodec.create();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "data2flow.core.events", name = "listener-enabled", havingValue = "true", matchIfMissing = true)
    static class EventsListener {

        @Bean
        Declarables coreEventsDeclarables(CoreEventConsumer consumer) {
            DirectExchange dlx = new DirectExchange(MessagingNames.EXCHANGE_DLX, true, false);
            Queue queue = QueueBuilder.durable(CoreEventConsumer.QUEUE).quorum()
                    .deadLetterExchange(MessagingNames.EXCHANGE_DLX).deadLetterRoutingKey(CoreEventConsumer.QUEUE)
                    .deliveryLimit(MessagingNames.DELIVERY_LIMIT).build();
            Queue dlq = QueueBuilder.durable(MessagingNames.deadLetterQueue(CoreEventConsumer.QUEUE)).quorum().build();
            List<Declarable> all = new ArrayList<>(List.of(dlx, queue, dlq,
                    BindingBuilder.bind(dlq).to(dlx).with(CoreEventConsumer.QUEUE)));
            for (String key : consumer.routingKeys()) {
                all.add(new Binding(CoreEventConsumer.QUEUE, Binding.DestinationType.QUEUE, MessagingNames.EXCHANGE_EVENTS, key, null));
            }
            return new Declarables(all);
        }

        @Bean
        SimpleMessageListenerContainer coreEventsContainer(ConnectionFactory connectionFactory, CoreEventConsumer consumer,
                                                           CoreProperties properties) {
            SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
            container.setQueueNames(CoreEventConsumer.QUEUE);
            container.setAcknowledgeMode(AcknowledgeMode.AUTO);
            container.setDefaultRequeueRejected(true);
            container.setPrefetchCount(20);
            container.setConcurrentConsumers(properties.events().concurrency());
            container.setMissingQueuesFatal(false);
            container.setMessageListener(message -> consumer.onMessage(message.getBody()));
            return container;
        }
    }
}
