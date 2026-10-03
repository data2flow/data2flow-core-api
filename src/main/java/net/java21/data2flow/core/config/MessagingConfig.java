package net.java21.data2flow.core.config;

import net.java21.data2flow.contracts.messaging.MessagingNames;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RabbitMQ 이름(ADR-020, architecture.md §4). 도메인 이벤트 exchange는 durable topic이고, 이미 있으면 같은 설정으로 그대로 둔다.
 * 연결은 처음 발행할 때 맺으므로 RabbitMQ가 잠시 없어도 서비스는 뜬다(아웃박스가 나중에 보낸다).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class MessagingConfig {

    @Bean
    TopicExchange data2flowEventsExchange() {
        return new TopicExchange(MessagingNames.EXCHANGE_EVENTS, true, false);
    }
}
