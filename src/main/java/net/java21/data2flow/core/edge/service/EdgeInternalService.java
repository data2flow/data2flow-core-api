package net.java21.data2flow.core.edge.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.message.event.EdgeEvent;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.edge.domain.EdgeErrorCode;
import net.java21.data2flow.core.edge.domain.EdgeStates;
import net.java21.data2flow.core.edge.dto.EdgeDtos.Command;
import net.java21.data2flow.core.edge.dto.EdgeDtos.DesiredConfig;
import net.java21.data2flow.core.edge.dto.EdgeDtos.HeartbeatResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.PendingUpdate;
import net.java21.data2flow.core.edge.dto.EdgeDtos.RegisteredResponse;
import net.java21.data2flow.core.edge.repository.EdgeRepository;
import net.java21.data2flow.core.edge.repository.EdgeRepository.EdgeRow;
import net.java21.data2flow.core.edge.repository.EdgeRepository.TokenRow;
import net.java21.data2flow.core.edge.repository.EdgeRepository.UpdateRow;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * ingress 엣지 수신 측이 부르는 내부 API(DSC-08.03·08.04). 엣지 에이전트와 mTLS·인증서 발급은 ingress가 맡고, core는 등록 토큰 확인과
 * 원하는 상태(설정 판·업데이트·명령)를 준다.
 * <ul>
 *   <li>API-DSC-78 {@code POST /internal/core/edges/register}: 토큰(24시간·1회용) 확인 → ONLINE, 인증서 지문 기록. 틀린·만료·사용한 토큰 401</li>
 *   <li>API-DSC-79 {@code POST /internal/core/edges/{edge-id}/heartbeat}: 보고값 저장, 설정 적용 결과·업데이트 결과 반영, 원하는 설정·승인된
 *       업데이트·대기 명령을 돌려준다. 폐기된 엣지는 {@code revoked=true}</li>
 * </ul>
 * 범위는 이 배포가 맡는 조직(ADR-030).
 */
@Service
public class EdgeInternalService {

