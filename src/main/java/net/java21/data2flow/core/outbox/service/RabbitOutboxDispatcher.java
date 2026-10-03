package net.java21.data2flow.core.outbox.service;

import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.core.outbox.repository.OutboxRepository.OutboxMessage;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * RabbitMQ 발행. publisher confirm(ack)을 받아야 성공으로 본다(reliability-and-ha.md ⑦). 헤더에 messageId·v·X-REQUEST-ID·organizationId를
 * 싣는다(IAM-api §8 공통 헤더). 메시지는 영속(persistent)이다.
 */
@Component
public class RabbitOutboxDispatcher implements OutboxDispatcher {

    static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final RabbitTemplate rabbitTemplate;
    private final JsonMapper json;

    public RabbitOutboxDispatcher(RabbitTemplate rabbitTemplate, JsonMapper json) {
        this.rabbitTemplate = rabbitTemplate;
        this.json = json;
    }

    @Override
    public boolean supports(OutboxMessage message) {
        return message.exchange().startsWith("data2flow.");
    }

    @Override
    public void dispatch(OutboxMessage message) throws Exception {
        JsonNode payload = json.readTree(message.payload());
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        String messageId = payload.path(MessagingNames.FIELD_MESSAGE_ID).asString(null);
        props.setMessageId(messageId);
        props.setHeader(MessagingNames.FIELD_MESSAGE_ID, messageId);
        props.setHeader(MessagingNames.FIELD_SCHEMA_VERSION, payload.path(MessagingNames.FIELD_SCHEMA_VERSION).asInt(1));
        props.setHeader("organizationId", message.organizationId());
        String requestId = payload.path("requestId").asString(null);
        if (requestId != null) {
            props.setHeader(DataflowHeaders.REQUEST_ID, requestId);
        }
        CorrelationData correlation = new CorrelationData(Long.toString(message.id()));
        rabbitTemplate.send(message.exchange(), message.routingKey(),
                new Message(message.payload().getBytes(StandardCharsets.UTF_8), props), correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!confirm.ack()) {
            throw new IllegalStateException("RabbitMQ nack: " + confirm.reason());
        }
    }
}
