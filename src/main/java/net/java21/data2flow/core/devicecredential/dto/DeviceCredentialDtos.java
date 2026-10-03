package net.java21.data2flow.core.devicecredential.dto;

import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/** 플랫폼 브로커 기기 자격(design/api/DSC-api.md API-DSC-20~22, ADR-029) */
public final class DeviceCredentialDtos {

    private DeviceCredentialDtos() {
    }

    /** API-DSC-20 요청. 공용 브로커라 PASSWORD(nginx Basic)만(ADR-029) */
    public record IssueCredentialRequest(@Pattern(regexp = "PASSWORD") String type, Instant expiresAt) {
    }

    /** API-DSC-20 응답: 비밀번호·서명 키(HMAC-SHA256)는 이 응답에서 한 번만 보인다(DB에는 해시만) */
    public record IssuedCredentialResponse(String credentialId, String username, String password, String signingKey, Instant expiresAt) {
    }

    /** API-DSC-21 항목 */
    public record CredentialResponse(String id, String deviceId, String type, String username, String status, Instant expiresAt,
                                     Instant lastUsedAt, Instant createdAt, Instant revokedAt) {
    }

    /** API-DSC-72 항목(내부 전용, 로그 금지). ID는 문자열 */
    public record SigningKey(String credentialId, String organizationId, String sourceId, String deviceId, String deviceKey,
                             String signingKey, Instant expiresAt) {
        @Override
        public String toString() {
            return "SigningKey[credentialId=" + credentialId + ", deviceKey=" + deviceKey + "]";
        }
    }

    /** API-DSC-72 응답 {@code {version, keys[]}} */
    public record SigningKeysResponse(long version, java.util.List<SigningKey> keys) {
        @Override
        public String toString() {
            return "SigningKeysResponse[version=" + version + ", keys=" + keys.size() + "]";
        }
    }
}
