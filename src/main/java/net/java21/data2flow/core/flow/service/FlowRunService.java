package net.java21.data2flow.core.flow.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.core.flow.domain.FlowErrorCode;
import net.java21.data2flow.core.flow.repository.FlowRepository.FlowRow;
import net.java21.data2flow.core.flow.repository.FlowVersionRepository;
import net.java21.data2flow.core.telemetry.service.TelemetryHistoryService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시험 실행(API-FLW-12)과 과거 재생(API-FLW-13) 중계(ADR-051). 실행은 flow-engine이 하고 core는 정의를 찾아 넘기고, 원본 메시지
 * ({@code rawMessageId})는 엔진이 다른 서비스 스키마를 읽지 않으므로 core가 표준 텔레메트리로 바꿔 넘긴다. 재생 기간은 7일까지(넘으면
 * 400 FLOW_REPLAY_TOO_LARGE, 100만 건 판정은 엔진). 시작·시험은 FLOW_WRITE, 재생 결과 조회는 FLOW_READ(그 플로우가 보여야 함).
 */
@Service
public class FlowRunService {

    private final FlowSupport support;
    private final FlowVersionRepository versions;
    private final FlowEngineClient engine;
    private final TelemetryHistoryService history;
    private final RoleChecker roleChecker;

    public FlowRunService(FlowSupport support, FlowVersionRepository versions, FlowEngineClient engine, TelemetryHistoryService history,
                          RoleChecker roleChecker) {
        this.support = support;
        this.versions = versions;
        this.engine = engine;
        this.history = history;
        this.roleChecker = roleChecker;
    }

