package net.java21.data2flow.core.outbox;

import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.core.account.event.IamEventPublisher;
import net.java21.data2flow.core.outbox.service.OutboxRelay;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이벤트 아웃박스 → RabbitMQ {@code data2flow.events}(ADR-020, reliability-and-ha.md ⑦): publisher confirm 뒤 sent_at,
 * 헤더 messageId·v·organizationId, 재시도 안전(최소 1회). EVT-IAM-01·02·04·05.
 */
class OutboxMessagingIT extends IntegrationTestSupport {

    @Autowired
    IamEventPublisher events;
    @Autowired
    OutboxRelay relay;
    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    AmqpAdmin admin;
    @Autowired
    TransactionTemplate tx;

    @Test
    @DisplayName("[IAM-07.05][EVT-IAM-01·02·04·05] 업무 트랜잭션의 이벤트가 confirm 뒤 data2flow.events로 나가고 sent_at이 찍힌다")
    void relayPublishesWithConfirm() {
        Queue queue = new Queue("core-it-iam-events", false, false, false);
        admin.declareQueue(queue);
        admin.purgeQueue(queue.getName(), false);
        TopicExchange exchange = new TopicExchange(MessagingNames.EXCHANGE_EVENTS, true, false);
        admin.declareExchange(exchange);
        Binding binding = BindingBuilder.bind(queue).to(exchange).with("iam.#");
        admin.declareBinding(binding);

        tx.executeWithoutResult(s -> {
            events.userStateChanged(7, 42, "ACTIVE", "DISABLED", "ADMIN_DISABLED");
            events.permissionChanged(7, 42, "VIEWER", (Long) null, List.of(3L), 2);
            events.securityAlert(7, "REFRESH_REUSED", 42L, "10.0.0.1", Map.of("sid", "s"));
            events.roleChanged(7, 9, List.of("DEV_READ"), 1);
        });
        assertThat(relay.relayOnce()).isEqualTo(4);
        assertThat(relay.relayOnce()).isZero();

        Message first = rabbit.receive(queue.getName(), Duration.ofSeconds(5).toMillis());
        assertThat(first).isNotNull();
        assertThat(first.getMessageProperties().getReceivedRoutingKey()).isEqualTo("iam.user.state.changed");
        assertThat(first.getMessageProperties().<Integer>getHeader("v")).isEqualTo(1);
        assertThat(first.getMessageProperties().<Long>getHeader("organizationId")).isEqualTo(7L);
        assertThat(first.getMessageProperties().getMessageId()).isNotBlank();
        String body = new String(first.getBody(), StandardCharsets.UTF_8);
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.from")).isEqualTo("ACTIVE");
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.to")).isEqualTo("DISABLED");
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.messageId")).isEqualTo(first.getMessageProperties().getMessageId());
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(body, "$.v")).isEqualTo(1);
        int received = 1;
        while (rabbit.receive(queue.getName(), 2000) != null) {
            received++;
        }
        assertThat(received).isEqualTo(4);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE sent_at IS NOT NULL").query(Long.class).single()).isEqualTo(4);

        clock.advance(Duration.ofDays(8));
        assertThat(relay.purgeSent()).isEqualTo(4);
        admin.deleteQueue(queue.getName());
    }

    @Test
    @DisplayName("[ERD §11.1] 보낼 곳을 모르는 아웃박스 행은 실패로 남고 다시 시도된다")
    void unknownTarget() {
        jdbc.sql("""
                INSERT INTO data2flow_core.outboxes (organization_id, idempotency_key, kind, exchange, routing_key, payload)
                VALUES (1, repeat('c', 64), 'EVENT', 'nowhere', 'x', '{}')""").update();
        assertThat(relay.relayOnce()).isZero();
        assertThat(jdbc.sql("SELECT last_error FROM data2flow_core.outboxes").query(String.class).single()).contains("nowhere");
    }
}
