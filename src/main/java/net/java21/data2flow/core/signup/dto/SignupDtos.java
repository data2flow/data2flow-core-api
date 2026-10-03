package net.java21.data2flow.core.signup.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 가입 신청 DTO(API-IAM-67·68·69) */
public final class SignupDtos {

    private SignupDtos() {
    }

    public record CreateSignupRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 50) String name,
                                      @NotBlank String loginId, @NotNull String password, @Size(max = 500) String message,
                                      @NotNull Boolean privacyConsent) {
        @Override
        public String toString() {
            return "CreateSignupRequest[loginId=" + loginId + "]";
        }
    }

    /** 승인: 역할과 공간 범위 모두 필수(IAM-01.08 ③). 빈 공간 범위는 "전체 공간"이다 */
    public record ApproveSignupRequest(String role, String customRoleId, List<String> spaceScope) {
    }

    public record RejectSignupRequest(String reason) {
    }

    public record AcceptedResponse(String status) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SignupSummaryResponse(String id, String email, String name, String loginId, String message, String status,
                                        Instant emailVerifiedAt, String requestIp, Instant createdAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SignupDecisionResponse(String id, String status, String userId, String rejectReason, String decidedBy,
                                         Instant decidedAt) {
    }
}
