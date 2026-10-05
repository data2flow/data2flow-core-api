package net.java21.data2flow.core.messaging.service;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.message.MessageFormatException;
import net.java21.data2flow.core.messaging.repository.ProcessedMessageRepository;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code core.events} 소비(architecture.md §4.3, reliability-and-ha.md §2): 메시지 하나를 한 트랜잭션에서
 * 중복 확인({@code processed_messages}) → 처리기 실행 → 커밋하고, 커밋한 뒤에 ACK한다(리스너가 정상 반환해야 ACK).
 *
 * <ul>
 *   <li>형식 오류·모르는 스키마 버전 → 다시 시도하지 않고 DLQ({@code core.events.dlq})</li>
 *   <li>처리기 예외 → 롤백 후 다시 받음(Quorum delivery-limit 5회 넘으면 DLQ)</li>
 *   <li>이 코드가 모르는 종류(계약 모듈에 없는 라우팅 키)·처리기가 없는 종류 → 무시(ACK)</li>
 *   <li>배포 조직({@link DeploymentOrganization}) 밖 조직의 이벤트 → 무시</li>
 * </ul>
 */
@Component
public class CoreEventConsumer {

    public static final String QUEUE = "core.events";
    static final String CONSUMER = QUEUE;
    private static final Logger log = LoggerFactory.getLogger(CoreEventConsumer.class);

    private final Map<EventType, List<CoreEventHandler>> handlers = new EnumMap<>(EventType.class);
    private final MessageCodec codec;
    private final ProcessedMessageRepository processed;
    private final DeploymentOrganization deployment;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CoreEventConsumer(List<CoreEventHandler> handlerBeans, MessageCodec codec, ProcessedMessageRepository processed,
                             DeploymentOrganization deployment, PlatformTransactionManager txManager, Clock clock) {
        for (CoreEventHandler h : handlerBeans) {
            for (EventType type : h.types()) {
                handlers.computeIfAbsent(type, t -> new ArrayList<>()).add(h);
            }
        }
        this.codec = codec;
        this.processed = processed;
        this.deployment = deployment;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** 큐 바인딩 라우팅 키(처리기가 있는 종류만) */
    public Set<String> routingKeys() {
        Set<String> keys = new TreeSet<>();
        handlers.keySet().forEach(t -> keys.add(t.routingKey()));
        return keys;
    }

    /**
     * 메시지 하나 처리. 처리했으면 true, 중복·무시면 false.
     *
     * @throws AmqpRejectAndDontRequeueException 형식 오류(DLQ로)
     */
    public boolean onMessage(byte[] body) {
        DomainEvent<?> event;
        try {
            event = codec.readEvent(body);
        } catch (MessageFormatException ex) { // UnsupportedSchemaVersionException 포함
            if (ex.getMessage() != null && ex.getMessage().startsWith("모르는 이벤트 종류")) {
                log.debug("모르는 이벤트 종류라 무시합니다: {}", ex.getMessage());
                return false;
            }
            throw new AmqpRejectAndDontRequeueException("core.events 형식 오류", ex);
        }
        List<CoreEventHandler> targets = handlers.getOrDefault(event.eventType(), List.of());
        if (targets.isEmpty() || !deployment.includes(event.organizationId())) {
            return false;
        }
        Boolean done = tx.execute(status -> {
            if (!processed.insertIfAbsent(CONSUMER, event.messageId().toString(), clock.instant())) {
                return false;
            }
            for (CoreEventHandler h : targets) {
                h.handle(event);
            }
            return true;
        });
        return Boolean.TRUE.equals(done);
    }
}
