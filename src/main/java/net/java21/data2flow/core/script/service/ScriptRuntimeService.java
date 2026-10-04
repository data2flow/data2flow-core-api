package net.java21.data2flow.core.script.service;

import net.java21.data2flow.contracts.audit.AuditActorType;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.ConfigVersions;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import net.java21.data2flow.core.script.domain.ScriptErrorCode;
import net.java21.data2flow.core.script.domain.ScriptModels;
import net.java21.data2flow.core.script.domain.ScriptModels.ScriptStatus;
import net.java21.data2flow.core.script.dto.ScriptDtos.AutoDisableRequest;
import net.java21.data2flow.core.script.dto.ScriptDtos.AutoDisableResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.DeployAckRequest;
import net.java21.data2flow.core.script.dto.ScriptDtos.RuntimeBinding;
import net.java21.data2flow.core.script.dto.ScriptDtos.RuntimeBundle;
import net.java21.data2flow.core.script.dto.ScriptDtos.RuntimeScript;
import net.java21.data2flow.core.script.repository.ScriptRepository;
import net.java21.data2flow.core.script.repository.ScriptRepository.ScriptRow;
import net.java21.data2flow.core.script.repository.FormulaMetricRepository;
import net.java21.data2flow.core.script.repository.ScriptModuleRepository;
import net.java21.data2flow.core.script.repository.ScriptRuntimeRepository;
import net.java21.data2flow.core.script.repository.ScriptRuntimeRepository.RuntimeBindingRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 실행 엔진용 내부 API(SCR-03.04, BR-SCR-09·11): 실행 묶음 API-SCR-32, 자동 비활성 API-SCR-33, 적용 보고 API-SCR-34.
 * 호출자는 pipeline·flow-engine(내부망, X-CALLER-SERVICE)이고 사용자 신원이 없다. 조직은 배포 조직(ADR-030)으로 좁힌다:
 * staging 파드는 staging 조직의 스크립트만 보고 바꾼다.
 */
@Service
public class ScriptRuntimeService {

    static final String AUDIT_AUTO_DISABLED = "SCRIPT_AUTO_DISABLED";
    static final Set<String> AUTO_DISABLE_REASONS = Set.of("ERROR_RATE", "TIMEOUT_RATE");

    private final ScriptRuntimeRepository runtime;
    private final ScriptModuleRepository modules;
    private final FormulaMetricRepository formulas;
    private final ScriptRepository scripts;
    private final ScriptSupport support;
    private final ConfigVersions configVersions;
    private final DeploymentOrganization deployment;
    private final Audits audits;
    private final Clock clock;

    public ScriptRuntimeService(ScriptRuntimeRepository runtime, ScriptModuleRepository modules, FormulaMetricRepository formulas,
                                ScriptRepository scripts, ScriptSupport support, ConfigVersions configVersions,
                                DeploymentOrganization deployment, Audits audits, Clock clock) {
        this.runtime = runtime;
        this.modules = modules;
        this.formulas = formulas;
        this.scripts = scripts;
        this.support = support;
        this.configVersions = configVersions;
        this.deployment = deployment;
        this.audits = audits;
        this.clock = clock;
    }

