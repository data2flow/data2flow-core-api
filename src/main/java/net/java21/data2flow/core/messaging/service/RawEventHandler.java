package net.java21.data2flow.core.messaging.service;

import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * {@code data2flow-contracts}의 {@code EventType}에 아직 없는 도메인 이벤트 처리기. 봉투({@code DomainEvent} v1:
 * {@code {v, messageId, type, organizationId, occurredAt, payload}})는 같고 페이로드만 JSON 그대로 받는다.
 * 지금은 analytics가 내는 EVT-ANA-01 {@code analytics.run.*}·EVT-ANA-05 {@code analytics.schedule.stopped}(M6)를 받는다.
 * 계약 모듈에 종류가 생기면 {@link CoreEventHandler}로 옮긴다.
 */
public interface RawEventHandler {

    /** {@code data2flow.events} 바인딩 키(topic 패턴 가능, 예: {@code analytics.run.*}) */
    Set<String> bindings();

    /** 이 종류(라우팅 키)를 처리하는가 */
    boolean supports(String type);

    /** 같은 트랜잭션에서 처리(중복은 소비자가 messageId로 거른다). 예외면 롤백 뒤 다시 받는다 */
    void handle(long organizationId, String type, JsonNode payload);
}
