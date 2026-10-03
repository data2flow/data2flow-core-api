package net.java21.data2flow.core.messaging.service;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;

import java.util.Set;

/**
 * {@code core.events} 큐(Quorum, {@code data2flow.events} 구독)로 받은 도메인 이벤트를 처리하는 빈. 클러스터 안에서 한 파드만 받는다
 * (경쟁 소비자). DB에 반영하는 일(source_runtimes·source_stat_1m·connector_catalogs 저장, 시스템 주석 등)에 쓴다.
 *
 * <p>호출은 하나의 트랜잭션 안에서 이루어지고, 같은 messageId는 {@code processed_messages}로 한 번만 처리된다(최소 1회 + 멱등).
 * 처리 중 예외가 나면 트랜잭션을 되돌리고 메시지를 다시 받는다(5회 넘으면 {@code core.events.dlq}). 화면(SSE)으로 보내는 일은
 * 모든 파드가 받아야 하므로 여기서 하지 않는다.
 */
public interface CoreEventHandler {

    /** 처리할 이벤트 종류(큐 바인딩 라우팅 키가 된다) */
    Set<EventType> types();

    /** 이벤트 하나 처리. 페이로드 타입은 {@link EventType#payloadType()} */
    void handle(DomainEvent<?> event);
}