    private final EdgeRepository edges;
    private final DeploymentOrganization deployment;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public EdgeInternalService(EdgeRepository edges, DeploymentOrganization deployment, Audits audits, JsonMapper json, Clock clock) {
        this.edges = edges;
        this.deployment = deployment;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-DSC-78 {@code {token, agentVersion, arch, certFingerprint}} */
    @Transactional(noRollbackFor = BusinessException.class)
    public RegisteredResponse register(JsonNode body) {
        String token = text(body, "token");
        String fingerprint = text(body, "certFingerprint");
        if (token == null) {
            throw new BusinessException(EdgeErrorCode.EDGE_TOKEN_INVALID);
        }
        if (fingerprint != null && !fingerprint.matches("[A-Fa-f0-9]{64}")) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("certFingerprint", "Pattern", null)));
        }
        Instant now = clock.instant();
        TokenRow t = edges.lockTokenByHash(EdgeService.sha256(token)).orElseThrow(() -> new BusinessException(EdgeErrorCode.EDGE_TOKEN_INVALID));
        if (t.usedAt() != null || !t.expiresAt().isAfter(now) || !deployment.includes(t.organizationId())) {
            throw new BusinessException(EdgeErrorCode.EDGE_TOKEN_INVALID);
        }
        EdgeRow e = edges.lockById(t.organizationId(), t.edgeId()).orElseThrow(() -> new BusinessException(EdgeErrorCode.EDGE_TOKEN_INVALID));
        if (!EdgeStates.REGISTERING.equals(e.status())) {
            throw new BusinessException(EdgeErrorCode.EDGE_TOKEN_INVALID);
        }
        edges.updateTokenUsed(t.organizationId(), t.id(), now);
        edges.updateRegistered(t.organizationId(), e.id(), limit(text(body, "agentVersion"), 20), limit(text(body, "arch"), 20),
                fingerprint == null ? null : fingerprint.toLowerCase(Locale.ROOT), now);
        audits.record(audits.event(t.organizationId(), "EDGE_REGISTERED").target(EdgeService.TARGET, Long.toString(e.id()))
                .detail("agentVersion", text(body, "agentVersion")).detail("certFingerprint", fingerprint));
        return new RegisteredResponse(Long.toString(e.id()), Long.toString(t.organizationId()), Long.toString(e.siteId()),
                e.sourceId() == null ? null : Long.toString(e.sourceId()));
    }

    /** API-DSC-79 하트비트(30초) */
    @Transactional
    public HeartbeatResponse heartbeat(long edgeId, JsonNode body) {
        Instant now = clock.instant();
        Long orgId = organizationOf(body);
        EdgeRow e = edges.lockById(orgId, edgeId).orElseThrow(() -> new BusinessException(EdgeErrorCode.EDGE_NOT_FOUND));
        if (EdgeStates.REVOKED.equals(e.status())) {
            return new HeartbeatResponse(null, null, null, List.of(), true);
        }
        String reported = limit(text(body, "version"), 20);
        Integer applied = e.appliedConfigVersion();
        JsonNode cr = body.get("configResult");
        if (cr != null && cr.isObject() && cr.path("version").isIntegralNumber() && EdgeStates.CONFIG_RESULTS.contains(cr.path("result").asString(""))) {
            int v = cr.get("version").asInt();
            String result = cr.get("result").asString();
            edges.updateConfigResult(orgId, edgeId, v, result, text(cr, "error"));
            if ("APPLIED".equals(result)) {
                applied = v;
            }
        } else if (body.path("appliedConfigVersion").isIntegralNumber()) {
            applied = body.get("appliedConfigVersion").asInt();
        }
        // 업데이트: 결과 보고 또는 버전으로 판정(BR-DSC-33), 승인된 것은 이번 응답으로 내려보내며 IN_PROGRESS
        String status = EdgeStates.ONLINE;
        PendingUpdate pending = null;
        UpdateRow open = edges.findOpenUpdate(orgId, edgeId).orElse(null);
        if (open != null) {
            JsonNode ur = body.get("updateResult");
            String outcome = ur != null && ur.isObject() && Long.toString(open.id()).equals(ur.path("updateId").asString(""))
                    && EdgeStates.UPDATE_RESULTS.contains(ur.path("status").asString("")) ? ur.get("status").asString()
                    : "IN_PROGRESS".equals(open.status()) && reported != null
                    ? EdgeStates.updateOutcome(reported, open.fromVersion(), open.toVersion(), open.startedAt(), now) : null;
            if (outcome != null) {
                edges.updateUpdateFinished(orgId, open.id(), outcome, now);
                audits.record(audits.event(orgId, "EDGE_UPDATE_" + outcome).target(EdgeService.TARGET, Long.toString(edgeId))
                        .detail("updateId", Long.toString(open.id())).detail("toVersion", open.toVersion()));
            } else {
                if ("APPROVED".equals(open.status())) {
                    edges.updateUpdateStarted(orgId, open.id(), now);
                }
                status = EdgeStates.UPDATING;
                pending = new PendingUpdate(Long.toString(open.id()), open.toVersion());
            }
        }
        long dropped = body.path("droppedItems").isIntegralNumber() ? Math.max(0, body.get("droppedItems").asLong()) : 0;
        edges.updateHeartbeat(orgId, edgeId, status, reported, longOrNull(body, "bufferUsedBytes"), longOrNull(body, "bufferItems"), dropped,
                body.path("throughput").isNumber() ? body.get("throughput").asDouble() : null, applied, now);
        DesiredConfig desired = null;
        if (e.desiredConfigVersion() != null && (applied == null || !applied.equals(e.desiredConfigVersion()))) {
            desired = edges.findConfigVersion(orgId, edgeId, e.desiredConfigVersion())
                    .filter(c -> "PENDING".equals(c.result()))
                    .map(c -> new DesiredConfig(c.versionNo(), json.readTree(c.targets()), c.decoders() == null ? null : json.readTree(c.decoders())))
                    .orElse(null);
        }
        List<Command> commands = edges.listPendingCommands(orgId, edgeId).stream()
                .map(c -> new Command(c.requestId(), c.kind(), json.readTree(c.args()))).toList();
        if (!commands.isEmpty()) {
            edges.updateCommandsSent(orgId, edgeId, now);
        }
        return new HeartbeatResponse(e.desiredConfigVersion(), desired, pending, commands, false);
    }

    /** EVT-DSC-10 반영(ingress가 낸 상태·설정 적용·버퍼 버림). 하트비트 API와 같은 결과가 되도록 덮어쓰기만 한다 */
    @Transactional
    public void onEvent(long orgId, String type, EdgeEvent event) {
        EdgeRow e = edges.lockById(orgId, event.edgeId()).orElse(null);
        if (e == null || EdgeStates.REVOKED.equals(e.status())) {
            return;
        }
        Instant now = clock.instant();
        switch (type) {
            case "edge.status.changed" -> {
                if (event.status() != null && List.of(EdgeStates.ONLINE, EdgeStates.OFFLINE, EdgeStates.UPDATING, EdgeStates.ERROR)
                        .contains(event.status()) && !EdgeStates.OFFLINE.equals(event.status())) {
                    edges.updateStatus(orgId, e.id(), event.status(), now);
                }
            }
            case "edge.config.applied" -> {
                if (event.configVersion() != null && event.result() != null && EdgeStates.CONFIG_RESULTS.contains(event.result())) {
                    edges.updateConfigResult(orgId, e.id(), event.configVersion(), event.result(), null);
                }
            }
            case "edge.buffer.dropped" -> {
                if (event.droppedItems() != null && event.droppedItems() > 0) {
                    edges.addDropped(orgId, e.id(), event.droppedItems(), now);
                }
            }
            default -> {
                // 모르는 종류는 무시
            }
        }
    }

    private Long organizationOf(JsonNode body) {
        JsonNode o = body == null ? null : body.get("organizationId");
        Long org = o == null || o.isNull() ? null : o.isIntegralNumber() ? o.asLong()
                : o.asString("").matches("\\d{1,18}") ? Long.parseLong(o.asString()) : null;
        if (org == null) {
            org = deployment.current().map(x -> x.id()).orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    List.of(new FieldErrorDetail("organizationId", "NotNull", null))));
        }
        if (!deployment.includes(org)) {
            throw new BusinessException(EdgeErrorCode.EDGE_NOT_FOUND);
        }
        return org;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() || !v.isValueNode() || v.asString("").isBlank() ? null : v.asString().strip();
    }

    private static String limit(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static Long longOrNull(JsonNode n, String field) {
        return n.path(field).isIntegralNumber() ? Math.max(0, n.get(field).asLong()) : null;
    }
}
