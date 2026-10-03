package net.java21.data2flow.core.outbox.service;

import net.java21.data2flow.core.outbox.repository.OutboxRepository.OutboxMessage;

/** 아웃박스 행 하나를 대상(RabbitMQ, data2flow-auth HTTP)에 보낸다. 예외를 던지면 릴레이가 다음 주기에 다시 보낸다 */
public interface OutboxDispatcher {

    boolean supports(OutboxMessage message);

    void dispatch(OutboxMessage message) throws Exception;
}
