package net.java21.data2flow.core.flow.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.core.common.RelayedErrorException;
import net.java21.data2flow.core.flow.domain.FlowErrorCode;
import net.java21.data2flow.core.flow.domain.FlowModels;
import net.java21.data2flow.core.flow.dto.FlowDtos.Overlay;
import net.java21.data2flow.core.flow.dto.FlowDtos.RuntimeFlow;
import net.java21.data2flow.core.flow.dto.FlowDtos.RuntimeFlows;
import net.java21.data2flow.core.flow.repository.FlowRepository;
import net.java21.data2flow.core.flow.repository.FlowRepository.FlowRow;
import net.java21.data2flow.core.flow.repository.FlowRuntimeRepository;
import net.java21.data2flow.core.flow.repository.FlowVersionRepository;
import net.java21.data2flow.core.flow.repository.FlowVersionRepository.VersionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 엔진 연동: 지표 중계(API-FLW-14 → flow-engine), 엔진이 읽는 내부 API(API-FLW-80·81, FLW-api §8, ADR-044: EVT-FLW-04는 신호이고
 * 엔진은 정의를 여기서 다시 읽는다). 엔진 보고 이벤트(EVT-FLW-02·03)는 {@code event.FlowEngineEvents}가 받는다.
 */
@Service
public class FlowRuntimeService {

    static final Set<String> WINDOWS = Set.of("1h", "24h", "7d");
    static final Set<String> STEPS = Set.of("1m", "1h");

    private final FlowRepository flows;
    private final FlowVersionRepository versions;
    private final FlowRuntimeRepository runtime;
    private final FlowSupport support;
    private final FlowEngineClient engine;
    private final RoleChecker roleChecker;

    public FlowRuntimeService(FlowRepository flows, FlowVersionRepository versions, FlowRuntimeRepository runtime, FlowSupport support,
                              FlowEngineClient engine, RoleChecker roleChecker) {
        this.flows = flows;
        this.versions = versions;
        this.runtime = runtime;
        this.support = support;
        this.engine = engine;
        this.roleChecker = roleChecker;
    }

    /**
     * API-FLW-14 지표 — FLOW_READ. window 1h(기본)·24h·7d, step 1m(기본)·1h. 엔진 지표 API(FLW-05.05)는 M4라서 엔진에 경로가 없거나
     * 엔진이 응답하지 않으면 503 {@code FLOW_METRICS_UNAVAILABLE}(화면은 "지표 없음"으로 표시)
     */
    public JsonNode metrics(String flowId, String window, String step) {
        roleChecker.require(Permission.FLOW_READ);
        FlowRow flow = support.require(roleChecker.currentUser().organizationId(), flowId);
        String w = window == null || window.isBlank() ? "1h" : window;
        String s = step == null || step.isBlank() ? "1m" : step;
        if (!WINDOWS.contains(w)) {
            throw FlowModels.invalid("window", "Pattern");
        }
        if (!STEPS.contains(s)) {
            throw FlowModels.invalid("step", "Pattern");
        }
        try {
            return engine.metrics(flow.id(), w, s);
        } catch (RelayedErrorException ex) {
            if (ex.status() == 404) {
                throw new BusinessException(FlowErrorCode.FLOW_METRICS_UNAVAILABLE);   // 엔진에 경로가 아직 없음(FLW-05.05 M4)
            }
            throw ex;
        } catch (BusinessException ex) {
            if (ex.getErrorCode() == CommonErrorCode.RESOURCE_NOT_FOUND || ex.getErrorCode() == CommonErrorCode.SERVICE_UNAVAILABLE) {
                throw new BusinessException(FlowErrorCode.FLOW_METRICS_UNAVAILABLE);
            }
            throw ex;
        }
    }

    /**
     * API-FLW-80 내부: 배포 조직의 실행 대상(ACTIVE·DEGRADED·PAUSED) 전체와 목록 버전. sinceVersion이 지금 버전과 같으면 빈 값(204).
     * 엔진은 시작·재연결 때와 30초마다 부른다(ADR-044)
     */
    @Transactional(readOnly = true)
    public java.util.Optional<RuntimeFlows> runtimeFlows(long organizationId, Long sinceVersion) {
        long version = flows.runtimeVersion(organizationId);
        if (sinceVersion != null && sinceVersion == version) {
            return java.util.Optional.empty();
        }
        List<RuntimeFlow> list = flows.listRuntime(organizationId).stream().map(this::runtime).toList();
        return java.util.Optional.of(new RuntimeFlows(version, Long.toString(organizationId), list));
    }

    /** API-FLW-81 내부: 플로우 하나(실행 대상이 아니면 404 FLOW_NOT_FOUND → 엔진이 내림). 플로우 ID 전역 고유 */
    @Transactional(readOnly = true)
    public RuntimeFlow runtimeFlow(String flowId) {
        UUID id = FlowSupport.flowId(flowId);
        long orgId = flows.findOrganization(id).orElseThrow(() -> new BusinessException(FlowErrorCode.FLOW_NOT_FOUND));
        FlowRow f = flows.findById(orgId, id).filter(x -> x.activeVersion() != null && Set.of("ACTIVE", "DEGRADED", "PAUSED").contains(x.status()))
                .orElseThrow(() -> new BusinessException(FlowErrorCode.FLOW_NOT_FOUND));
        return runtime(f);
    }

    private RuntimeFlow runtime(FlowRow f) {
        VersionRow v = versions.find(f.organizationId(), f.id(), f.activeVersion()).orElseThrow(() -> new BusinessException(FlowErrorCode.FLOW_NOT_FOUND));
        Overlay overlay = runtime.findOverlay(f.organizationId(), f.id()).map(o -> new Overlay(o.bypass(), o.debug(), o.revision()))
                .orElse(new Overlay(List.of(), List.of(), 0));
        return new RuntimeFlow(f.id().toString(), Long.toString(f.organizationId()), f.name(), f.kind(), f.status(), f.activeVersion(),
                v.rateLimitPerSec(), f.pauseMode(), support.tree(v.definition()), overlay);
    }
}
