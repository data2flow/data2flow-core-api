package net.java21.data2flow.core.mfa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 2단계 인증 DTO(API-IAM-60·61·61a·61b·61c) */
public final class MfaDtos {

    private MfaDtos() {
    }

    public record MfaSetupResponse(String otpauthUri, String secret) {
    }

    public record MfaCodeRequest(@NotBlank String code) {
        @Override
        public String toString() {
            return "MfaCodeRequest[***]";
        }
    }

    public record RecoveryCodesResponse(List<String> recoveryCodes) {
    }

    public record CurrentPasswordRequest(@NotNull String currentPassword) {
        @Override
        public String toString() {
            return "CurrentPasswordRequest[***]";
        }
    }

    /**
     * @param ok                     확인 성공
     * @param recoveryCodeUsed       복구 코드로 통과했는가
     * @param remainingRecoveryCodes 남은 복구 코드(2개 이하면 재발급 안내, BR-IAM-26)
     */
    public record MfaVerifyResponse(boolean ok, boolean recoveryCodeUsed, long remainingRecoveryCodes) {
    }
}
