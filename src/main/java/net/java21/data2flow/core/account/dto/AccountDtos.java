package net.java21.data2flow.core.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 내 정보(API-IAM-04·12·15)와 회원 관리(API-IAM-21·23·25·28·31·32) DTO */
public final class AccountDtos {

    private AccountDtos() {
    }

    /** API-IAM-04 내 정보. 권한은 화면 표시 보조용이고 판정은 서버가 한다(IAM-04.05) */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MeResponse(String id, String loginId, String email, String name, String phone, String locale, String timezone,
                             String role, CustomRoleRef customRole, List<String> permissions, List<String> spaceScope,
                             boolean mustChangePassword, boolean mfaEnabled, Map<String, Object> notificationPref, int version) {
    }

    public record CustomRoleRef(String id, String name) {
    }

    /** API-IAM-12 비밀번호 변경 */
    public record ChangePasswordRequest(@NotNull String currentPassword, @NotNull String newPassword, Boolean keepCurrentSession) {
        @Override
        public String toString() {
            return "ChangePasswordRequest[***]";
        }
    }

    /** API-IAM-21 직접 생성 */
    public record CreateUserRequest(@NotBlank String loginId, @NotBlank @Size(max = 254) String email,
                                    @NotBlank @Size(max = 50) String name, @NotBlank String role, String customRoleId,
                                    List<String> spaceScope, String temporaryPassword) {
        @Override
        public String toString() {
            return "CreateUserRequest[loginId=" + loginId + ", role=" + role + "]";
        }
    }

    /** 임시 비밀번호는 자동 생성했을 때만 1회 담는다 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateUserResponse(String id, String temporaryPassword) {
    }

    /** API-IAM-23 역할 변경 */
    public record ChangeRoleRequest(@NotBlank String role, String customRoleId, List<String> spaceScope, @NotNull Integer baseVersion) {
    }

    public record ChangeRoleResponse(String userId, String role, String customRoleId, List<String> spaceScope, int version) {
    }

    public record DisableUserRequest(@Size(max = 200) String reason) {
    }

    public record DeleteUserRequest(@NotBlank String confirmLoginId) {
    }

    /** API-IAM-31 목록 항목 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UserSummaryResponse(String id, String name, String loginId, String email, String role, String spaceScopeSummary,
                                      String status, boolean mfaEnabled, Instant lastLoginAt) {
    }

    /** API-IAM-32 상세 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UserDetailResponse(String id, String loginId, String email, String name, String phone, String locale,
                                     String timezone, String status, String role, String customRoleId,
                                     List<SpaceRef> spaceScope, boolean mfaEnabled, boolean mustChangePassword,
                                     Instant lockedUntil, Instant lastLoginAt, String lastLoginIp, long activeSessionCount,
                                     Instant createdAt, int version) {
    }

    /** 공간 참조. 이름은 공간 계층이 생기는 M2에서 채운다 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SpaceRef(String id, String name) {
    }
}
