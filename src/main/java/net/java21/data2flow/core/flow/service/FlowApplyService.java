package net.java21.data2flow.core.flow.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.flow.FlowDefinition;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.FailureWithResponse;
import net.java21.data2flow.core.control.service.ControlSettingsService;
import net.java21.data2flow.core.flow.domain.FlowDiff;
import net.java21.data2flow.core.flow.domain.FlowErrorCode;
import net.java21.data2flow.core.flow.domain.FlowModels;
import net.java21.data2flow.core.flow.domain.FlowValidator;
import net.java21.data2flow.core.flow.dto.FlowDtos.ApplyResult;
import net.java21.data2flow.core.flow.dto.FlowDtos.Approval;
import net.java21.data2flow.core.flow.dto.FlowDtos.ApprovalPending;
import net.java21.data2flow.core.flow.dto.FlowDtos.Validation;
import net.java21.data2flow.core.flow.repository.FlowApprovalRepository;
import net.java21.data2flow.core.flow.repository.FlowApprovalRepository.ApprovalRow;
import net.java21.data2flow.core.flow.repository.FlowRepository;
import net.java21.data2flow.core.flow.repository.FlowRepository.FlowRow;
import net.java21.data2flow.core.flow.repository.FlowVersionRepository;
import net.java21.data2flow.core.flow.repository.FlowVersionRepository.VersionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 버전 적용·롤백·제어 노드 배포 승인(FLW-01.06·05.06, API-FLW-07·08·24, BR-FLW-08·09).
 *
 * <ol>
 *   <li>권한: FLOW_WRITE, 장기 토큰 거부(BR-IAM-19). 제어·장면 노드를 더하거나 바꾸면 FLOW_DEPLOY_CONTROL(없으면 403 + 감사 ACCESS_DENIED)과
 *       위험 확인({@code acknowledgedRisks=true})</li>
 *   <li>기준 버전: 요청 baseVersion이 지금 ACTIVE 번호(없으면 0)와 달라야 하지 않는다(409 FLOW_VERSION_CONFLICT)</li>
 *   <li>검증: 오류가 있으면 400 FLOW_VALIDATION_FAILED({@code errors[{field, code, message}]} + {@code response{errors, warnings}}),
 *       대상이 사용자 공간 범위 밖이면 404</li>
 *   <li>조직 설정 {@code requireApprovalForControlNodes}가 켜져 있고 제어 노드가 바뀌면 버전을 PENDING_APPROVAL로 두고 202
 *       FLOW_APPROVAL_REQUIRED{approvalId}. 실행 중인 버전은 그대로다(UC-FLW-23)</li>
 *   <li>아니면 원자적 전환(이전 ACTIVE → ARCHIVED), {@code data2flow.config} FLOW 발행(EVT-FLW-04) → 엔진이 적용 후 EVT-FLW-02로 보고</li>
 * </ol>
 */
@Service
public class FlowApplyService {

    static final String AUDIT_APPLIED = "FLOW_APPLIED";
    static final String AUDIT_ROLLED_BACK = "FLOW_ROLLED_BACK";
    static final String AUDIT_APPROVAL_REQUESTED = "FLOW_APPROVAL_REQUESTED";
    static final String AUDIT_APPROVED = "FLOW_APPROVED";
    static final String AUDIT_REJECTED = "FLOW_APPROVAL_REJECTED";

    private final FlowRepository flows;
    private final FlowVersionRepository versions;
    private final FlowApprovalRepository approvals;
    private final FlowService flowService;
    private final FlowSupport support;
    private final ControlSettingsService settings;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final Clock clock;

    public FlowApplyService(FlowRepository flows, FlowVersionRepository versions, FlowApprovalRepository approvals, FlowService flowService,
                            FlowSupport support, ControlSettingsService settings, RoleChecker roleChecker, Audits audits, Clock clock) {
        this.flows = flows;
        this.versions = versions;
        this.approvals = approvals;
        this.flowService = flowService;
        this.support = support;
        this.settings = settings;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.clock = clock;
    }

    /** 적용 결과: 바로 적용했거나(applied) 승인 대기(pending) */
    public record Outcome(ApplyResult applied, ApprovalPending pending) {
    }

