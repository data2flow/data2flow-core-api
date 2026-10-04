package net.java21.data2flow.core.script.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.script.domain.ScriptErrorCode;
import net.java21.data2flow.core.script.domain.ScriptM5Rules;
import net.java21.data2flow.core.script.domain.ScriptModels;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ModuleRequest;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ModuleResponse;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ModuleSummary;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ModuleUsageResponse;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ModuleUsageScript;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ModuleVersionSummary;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ReleaseResponse;
import net.java21.data2flow.core.script.repository.ScriptModuleRepository;
import net.java21.data2flow.core.script.repository.ScriptModuleRepository.ModuleRow;
import net.java21.data2flow.core.script.repository.ScriptModuleRepository.VersionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 공유 모듈(SCR-04.01, BR-SCR-13·15, AT-SCR-08.1): 목록·생성·상세·DRAFT 저장·사용처 API-SCR-18, 버전 배포·삭제 API-SCR-19.
 * 스크립트는 {@code import … from 'module:이름@버전'}으로 버전을 지정해서만 가져다 쓰므로 새 버전을 배포해도 기존 스크립트 결과는 바뀌지
 * 않는다. 배포·삭제는 실행 묶음(API-SCR-32 modules)이 바뀌므로 EVT-SCR-01을 낸다.
 */
@Service
public class ScriptModuleService {

    static final String AUDIT_CHANGED = "SCRIPT_MODULE_CHANGED";

    private final RoleChecker roleChecker;
    private final ScriptModuleRepository modules;
    private final ScriptSupport support;
    private final Audits audits;
    private final Clock clock;

