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
}
