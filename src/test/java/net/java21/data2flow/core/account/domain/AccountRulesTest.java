package net.java21.data2flow.core.account.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.account.service.LoginIdRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static net.java21.data2flow.core.account.domain.UserStatus.ACTIVE;
import static net.java21.data2flow.core.account.domain.UserStatus.DELETED;
import static net.java21.data2flow.core.account.domain.UserStatus.DISABLED;
import static net.java21.data2flow.core.account.domain.UserStatus.INVITED;
import static net.java21.data2flow.core.account.domain.UserStatus.LOCKED;
import static net.java21.data2flow.core.account.domain.UserStatus.PENDING_APPROVAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** IAM-01.10 계정 상태 전이 표(허용·거부)와 IAM-02.01 로그인 아이디 규칙(BR-IAM-02) */
class AccountRulesTest {

    @ParameterizedTest(name = "{0} → {1} = {2}")
    @CsvSource({
            "INVITED,ACTIVE,true", "INVITED,LOCKED,false", "INVITED,DELETED,true",
            "PENDING_APPROVAL,ACTIVE,true", "PENDING_APPROVAL,DISABLED,false",
            "ACTIVE,LOCKED,true", "ACTIVE,DISABLED,true", "ACTIVE,DELETED,true", "ACTIVE,INVITED,false",
            "LOCKED,ACTIVE,true", "LOCKED,DISABLED,true",
            "DISABLED,ACTIVE,true", "DISABLED,LOCKED,false", "DISABLED,DELETED,true",
            "DELETED,ACTIVE,false", "DELETED,DISABLED,false"})
    @DisplayName("[IAM-01.10] 상태 전이 표대로 허용·거부한다")
    void transitions(UserStatus from, UserStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    @DisplayName("[IAM-02.03] 잠금 시간이 지났는가")
    void lockExpired() {
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        AppUser locked = user(LOCKED, now.minusSeconds(1));
        assertThat(locked.lockExpired(now)).isTrue();
        assertThat(user(LOCKED, now.plusSeconds(1)).lockExpired(now)).isFalse();
        assertThat(user(ACTIVE, null).lockExpired(now)).isFalse();
        assertThat(INVITED.canTransitionTo(DELETED)).isTrue();
        assertThat(PENDING_APPROVAL.canTransitionTo(DELETED)).isTrue();
        assertThat(DISABLED.canTransitionTo(DELETED)).isTrue();
        assertThat(DELETED.canTransitionTo(DELETED)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"kim.op,", "KIM.OP,", "a_b-c.9,", "abc,FORMAT", "has space,FORMAT", "한글아이디,FORMAT", "admin,RESERVED",
            "Root,RESERVED", "deleted-12,FORMAT", "abcdefghijklmnopqrstuvwxyz12345,FORMAT"})
    @DisplayName("[IAM-02.01][BR-IAM-02][AT-IAM-06.3] 로그인 아이디: 소문자·숫자·._- 4~30자, 예약어 금지 — TC-IAM-027")
    void loginIdRules(String raw, String reason) {
        assertThat(LoginIdRules.rejectReason(raw)).isEqualTo(reason);
        if (reason == null) {
            assertThat(LoginIdRules.normalize(raw)).isEqualTo(raw.toLowerCase());
        } else {
            assertThatThrownBy(() -> LoginIdRules.normalize(raw)).isInstanceOf(BusinessException.class);
        }
        assertThat(LoginIdRules.rejectReason(null)).isEqualTo("FORMAT");
    }

    private static AppUser user(UserStatus status, Instant lockedUntil) {
        return new AppUser(1, 1, "kim.op", "k@s.kr", "김", null, "ko", "Asia/Seoul", status, null, null, false, 0, lockedUntil,
                null, null, null, false, "{}", null, 0, null, null);
    }
}
