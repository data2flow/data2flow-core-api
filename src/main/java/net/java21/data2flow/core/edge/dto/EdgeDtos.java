package net.java21.data2flow.core.edge.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** 엣지 게이트웨이 API(design/api/DSC-api.md §8 API-DSC-62·64·65·67, 내부 API-DSC-78·79) */
public final class EdgeDtos {

    private EdgeDtos() {
    }

    public record Ref(String id, String name) {
    }

    /** 엣지 상세·목록 항목. status는 하트비트로 계산(OFFLINE), updateAvailable은 게시된 최신 버전이 지금 버전과 다를 때 */
    public record EdgeResponse(String id, String name, Ref site, String status, String agentVersion, String arch, String certFingerprint,
                               Integer appliedConfigVersion, Integer desiredConfigVersion, Long bufferUsedBytes, Long bufferItems,
                               long droppedItems, Double throughput, Instant lastSeenAt, Instant revokedAt, String latestAgentVersion,
                               boolean updateAvailable, int version, Instant createdAt, Instant updatedAt) {
    }

    /** API-DSC-62 생성(201)·등록 토큰 재발급 응답. 토큰은 이 응답에서 한 번만 보인다 */
    public record RegistrationResponse(String id, String name, String status, String registrationToken, Instant expiresAt, String installCommand,
                                       String offlinePackageUrl) {
    }

    /** API-DSC-64 설정 판 */
    public record ConfigVersionResponse(int version, JsonNode targets, JsonNode decoders, String result, String error, Instant createdAt,
                                        Instant deployedAt, boolean desired, boolean applied) {
    }

    /** API-DSC-65 업데이트 */
    public record UpdateResponse(String updateId, String fromVersion, String toVersion, String status, Instant createdAt, Instant startedAt,
                                 Instant finishedAt) {
    }

    /** API-DSC-65 GET: 게시된 버전과 이력 */
    public record UpdatesResponse(String currentVersion, String latestVersion, List<String> availableVersions, List<UpdateResponse> updates) {
    }

    /** API-DSC-67 */
    public record RequestResponse(String requestId) {
    }

    /** API-DSC-78 응답 */
    public record RegisteredResponse(String edgeId, String organizationId, String siteId, String sourceId) {
    }

    /** API-DSC-79 응답: 원하는 설정·승인된 업데이트·원격 명령·폐기 여부 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HeartbeatResponse(Integer desiredConfigVersion, DesiredConfig desiredConfig, PendingUpdate pendingUpdate,
                                    List<Command> commands, boolean revoked) {
    }

    public record DesiredConfig(int version, JsonNode targets, JsonNode decoders) {
    }

    public record PendingUpdate(String updateId, String toVersion) {
    }

    public record Command(String requestId, String kind, JsonNode args) {
    }
}
