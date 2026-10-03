package net.java21.data2flow.core.account.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 계정 상태와 허용 전이(IAM-01.10, domain-model §3.1). 표에 없는 전이는 {@code USER_STATE_CONFLICT}다.
 *
 * <pre>
 * INVITED → ACTIVE(초대 수락)            PENDING_APPROVAL → ACTIVE(승인)
 * ACTIVE ↔ LOCKED(5회 실패 / 해제·시간 경과)   ACTIVE·LOCKED → DISABLED → ACTIVE(재활성화)
 * * → DELETED(익명화, 되돌릴 수 없음)
 * </pre>
 */
public enum UserStatus {
    INVITED, PENDING_APPROVAL, ACTIVE, LOCKED, DISABLED, DELETED;

    private static final Map<UserStatus, Set<UserStatus>> ALLOWED = Map.of(
            INVITED, EnumSet.of(ACTIVE, DELETED),
            PENDING_APPROVAL, EnumSet.of(ACTIVE, DELETED),
            ACTIVE, EnumSet.of(LOCKED, DISABLED, DELETED),
            LOCKED, EnumSet.of(ACTIVE, DISABLED, DELETED),
            DISABLED, EnumSet.of(ACTIVE, DELETED),
            DELETED, EnumSet.noneOf(UserStatus.class));

    public boolean canTransitionTo(UserStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
