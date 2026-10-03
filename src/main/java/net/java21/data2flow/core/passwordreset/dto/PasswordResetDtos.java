package net.java21.data2flow.core.passwordreset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 비밀번호 재설정 DTO(API-IAM-13·14) */
public final class PasswordResetDtos {

    private PasswordResetDtos() {
    }

    public record CreatePasswordResetRequest(@NotBlank @Size(max = 254) String loginIdOrEmail) {
    }

    public record ConfirmPasswordResetRequest(@NotNull String newPassword) {
        @Override
        public String toString() {
            return "ConfirmPasswordResetRequest[***]";
        }
    }

    /** 202 응답(존재 여부와 관계없이 같다) */
    public record AcceptedResponse(String status) {
    }
}