    /**
     * API-SCR-32 실행 묶음: ENABLED이고 ACTIVE 버전이 있는 스크립트의 코드·설정값·연결(대상별 실패 정책). bundleVersion은 SCRIPTS 설정
     * 버전(조직을 주면 그 조직, 아니면 배포 조직 범위의 합)이고, sinceVersion과 같으면 빈 값(204)이다. M5: 스크립트마다 설정 판
     * {@code configRevision}·운영 로그 수집 끝 {@code logCaptureUntil}·가져오는 모듈 {@code moduleRefs["이름@버전"]}, 배포된 공유 모듈 버전
     * {@code modules[]}(SCR-04.01), 수식 항목 {@code formulaMetrics[]}(SCR-01.06, expression이 있어 pipeline이 직접 컴파일한다).
     * 모듈·수식이 바뀌어도 SCRIPTS 설정 버전이 오른다.
     */
    @Transactional(readOnly = true)
    public Optional<RuntimeBundle> bundle(Long organizationId, Long sinceVersion) {
        OptionalLong restriction = deployment.restriction();
        Long restricted = restriction.isPresent() ? restriction.getAsLong() : null;
        long bundleVersion;
        if (organizationId != null) {
            bundleVersion = deployment.includes(organizationId) ? configVersions.current(organizationId, ConfigVersions.SCRIPTS) : 0L;
            if (!deployment.includes(organizationId)) {
                restricted = DeploymentOrganization.NO_ORGANIZATION;
            }
        } else {
            bundleVersion = configVersions.sum(ConfigVersions.SCRIPTS, restriction);
        }
        if (sinceVersion != null && sinceVersion == bundleVersion) {
            return Optional.empty();
        }
        Map<Long, List<RuntimeBinding>> bindings = runtime.listRuntimeBindings(organizationId, restricted).stream()
                .collect(Collectors.groupingBy(RuntimeBindingRow::scriptId, Collectors.mapping(
                        b -> new RuntimeBinding(b.targetType(), b.targetId(), b.failurePolicy(), b.enabled()), Collectors.toList())));
        List<RuntimeScript> list = runtime.listRuntimeScripts(organizationId, restricted).stream()
                .map(s -> new RuntimeScript(Long.toString(s.scriptId()), Long.toString(s.organizationId()), s.kind(),
                        Long.toString(s.versionId()), s.versionNo(), s.code(), s.codeSha256(), support.map(s.config()),
                        moduleRefs(s.moduleRefs()), bindings.getOrDefault(s.scriptId(), List.of()), s.configRevision(),
                        s.logCaptureUntil()))
                .toList();
        List<Map<String, Object>> moduleList = modules.listReleased(organizationId, restricted).stream().map(m -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("organizationId", Long.toString(m.organizationId()));
            item.put("name", m.name());
            item.put("versionNo", m.versionNo());
            item.put("code", m.code());
            return item;
        }).toList();
        List<Map<String, Object>> formulaList = formulas.listForBundle(organizationId, restricted).stream().map(f -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", Long.toString(f.id()));
            item.put("organizationId", Long.toString(f.organizationId()));
            item.put("resultKey", f.resultKey());
            item.put("unit", f.unit());
            item.put("expression", f.expression());
            item.put("compiledJs", f.compiledJs());
            item.put("targetType", f.targetType());
            item.put("targetId", f.targetId());
            item.put("status", f.status());
            return item;
        }).toList();
        return Optional.of(new RuntimeBundle(bundleVersion, list, moduleList, formulaList));
    }

    /** 스크립트 버전의 module_refs(JSON 배열: "이름@버전" 또는 {name, version})를 그대로 */
    private List<Object> moduleRefs(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        tools.jackson.databind.JsonNode node = support.json().readTree(raw);
        if (!node.isArray()) {
            return List.of();
        }
        List<Object> out = new java.util.ArrayList<>();
        for (tools.jackson.databind.JsonNode e : node) {
            out.add(e.isString() ? e.asString() : support.json().treeToValue(e, Map.class));
        }
        return out;
    }

    /** API-SCR-34 인스턴스 적용 보고. 같은 인스턴스·버전은 한 행(멱등) */
    @Transactional
    public void acknowledge(DeployAckRequest request) {
        long scriptId = ScriptSupport.parseId(request.scriptId(), "scriptId");
        long versionId = ScriptSupport.parseId(request.versionId(), "versionId");
        long orgId = organizationOf(scriptId);
        if (!runtime.existsVersion(orgId, scriptId, versionId)) {
            throw new BusinessException(ScriptErrorCode.SCRIPT_NOT_FOUND);
        }
        Instant now = clock.instant();
        runtime.upsertAck(orgId, scriptId, versionId, request.instance().strip(), request.appliedAt() == null ? now : request.appliedAt(),
                now);
    }

    /**
     * API-SCR-33 자동 비활성(BR-SCR-11: 10분 오류율 50% 이상). AUTO_DISABLED로 바꾸고 EVT-SCR-01을 내 실행 엔진이 그 단계를 건너뛰게 한다.
     * 다시 켜기는 사람이 API-SCR-17로 한다. 이미 자동 비활성이면 그대로 돌려준다(재시도 멱등).
     */
    @Transactional
    public AutoDisableResponse autoDisable(long scriptId, AutoDisableRequest request) {
        String reason = request.reason().strip().toUpperCase(java.util.Locale.ROOT);
        if (!AUTO_DISABLE_REASONS.contains(reason)) {
            throw ScriptModels.invalid("reason", "Enum");
        }
        long orgId = organizationOf(scriptId);
        ScriptRow script = support.lock(orgId, scriptId);
        if (ScriptStatus.AUTO_DISABLED.name().equals(script.status())) {
            return new AutoDisableResponse(Long.toString(scriptId), script.status(), script.autoDisabledAt());
        }
        Instant now = clock.instant();
        String detail = reason + (request.observedRate() == null ? "" : " " + Math.round(request.observedRate() * 1000) / 10.0 + "%")
                + (request.window() == null ? "" : " / " + request.window());
        int version = scripts.updateStatus(orgId, scriptId, ScriptStatus.AUTO_DISABLED.name(), now,
                detail.length() > 200 ? detail.substring(0, 200) : detail, null, now);
        support.runtimeChanged(orgId, scriptId, version, false);
        var event = audits.event(orgId, AUDIT_AUTO_DISABLED).actor(AuditActorType.SERVICE, "svc:data2flow-pipeline", "data2flow-pipeline")
                .target("SCRIPT", Long.toString(scriptId)).detail("previousStatus", script.status()).detail("reason", reason);
        if (request.observedRate() != null) {
            event.detail("observedRate", request.observedRate());
        }
        if (request.window() != null) {
            event.detail("window", request.window());
        }
        if (request.versionId() != null) {
            event.detail("versionId", request.versionId());
        }
        audits.record(event);
        return new AutoDisableResponse(Long.toString(scriptId), ScriptStatus.AUTO_DISABLED.name(), now);
    }

    /** 스크립트의 조직(배포 조직 밖이면 없는 것으로 404) */
    private long organizationOf(long scriptId) {
        return runtime.findOrganizationOfScript(scriptId).filter(deployment::includes)
                .orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_NOT_FOUND));
    }
}
