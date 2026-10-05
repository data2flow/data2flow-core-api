package net.java21.data2flow.core.apitoken.domain;

import net.java21.data2flow.core.apitoken.repository.ApiTokenRepository.TokenRow;

import java.time.Instant;

/**
 * 장기 토큰을 지금 쓸 수 있는가(API-IAM-46, BR-IAM-18·34·35). 상태 정리 작업(1분)을 기다리지 않고 시각으로 바로 판정한다.
 * <ul>
 *   <li>ACTIVE이고 만료 전</li>
 *   <li>ROTATING(교체 유예)이고 유예 끝·만료 전(BR-IAM-34)</li>
 *   <li>소유자(사용자·서비스 계정)가 ACTIVE(비활성화·삭제·잠금이면 거부, IAM-01.04·BR-IAM-35)</li>
 * </ul>
 */
public final class TokenUsability {

    private TokenUsability() {
    }

    public static boolean usable(TokenRow t, Instant now) {
        if (t == null || !"ACTIVE".equals(t.ownerStatus()) || !t.expiresAt().isAfter(now)) {
            return false;
        }
        return switch (t.status()) {
            case "ACTIVE" -> true;
            case "ROTATING" -> t.graceUntil() != null && t.graceUntil().isAfter(now);
            default -> false;
        };
    }
}
