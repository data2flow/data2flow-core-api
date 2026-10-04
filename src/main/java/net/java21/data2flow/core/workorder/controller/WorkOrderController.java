package net.java21.data2flow.core.workorder.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.filestore.repository.FileBlobRepository.Blob;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.AttachmentResponse;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.ChecklistItemResponse;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.ChecklistPatchRequest;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.CommentRequest;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.CommentResponse;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.CreatePlanRequest;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.CreateWorkOrderRequest;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.InternalCreateWorkOrderRequest;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.InternalCreateWorkOrderResponse;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.PlanResponse;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.TransitionRequest;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.WorkOrderDetailResponse;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.WorkOrderListResponse;
import net.java21.data2flow.core.workorder.dto.WorkOrderDtos.WorkOrderResponse;
import net.java21.data2flow.core.workorder.service.MaintenancePlanService;
import net.java21.data2flow.core.workorder.service.WorkOrderService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;

/** 작업 지시·정기 점검(design/api/DEV-api.md §9 API-DEV-90~95, 내부 API-DEV-129, DEV-08.02·08.05·08.06, DSH-13.04) */
@RestController
public class WorkOrderController {

    private final WorkOrderService service;
    private final MaintenancePlanService plans;

    public WorkOrderController(WorkOrderService service, MaintenancePlanService plans) {
        this.service = service;
        this.plans = plans;
    }

    /** API-DEV-90 — WORKORDER_WRITE, 201(+Location). 기존 작업 지시에 연결되면 200 + linkedToExisting=true */
    @PostMapping("/core/work-orders")
    @Idempotent
    public ResponseEntity<ApiResponse<WorkOrderResponse>> create(@Valid @RequestBody CreateWorkOrderRequest request) {
        WorkOrderResponse created = service.create(request);
        if (created.linkedToExisting()) {
            return ResponseEntity.ok(ApiResponse.success(created));
        }
        return ResponseEntity.created(URI.create("/api/v1/core/work-orders/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-DEV-91 — DEV_READ. assigneeId=me, dueBefore(마감 임박 = now+48h), overdue, spaceId(하위 포함), from·to(생성 기간, 평균 처리 시간) */
    @GetMapping("/core/work-orders")
    public WorkOrderListResponse list(@RequestParam(required = false) List<String> status, @RequestParam(required = false) String assigneeId,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dueBefore,
                                      @RequestParam(required = false) Boolean overdue, @RequestParam(required = false) String spaceId,
                                      @RequestParam(required = false) String type,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.list(status, assigneeId, dueBefore, overdue, spaceId, type, from, to, page, size);
    }

    /** 작업 지시 상세(첨부·댓글·덧붙은 출처 포함) — DEV_READ */
    @GetMapping("/core/work-orders/{work-order-id}")
    public ApiResponse<WorkOrderDetailResponse> get(@PathVariable("work-order-id") long id) {
        return ApiResponse.success(service.get(id));
    }

    /** API-DEV-92 — WORKORDER_WRITE. 같은 Idempotency-Key 재전송은 한 번만 반영(TC-DSH-126) */
    @PostMapping("/core/work-orders/{work-order-id}/transition")
    @Idempotent
    public ApiResponse<WorkOrderResponse> transition(@PathVariable("work-order-id") long id, @Valid @RequestBody TransitionRequest request) {
        return ApiResponse.success(service.transition(id, request));
    }

    /** API-DEV-93 첨부(multipart file, 이미지·PDF ≤20MB) — WORKORDER_WRITE, 201 */
    @PostMapping(value = "/core/work-orders/{work-order-id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Idempotent
    public ResponseEntity<ApiResponse<AttachmentResponse>> attach(@PathVariable("work-order-id") long id,
                                                                  @RequestPart("file") MultipartFile file) throws IOException {
        AttachmentResponse created = service.attach(id, file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
    }

    @DeleteMapping("/core/work-orders/{work-order-id}/attachments/{attachment-id}")
    public ResponseEntity<Void> detach(@PathVariable("work-order-id") long id, @PathVariable("attachment-id") long attachmentId) {
        service.detach(id, attachmentId);
        return ResponseEntity.noContent().build();
    }

    /** 첨부 내려받기(권한을 다시 확인) — DEV_READ */
    @GetMapping("/core/work-orders/{work-order-id}/attachments/{attachment-id}/content")
    public ResponseEntity<byte[]> content(@PathVariable("work-order-id") long id, @PathVariable("attachment-id") long attachmentId) {
        Blob blob = service.attachmentContent(id, attachmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(blob.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600").body(blob.data());
    }

    /** API-DEV-94 댓글 — WORKORDER_WRITE, 201 */
    @PostMapping("/core/work-orders/{work-order-id}/comments")
    public ResponseEntity<ApiResponse<CommentResponse>> comment(@PathVariable("work-order-id") long id,
                                                                @Valid @RequestBody CommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.comment(id, request.text())));
    }

    /** API-DEV-94 체크리스트 — WORKORDER_WRITE */
    @PatchMapping("/core/work-orders/{work-order-id}/checklist/{checklist-item-id}")
    public ApiResponse<ChecklistItemResponse> check(@PathVariable("work-order-id") long id, @PathVariable("checklist-item-id") long itemId,
                                                    @Valid @RequestBody ChecklistPatchRequest request) {
        return ApiResponse.success(service.check(id, itemId, request.done()));
    }

    /** API-DEV-95 계획 — DEV_ADMIN */
    @GetMapping("/core/maintenance-plans")
    public ListApiResponse<PlanResponse> plans(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return plans.list(page, size);
    }

    @PostMapping("/core/maintenance-plans")
    public ResponseEntity<ApiResponse<PlanResponse>> createPlan(@Valid @RequestBody CreatePlanRequest request) {
        PlanResponse created = plans.create(request);
        return ResponseEntity.created(URI.create("/api/v1/core/maintenance-plans/" + created.id())).body(ApiResponse.success(created));
    }

    @PatchMapping("/core/maintenance-plans/{maintenance-plan-id}")
    public ApiResponse<PlanResponse> updatePlan(@PathVariable("maintenance-plan-id") long id, @RequestBody JsonNode body) {
        return ApiResponse.success(plans.update(id, body));
    }

    @DeleteMapping("/core/maintenance-plans/{maintenance-plan-id}")
    public ResponseEntity<Void> deletePlan(@PathVariable("maintenance-plan-id") long id) {
        plans.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** API-DEV-129 내부 생성(같은 originRef의 열린 작업 지시가 있으면 연결) */
    @PostMapping("/internal/core/work-orders")
    public ApiResponse<InternalCreateWorkOrderResponse> createInternal(@Valid @RequestBody InternalCreateWorkOrderRequest request) {
        return ApiResponse.success(service.createInternal(request));
    }
}
