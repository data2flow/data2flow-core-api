package net.java21.data2flow.core.account.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 내부 자격 확인 DTO(API-IAM-30, auth → core) */
public final class LoginDtos {

    private LoginDtos() {
    }

    /** 비밀번호는 로그·응답에 나가지 않게 toString을 가린다 */
    public record VerifyCredentialsRequest(@NotNull @Size(max = 100) String loginId, @NotNull @Size(max = 256) String password,
                                           String ip, String userAgent) {
        @Override
        public String toString() {
            return "VerifyCredentialsRequest[loginId=" + loginId + ", password=***]";
        }
    }

    public record VerifyCredentialsResponse(String userId, String orgId, boolean mustChangePassword, boolean mfaEnabled) {
    }
}
