package net.java21.data2flow.core.session.service;

import net.java21.data2flow.core.outbox.service.OutboxWriter;
import net.java21.data2flow.core.session.domain.RefreshToken.RevokeReason;
import net.java21.data2flow.core.session.repository.RefreshTokenRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 세션 폐기의 공통 경로(BR-IAM-12, IAM-07.05). 원천(core DB {@code refresh_tokens.revoked_at})을 먼저 바꾸고, 같은 트랜잭션에서
 * auth 블랙리스트 등록(API-IAM-37b, {@code bl:sid})을 아웃박스에 남긴다. 릴레이가 보내면 auth가 Redis에 넣고 EVT-IAM-03으로
 * 게이트웨이·BFF 캐시를 지운다(보통 1초). auth가 잠시 죽어도 아웃박스가 다시 보내고, Redis가 비면 auth가 API-IAM-39a로 재적재한다.
 */
@Component
public class SessionRevocations {

    private final RefreshTokenRepository tokens;
    private final OutboxWriter outbox;
    private final JdbcClient jdbc;
    private final Clock clock;

    public SessionRevocations(RefreshTokenRepository tokens, OutboxWriter outbox, JdbcClient jdbc, Clock clock) {
        this.tokens = tokens;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 사용자의 모든 세션 폐기(exceptSessionId만 남김). 폐기한 sid 목록 */
    public List<UUID> revokeAll(long organizationId, long userId, RevokeReason reason, UUID exceptSessionId) {
        Instant now = clock.instant();
        List<UUID> sids = tokens.revokeAllForUser(organizationId, userId, exceptSessionId, reason.name(), now);
        outbox.authBlacklist(organizationId, sids.stream().map(UUID::toString).toList(), List.of(), reason.name());
        return sids;
    }

    /** 세션 하나 폐기(사용자의 개별 종료, 재사용 탐지) */
    public int revokeSession(long organizationId, UUID sessionId, RevokeReason reason, boolean notifyAuth) {
        int revoked = tokens.revokeSession(sessionId, reason.name(), clock.instant());
        if (notifyAuth) {
            outbox.authBlacklist(organizationId, List.of(sessionId.toString()), List.of(), reason.name());
        }
        return revoked;
    }

    /** 비활성화·삭제 시 장기 토큰(API 키·MCP)도 즉시 무효(IAM-01.04). 발급·검증 API는 M6(IAM-05)에서 만든다 */
    public int revokeApiTokensOfUser(long organizationId, long userId) {
        return jdbc.sql("""
                        UPDATE data2flow_core.api_tokens SET status = 'REVOKED', updated_at = :now
                         WHERE organization_id = :org AND owner_type = 'USER' AND owner_id = :user
                           AND status IN ('PENDING_APPROVAL', 'ACTIVE', 'ROTATING')""")
                .param("now", net.java21.data2flow.core.common.Pg.ts(clock.instant()))
                .param("org", organizationId).param("user", userId).update();
    }
}
