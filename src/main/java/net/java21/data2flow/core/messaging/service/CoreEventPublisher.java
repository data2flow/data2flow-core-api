package net.java21.data2flow.core.messaging.service;

import net.java21.data2flow.contracts.message.ConfigChangedMessage;
import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.event.EventPayload;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.core.outbox.service.OutboxWriter;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * M2 메시지 발행(ADR-020, contracts README §13). 업무 트랜잭션 안에서 아웃박스에 기록하고 릴레이가 confirm 뒤 보낸다.
 *
 * <ul>
 *   <li>{@link #event}: 도메인 이벤트 봉투 {@code DomainEvent} v1 → topic {@code data2flow.events}, 라우팅 키 = {@code type}
 *       (EVT-DEV-01·03·05·07, EVT-DSC-04·05 등)</li>
 *   <li>{@link #configChanged}: {@code ConfigChangedMessage} v1 → fanout {@code data2flow.config}(EVT-DEV-04·DSC-01·SCR-01).
 *       받은 서비스는 entityType·id·version만 보고 캐시를 지운 뒤 내부 API로 다시 읽는다</li>
 * </ul>
 */
@Component
public class CoreEventPublisher {

    static final String MDC_REQUEST_ID = "requestId";

    private final OutboxWriter outbox;
    private final MessageCodec codec;
    private final Clock clock;

    public CoreEventPublisher(OutboxWriter outbox, MessageCodec codec, Clock clock) {
        this.outbox = outbox;
        this.codec = codec;
        this.clock = clock;
    }

    /** 도메인 이벤트. 원인 요청의 X-REQUEST-ID(MDC)를 봉투에 싣는다 */
    public <P extends EventPayload> DomainEvent<P> event(EventType type, long organizationId, P payload) {
        DomainEvent<P> event = DomainEvent.of(type, organizationId, payload, MDC.get(MDC_REQUEST_ID), clock);
        outbox.message(organizationId, "EVENT", MessagingNames.EXCHANGE_EVENTS, event.type(), event.messageId().toString(),
                codec.writeAsString(event));
        return event;
    }

    /** 설정 변경(UPSERT) */
    public ConfigChangedMessage configChanged(EntityType type, long id, long version, long organizationId) {
        return config(ConfigChangedMessage.upsert(type, id, version, organizationId, clock));
    }

    /** 설정 삭제(DELETE) */
    public ConfigChangedMessage configDeleted(EntityType type, long id, long version, long organizationId) {
        return config(ConfigChangedMessage.delete(type, id, version, organizationId, clock));
    }

    private ConfigChangedMessage config(ConfigChangedMessage message) {
        outbox.message(Long.parseLong(message.orgId()), "CONFIG", MessagingNames.EXCHANGE_CONFIG, "",
                message.messageId().toString(), codec.writeAsString(message));
        return message;
    }
}
