package net.java21.data2flow.core.outbox.service;

import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.outbox.repository.OutboxRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 업무 트랜잭션 안에서 아웃박스에 기록한다(ADR-020, reliability-and-ha.md ⑦). 릴레이가 나중에 보낸다.
 *
 * <ul>
 *   <li>{@link #event}: RabbitMQ {@code data2flow.events}(topic)로 보낼 도메인 이벤트. 본문에 {@code v}·{@code messageId}가 들어간다.</li>
 *   <li>{@link #authBlacklist}: data2flow-auth의 블랙리스트 등록(API-IAM-37b, core → auth). 폐기 원천은 core DB이고
 *       auth가 Redis에 사본을 둔다(ADR-022). 실패해도 릴레이가 다시 보내므로 폐기가 사라지지 않는다.</li>
 * </ul>
 */
@Component
public class OutboxWriter {

    /** 아웃박스 exchange 열의 data2flow-auth HTTP 대상 표시 */
    public static final String AUTH_TARGET = "data2flow-auth";
    public static final String AUTH_BLACKLISTS = "blacklists";
    static final String MDC_REQUEST_ID = "requestId";

    private final OutboxRepository repository;
    private final JsonMapper json;
    private final Clock clock;

    public OutboxWriter(OutboxRepository repository, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    /** 도메인 이벤트(스키마 버전 v=1) */
    public String event(long organizationId, String routingKey, Map<String, Object> body) {
        String messageId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(MessagingNames.FIELD_SCHEMA_VERSION, 1);
        payload.put(MessagingNames.FIELD_MESSAGE_ID, messageId);
        payload.putAll(body);
        payload.putIfAbsent("occurredAt", now.toString());
        String requestId = MDC.get(MDC_REQUEST_ID);
        if (requestId != null) {
            payload.put("requestId", requestId);
        }
        repository.insert(organizationId, Tokens.sha256Hex("EVENT|" + messageId), "EVENT", MessagingNames.EXCHANGE_EVENTS,
                routingKey, json.writeValueAsString(payload), now);
        return messageId;
    }

    /**
     * 이미 직렬화한 메시지(계약 봉투 {@code DomainEvent}·{@code ConfigChangedMessage})를 그대로 기록한다. 본문에 {@code v}·{@code messageId}가
     * 들어 있어야 한다(릴레이가 헤더로 싣는다). 같은 messageId는 한 번만 기록된다.
     *
     * @param kind {@code EVENT}(data2flow.events) 또는 {@code CONFIG}(data2flow.config)
     */
    public void message(long organizationId, String kind, String exchange, String routingKey, String messageId, String payloadJson) {
        repository.insert(organizationId, Tokens.sha256Hex(kind + "|" + messageId), kind, exchange, routingKey, payloadJson,
                clock.instant());
    }

    /** API-IAM-37b 본문 {sids, jtis, reason} */
    public void authBlacklist(long organizationId, java.util.Collection<String> sids, java.util.Collection<String> jtis,
                              String reason) {
        authBlacklist(organizationId, sids, jtis, java.util.List.of(), reason);
    }

    /**
     * API-IAM-37b 본문 {sids, jtis, tokenIds, reason}. tokenIds는 장기 토큰(API 키·MCP) 폐기 알림이다: auth가 EVT-IAM-03
     * {@code TOKEN_ID}를 내어 gateway 검증 캐시에서 바로 지운다(IAM-05.03 "보통 1초"). 원천 판정은 core(API-IAM-46)라 놓쳐도 캐시 수명
     * 30초 안에 거부된다.
     */
    public void authBlacklist(long organizationId, java.util.Collection<String> sids, java.util.Collection<String> jtis,
                              java.util.Collection<String> tokenIds, String reason) {
        if ((sids == null || sids.isEmpty()) && (jtis == null || jtis.isEmpty()) && (tokenIds == null || tokenIds.isEmpty())) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sids", sids == null ? java.util.List.of() : java.util.List.copyOf(sids));
        payload.put("jtis", jtis == null ? java.util.List.of() : java.util.List.copyOf(jtis));
        if (tokenIds != null && !tokenIds.isEmpty()) {
            payload.put("tokenIds", java.util.List.copyOf(tokenIds));
        }
        payload.put("reason", reason);
        String body = json.writeValueAsString(payload);
        repository.insert(organizationId, Tokens.sha256Hex("AUTH|" + UUID.randomUUID()), "EVENT", AUTH_TARGET, AUTH_BLACKLISTS,
                body, clock.instant());
    }
}
