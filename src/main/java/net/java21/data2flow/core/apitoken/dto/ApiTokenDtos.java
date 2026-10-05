package net.java21.data2flow.core.apitoken.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** 장기 토큰·서비스 계정 요청·응답(design/api/IAM-api.md §5, API-IAM-40~46). ID는 JSON 문자열 */
public final class ApiTokenDtos {

    private ApiTokenDtos() {
    }

    /** API-IAM-40 요청 */
    public record IssueRequest(String kind, String name, List<String> scopes, List<String> spaceScope, Instant expiresAt,
                               Integer rateLimitPerMin, String serviceAccountId) {
    }

    /** API-IAM-40 응답. token은 이 응답에서 한 번만 */
    public record IssueResponse(String id, String token, String prefix, String status) {
    }

    /** API-IAM-41 목록 항목(원문 없음) */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TokenItem(String id, String kind, String name, String tokenPrefix, String ownerType, String ownerId, String ownerName,
                            List<String> scopes, List<String> spaceScope, String status, Instant expiresAt, int rateLimitPerMin,
                            Instant lastUsedAt, String lastUsedIp, Instant graceUntil, Instant createdAt) {
    }

    /** API-IAM-42 요청 */
    public record DecisionRequest(String reason) {
    }

    /** API-IAM-44 요청 */
    public record RotateRequest(Integer graceHours) {
    }

    /** API-IAM-44 응답. token은 이 응답에서 한 번만 */
    public record RotateResponse(String newTokenId, String token) {
    }

    /** API-IAM-45 요청 */
    public record ServiceAccountRequest(String name, String description) {
    }

    /** API-IAM-45 응답 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ServiceAccountResponse(String id, String name, String description, String status, long tokenCount, int version,
                                         Instant createdAt) {
    }

    /** API-IAM-46 요청(auth introspection). ip는 선택(넘기면 마지막 사용 IP로 기록) */
    public record VerifyRequest(String tokenHash, String ip) {
    }

    /**
     * API-IAM-46 응답. 쓸 수 없는 토큰은 active=false만 채운다. 서비스 계정 토큰은 userId가 null이고 ownerId가 계정 ID다
     * (auth는 sub = userId ?: ownerId로 둔다. core는 X-ACCESS-TOKEN-ID로 토큰을 다시 찾아 권한을 정하므로 사용자 ID와 섞이지 않는다).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record VerifyResponse(boolean active, String ownerType, String ownerId, String userId, String org, List<String> scopes,
                                 List<String> spaceScope, String kind, String tokenId, Integer rateLimitPerMin) {

        public static VerifyResponse inactive() {
            return new VerifyResponse(false, null, null, null, null, List.of(), List.of(), null, null, null);
        }
    }
}
