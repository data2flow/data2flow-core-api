package net.java21.data2flow.core.script.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.script.domain.ScriptErrorCode;
import net.java21.data2flow.core.script.domain.ScriptModels;
import net.java21.data2flow.core.script.domain.ScriptModels.ScriptKind;
import net.java21.data2flow.core.script.domain.ScriptModels.ScriptStatus;
import net.java21.data2flow.core.script.domain.ScriptModels.TargetType;
import net.java21.data2flow.core.script.domain.ScriptTemplates;
import net.java21.data2flow.core.script.domain.ScriptTemplates.Template;
import net.java21.data2flow.core.script.dto.ScriptDtos.ActiveVersion;
import net.java21.data2flow.core.script.dto.ScriptDtos.BindingsSummary;
import net.java21.data2flow.core.script.dto.ScriptDtos.CreateScriptRequest;
import net.java21.data2flow.core.script.dto.ScriptDtos.CreateScriptResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.Draft;
import net.java21.data2flow.core.script.dto.ScriptDtos.Impact;
import net.java21.data2flow.core.script.dto.ScriptDtos.ScriptDetailResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.ScriptSummaryResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.StaticCheck;
import net.java21.data2flow.core.script.dto.ScriptDtos.StatusResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.TemplateResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.Usage;
import net.java21.data2flow.core.script.dto.ScriptDtos.UsageBinding;
import net.java21.data2flow.core.script.dto.ScriptDtos.VersionResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.VersionSummary;
import net.java21.data2flow.core.script.repository.ScriptBindingRepository;
import net.java21.data2flow.core.script.repository.ScriptBindingRepository.BindingRow;
import net.java21.data2flow.core.script.repository.ScriptRepository;
import net.java21.data2flow.core.script.repository.ScriptRepository.ScriptRow;
import net.java21.data2flow.core.script.repository.ScriptRepository.ScriptSearch;
import net.java21.data2flow.core.script.repository.ScriptVersionRepository;
import net.java21.data2flow.core.script.repository.ScriptVersionRepository.VersionRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 스크립트 정의(SCR-01.01·01.02, SCR-02.05, SCR-04.03): 목록 API-SCR-01, 생성·수정·삭제 API-SCR-02, 상세 API-SCR-04, 버전 코드 API-SCR-06,
 * 템플릿 API-SCR-15, 활성 전환 API-SCR-17. 조회는 SCRIPT_READ(OPERATOR+), 쓰기는 SCRIPT_WRITE(INTEGRATOR+). 스크립트는 공간에 속하지 않는
 * 조직 단위 자원이라 공간 범위는 기기 연결에만 적용한다.
 */
@Service
public class ScriptService {

    static final String AUDIT_CREATED = "SCRIPT_CREATED";
    static final String AUDIT_UPDATED = "SCRIPT_UPDATED";
    static final String AUDIT_DELETED = "SCRIPT_DELETED";
    static final String AUDIT_ENABLED = "SCRIPT_ENABLED";
    static final String AUDIT_DISABLED = "SCRIPT_DISABLED";
    static final Set<String> INCLUDES = Set.of("versions", "usage", "tests", "config");

    private final ScriptRepository scripts;
    private final ScriptVersionRepository versions;
    private final ScriptBindingRepository bindings;
    private final ScriptBindingService bindingService;
    private final ScriptSupport support;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ScriptTestCaseService testCases;

    public ScriptService(ScriptRepository scripts, ScriptVersionRepository versions, ScriptBindingRepository bindings,
                         ScriptBindingService bindingService, ScriptSupport support, RoleChecker roleChecker, Audits audits,
                         TransactionTemplate tx, Clock clock, ScriptTestCaseService testCases) {
        this.testCases = testCases;
        this.scripts = scripts;
        this.versions = versions;
        this.bindings = bindings;
        this.bindingService = bindingService;
        this.support = support;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.tx = tx;
        this.clock = clock;
    }

