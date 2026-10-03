package net.java21.data2flow.core.flow.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.java21.data2flow.core.flow.domain.FlowDiff;
import net.java21.data2flow.core.flow.domain.FlowValidator.Issue;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 플로우(FLW) API 모양(FLW-api §1·부록 A). ID는 문자열(flowId는 UUID) */
public final class FlowDtos {

    private FlowDtos() {
    }

    public record UserRef(String userId, String name) {
    }

    /** API-FLW-01 목록 항목. metrics1h는 엔진 지표 묶음 조회가 생기기 전까지 생략 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FlowSummary(String flowId, String name, String kind, String status, String environment, Integer activeVersion,
                              Integer draftVersion, List<String> spaceIds, boolean hasControlNode, Map<String, Object> metrics1h,
                              UserRef updatedBy, Instant updatedAt) {
    }

    /** 플로우 머리(API-FLW-02 flow, API-FLW-10 응답) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FlowInfo(String flowId, String name, String purpose, String description, String kind, String status, String statusReason,
                           String environment, String ownerUserId, List<String> relatedSpaceIds, List<String> tags, String pauseMode,
                           boolean autoPauseOnDegraded, BigDecimal errorRateThreshold, String catchFlowId, Integer activeVersion,
                           Integer draftVersion, int rateLimitPerSec, int version, UserRef updatedBy, Instant createdAt, Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VersionDetail(int version, String state, Integer baseVersion, JsonNode definition, JsonNode validation,
                                JsonNode changeSummary, String memo, boolean hasControlNode, UserRef appliedBy, Instant appliedAt,
                                UserRef createdBy, Instant createdAt) {
    }

    public record Overlay(List<String> bypass, List<String> debug, int revision) {
    }

    public record Instance(String instanceId, int appliedVersion, Instant reportedAt) {
    }

    /** 적용 상태(엔진 인스턴스 보고, EVT-FLW-02) */
    public record ApplyStatus(Integer targetVersion, List<Instance> instances, boolean converged) {
    }

    public record EmergencyStop(boolean active, String scope, Instant since) {
    }

    /** API-FLW-02 상세 */
    public record FlowDetail(FlowInfo flow, VersionDetail version, Overlay overlay, ApplyStatus applyStatus, List<UserRef> editors,
                             EmergencyStop emergencyStop) {
    }

    public record Validation(List<Issue> errors, List<Issue> warnings) {
    }

    /** API-FLW-03 응답 */
    public record SaveResult(String flowId, int draftVersion, Validation validation) {
    }

    /** API-FLW-06 응답 */
    public record ValidateResult(List<Issue> errors, List<Issue> warnings, FlowDiff.Summary changeSummary, FlowDiff.Risky risky,
                                 boolean approvalRequired) {
    }

    /** API-FLW-07·08 응답 */
    public record ApplyResult(int appliedVersion, ApplyStatus applyStatus) {
    }

    /** API-FLW-07 승인 대기(202 FLOW_APPROVAL_REQUIRED) */
    public record ApprovalPending(String approvalId, int version) {
    }

    /** API-FLW-04 목록 항목 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VersionSummary(int version, String state, UserRef appliedBy, Instant appliedAt, String memo, boolean hasControlNode,
                                 UserRef createdBy, Instant createdAt) {
    }

    /** API-FLW-09 응답 */
    public record StatusResult(String flowId, String status) {
    }

    /** API-FLW-24 목록·결정 응답 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Approval(String approvalId, String flowId, String flowName, int version, String kind, String status, boolean hasControlNode,
                           String memo, UserRef requestedBy, Instant requestedAt, UserRef decidedBy, Instant decidedAt, String reason,
                           Integer appliedVersion) {
    }

    /** API-FLW-20 생성 결과 */
    public record TemplateResult(String flowId, int draftVersion, List<Issue> warnings) {
    }

    /** API-FLW-80·81 내부: 엔진이 실행할 플로우(FlowRuntime, ACTIVE 버전 정의 포함) */
    public record RuntimeFlow(String flowId, String organizationId, String name, String kind, String status, int activeVersion,
                              int rateLimitPerSec, String pauseMode, JsonNode definition, Overlay overlay) {
    }

    /** API-FLW-80 응답 */
    public record RuntimeFlows(long version, String organizationId, List<RuntimeFlow> flows) {
    }
}
