package net.java21.data2flow.core.session.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Refresh 계보 한 행({@code data2flow_core.refresh_tokens}, domain-model §2.5, crowfoot 준용). 같은 sid(로그인 한 번)의 토큰이 회전하며 이어진다.
 */
public record RefreshToken(UUID jti, UUID sessionId, long organizationId, long userId, String tokenHash, Instant issuedAt,
                           Instant lastUsedAt, Instant expiresAt, Instant absoluteExpiresAt, Instant rotatedAt,
                           Instant revokedAt, String revokeReason, String ip, String userAgent) {

    /** 폐기 사유(DDL ck_refresh_tokens_revoke_reason) */
    public enum RevokeReason { LOGOUT, REUSE_DETECTED, PASSWORD_CHANGED, USER_DISABLED, FORCED, ROLE_CHANGED, EXPIRED }

    public boolean revoked() {
        return revokedAt != null;
    }
}