    /** API-SCR-01 목록. totalCount가 조직 한도 300개 대비 사용량이다 */
    @Transactional(readOnly = true)
    public ListApiResponse<ScriptSummaryResponse> list(String kind, String status, Boolean checkFailed, String targetType, String keyword,
                                                       Integer page, Integer size) {
        roleChecker.require(Permission.SCRIPT_READ);
        long orgId = roleChecker.currentUser().organizationId();
        ScriptSearch search = new ScriptSearch(orgId,
                kind == null || kind.isBlank() ? null : ScriptModels.parse(ScriptKind.class, kind, "kind").name(),
                status == null || status.isBlank() ? null : ScriptModels.parse(ScriptStatus.class, status, "status").name(),
                Boolean.TRUE.equals(checkFailed),
                targetType == null || targetType.isBlank() ? null : ScriptModels.parse(TargetType.class, targetType, "targetType").name(),
                PageParams.keyword(keyword));
        PageParams params = PageParams.of(page, size);
        List<ScriptSummaryResponse> rows = scripts.search(search, params.size(), params.offset()).stream()
                .map(r -> new ScriptSummaryResponse(Long.toString(r.id()), r.name(), r.kind(), r.status(), r.description(),
                        r.activeVersionNo(), r.hasDraft(), r.checkFailed(), new BindingsSummary(r.sources(), r.models(), r.devices()),
                        null, r.lastDeployedBy(), r.lastDeployedAt()))
                .toList();
        return ListApiResponse.of(params, rows, scripts.countSearch(search));
    }

    /** API-SCR-15 템플릿 목록(고정 목록) */
    public ListApiResponse<TemplateResponse> templates(String kind) {
        roleChecker.require(Permission.SCRIPT_READ);
        ScriptKind filter = kind == null || kind.isBlank() ? null : ScriptModels.kind(kind);
        List<TemplateResponse> list = ScriptTemplates.all().stream().filter(t -> filter == null || t.kind() == filter)
                .map(t -> new TemplateResponse(t.key(), t.kind().name(), t.name(), t.description(), t.code(), t.configDefaults()))
                .toList();
        return ListApiResponse.of(PageParams.of(1, PageParams.MAX_SIZE), list, list.size());
    }

