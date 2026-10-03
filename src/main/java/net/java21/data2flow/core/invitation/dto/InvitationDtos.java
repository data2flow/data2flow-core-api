package net.java21.data2flow.core.invitation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 초대 API DTO(API-IAM-10·10a·11·20·22·29·33) */
public final class InvitationDtos {

    private InvitationDtos() {
    }

    public record CreateInvitationsRequest(@NotEmpty @Size(max = 20) List<String> emails, @Size(max = 50) String name,
                                           @NotBlank String role, String customRoleId, List<String> spaceScope) {
    }

    public record CreateInvitationsResponse(List<InvitationResult> results) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvitationResult(String email, String status, String invitationId, String resultCode) {
    }

    /** API-IAM-10 공개 초대 확인 */
    public record InvitationInfoResponse(String organizationName, String invitedBy, String role, String email, Instant expiresAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LoginIdAvailabilityResponse(boolean available, String reason) {
    }

    /** API-IAM-11 초대 수락. privacyConsent는 개인정보 수집·이용 고지 동의(NFR-12.01) */
    public record AcceptInvitationRequest(@NotBlank String loginId, @NotNull String password, @NotNull Boolean privacyConsent) {
        @Override
        public String toString() {
            return "AcceptInvitationRequest[loginId=" + loginId + "]";
        }
    }

    public record AcceptInvitationResponse(String loginId) {
    }

    public record ResendInvitationResponse(Instant expiresAt, int sentCount) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record InvitationSummaryResponse(String id, String email, String role, String customRoleId, List<String> spaceScope,
                                            String status, int sentCount, String invitedBy, Instant expiresAt, Instant createdAt) {
    }
}
