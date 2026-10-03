package net.java21.data2flow.core.account.domain;

import java.time.Instant;

/**
 * 회원({@code data2flow_core.app_users}, domain-model §2.2). 상태 전이는 {@link UserStatus}가 판정한다.
 * 삭제(DELETED)는 개인정보를 {@code deleted-{id}}로 익명화하고 행은 남긴다(IAM-01.10, NFR-12.01).
 */
public record AppUser(long id, long organizationId, String loginId, String email, String name, String phone, String locale,
                      String timezone, UserStatus status, String passwordHash, Instant passwordChangedAt,
                      boolean mustChangePassword, int failedLoginCount, Instant lockedUntil, Instant lastLoginAt,
                      String lastLoginIp, byte[] totpSecretEnc, boolean totpEnabled, String notificationPref,
                      Instant anonymizedAt, int version, Instant createdAt, Instant updatedAt) {

    /** 잠금 시간이 지났는가(LOCKED → ACTIVE는 다음 로그인 때 판정, §3.1) */
    public boolean lockExpired(Instant now) {
        return status == UserStatus.LOCKED && lockedUntil != null && !lockedUntil.isAfter(now);
    }
}
