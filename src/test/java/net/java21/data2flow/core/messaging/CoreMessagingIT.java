package net.java21.data2flow.core.messaging;

import net.java21.data2flow.contracts.message.ConfigChangedMessage;
import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.event.SpaceChanged;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.core.messaging.service.CoreEventConsumer;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.outbox.service.OutboxRelay;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2 메시지 기반(ADR-020, contracts README §13): 도메인 이벤트 봉투와 설정 변경 메시지가 아웃박스 → confirm → 각 exchange로 나가고,
 * {@code core.events} 소비자는 형식 오류를 DLQ로 보내고 모르는 종류는 무시한다.
 */
class CoreMessagingIT extends IntegrationTestSupport {

    @Autowired
    CoreEventPublisher publisher;
    @Autowired
    CoreEventConsumer consumer;
    @Autowired
    OutboxRelay relay;
    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    AmqpAdmin admin;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    MessageCodec codec;

    @Test
    @DisplayName("[DEV-01.01][EVT-DEV-05·EVT-DEV-04] 도메인 이벤트는 data2flow.events(라우팅 키=type), 설정 변경은 data2flow.config로 나간다")
    void eventAndConfigAreRelayed() {
        Queue events = new Queue("core-it-m2-events", false, false, true);
        Queue config = new Queue("core-it-m2-config", false, false, true);
        admin.declareQueue(events);
        admin.declareQueue(config);
        admin.declareBinding(BindingBuilder.bind(events).to(new TopicExchange(MessagingNames.EXCHANGE_EVENTS)).with("space.#"));
        admin.declareBinding(BindingBuilder.bind(config).to(new FanoutExchange(MessagingNames.EXCHANGE_CONFIG)));

        tx.executeWithoutResult(s -> {
            publisher.event(EventType.SPACE_CHANGED, 7, new SpaceChanged(31, "CREATED", "/1/7/31"));
            publisher.configChanged(EntityType.SPACE, 31, 0, 7);
        });
        assertThat(relay.relayOnce()).isEqualTo(2);

        Message e = rabbit.receive(events.getName(), 5000);
        assertThat(e).isNotNull();
        assertThat(e.getMessageProperties().getReceivedRoutingKey()).isEqualTo("space.changed");
        DomainEvent<SpaceChanged> event = codec.readEvent(e.getBody(), SpaceChanged.class);
        assertThat(event.organizationId()).isEqualTo(7);
        assertThat(event.payload().path()).isEqualTo("/1/7/31");
        assertThat(e.getMessageProperties().getMessageId()).isEqualTo(event.messageId().toString());

        Message c = rabbit.receive(config.getName(), 5000);
        assertThat(c).isNotNull();
        ConfigChangedMessage m = codec.read(c.getBody(), ConfigChangedMessage.class);
        assertThat(m.entityType()).isEqualTo(EntityType.SPACE);
        assertThat(m.id()).isEqualTo("31");
        assertThat(m.orgId()).isEqualTo("7");
        assertThat(m.op()).isEqualTo(ConfigChangedMessage.Op.UPSERT);
    }

    @Test
    @DisplayName("[reliability §2] core.events: 형식 오류는 다시 시도하지 않고 DLQ로, 모르는 종류·처리기 없는 종류는 무시")
    void consumerRejectsMalformedAndIgnoresUnknown() {
        assertThatThrownBy(() -> consumer.onMessage("not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        String unknown = "{\"v\":1,\"messageId\":\"6f1c7c1e-1d7a-4c3e-9a51-0d6c5f1f0a11\",\"type\":\"future.event\","
                + "\"organizationId\":1,\"occurredAt\":\"2026-10-03T00:00:00Z\",\"payload\":{}}";
        assertThat(consumer.onMessage(unknown.getBytes(StandardCharsets.UTF_8))).isFalse();
    }
}