    /**
     * API-SCR-02 생성: v1 DRAFT(템플릿 또는 기본 코드)와 요청한 연결을 만든다(AT-SCR-01.1). 조직 300개 한도(409 SCRIPT_QUOTA_EXCEEDED),
     * 이름 중복(409 SCRIPT_NAME_DUPLICATED). 정적 검사(pipeline)는 트랜잭션 밖에서 먼저 한다.
     */
    public CreateScriptResponse create(CreateScriptRequest request) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ScriptKind kind = ScriptModels.kind(request.kind());
        String name = request.name().strip();
        if (name.length() < 2) {
            throw ScriptModels.invalid("name", "Size");
        }
        Template template = null;
        String templateKey = request.templateKey() == null || request.templateKey().isBlank() ? null : request.templateKey().strip();
        if (templateKey != null) {
            template = ScriptTemplates.find(templateKey)
                    .filter(t -> t.kind() == kind).orElseThrow(() -> ScriptModels.invalid("templateKey", "Invalid"));
        }
        String code = template == null ? ScriptTemplates.defaultCode(kind) : template.code();
        Map<String, Object> config = template == null ? Map.of() : template.configDefaults();
        StaticCheck check = support.checkOrUnavailable(orgId, kind.name(), code);
        String description = request.description() == null || request.description().isBlank() ? null : request.description().strip();
        try {
            return tx.execute(status -> {
                scripts.lockOrganization(orgId);
                if (scripts.countByOrganization(orgId) >= ScriptModels.MAX_SCRIPTS_PER_ORGANIZATION) {
                    throw new BusinessException(ScriptErrorCode.SCRIPT_QUOTA_EXCEEDED);
                }
                if (scripts.existsName(orgId, name, null)) {
                    throw new BusinessException(ScriptErrorCode.SCRIPT_NAME_DUPLICATED);
                }
                long id = scripts.insert(orgId, name, kind.name(), description, support.write(config), user.userId(), clock.instant());
                long versionId = versions.insertDraft(orgId, id, 1, code, ScriptModels.sha256(code), support.write(check),
                        user.userId(), clock.instant());
                List<ScriptBindingService.Resolved> resolved = bindingService.resolve(orgId, kind, id, request.bindings());
                bindingService.insert(orgId, id, kind.name(), resolved);
                var event = audits.event(orgId, AUDIT_CREATED).actor(user).target("SCRIPT", Long.toString(id))
                        .detail("name", name).detail("kind", kind.name()).detail("versionNo", 1)
                        .detail("bindings", resolved.stream().map(r -> r.type() + ":" + r.targetId()).toList());
                if (templateKey != null) {
                    event.detail("template", templateKey);
                }
                audits.record(event);
                return new CreateScriptResponse(Long.toString(id), name, kind.name(), ScriptStatus.ENABLED.name(),
                        Long.toString(versionId), 0);
            });
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ScriptErrorCode.SCRIPT_NAME_DUPLICATED);
        }
    }

    /** API-SCR-02 PATCH: 이름·설명(온 키만), baseVersion 필수(다르면 409 SCRIPT_VERSION_CONFLICT) */
    @Transactional
    public ScriptDetailResponse update(long scriptId, JsonNode body) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ScriptRow script = support.require(orgId, scriptId);
        long base = VersionCheck.baseVersion(body);
        VersionCheck.require(base, script.version(), ScriptErrorCode.SCRIPT_VERSION_CONFLICT);
        String name = script.name();
        if (body.has("name")) {
            name = body.get("name").isString() ? body.get("name").asString().strip() : "";
            if (name.length() < 2 || name.length() > 80) {
                throw ScriptModels.invalid("name", "Size");
            }
            if (scripts.existsName(orgId, name, scriptId)) {
                throw new BusinessException(ScriptErrorCode.SCRIPT_NAME_DUPLICATED);
            }
        }
        String description = script.description();
        if (body.has("description")) {
            JsonNode d = body.get("description");
            description = d == null || d.isNull() || d.asString().isBlank() ? null : d.asString().strip();
            if (description != null && description.length() > 500) {
                throw ScriptModels.invalid("description", "Size");
            }
        }
        VersionCheck.requireUpdated(scripts.updateMeta(orgId, scriptId, base, name, description, user.userId(), clock.instant()),
                ScriptErrorCode.SCRIPT_VERSION_CONFLICT);
        audits.record(audits.event(orgId, AUDIT_UPDATED).actor(user).target("SCRIPT", Long.toString(scriptId))
                .detail("before", Map.of("name", script.name(), "description", String.valueOf(script.description())))
                .detail("after", Map.of("name", name, "description", String.valueOf(description))));
        return detail(scriptId, Set.of());
    }

    /** API-SCR-02 DELETE: 연결이 하나라도 있거나 소스·모델이 가리키면 409 SCRIPT_IN_USE(BR-SCR-15) */
    @Transactional
    public void delete(long scriptId) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ScriptRow script = support.lock(orgId, scriptId);
        if (bindings.countByScript(orgId, scriptId) > 0 || scripts.countExternalReferences(orgId, scriptId) > 0) {
            throw new BusinessException(ScriptErrorCode.SCRIPT_IN_USE);
        }
        scripts.delete(orgId, scriptId);
        if (script.activeVersionId() != null) {
            support.runtimeChanged(orgId, scriptId, script.version() + 1L, true);
        }
        audits.record(audits.event(orgId, AUDIT_DELETED).actor(user).target("SCRIPT", Long.toString(scriptId))
                .detail("name", script.name()).detail("kind", script.kind()));
    }

    /** API-SCR-04 상세. include=versions,usage,tests,config(tests = 테스트 케이스 SCR-03.03) */
    @Transactional(readOnly = true)
    public ScriptDetailResponse detail(long scriptId, String include) {
        roleChecker.require(Permission.SCRIPT_READ);
        Set<String> includes = include == null ? Set.of() : Arrays.stream(include.split(",")).map(String::strip)
                .filter(INCLUDES::contains).collect(Collectors.toSet());
        return detail(scriptId, includes);
    }

    private ScriptDetailResponse detail(long scriptId, Set<String> includes) {
        long orgId = roleChecker.currentUser().organizationId();
        ScriptRow s = support.require(orgId, scriptId);
        VersionRow active = s.activeVersionId() == null ? null : versions.findById(orgId, scriptId, s.activeVersionId()).orElse(null);
        VersionRow draft = versions.findDraft(orgId, scriptId).orElse(null);
        List<BindingRow> rows = bindings.findByScript(orgId, scriptId);
        ActiveVersion activeVersion = active == null ? null : new ActiveVersion(Long.toString(active.id()), active.versionNo(),
                active.code(), active.deployedAt(), active.deployedByName(), active.deployMemo(), active.forced(),
                support.applied(orgId, active.id()));
        Draft draftView = draft == null ? null : new Draft(Long.toString(draft.id()), draft.versionNo(), draft.code(),
                support.staticCheck(draft.staticCheck()), draft.createdByName(), draft.updatedAt());
        List<VersionSummary> versionList = includes.contains("versions")
                ? versions.listByScript(orgId, scriptId, ScriptModels.KEEP_VERSIONS).stream().map(this::summary).toList() : null;
        Usage usage = includes.contains("usage")
                ? new Usage(rows.stream().map(b -> new UsageBinding(b.targetType(), b.targetId(), b.targetName(), b.deviceCount(), null,
                b.failurePolicy())).toList(), List.of())
                : null;
        Map<String, Object> config = includes.contains("config") ? support.map(s.config()) : null;
        return new ScriptDetailResponse(Long.toString(s.id()), s.name(), s.kind(), s.description(), s.status(),
                ScriptSupport.id(s.activeVersionId()), draft == null ? null : Long.toString(draft.id()), s.autoDisabledAt(),
                s.autoDisabledReason(), config, s.logCaptureUntil(), s.version(), s.updatedByName(), s.updatedAt(), s.createdAt(),
                activeVersion, draftView, versionList, rows.stream().map(ScriptBindingService::toResponse).toList(), usage,
                includes.contains("tests") ? testCases.listForDetail(orgId, scriptId) : null);
    }

    private VersionSummary summary(VersionRow v) {
        return new VersionSummary(Long.toString(v.id()), v.versionNo(), v.status(), v.createdByName(), v.updatedAt(), v.deployMemo(),
                v.deployedByName(), v.deployedAt(), v.forced(), support.staticCheck(v.staticCheck()));
    }

    /** API-SCR-06 버전 코드(비교용) */
    @Transactional(readOnly = true)
    public VersionResponse version(long scriptId, long versionId) {
        roleChecker.require(Permission.SCRIPT_READ);
        long orgId = roleChecker.currentUser().organizationId();
        support.require(orgId, scriptId);
        VersionRow v = versions.findById(orgId, scriptId, versionId)
                .orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_NOT_FOUND));
        return new VersionResponse(Long.toString(v.id()), Long.toString(v.scriptId()), v.versionNo(), v.status(), v.code(),
                v.codeSha256(), support.staticCheck(v.staticCheck()), v.deployMemo(), v.deployedByName(), v.deployedAt(), v.forced(),
                v.forceReason(), v.createdByName(), v.updatedAt());
    }

    /**
     * API-SCR-17 활성화. 자동 비활성(AUTO_DISABLED) 해제도 여기서 사람이 한다(BR-SCR-11) — 감사 detail.previousStatus로 남는다(BR-SCR-16).
     * 이미 ENABLED면 아무것도 바꾸지 않는다.
     */
    @Transactional
    public StatusResponse enable(long scriptId) {
        return changeStatus(scriptId, ScriptStatus.ENABLED);
    }

    /** API-SCR-17 비활성화: 다음 메시지부터 그 단계를 건너뛴다(EVT-SCR-01). 응답에 영향 범위 */
    @Transactional
    public StatusResponse disable(long scriptId) {
        return changeStatus(scriptId, ScriptStatus.DISABLED);
    }

    private StatusResponse changeStatus(long scriptId, ScriptStatus target) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ScriptRow script = support.lock(orgId, scriptId);
        int version = script.version();
        if (!target.name().equals(script.status())) {
            version = scripts.updateStatus(orgId, scriptId, target.name(), null, null, user.userId(), clock.instant());
            support.runtimeChanged(orgId, scriptId, version, false);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("previousStatus", script.status());
            detail.put("status", target.name());
            audits.record(audits.event(orgId, target == ScriptStatus.ENABLED ? AUDIT_ENABLED : AUDIT_DISABLED).actor(user)
                    .target("SCRIPT", Long.toString(scriptId)).detail(detail));
        }
        Impact impact = null;
        if (target == ScriptStatus.DISABLED) {
            List<BindingRow> rows = bindings.findByScript(orgId, scriptId);
            impact = new Impact(rows.size(), rows.stream().mapToLong(BindingRow::deviceCount).sum(), null);
        }
        return new StatusResponse(Long.toString(scriptId), target.name(), version, impact);
    }
}
