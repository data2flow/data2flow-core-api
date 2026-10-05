package net.java21.data2flow.core.workorder.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import net.java21.data2flow.contracts.web.ApiHeader;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 작업 지시·정기 점검 API(design/api/DEV-api.md §9 API-DEV-90~95, 내부 API-DEV-129) */
public final class WorkOrderDtos {

    private WorkOrderDtos() {
    }

    /** 대상: 기기 또는 공간 하나(ID는 문자열 또는 숫자) */
    public record TargetDto(String deviceId, String spaceId) {
    }

    /** API-DEV-90 생성 */
    public record CreateWorkOrderRequest(@NotBlank @Size(max = 150) String title, @NotBlank String type, String priority,
                                         @NotNull @Size(min = 1, max = 200) List<@Valid TargetDto> targets, String assigneeId,
                                         Instant dueAt, @Size(max = 50) List<@NotBlank @Size(max = 200) String> checklist,
                                         String origin, @Size(max = 100) String originRef) {
    }

    /** API-DEV-129 내부 생성(flow-engine·analytics·core 알람). origin·originRef 필수 */
    public record InternalCreateWorkOrderRequest(@NotNull Long organizationId, @NotBlank @Size(max = 150) String title,
                                                 @NotBlank String type, String priority,
                                                 @NotNull @Size(min = 1, max = 200) List<@Valid TargetDto> targets, String assigneeId,
                                                 Instant dueAt, @Size(max = 50) List<@NotBlank @Size(max = 200) String> checklist,
                                                 @NotBlank String origin, @NotBlank @Size(max = 100) String originRef) {
    }

    public record InternalCreateWorkOrderResponse(String id, String status, boolean linkedToExisting) {
    }

    public record TargetResponse(String deviceId, String spaceId) {
    }

    public record ChecklistItemResponse(String id, String text, boolean done, String doneBy, Instant doneAt) {
    }

    /** 작업 지시(API-DEV-90·92 응답, 목록 항목) */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WorkOrderResponse(String id, String title, String type, String status, String priority, List<TargetResponse> targets,
                                    String assigneeId, String requesterId, Instant dueAt, String origin, String originRef,
                                    List<ChecklistItemResponse> checklist, JsonNode result, Instant completedAt, boolean linkedToExisting,
                                    int version, Instant createdAt, Instant updatedAt) {
    }

    /** 상세: 작업 지시 + 덧붙은 출처·첨부·댓글 */
    public record WorkOrderDetailResponse(String id, String title, String type, String status, String priority, List<TargetResponse> targets,
                                          String assigneeId, String requesterId, Instant dueAt, String origin, String originRef,
                                          JsonNode linkedOrigins, List<ChecklistItemResponse> checklist, JsonNode result,
                                          Instant completedAt, List<AttachmentResponse> attachments, List<CommentResponse> comments,
                                          int version, Instant createdAt, Instant updatedAt) {
    }

    /** API-DEV-91 목록 + 평균 처리 시간(totalCount 뒤) */
    public record WorkOrderListResponse(ApiHeader header, int page, int size, int totalPages, List<WorkOrderResponse> responses,
                                        long totalCount, Stats stats) {
    }

    public record Stats(Double avgLeadTimeHours) {
    }

    /** API-DEV-92 전이 */
    public record TransitionRequest(@NotBlank String action, String assigneeId, JsonNode result, @Size(max = 2000) String note) {
    }

    /** API-DEV-93 첨부 응답. url은 권한을 다시 확인하는 내려받기 경로 */
    public record AttachmentResponse(String id, String workOrderId, String kind, String fileName, long sizeBytes, String url,
                                     String uploadedBy, Instant createdAt) {
    }

    public record CommentRequest(@NotBlank @Size(max = 2000) String text) {
    }

    public record CommentResponse(String id, String authorId, String authorName, String body, Instant createdAt) {
    }

    public record ChecklistPatchRequest(@NotNull Boolean done) {
    }

    /** API-DEV-95 계획 생성 */
    public record CreatePlanRequest(@NotBlank @Size(max = 100) String name, @NotBlank String targetGroupId, @NotBlank String workType,
                                    @NotNull Integer intervalDays, Integer leadDays, @NotNull LocalDate nextDueOn,
                                    String defaultAssigneeId, @Size(max = 50) List<@NotBlank @Size(max = 200) String> checklistTemplate,
                                    Boolean enabled) {
    }

    public record PlanResponse(String id, String name, String targetGroupId, String workType, int intervalDays, int leadDays,
                               LocalDate nextDueOn, String defaultAssigneeId, List<String> checklistTemplate, boolean enabled, int version,
                               Instant updatedAt) {
    }
}