    public ScriptModuleService(RoleChecker roleChecker, ScriptModuleRepository modules, ScriptSupport support, Audits audits, Clock clock) {
        this.roleChecker = roleChecker;
        this.modules = modules;
        this.support = support;
        this.audits = audits;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ListApiResponse<ModuleSummary> list(Integer page, Integer size) {
        roleChecker.require(Permission.SCRIPT_READ);
        long org = roleChecker.currentUser().organizationId();
        PageParams params = PageParams.of(page, size);
        List<ModuleSummary> items = modules.list(org, params.size(), params.offset()).stream()
                .map(m -> new ModuleSummary(Long.toString(m.id()), m.name(), m.description(), m.latestVersionNo(),
                        modules.findUsage(org, m.name(), null).stream().map(u -> u.scriptId()).distinct().count(), m.updatedAt()))
                .toList();
        return ListApiResponse.of(params, items, modules.countByOrganization(org));
    }

    /** API-SCR-18 생성(DRAFT 코드 포함) — 201. 이름 형식·중복 400 errors[name], 100개 초과 409 SCRIPT_QUOTA_EXCEEDED */
    @Transactional
    public ModuleResponse create(ModuleRequest r) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        String name = r == null || r.name() == null ? "" : r.name().strip();
        if (!ScriptM5Rules.validModuleName(name)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "Pattern", "^[a-z0-9-]{3,40}$")));
        }
        String code = ScriptModels.requireCodeSize(r.code() == null ? "" : r.code());
        String description = description(r.description());
        if (modules.countByOrganization(org) >= ScriptM5Rules.MAX_MODULES) {
            throw new BusinessException(ScriptErrorCode.SCRIPT_QUOTA_EXCEEDED);
        }
        if (modules.existsName(org, name)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "DUPLICATE", null)));
        }
        Instant now = clock.instant();
        long id = modules.insert(org, name, description, user.userId(), now);
        modules.saveDraft(org, id, code, user.userId(), now);
        audits.record(audits.event(org, AUDIT_CHANGED).actor(user).target("SCRIPT_MODULE", Long.toString(id)).detail("op", "CREATED")
                .detail("name", name));
        return detail(org, id);
    }

    @Transactional(readOnly = true)
    public ModuleResponse get(long moduleId) {
        roleChecker.require(Permission.SCRIPT_READ);
        return detail(roleChecker.currentUser().organizationId(), moduleId);
    }

    /** API-SCR-18 PUT: DRAFT 코드·설명 저장(배포된 버전은 그대로, BR-SCR-13). 이름은 바꾸지 않는다(가져오기 문장이 이름을 쓴다) */
    @Transactional
    public ModuleResponse saveDraft(long moduleId, ModuleRequest r) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        lock(org, moduleId);
        if (r == null || r.code() == null) {
            throw ScriptModels.invalid("code", "NotNull");
        }
        String code = ScriptModels.requireCodeSize(r.code());
        Instant now = clock.instant();
        if (r.description() != null) {
            modules.updateMeta(org, moduleId, description(r.description()), now);
        }
        modules.saveDraft(org, moduleId, code, user.userId(), now);
        return detail(org, moduleId);
    }

    /** API-SCR-18 사용처: 이 모듈을 가져오는 스크립트(ACTIVE·DRAFT 버전)와 그 모듈 버전 */
    @Transactional(readOnly = true)
    public ModuleUsageResponse usage(long moduleId) {
        roleChecker.require(Permission.SCRIPT_READ);
        long org = roleChecker.currentUser().organizationId();
        ModuleRow m = modules.findById(org, moduleId).orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_MODULE_NOT_FOUND));
        List<ModuleUsageScript> scripts = modules.findUsage(org, m.name(), null).stream()
                .map(u -> new ModuleUsageScript(Long.toString(u.scriptId()), u.scriptName(), versionOf(u.ref()))).toList();
        return new ModuleUsageResponse(Long.toString(moduleId), scripts);
    }

    /** API-SCR-19 배포: DRAFT를 불변 버전으로 — 201. DRAFT가 없으면 409 SCRIPT_VERSION_CONFLICT */
    @Transactional
    public ReleaseResponse release(long moduleId) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        lock(org, moduleId);
        Instant now = clock.instant();
        int versionNo = modules.release(org, moduleId, now).orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_VERSION_CONFLICT));
        support.runtimeChanged(org, moduleId, versionNo, false);
        audits.record(audits.event(org, AUDIT_CHANGED).actor(user).target("SCRIPT_MODULE", Long.toString(moduleId)).detail("op", "RELEASED")
                .detail("versionNo", versionNo));
        return new ReleaseResponse(Long.toString(moduleId), versionNo, now);
    }

    /** API-SCR-19 버전 삭제 — 204. 쓰는 스크립트 버전이 있으면 409 SCRIPT_MODULE_IN_USE(BR-SCR-15) */
    @Transactional
    public void deleteVersion(long moduleId, int versionNo) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        lock(org, moduleId);
        ModuleRow m = modules.findById(org, moduleId).orElseThrow();
        VersionRow v = modules.listVersions(org, moduleId).stream().filter(x -> x.versionNo() == versionNo).findFirst()
                .orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_MODULE_NOT_FOUND));
        if (!modules.findUsage(org, m.name(), versionNo).isEmpty()) {
            throw new BusinessException(ScriptErrorCode.SCRIPT_MODULE_IN_USE);
        }
        modules.deleteVersion(org, moduleId, versionNo);
        if ("RELEASED".equals(v.status())) {
            support.runtimeChanged(org, moduleId, versionNo, false);
        }
        audits.record(audits.event(org, AUDIT_CHANGED).actor(user).target("SCRIPT_MODULE", Long.toString(moduleId)).detail("op", "VERSION_DELETED")
                .detail("versionNo", versionNo));
    }

    private void lock(long org, long moduleId) {
        modules.lockById(org, moduleId).orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_MODULE_NOT_FOUND));
    }

    private ModuleResponse detail(long org, long moduleId) {
        ModuleRow m = modules.findById(org, moduleId).orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_MODULE_NOT_FOUND));
        List<VersionRow> versions = modules.listVersions(org, moduleId);
        String draft = versions.stream().filter(v -> "DRAFT".equals(v.status())).map(VersionRow::code).findFirst().orElse(null);
        return new ModuleResponse(Long.toString(m.id()), m.name(), m.description(), draft, m.latestVersionNo(),
                versions.stream().map(v -> new ModuleVersionSummary(v.versionNo(), v.status(), v.releasedAt())).toList(), m.updatedAt());
    }

    private static String description(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.strip().length() > 500) {
            throw ScriptModels.invalid("description", "Size");
        }
        return raw.strip();
    }

    private static int versionOf(String ref) {
        try {
            return Integer.parseInt(ref.substring(ref.lastIndexOf('@') + 1));
        } catch (RuntimeException ex) {
            return 0;
        }
    }
}
