package net.java21.data2flow.core.script.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.script.domain.ScriptErrorCode;
import net.java21.data2flow.core.script.domain.ScriptModels;
import net.java21.data2flow.core.script.domain.ScriptModels.FailurePolicy;
import net.java21.data2flow.core.script.domain.ScriptModels.ScriptKind;
import net.java21.data2flow.core.script.domain.ScriptModels.TargetType;
import net.java21.data2flow.core.script.dto.ScriptDtos.BindingRequest;
import net.java21.data2flow.core.script.dto.ScriptDtos.BindingResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.BindingsResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.ReplaceBindingsRequest;
import net.java21.data2flow.core.script.repository.ScriptBindingRepository;
import net.java21.data2flow.core.script.repository.ScriptBindingRepository.BindingRow;
import net.java21.data2flow.core.script.repository.ScriptRepository;
import net.java21.data2flow.core.script.repository.ScriptRepository.ScriptRow;
import net.java21.data2flow.core.script.repository.ScriptTargetRepository;
import net.java21.data2flow.core.script.repository.ScriptTargetRepository.DeviceRef;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 스크립트 연결과 실패 정책(SCR-01.01 DECODE → 소스, SCR-01.02 TRANSFORM → 모델·기기, SCR-02.03 FAIL_OPEN·FAIL_CLOSED, API-SCR-09).
 *
 * <ul>
 *   <li>DECODE는 SOURCE에만, TRANSFORM은 MODEL·DEVICE에만 연결한다. 어기면 400 SCRIPT_BINDING_INVALID(TC-SCR-002)</li>
 *   <li>한 대상에는 종류별로 스크립트 하나: 다른 스크립트가 이미 가졌으면 409 SCRIPT_BINDING_DUPLICATED</li>
 *   <li>실행 순서는 모델 → 기기(BR-SCR-03)이고, 정책은 연결마다 둔다(기기 정책이 모델 정책보다 우선은 pipeline이 판정, TC-SCR-033)</li>
 *   <li>target_id는 소스 ID, <b>모델 코드</b>, 기기 ID(ERD 주석). 화면이 모델 ID를 보내면 코드로 바꿔 저장한다</li>
 *   <li>없는 대상, 권한 밖 공간의 기기는 400 SCRIPT_BINDING_INVALID(존재를 드러내지 않음)</li>
 * </ul>
 */
@Service
public class ScriptBindingService {

    static final String AUDIT_BINDING_CHANGED = "SCRIPT_BINDING_CHANGED";

    private final ScriptBindingRepository bindings;
    private final ScriptRepository scripts;
    private final ScriptTargetRepository targets;
    private final ScriptSupport support;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final Clock clock;

    public ScriptBindingService(ScriptBindingRepository bindings, ScriptRepository scripts, ScriptTargetRepository targets,
                                ScriptSupport support, RoleChecker roleChecker, Audits audits, Clock clock) {
        this.bindings = bindings;
        this.scripts = scripts;
        this.targets = targets;
        this.support = support;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.clock = clock;
    }

