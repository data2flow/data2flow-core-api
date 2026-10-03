package net.java21.data2flow.core.flow.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiHeader;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ErrorMessages;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.common.ItemsResponse;
import net.java21.data2flow.core.flow.domain.FlowDiff;
import net.java21.data2flow.core.flow.domain.FlowErrorCode;
import net.java21.data2flow.core.flow.dto.FlowDtos.Approval;
import net.java21.data2flow.core.flow.dto.FlowDtos.FlowDetail;
import net.java21.data2flow.core.flow.dto.FlowDtos.FlowInfo;
import net.java21.data2flow.core.flow.dto.FlowDtos.FlowSummary;
import net.java21.data2flow.core.flow.dto.FlowDtos.SaveResult;
import net.java21.data2flow.core.flow.dto.FlowDtos.StatusResult;
import net.java21.data2flow.core.flow.dto.FlowDtos.ValidateResult;
import net.java21.data2flow.core.flow.dto.FlowDtos.VersionSummary;
import net.java21.data2flow.core.flow.service.FlowApplyService;
import net.java21.data2flow.core.flow.service.FlowApplyService.Outcome;
import net.java21.data2flow.core.flow.service.FlowRuntimeService;
import net.java21.data2flow.core.flow.service.FlowService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.List;

/** 플로우 정의·버전·적용·승인(FLW-01·05.06, API-FLW-01~10·14·24). 조회 FLOW_READ, 쓰기 FLOW_WRITE, 제어 노드 적용 FLOW_DEPLOY_CONTROL, 승인 FLOW_APPROVE */
@RestController
public class FlowController {

    private final FlowService flows;
    private final FlowApplyService apply;
    private final FlowRuntimeService runtime;
    private final ErrorMessages messages;

    public FlowController(FlowService flows, FlowApplyService apply, FlowRuntimeService runtime, ErrorMessages messages) {
        this.flows = flows;
        this.apply = apply;
        this.runtime = runtime;
        this.messages = messages;
    }

    @GetMapping("/core/flows")
    public ListApiResponse<FlowSummary> list(@RequestParam(required = false) String q, @RequestParam(required = false) String owner,
                                             @RequestParam(required = false) List<String> status, @RequestParam(required = false) String kind,
                                             @RequestParam(required = false) String environment, @RequestParam(required = false) String spaceId,
                                             @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                             @RequestParam(required = false) String sort) {
        return flows.list(q, owner, status, kind, environment, spaceId, page, size, sort);
    }

    @PostMapping("/core/flows")
    @Idempotent
    public ResponseEntity<ApiResponse<SaveResult>> create(@RequestBody JsonNode body) {
        SaveResult created = flows.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/flows/" + created.flowId())).body(ApiResponse.success(created));
    }

    /** API-FLW-24 승인 목록(리터럴 경로라 {flow-id}보다 먼저 맞는다) */
    @GetMapping("/core/flows/approvals")
    public ListApiResponse<Approval> approvals(@RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return apply.list(status, page, size);
    }

    @GetMapping("/core/flows/{flow-id}")
    public ApiResponse<FlowDetail> detail(@PathVariable("flow-id") String flowId, @RequestParam(required = false) Integer version) {
        return ApiResponse.success(flows.detail(flowId, version));
    }

    @PatchMapping("/core/flows/{flow-id}")
    public ApiResponse<FlowInfo> patch(@PathVariable("flow-id") String flowId, @RequestBody JsonNode body) {
        return ApiResponse.success(flows.patch(flowId, body));
    }

    @DeleteMapping("/core/flows/{flow-id}")
    public ResponseEntity<Void> delete(@PathVariable("flow-id") String flowId) {
        flows.delete(flowId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/core/flows/{flow-id}/draft")
    public ApiResponse<SaveResult> saveDraft(@PathVariable("flow-id") String flowId, @RequestBody JsonNode body) {
        return ApiResponse.success(flows.saveDraft(flowId, body));
    }

    @PostMapping("/core/flows/{flow-id}/validate")
    public ApiResponse<ValidateResult> validate(@PathVariable("flow-id") String flowId, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(flows.validate(flowId, body));
    }

    /** API-FLW-07: 바로 적용 200 / 승인 대기 202(resultCode FLOW_APPROVAL_REQUIRED, isSuccessful=true) */
    @PostMapping("/core/flows/{flow-id}/apply")
    @Idempotent
    public ResponseEntity<?> apply(@PathVariable("flow-id") String flowId, @RequestBody JsonNode body) {
        return outcome(apply.apply(flowId, body));
    }

    @PostMapping("/core/flows/{flow-id}/rollback")
    @Idempotent
    public ResponseEntity<?> rollback(@PathVariable("flow-id") String flowId, @RequestBody JsonNode body) {
        return outcome(apply.rollback(flowId, body));
    }

    private ResponseEntity<?> outcome(Outcome outcome) {
        if (outcome.pending() != null) {
            ApiHeader header = new ApiHeader(true, FlowErrorCode.FLOW_APPROVAL_REQUIRED.code(),
                    messages.resolve(FlowErrorCode.FLOW_APPROVAL_REQUIRED));
            return ResponseEntity.status(202).body(new ApiResponse<>(header, outcome.pending()));
        }
        return ResponseEntity.ok(ApiResponse.success(outcome.applied()));
    }

    @PostMapping("/core/flows/{flow-id}/pause")
    public ApiResponse<StatusResult> pause(@PathVariable("flow-id") String flowId, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(flows.transition(flowId, "pause", body));
    }

    @PostMapping("/core/flows/{flow-id}/resume")
    public ApiResponse<StatusResult> resume(@PathVariable("flow-id") String flowId) {
        return ApiResponse.success(flows.transition(flowId, "resume", null));
    }

    @PostMapping("/core/flows/{flow-id}/disable")
    public ApiResponse<StatusResult> disable(@PathVariable("flow-id") String flowId) {
        return ApiResponse.success(flows.transition(flowId, "disable", null));
    }

    @GetMapping("/core/flows/{flow-id}/versions")
    public ItemsResponse<VersionSummary> versions(@PathVariable("flow-id") String flowId) {
        return ItemsResponse.of(flows.versions(flowId));
    }

    @GetMapping("/core/flows/{flow-id}/version-diff")
    public ApiResponse<FlowDiff.Diff> diff(@PathVariable("flow-id") String flowId, @RequestParam(required = false) Integer from,
                                           @RequestParam(required = false) Integer to) {
        return ApiResponse.success(flows.diff(flowId, from, to));
    }

    @GetMapping("/core/flows/{flow-id}/metrics")
    public ApiResponse<JsonNode> metrics(@PathVariable("flow-id") String flowId, @RequestParam(required = false) String window,
                                         @RequestParam(required = false) String step) {
        return ApiResponse.success(runtime.metrics(flowId, window, step));
    }

    @PostMapping("/core/flow-approvals/{approval-id}/approve")
    public ApiResponse<Approval> approve(@PathVariable("approval-id") String approvalId) {
        return ApiResponse.success(apply.approve(approvalId));
    }

    @PostMapping("/core/flow-approvals/{approval-id}/reject")
    public ApiResponse<Approval> reject(@PathVariable("approval-id") String approvalId, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(apply.reject(approvalId, body));
    }
}
