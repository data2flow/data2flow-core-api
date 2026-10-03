package net.java21.data2flow.core.audit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

/** 감사 로그 API DTO(API-IAM-39·50·51·52) */
public final class AuditLogDtos {

    private AuditLogDtos() {
    }

    /** 목록 항목(API-IAM-50) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuditLogSummaryResponse(String id, Instant occurredAt, String actorType, String actorId, String actorName,
                                          String action, String targetType, String targetId, String result, String ip,
                                          String requestId) {
    }

    /** 상세(API-IAM-52). cause.executionUrl로 원인 플로우 실행 기록에 간다(IAM-06.04) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuditLogDetailResponse(String id, Instant occurredAt, String actorType, String actorId, String actorName,
                                         String action, String targetType, String targetId, String result,
                                         Map<String, Object> detail, Map<String, Object> cause, String ip, String userAgent,
                                         String requestId) {
    }

    /** 내보내기 검색 조건(API-IAM-51). 목록과 같은 이름 */
    public record ExportAuditLogsRequest(Instant from, Instant to, String actorType, String actor, String action,
                                         String targetType, String targetId, String result, String ip) {
    }

    /**
     * 다른 서비스가 보내는 감사 기록(API-IAM-39). 모양은 contracts {@code AuditEvent}와 같다.
     * organizationId가 없으면 X-ORG-ID(사용자를 대신한 호출)를 쓴다.
     */
    public record CreateAuditLogRequest(Long organizationId, Instant occurredAt, @NotBlank @Size(max = 20) String actorType,
                                        @Size(max = 64) String actorId, @Size(max = 100) String actorName,
                                        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,59}") String action,
                                        @Size(max = 40) String targetType, @Size(max = 64) String targetId,
                                        @NotBlank String result, Map<String, Object> detail, CauseDto cause, String ip,
                                        @Size(max = 300) String userAgent, @Size(max = 64) String requestId) {
    }

    public record CauseDto(String flowId, Integer flowVersion, String nodeId, String triggerMessageId) {
    }

    /** 202 응답 본문(common.yaml AsyncAccepted) */
    public record AcceptedResponse(String status) {
    }
}
