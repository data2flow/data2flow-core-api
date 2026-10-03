package net.java21.data2flow.core.session.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 세션·Refresh 계보 API DTO(API-IAM-16·17·24·35·36·37·39a) */
public final class SessionDtos {

    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final String HASH_PATTERN = "[0-9a-f]{64}";

    private SessionDtos() {
    }

    /** API-IAM-36 Refresh 계보 등록 */
    public record CreateRefreshTokenRequest(@NotBlank @Pattern(regexp = UUID_PATTERN) String jti,
                                            @NotBlank @Pattern(regexp = UUID_PATTERN) String sid,
                                            @NotBlank @Pattern(regexp = "\\d{1,18}") String userId,
                                            @NotBlank @Pattern(regexp = "\\d{1,18}") String orgId,
                                            @NotBlank @Pattern(regexp = HASH_PATTERN) String tokenHash,
                                            @NotNull Instant expiresAt, @NotNull Instant absoluteExpiresAt,
                                            String ip, String userAgent) {
    }

    public record RefreshTokenResponse(String jti, String sid, Instant expiresAt) {
    }

    /** API-IAM-35 회전 */
    public record RotateRefreshTokenRequest(@NotBlank @Pattern(regexp = UUID_PATTERN) String presentedJti,
                                            @NotBlank @Pattern(regexp = UUID_PATTERN) String nextJti,
                                            @NotBlank @Pattern(regexp = HASH_PATTERN) String nextTokenHash,
                                            @NotNull Instant nextExpiresAt) {
    }

    /**
     * @param decision     ROTATED(새 토큰 저장) / GRACE(30초 안 재사용, 최신 토큰으로 처리)
     * @param effectiveJti 이후 유효한 jti
     * @param sid          세션 ID
     * @param userId       사용자
     * @param orgId        조직
     * @param expiresAt    유효 Refresh 만료(절대 만료를 넘지 않음)
     */
    public record RotateRefreshTokenResponse(String decision, String effectiveJti, String sid, String userId, String orgId,
                                             Instant expiresAt) {
    }

    /** API-IAM-37 폐기 사유 */
    public record RevokeRequest(@Size(max = 30) String reason) {
    }

    /** API-IAM-39a */
    public record RevocationsResponse(List<String> sids, List<String> jtis) {
    }

    /** API-IAM-16 활성 로그인 한 줄 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionResponse(String sid, String userAgent, String ip, Instant firstLoginAt, Instant lastUsedAt,
                                  boolean current) {
    }

    /** API-IAM-24 */
    public record RevokeAllResponse(int revokedSessions) {
    }
}