    /** {version | definition, input:{rawMessageId} | {message}, startNodeId?} → {trace} */
    public JsonNode testRun(String flowId, JsonNode body) {
        roleChecker.require(Permission.FLOW_WRITE);
        long orgId = roleChecker.currentUser().organizationId();
        FlowRow flow = support.require(orgId, flowId);
        if (body == null || !body.path("input").isObject()) {
            throw new BusinessException(FlowErrorCode.FLOW_TEST_INPUT_INVALID, List.of(new FieldErrorDetail("input", "NotNull", null)));
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("flowId", flow.id().toString());
        req.put("organizationId", Long.toString(orgId));
        definition(orgId, flow, body, req);
        JsonNode input = body.get("input");
        if (input.hasNonNull("rawMessageId")) {
            long raw;
            try {
                raw = Long.parseLong(input.get("rawMessageId").asString(""));
            } catch (NumberFormatException ex) {
                throw new BusinessException(FlowErrorCode.FLOW_TEST_INPUT_INVALID,
                        List.of(new FieldErrorDetail("input.rawMessageId", "Pattern", null)));
            }
            var telemetry = history.fromRawMessage(orgId, raw).orElseThrow(() -> new BusinessException(FlowErrorCode.FLOW_TEST_INPUT_INVALID,
                    List.of(new FieldErrorDetail("input.rawMessageId", "NO_TELEMETRY", null))));
            req.put("input", Map.of("message", support.json().valueToTree(telemetry)));
        } else if (input.has("message") || input.has("body")) {
            req.put("input", input);
        } else {
            throw new BusinessException(FlowErrorCode.FLOW_TEST_INPUT_INVALID, List.of(new FieldErrorDetail("input", "NotNull", null)));
        }
        if (body.hasNonNull("startNodeId")) {
            req.put("startNodeId", body.get("startNodeId").asString());
        }
        return engine.testRun(req);
    }

    /** {version, from, to(≤7일), deviceIds?} → 202 {jobId} */
    public JsonNode replay(String flowId, JsonNode body) {
        roleChecker.require(Permission.FLOW_WRITE);
        long orgId = roleChecker.currentUser().organizationId();
        FlowRow flow = support.require(orgId, flowId);
        if (body == null) {
            throw invalid("from");
        }
        Instant from = instant(body, "from");
        Instant to = instant(body, "to");
        if (!to.isAfter(from)) {
            throw invalid("to");
        }
        if (Duration.between(from, to).compareTo(TelemetryHistoryService.MAX_RANGE) > 0) {
            throw new BusinessException(FlowErrorCode.FLOW_REPLAY_TOO_LARGE);
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("flowId", flow.id().toString());
        req.put("organizationId", Long.toString(orgId));
        definition(orgId, flow, body, req);
        req.put("from", from.toString());
        req.put("to", to.toString());
        if (body.path("deviceIds").isArray()) {
            List<String> ids = new ArrayList<>();
            body.get("deviceIds").forEach(d -> ids.add(d.asString()));
            req.put("deviceIds", ids);
        }
        return engine.replay(req);
    }

    /** 재생 작업 조회 — 그 플로우가 이 사용자에게 보여야 한다(아니면 404) */
    public JsonNode replayJob(String jobId) {
        roleChecker.require(Permission.FLOW_READ);
        long orgId = roleChecker.currentUser().organizationId();
        JsonNode job = engine.replayJob(jobId);
        if (job == null || !job.hasNonNull("flowId")) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        support.require(orgId, job.get("flowId").asString());
        return job;
    }

    /** 재생 작업 취소 — FLOW_WRITE, 그 플로우가 보여야 한다 */
    public JsonNode cancelReplay(String jobId) {
        roleChecker.require(Permission.FLOW_WRITE);
        replayJobVisible(jobId);
        return engine.cancelReplay(jobId);
    }

    private void replayJobVisible(String jobId) {
        long orgId = roleChecker.currentUser().organizationId();
        JsonNode job = engine.replayJob(jobId);
        if (job == null || !job.hasNonNull("flowId")) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        support.require(orgId, job.get("flowId").asString());
    }

    /**
     * API-FLW-41 실행 추적 — FLOW_READ. 엔진은 가리지 않으므로(ADR-048) 공간 범위가 제한된 사용자에게는 범위 밖 {@code spaceId}를 가진
     * 입력·출력 내용을 지우고 {@code masked=true}로 표시한다.
     */
    public JsonNode trace(String flowId, String messageId) {
        roleChecker.require(Permission.FLOW_READ);
        FlowRow flow = support.require(roleChecker.currentUser().organizationId(), flowId);
        JsonNode trace = engine.trace(messageId, flow.id());
        var scope = roleChecker.spaceScope();
        if (trace == null || scope.unrestricted() || !trace.path("steps").isArray()) {
            return trace;
        }
        for (JsonNode step : trace.get("steps")) {
            if (step instanceof tools.jackson.databind.node.ObjectNode o) {
                if (outOfScope(o.get("input"), scope)) {
                    o.putNull("input");
                    o.put("masked", true);
                }
                if (o.path("outputs").isArray()) {
                    for (JsonNode out : o.get("outputs")) {
                        if (out instanceof tools.jackson.databind.node.ObjectNode po && outOfScope(po.get("payload"), scope)) {
                            po.putNull("payload");
                            po.put("masked", true);
                            o.put("masked", true);
                        }
                    }
                }
            }
        }
        return trace;
    }

    static boolean outOfScope(JsonNode payload, net.java21.data2flow.contracts.authz.SpaceScope scope) {
        if (payload == null || !payload.isObject()) {
            return false;
        }
        JsonNode space = payload.has("spaceId") ? payload.get("spaceId") : payload.path("message").get("spaceId");
        if (space == null || space.isNull()) {
            return false;
        }
        try {
            return !scope.includes(Long.parseLong(space.asString()));
        } catch (NumberFormatException ex) {
            return true;
        }
    }

    /** 정의: 본문 definition이 있으면 그것, 아니면 version(없으면 초안 → ACTIVE) */
    private void definition(long orgId, FlowRow flow, JsonNode body, Map<String, Object> req) {
        if (body.hasNonNull("definition")) {
            req.put("definition", body.get("definition"));
            return;
        }
        Integer no = body.hasNonNull("version") ? Integer.valueOf(body.get("version").asInt())
                : flow.draftVersion() != null ? flow.draftVersion() : flow.activeVersion();
        if (no == null) {
            throw invalid("version");
        }
        var v = versions.find(orgId, flow.id(), no).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        req.put("version", no);
        req.put("definition", support.tree(v.definition()));
    }

    static Instant instant(JsonNode body, String field) {
        try {
            return Instant.parse(body.path(field).asString(""));
        } catch (DateTimeParseException ex) {
            throw invalid(field);
        }
    }

    static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