    /** API-FLW-07 적용 {version, baseVersion, memo?, acknowledgedRisks} */
    @Transactional
    public Outcome apply(String flowId, JsonNode body) {
        roleChecker.require(Permission.FLOW_WRITE);
        roleChecker.requireInteractive();
        long orgId = roleChecker.currentUser().organizationId();
        if (body == null || !body.hasNonNull("version") || !body.get("version").isIntegralNumber()) {
            throw FlowModels.invalid("version", "NotNull");
        }
        int version = body.get("version").asInt();
        Integer base = body.hasNonNull("baseVersion") && body.get("baseVersion").isIntegralNumber() ? body.get("baseVersion").asInt() : null;
        String memo = FlowService.text(body.get("memo"), "memo", 500);
        boolean acknowledged = body.path("acknowledgedRisks").asBoolean(false);
        FlowRow flow = support.require(orgId, flowId);
        flows.lockById(orgId, flow.id());
        flow = support.require(orgId, flowId);
        int active = flow.activeVersion() == null ? 0 : flow.activeVersion();
        if (base == null) {
            throw FlowModels.invalid("baseVersion", "NotNull");
        }
        if (base != active) {
            throw new BusinessException(FlowErrorCode.FLOW_VERSION_CONFLICT);
        }
        VersionRow target = versions.find(orgId, flow.id(), version)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!"DRAFT".equals(target.state())) {
            throw new BusinessException(FlowErrorCode.FLOW_STATE_CONFLICT);
        }
        return applyVersion(flow, target, memo, acknowledged, false);
    }

    /** API-FLW-08 롤백 {toVersion, memo?}: 보관(ARCHIVED) 버전을 같은 절차로 다시 적용(삭제 유예 상태 복원, live-reload §4.5) */
    @Transactional
    public Outcome rollback(String flowId, JsonNode body) {
        roleChecker.require(Permission.FLOW_WRITE);
        roleChecker.requireInteractive();
        long orgId = roleChecker.currentUser().organizationId();
        if (body == null || !body.hasNonNull("toVersion") || !body.get("toVersion").isIntegralNumber()) {
            throw FlowModels.invalid("toVersion", "NotNull");
        }
        String memo = FlowService.text(body.get("memo"), "memo", 500);
        FlowRow flow = support.require(orgId, flowId);
        flows.lockById(orgId, flow.id());
        flow = support.require(orgId, flowId);
        VersionRow target = versions.find(orgId, flow.id(), body.get("toVersion").asInt())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!"ARCHIVED".equals(target.state())) {
            throw new BusinessException(FlowErrorCode.FLOW_STATE_CONFLICT);
        }
        return applyVersion(flow, target, memo, true, true);
    }

    private Outcome applyVersion(FlowRow flow, VersionRow target, String memo, boolean acknowledged, boolean rollback) {
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        JsonNode definition = support.tree(target.definition());
        FlowValidator.Result result = support.validate(orgId, flow.kind(), flow.id(), definition);
        if (!result.outOfScope().isEmpty()) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!result.ok()) {
            List<FieldErrorDetail> errors = result.errors().stream()
                    .map(e -> new FieldErrorDetail(e.field() == null ? "definition" : e.field(), e.code(), e.message())).toList();
            throw new FailureWithResponse(FlowErrorCode.FLOW_VALIDATION_FAILED, errors, new Validation(result.errors(), result.warnings()));
        }
        FlowDefinition active = flowService.activeDefinition(orgId, flow);
        FlowDiff.Risky risky = FlowDiff.risky(active, result.definition(), support.catalog());
        FlowDiff.Summary summary = FlowDiff.summary(active, result.definition(), support.catalog());
        if (risky.controlNodesChanged()) {
            roleChecker.require(Permission.FLOW_DEPLOY_CONTROL);
        }
        if ((risky.controlNodesChanged() || risky.executionModeChanged()) && !acknowledged) {
            throw FlowModels.invalid("acknowledgedRisks", "AssertTrue");
        }
        Instant now = clock.instant();
        versions.updateApplyInfo(orgId, flow.id(), target.versionNo(), support.write(new Validation(result.errors(), result.warnings())),
                support.write(summary), memo, now);
        if (risky.controlNodesChanged() && settings.settings(orgId).requireApprovalForControlNodes()) {
            if (!approvals.listPendingIds(orgId, flow.id()).isEmpty()) {
                throw new BusinessException(FlowErrorCode.FLOW_STATE_CONFLICT);
            }
            versions.updateState(orgId, flow.id(), target.versionNo(), "PENDING_APPROVAL", now);
            long approvalId = approvals.insert(orgId, flow.id(), target.versionNo(), "APPLY", memo, user.userId(), now);
            audits.record(audits.event(orgId, AUDIT_APPROVAL_REQUESTED).actor(user).target("FLOW", flow.id().toString())
                    .detail("version", target.versionNo()).detail("approvalId", Long.toString(approvalId)).detail("rollback", rollback));
            return new Outcome(null, new ApprovalPending(Long.toString(approvalId), target.versionNo()));
        }
        activate(flow, target, user.userId(), now);
        var event = audits.event(orgId, rollback ? AUDIT_ROLLED_BACK : AUDIT_APPLIED).actor(user).target("FLOW", flow.id().toString())
                .detail("version", target.versionNo()).detail("hasControlNode", result.hasControlNode());
        if (memo != null) {
            event.detail("memo", memo);
        }
        audits.record(event);
        FlowRow after = flows.findById(orgId, flow.id()).orElseThrow();
        return new Outcome(new ApplyResult(target.versionNo(), flowService.applyStatus(orgId, after)), null);
    }

    /**
     * 규칙 저장의 내부 적용(API-RUL-02 "플로우 적용(API-FLW-07 내부 호출, 원자적 전환)"): 권한은 규칙 쪽(RULE_WRITE)에서 이미 봤고,
     * 규칙 플로우에는 제어 노드가 없어 승인도 없다. 검증 오류면 적용하지 않고 오류 목록을 돌려준다(규칙은 ERROR(FLOW_ERROR)).
     *
     * @return 검증 오류(비면 적용됨)
     */
    @Transactional
    public List<net.java21.data2flow.core.flow.domain.FlowValidator.Issue> applyRuleVersion(long organizationId, java.util.UUID flowId,
                                                                                          int versionNo, long userId) {
        flows.lockById(organizationId, flowId);
        FlowRow flow = flows.findById(organizationId, flowId).orElseThrow(() -> new BusinessException(FlowErrorCode.FLOW_NOT_FOUND));
        VersionRow target = versions.find(organizationId, flowId, versionNo)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        JsonNode definition = support.tree(target.definition());
        FlowValidator.Result result = support.validate(organizationId, flow.kind(), flowId, definition);
        Instant now = clock.instant();
        versions.updateApplyInfo(organizationId, flowId, versionNo, support.write(new Validation(result.errors(), result.warnings())), null,
                "rule", now);
        if (!result.ok()) {
            return result.errors();
        }
        activate(flow, target, userId, now);
        return List.of();
    }

    /** 원자적 전환: 이전 ACTIVE → ARCHIVED, 대상 → ACTIVE. 실행 중 상태(PAUSED·DEGRADED)는 유지, DRAFT·DISABLED·ACTIVE는 ACTIVE */
    private void activate(FlowRow flow, VersionRow target, long userId, Instant now) {
        long orgId = flow.organizationId();
        if (!"RULE".equals(flow.kind()) && !FlowModels.RUNNING.contains(flow.status())
                && flows.countRunning(orgId) >= FlowModels.MAX_ACTIVE_FLOWS) {
            throw new BusinessException(FlowErrorCode.FLOW_LIMIT_EXCEEDED);
        }
        versions.activate(orgId, flow.id(), target.versionNo(), userId, now);
        String status = "PAUSED".equals(flow.status()) || "DEGRADED".equals(flow.status()) ? flow.status() : "ACTIVE";
        Integer draft = flow.draftVersion() != null && flow.draftVersion() == target.versionNo() ? null : flow.draftVersion();
        flows.updateApplied(orgId, flow.id(), target.versionNo(), draft, status, target.rateLimitPerSec(), userId, now);
        versions.deleteOverflow(orgId, flow.id(), flow.activeVersion() == null ? target.versionNo() : flow.activeVersion(),
                FlowModels.MAX_VERSIONS);
        support.flowChanged(orgId, flow.id(), target.versionNo(), false);
    }

    /** API-FLW-24 승인 목록 — FLOW_APPROVE는 전체, FLOW_WRITE는 본인 요청만 */
    @Transactional(readOnly = true)
    public ListApiResponse<Approval> list(String status, Integer page, Integer size) {
        CurrentUser user = roleChecker.currentUser();
        boolean approver = roleChecker.has(Permission.FLOW_APPROVE);
        if (!approver) {
            roleChecker.require(Permission.FLOW_WRITE);
        }
        String filter = status == null || status.isBlank() ? null
                : FlowService.enumValue(status, Set.of("PENDING", "APPROVED", "REJECTED"), "status");
        Long requestedBy = approver ? null : user.userId();
        PageParams params = PageParams.of(page, size);
        List<Approval> rows = approvals.list(user.organizationId(), filter, requestedBy, params.size(), params.offset()).stream()
                .map(a -> approval(a, null)).toList();
        return ListApiResponse.of(params, rows, approvals.count(user.organizationId(), filter, requestedBy));
    }

    /** API-FLW-24 승인 — FLOW_APPROVE. 대기 버전을 원자적으로 적용하고 감사에 요청자·승인자를 남긴다(TC-FLW-115) */
    @Transactional
    public Approval approve(String approvalId) {
        roleChecker.require(Permission.FLOW_APPROVE);
        roleChecker.requireInteractive();
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ApprovalRow a = pending(orgId, approvalId);
        FlowRow flow = support.require(orgId, a.flowId().toString());
        flows.lockById(orgId, flow.id());
        flow = support.require(orgId, a.flowId().toString());
        VersionRow target = versions.find(orgId, flow.id(), a.versionNo())
                .filter(v -> "PENDING_APPROVAL".equals(v.state()))
                .orElseThrow(() -> new BusinessException(FlowErrorCode.FLOW_STATE_CONFLICT));
        Instant now = clock.instant();
        activate(flow, target, a.requestedBy(), now);
        approvals.decide(orgId, a.id(), "APPROVED", user.userId(), null, now);
        audits.record(audits.event(orgId, AUDIT_APPROVED).actor(user).target("FLOW", flow.id().toString())
                .detail("version", a.versionNo()).detail("approvalId", Long.toString(a.id()))
                .detail("requestedBy", Long.toString(a.requestedBy())).detail("approvedBy", Long.toString(user.userId())));
        return approval(approvals.findById(orgId, a.id()).orElseThrow(), a.versionNo());
    }

    /** API-FLW-24 반려 {reason(1~500)} — FLOW_APPROVE. 버전은 REJECTED, 실행 중 버전은 그대로 */
    @Transactional
    public Approval reject(String approvalId, JsonNode body) {
        roleChecker.require(Permission.FLOW_APPROVE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        String reason = body == null ? null : FlowService.text(body.get("reason"), "reason", 500);
        if (reason == null) {
            throw FlowModels.invalid("reason", "NotBlank");
        }
        ApprovalRow a = pending(orgId, approvalId);
        Instant now = clock.instant();
        versions.updateState(orgId, a.flowId(), a.versionNo(), "REJECTED", now);
        approvals.decide(orgId, a.id(), "REJECTED", user.userId(), reason, now);
        audits.record(audits.event(orgId, AUDIT_REJECTED).actor(user).target("FLOW", a.flowId().toString())
                .detail("version", a.versionNo()).detail("approvalId", Long.toString(a.id())).detail("reason", reason));
        return approval(approvals.findById(orgId, a.id()).orElseThrow(), null);
    }

    private ApprovalRow pending(long orgId, String rawId) {
        long id;
        try {
            id = Long.parseLong(rawId);
        } catch (NumberFormatException ex) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        ApprovalRow row = approvals.findById(orgId, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (row.decision() != null) {
            throw new BusinessException(FlowErrorCode.FLOW_STATE_CONFLICT);
        }
        return approvals.lockPending(orgId, id).orElseThrow(() -> new BusinessException(FlowErrorCode.FLOW_STATE_CONFLICT));
    }

    private static Approval approval(ApprovalRow a, Integer applied) {
        return new Approval(Long.toString(a.id()), a.flowId().toString(), a.flowName(), a.versionNo(), a.kind(), a.status(),
                a.hasControlNode(), a.memo(), FlowSupport.user(a.requestedBy(), a.requestedByName()), a.requestedAt(),
                FlowSupport.user(a.decidedBy(), a.decidedByName()), a.decidedAt(), a.reason(), applied);
    }
}