    /** API-SCR-09 연결 전체 교체. 감사 SCRIPT_BINDING_CHANGED(이전·이후), EVT-SCR-01 */
    @Transactional
    public BindingsResponse replace(long scriptId, ReplaceBindingsRequest request) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ScriptRow script = support.lock(orgId, scriptId);
        List<Resolved> resolved = resolve(orgId, ScriptKind.valueOf(script.kind()), scriptId, request.bindings());
        List<BindingRow> before = bindings.findByScript(orgId, scriptId);
        bindings.deleteByScript(orgId, scriptId);
        insert(orgId, scriptId, script.kind(), resolved);
        List<BindingRow> after = bindings.findByScript(orgId, scriptId);
        int version = scripts.touch(orgId, scriptId, user.userId(), clock.instant());
        support.runtimeChanged(orgId, scriptId, version, false);
        audits.record(audits.event(orgId, AUDIT_BINDING_CHANGED).actor(user).target("SCRIPT", Long.toString(scriptId))
                .detail("before", summary(before)).detail("after", summary(after)));
        return new BindingsResponse(Long.toString(scriptId), after.stream().map(ScriptBindingService::toResponse).toList());
    }

    /** 요청 연결을 검증하고 저장할 값으로 바꾼다(생성 API-SCR-02에서도 쓴다) */
    List<Resolved> resolve(long orgId, ScriptKind kind, long scriptId, List<BindingRequest> requested) {
        List<Resolved> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BindingRequest b : requested == null ? List.<BindingRequest>of() : requested) {
            TargetType type = ScriptModels.parse(TargetType.class, b.targetType(), "bindings.targetType");
            if (!ScriptModels.bindingAllowed(kind, type)) {
                throw new BusinessException(ScriptErrorCode.SCRIPT_BINDING_INVALID);
            }
            FailurePolicy policy = b.failurePolicy() == null || b.failurePolicy().isBlank() ? FailurePolicy.FAIL_OPEN
                    : ScriptModels.parse(FailurePolicy.class, b.failurePolicy(), "bindings.failurePolicy");
            String targetId = canonicalTarget(orgId, type, b.targetId().strip());
            if (!seen.add(type + ":" + targetId)) {
                throw new BusinessException(ScriptErrorCode.SCRIPT_BINDING_DUPLICATED);
            }
            if (bindings.findHolder(orgId, kind.name(), type.name(), targetId, scriptId).isPresent()) {
                throw new BusinessException(ScriptErrorCode.SCRIPT_BINDING_DUPLICATED);
            }
            result.add(new Resolved(type, targetId, policy, b.enabled() == null || b.enabled()));
        }
        return result;
    }

    void insert(long orgId, long scriptId, String kind, List<Resolved> resolved) {
        for (Resolved r : resolved) {
            bindings.insert(orgId, scriptId, kind, r.type().name(), r.targetId(), r.policy().name(), r.enabled(), clock.instant());
        }
    }

    /** 대상이 있고 보이는지 확인하고 저장할 target_id(소스 ID, 모델 코드, 기기 ID)를 돌려준다 */
    private String canonicalTarget(long orgId, TargetType type, String raw) {
        switch (type) {
            case SOURCE -> {
                long id = numeric(raw);
                return targets.findSource(orgId, id).map(s -> Long.toString(s.id()))
                        .orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_BINDING_INVALID));
            }
            case MODEL -> {
                // 화면(새 스크립트 대화상자)은 모델 ID를, 문서(ERD)는 모델 코드를 쓴다: ID로 먼저 찾고 없으면 코드로 찾는다
                var byId = raw.chars().allMatch(Character::isDigit) && !raw.isEmpty()
                        ? targets.findModelById(orgId, Long.parseLong(raw)) : java.util.Optional.<ScriptTargetRepository.ModelRef>empty();
                return byId.or(() -> targets.findModelByCode(orgId, raw)).map(ScriptTargetRepository.ModelRef::code)
                        .orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_BINDING_INVALID));
            }
            default -> {
                DeviceRef device = targets.findDevice(orgId, numeric(raw))
                        .orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_BINDING_INVALID));
                if (!roleChecker.spaceScope().includes(device.spaceId())) {
                    throw new BusinessException(ScriptErrorCode.SCRIPT_BINDING_INVALID);
                }
                return Long.toString(device.id());
            }
        }
    }

    private static long numeric(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            throw new BusinessException(ScriptErrorCode.SCRIPT_BINDING_INVALID);
        }
    }

    static BindingResponse toResponse(BindingRow b) {
        return new BindingResponse(Long.toString(b.id()), b.targetType(), b.targetId(), b.targetName(), b.failurePolicy(), b.enabled());
    }

    static List<String> summary(List<BindingRow> rows) {
        return rows.stream().map(b -> b.targetType() + ":" + b.targetId() + ":" + b.failurePolicy() + (b.enabled() ? "" : ":DISABLED"))
                .toList();
    }

    /** 검증을 마친 연결 하나 */
    record Resolved(TargetType type, String targetId, FailurePolicy policy, boolean enabled) {
    }
}
