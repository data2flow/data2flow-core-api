package net.java21.data2flow.core.flow.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.flow.FlowDefinition;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.control.service.ControlSettingsService;
import net.java21.data2flow.core.flow.domain.FlowDiff;
import net.java21.data2flow.core.flow.domain.FlowErrorCode;
import net.java21.data2flow.core.flow.domain.FlowModels;
import net.java21.data2flow.core.flow.domain.FlowValidator;
import net.java21.data2flow.core.flow.dto.FlowDtos.ApplyStatus;
import net.java21.data2flow.core.flow.dto.FlowDtos.EmergencyStop;
import net.java21.data2flow.core.flow.dto.FlowDtos.FlowDetail;
import net.java21.data2flow.core.flow.dto.FlowDtos.FlowInfo;
import net.java21.data2flow.core.flow.dto.FlowDtos.FlowSummary;
import net.java21.data2flow.core.flow.dto.FlowDtos.Instance;
import net.java21.data2flow.core.flow.dto.FlowDtos.Overlay;
import net.java21.data2flow.core.flow.dto.FlowDtos.SaveResult;
import net.java21.data2flow.core.flow.dto.FlowDtos.StatusResult;
import net.java21.data2flow.core.flow.dto.FlowDtos.ValidateResult;
import net.java21.data2flow.core.flow.dto.FlowDtos.Validation;
import net.java21.data2flow.core.flow.dto.FlowDtos.VersionDetail;
import net.java21.data2flow.core.flow.dto.FlowDtos.VersionSummary;
import net.java21.data2flow.core.flow.repository.FlowRepository;
import net.java21.data2flow.core.flow.repository.FlowRepository.FlowRow;
import net.java21.data2flow.core.flow.repository.FlowRepository.Search;
import net.java21.data2flow.core.flow.repository.FlowRuntimeRepository;
import net.java21.data2flow.core.flow.repository.FlowRuntimeRepository.ApplyReport;
import net.java21.data2flow.core.flow.repository.FlowTargetRepository;
import net.java21.data2flow.core.flow.repository.FlowVersionRepository;
import net.java21.data2flow.core.flow.repository.FlowVersionRepository.VersionRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 플로우 정의(FLW-01.01~01.03·01.06·05.06): 목록 API-FLW-01, 상세 02, 생성·초안 03, 버전·비교 04, 삭제 05, 검증 06, 상태 09, 설정 10,
 * 노드 카탈로그 30. 조회 FLOW_READ(ANALYST+), 쓰기 FLOW_WRITE(OPERATOR+). 적용·롤백·승인은 {@link FlowApplyService}.
 * 저장은 검증 오류가 있어도 되고(BR-FLW-05) 크기(2MB, 413)·노드 수(200, 400)만 막는다.
 */
@Service
public class FlowService {

    static final String AUDIT_CREATED = "FLOW_CREATED";
    static final String AUDIT_DRAFT_SAVED = "FLOW_DRAFT_SAVED";
    static final String AUDIT_UPDATED = "FLOW_UPDATED";
    static final String AUDIT_DELETED = "FLOW_DELETED";
    /** 적용 상태에 세는 엔진 인스턴스(최근 보고) */
    static final Duration INSTANCE_WINDOW = Duration.ofHours(24);

    private final FlowRepository flows;
    private final FlowVersionRepository versions;
    private final FlowRuntimeRepository runtime;
    private final FlowTargetRepository targets;
    private final FlowSupport support;
    private final ControlSettingsService settings;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final Clock clock;
    private final FlowEngineClient engine;

    public FlowService(FlowRepository flows, FlowVersionRepository versions, FlowRuntimeRepository runtime, FlowTargetRepository targets,
                       FlowSupport support, ControlSettingsService settings, RoleChecker roleChecker, Audits audits, Clock clock,
                       FlowEngineClient engine) {
        this.engine = engine;
        this.flows = flows;
        this.versions = versions;
        this.runtime = runtime;
        this.targets = targets;
        this.support = support;
        this.settings = settings;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.clock = clock;
    }

    /** API-FLW-01 목록 — FLOW_READ. 정렬 health(문제 있는 것 먼저, 기본)·updatedAt·name */
    @Transactional(readOnly = true)
    public ListApiResponse<FlowSummary> list(String q, String owner, List<String> status, String kind, String environment, String spaceId,
                                             Integer page, Integer size, String sort) {
        roleChecker.require(Permission.FLOW_READ);
        long orgId = roleChecker.currentUser().organizationId();
        SpaceScope scope = roleChecker.spaceScope();
        List<String> statuses = new ArrayList<>();
        for (String raw : status == null ? List.<String>of() : status) {
            for (String one : raw.split(",")) {
                if (!one.isBlank()) {
                    statuses.add(enumValue(one, FlowModels.STATUSES, "status"));
                }
            }
        }
        Search search = new Search(orgId, PageParams.keyword(q), owner == null || owner.isBlank() ? null : parseLong(owner, "owner"),
                statuses, kind == null || kind.isBlank() ? null : enumValue(kind, FlowModels.KINDS, "kind"),
                environment == null || environment.isBlank() ? null : enumValue(environment, FlowModels.ENVIRONMENTS, "environment"),
                spaceId == null || spaceId.isBlank() ? null : parseLong(spaceId, "spaceId"),
                scope.unrestricted() ? null : scope.allowedSpaceIds(), orderBy(sort));
        PageParams params = PageParams.of(page, size);
        List<FlowSummary> rows = flows.search(search, params.size(), params.offset()).stream()
                .map(f -> new FlowSummary(f.id().toString(), f.name(), f.kind(), f.status(), f.environment(), f.activeVersion(),
                        f.draftVersion(), spaceIds(f), f.hasControlNode(), null, FlowSupport.user(f.updatedBy(), f.updatedByName()),
                        f.updatedAt()))
                .toList();
        return ListApiResponse.of(params, rows, flows.countSearch(search));
    }

    private static List<String> spaceIds(FlowRow f) {
        Set<Long> ids = new LinkedHashSet<>(f.spaceIds());
        ids.addAll(f.relatedSpaceIds());
        return ids.stream().map(String::valueOf).toList();
    }

    static String orderBy(String sort) {
        String raw = sort == null || sort.isBlank() ? "health" : sort.strip();
        String[] parts = raw.split(",");
        String field = parts[0];
        String dir = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : null;
        if (dir != null && !dir.equals("asc") && !dir.equals("desc")) {
            throw FlowModels.invalid("sort", "Pattern");
        }
        return switch (field) {
            case "health" -> "CASE f.status WHEN 'DEGRADED' THEN 0 WHEN 'PAUSED' THEN 1 WHEN 'ACTIVE' THEN 2 WHEN 'DRAFT' THEN 3 ELSE 4 END, "
                    + "f.updated_at DESC, f.id";
            case "updatedAt" -> "f.updated_at " + (dir == null ? "DESC" : dir.toUpperCase(Locale.ROOT)) + ", f.id";
            case "name" -> "f.name " + (dir == null ? "ASC" : dir.toUpperCase(Locale.ROOT)) + ", f.id";
            default -> throw FlowModels.invalid("sort", "Pattern");
        };
    }

    /** API-FLW-02 상세 — FLOW_READ. version 생략 시 초안이 있으면 초안, 없으면 ACTIVE */
    @Transactional(readOnly = true)
    public FlowDetail detail(String flowId, Integer version) {
        roleChecker.require(Permission.FLOW_READ);
        long orgId = roleChecker.currentUser().organizationId();
        FlowRow flow = support.require(orgId, flowId);
        Integer no = version != null ? version : flow.draftVersion() != null ? flow.draftVersion() : flow.activeVersion();
        VersionDetail detail = null;
        if (no != null) {
            VersionRow v = versions.find(orgId, flow.id(), no).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            detail = versionDetail(v);
        }
        Overlay overlay = runtime.findOverlay(orgId, flow.id()).map(o -> new Overlay(o.bypass(), o.debug(), o.revision()))
                .orElse(new Overlay(List.of(), List.of(), 0));
        return new FlowDetail(support.info(flow), detail, overlay, applyStatus(orgId, flow), List.of(), new EmergencyStop(false, null, null));
    }

    VersionDetail versionDetail(VersionRow v) {
        return new VersionDetail(v.versionNo(), v.state(), v.baseVersion(), support.tree(v.definition()), support.tree(v.validation()),
                support.tree(v.changeSummary()), v.memo(), v.hasControlNode(), FlowSupport.user(v.appliedBy(), v.appliedByName()),
                v.appliedAt(), FlowSupport.user(v.createdBy(), v.createdByName()), v.createdAt());
    }

    /**
     * 엔진 인스턴스별 적용 버전: 원천은 엔진 API-FLW-82(최근 10분 보고). 엔진이 응답하지 않으면 EVT-FLW-02로 받아 둔 사본.
     * 모든 인스턴스가 ACTIVE 버전을 보고하면 converged
     */
    ApplyStatus applyStatus(long orgId, FlowRow flow) {
        List<Instance> instances = null;
        try {
            JsonNode status = engine.applyStatus(flow.id());
            if (status != null && status.path("instances").isArray()) {
                instances = new java.util.ArrayList<>();
                for (JsonNode i : status.get("instances").values()) {
                    instances.add(new Instance(i.path("instanceId").asString(""), i.path("appliedVersion").asInt(),
                            i.hasNonNull("reportedAt") ? Instant.parse(i.get("reportedAt").asString()) : null));
                }
            }
        } catch (BusinessException | net.java21.data2flow.core.common.RelayedErrorException | java.time.format.DateTimeParseException ex) {
            instances = null;
        }
        if (instances == null) {
            List<ApplyReport> reports = runtime.listReports(orgId, flow.id(), clock.instant().minus(INSTANCE_WINDOW));
            instances = reports.stream().map(r -> new Instance(r.instanceId(), r.appliedVersion(), r.reportedAt())).toList();
        }
        Integer target = flow.activeVersion();
        boolean converged = target != null && !instances.isEmpty() && instances.stream().allMatch(i -> i.appliedVersion() == target);
        return new ApplyStatus(target, instances, converged);
    }

    /** API-FLW-03 생성 — FLOW_WRITE, v1 DRAFT. 같은 이름 409 FLOW_NAME_DUPLICATED */
    @Transactional
    public SaveResult create(JsonNode body) {
        roleChecker.require(Permission.FLOW_WRITE);
        if (body == null || !body.isObject()) {
            throw FlowModels.invalid("body", "NotNull");
        }
        String name = name(body.get("name"));
        String description = text(body.get("description"), "description", 500);
        String environment = body.hasNonNull("environment") ? enumValue(body.get("environment").asString(""), FlowModels.ENVIRONMENTS,
                "environment") : "PROD";
        return createFlow(name, description, environment, body.get("definition"), null);
    }

    /** 생성 본체(템플릿에서도 씀). 관련 공간은 정의의 대상 공간으로 채운다(공간 범위 사용자가 다시 볼 수 있게) */
    @Transactional
    public SaveResult createFlow(String name, String description, String environment, JsonNode definition, String template) {
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        String raw = definitionText(definition);
        FlowValidator.Result validation = support.validate(orgId, "FLOW", null, definition);
        if (flows.existsName(orgId, name, null)) {
            throw new BusinessException(FlowErrorCode.FLOW_NAME_DUPLICATED);
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            flows.insert(id, orgId, name, description, environment, 1, targetSpaces(definition), user.userId(), now);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(FlowErrorCode.FLOW_NAME_DUPLICATED);
        }
        versions.insert(orgId, id, 1, "DRAFT", null, raw, FlowSupport.hash(raw),
                support.write(new Validation(validation.errors(), validation.warnings())), validation.hasControlNode(), user.userId(), now);
        var event = audits.event(orgId, AUDIT_CREATED).actor(user).target("FLOW", id.toString()).detail("name", name);
        if (template != null) {
            event.detail("template", template);
        }
        audits.record(event);
        return new SaveResult(id.toString(), 1, new Validation(validation.errors(), validation.warnings()));
    }

    /**
     * API-FLW-03 초안 저장 — FLOW_WRITE. baseVersion은 편집을 시작한 버전(초안이 있으면 초안 번호, 없으면 ACTIVE 번호, 둘 다 없으면 0)이고
     * 다르면 409 FLOW_VERSION_CONFLICT. 저장할 때마다 새 번호를 받는다(응답 draftVersion이 다음 baseVersion).
     */
    @Transactional
    public SaveResult saveDraft(String flowId, JsonNode body) {
        roleChecker.require(Permission.FLOW_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        FlowRow flow = support.require(orgId, flowId);
        flows.lockById(orgId, flow.id());
        flow = support.require(orgId, flowId);
        if (body == null || !body.hasNonNull("baseVersion") || !body.get("baseVersion").isIntegralNumber()) {
            throw FlowModels.invalid("baseVersion", "NotNull");
        }
        int base = body.get("baseVersion").asInt();
        int current = flow.draftVersion() != null ? flow.draftVersion() : flow.activeVersion() != null ? flow.activeVersion() : 0;
        if (base != current) {
            throw new BusinessException(FlowErrorCode.FLOW_VERSION_CONFLICT);
        }
        JsonNode definition = body.get("definition");
        String raw = definitionText(definition);
        FlowValidator.Result validation = support.validate(orgId, flow.kind(), flow.id(), definition);
        Instant now = clock.instant();
        int next = versions.maxVersionNo(orgId, flow.id()) + 1;
        if (flow.draftVersion() != null) {
            versions.deleteDraft(orgId, flow.id(), flow.draftVersion());
        }
        versions.insert(orgId, flow.id(), next, "DRAFT", flow.activeVersion(), raw, FlowSupport.hash(raw),
                support.write(new Validation(validation.errors(), validation.warnings())), validation.hasControlNode(), user.userId(), now);
        flows.updateDraftVersion(orgId, flow.id(), next, user.userId(), now);
        audits.record(audits.event(orgId, AUDIT_DRAFT_SAVED).actor(user).target("FLOW", flow.id().toString()).detail("version", next));
        return new SaveResult(flow.id().toString(), next, new Validation(validation.errors(), validation.warnings()));
    }

    /** API-FLW-06 검증 — FLOW_READ. {version} 또는 {definition}. 변경 요약은 지금 ACTIVE 대비 */
    @Transactional(readOnly = true)
    public ValidateResult validate(String flowId, JsonNode body) {
        roleChecker.require(Permission.FLOW_READ);
        long orgId = roleChecker.currentUser().organizationId();
        FlowRow flow = support.require(orgId, flowId);
        JsonNode definition;
        if (body != null && body.hasNonNull("definition")) {
            definition = body.get("definition");
        } else {
            Integer no = body != null && body.hasNonNull("version") ? Integer.valueOf(body.get("version").asInt())
                    : flow.draftVersion() != null ? flow.draftVersion() : flow.activeVersion();
            if (no == null) {
                throw FlowModels.invalid("version", "NotNull");
            }
            VersionRow v = versions.find(orgId, flow.id(), no).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            definition = support.tree(v.definition());
        }
        FlowValidator.Result result = support.validate(orgId, flow.kind(), flow.id(), definition);
        FlowDefinition active = activeDefinition(orgId, flow);
        FlowDiff.Summary summary = result.definition() == null ? null : FlowDiff.summary(active, result.definition(), support.catalog());
        FlowDiff.Risky risky = result.definition() == null ? new FlowDiff.Risky(false, false)
                : FlowDiff.risky(active, result.definition(), support.catalog());
        boolean approval = risky.controlNodesChanged() && settings.settings(orgId).requireApprovalForControlNodes();
        return new ValidateResult(result.errors(), result.warnings(), summary, risky, approval);
    }

    FlowDefinition activeDefinition(long orgId, FlowRow flow) {
        if (flow.activeVersion() == null) {
            return null;
        }
        return versions.find(orgId, flow.id(), flow.activeVersion())
                .flatMap(v -> support.validator().parse(support.tree(v.definition()))).orElse(null);
    }

    /** API-FLW-04 버전 목록(최대 100개, 페이징 없음) — FLOW_READ */
    @Transactional(readOnly = true)
    public List<VersionSummary> versions(String flowId) {
        roleChecker.require(Permission.FLOW_READ);
        long orgId = roleChecker.currentUser().organizationId();
        FlowRow flow = support.require(orgId, flowId);
        return versions.listByFlow(orgId, flow.id()).stream()
                .map(v -> new VersionSummary(v.versionNo(), v.state(), FlowSupport.user(v.appliedBy(), v.appliedByName()), v.appliedAt(),
                        v.memo(), v.hasControlNode(), FlowSupport.user(v.createdBy(), v.createdByName()), v.createdAt()))
                .toList();
    }

    /** API-FLW-04 버전 비교 — FLOW_READ */
    @Transactional(readOnly = true)
    public FlowDiff.Diff diff(String flowId, Integer from, Integer to) {
        roleChecker.require(Permission.FLOW_READ);
        long orgId = roleChecker.currentUser().organizationId();
        FlowRow flow = support.require(orgId, flowId);
        if (from == null || to == null) {
            throw FlowModels.invalid(from == null ? "from" : "to", "NotNull");
        }
        FlowDefinition a = definition(orgId, flow.id(), from);
        FlowDefinition b = definition(orgId, flow.id(), to);
        return FlowDiff.diff(a, b, support.catalog());
    }

    private FlowDefinition definition(long orgId, UUID flowId, int no) {
        VersionRow v = versions.find(orgId, flowId, no).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return support.validator().parse(support.tree(v.definition()))
                .orElseThrow(() -> new BusinessException(FlowErrorCode.FLOW_VALIDATION_FAILED));
    }

    /** API-FLW-10 설정 변경(온 키만, baseVersion 선택) — FLOW_WRITE */
    @Transactional
    public FlowInfo patch(String flowId, JsonNode body) {
        roleChecker.require(Permission.FLOW_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        FlowRow f = support.require(orgId, flowId);
        if (body == null || !body.isObject()) {
            throw FlowModels.invalid("body", "NotNull");
        }
        int base = f.version();
        if (body.hasNonNull("baseVersion") || body.hasNonNull("lockVersion")) {
            JsonNode b = body.hasNonNull("baseVersion") ? body.get("baseVersion") : body.get("lockVersion");
            if (!b.isIntegralNumber()) {
                throw FlowModels.invalid("baseVersion", "Type");
            }
            if (b.asInt() != f.version()) {
                throw new BusinessException(FlowErrorCode.FLOW_VERSION_CONFLICT);
            }
        }
        String name = body.has("name") ? name(body.get("name")) : f.name();
        if (!name.equals(f.name()) && flows.existsName(orgId, name, f.id())) {
            throw new BusinessException(FlowErrorCode.FLOW_NAME_DUPLICATED);
        }
        String purpose = body.has("purpose") ? text(body.get("purpose"), "purpose", 200) : f.purpose();
        String description = body.has("description") ? text(body.get("description"), "description", 4000) : f.description();
        List<String> tags = body.has("tags") ? stringList(body.get("tags"), "tags") : f.tags();
        String pauseMode = body.has("pauseMode") ? enumValue(body.get("pauseMode").asString(""), Set.of("DROP", "BUFFER"), "pauseMode")
                : f.pauseMode();
        boolean autoPause = body.has("autoPauseOnDegraded") ? body.get("autoPauseOnDegraded").asBoolean(false) : f.autoPauseOnDegraded();
        BigDecimal threshold = f.errorRateThreshold();
        if (body.has("errorRateThreshold")) {
            JsonNode t = body.get("errorRateThreshold");
            if (!t.isNumber() || t.asDouble() < 0 || t.asDouble() > 100) {
                throw FlowModels.invalid("errorRateThreshold", "Range");
            }
            threshold = BigDecimal.valueOf(t.asDouble());
        }
        UUID catchFlow = f.catchFlowId();
        if (body.has("catchFlowId")) {
            JsonNode c = body.get("catchFlowId");
            catchFlow = c == null || c.isNull() ? null : FlowSupport.flowId(c.asString(""));
            if (catchFlow != null && flows.findById(orgId, catchFlow).isEmpty()) {
                throw FlowModels.invalid("catchFlowId", "NOT_FOUND");
            }
        }
        Long owner = f.ownerUserId();
        if (body.has("ownerUserId")) {
            JsonNode o = body.get("ownerUserId");
            owner = o == null || o.isNull() ? null : parseLong(o.asString(""), "ownerUserId");
            if (owner != null && !targets.existsActiveUser(orgId, owner)) {
                throw new BusinessException(FlowErrorCode.FLOW_OWNER_INVALID);
            }
        }
        List<Long> related = f.relatedSpaceIds();
        if (body.has("relatedSpaceIds")) {
            related = new ArrayList<>();
            for (String s : stringList(body.get("relatedSpaceIds"), "relatedSpaceIds")) {
                long id = parseLong(s, "relatedSpaceIds");
                if (!targets.existsSpace(orgId, id) || !roleChecker.spaceScope().includes(id)) {
                    throw FlowModels.invalid("relatedSpaceIds", "NOT_FOUND");
                }
                related.add(id);
            }
        }
        int updated;
        try {
            updated = flows.updateSettings(orgId, f.id(), base, name, purpose, description, tags, pauseMode, autoPause, threshold, catchFlow,
                    owner, related, user.userId(), clock.instant());
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(FlowErrorCode.FLOW_NAME_DUPLICATED);
        }
        if (updated == 0) {
            throw new BusinessException(FlowErrorCode.FLOW_VERSION_CONFLICT);
        }
        List<String> fields = new ArrayList<>(body.propertyNames());
        audits.record(audits.event(orgId, AUDIT_UPDATED).actor(user).target("FLOW", f.id().toString()).detail("fields", fields));
        if (body.has("pauseMode") && f.activeVersion() != null) {
            support.flowChanged(orgId, f.id(), f.activeVersion(), false);
        }
        return support.info(flows.findById(orgId, f.id()).orElseThrow());
    }

    /** API-FLW-05 삭제(DRAFT·DISABLED만, 그 밖은 409 FLOW_STATE_CONFLICT) — FLOW_WRITE, 204 */
    @Transactional
    public void delete(String flowId) {
        roleChecker.require(Permission.FLOW_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        FlowRow f = support.require(orgId, flowId);
        flows.lockById(orgId, f.id());
        if (!FlowModels.FROM.get("delete").contains(f.status())) {
            throw new BusinessException(FlowErrorCode.FLOW_STATE_CONFLICT);
        }
        flows.updateStatus(orgId, f.id(), "DELETED", null, user.userId(), clock.instant());
        if (f.activeVersion() != null) {
            support.flowChanged(orgId, f.id(), f.activeVersion(), true);
        }
        audits.record(audits.event(orgId, AUDIT_DELETED).actor(user).target("FLOW", f.id().toString()).detail("name", f.name()));
    }

    /** API-FLW-09 일시정지·재개·비활성 — FLOW_WRITE. 허용하지 않는 전이는 409 FLOW_STATE_CONFLICT */
    @Transactional
    public StatusResult transition(String flowId, String action, JsonNode body) {
        roleChecker.require(Permission.FLOW_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        FlowRow f = support.require(orgId, flowId);
        flows.lockById(orgId, f.id());
        f = support.require(orgId, flowId);
        if (!FlowModels.FROM.get(action).contains(f.status())) {
            throw new BusinessException(FlowErrorCode.FLOW_STATE_CONFLICT);
        }
        if ("resume".equals(action) && f.activeVersion() == null) {
            throw new BusinessException(FlowErrorCode.FLOW_STATE_CONFLICT);
        }
        if ("pause".equals(action) && body != null && body.hasNonNull("mode")) {
            flows.updatePauseMode(orgId, f.id(), enumValue(body.get("mode").asString(""), Set.of("DROP", "BUFFER"), "mode"));
        }
        String next = FlowModels.TO.get(action);
        flows.updateStatus(orgId, f.id(), next, null, user.userId(), clock.instant());
        if (f.activeVersion() != null) {
            support.flowChanged(orgId, f.id(), f.activeVersion(), false);
        }
        String auditAction = switch (action) {
            case "pause" -> "FLOW_PAUSED";
            case "resume" -> "FLOW_RESUMED";
            default -> "FLOW_DISABLED";
        };
        audits.record(audits.event(orgId, auditAction).actor(user).target("FLOW", f.id().toString()).detail("from", f.status()));
        return new StatusResult(f.id().toString(), next);
    }

    private String definitionText(JsonNode definition) {
        if (definition == null || !definition.isObject()) {
            throw FlowModels.invalid("definition", "NotNull");
        }
        JsonNode nodes = definition.get("nodes");
        if (nodes != null && nodes.isArray() && nodes.size() > FlowValidator.MAX_NODES) {
            throw new BusinessException(FlowErrorCode.FLOW_NODE_LIMIT_EXCEEDED);
        }
        String raw = support.write(definition);
        if (FlowSupport.size(raw) > FlowDefinition.MAX_BYTES) {
            throw new BusinessException(FlowErrorCode.FLOW_DEFINITION_TOO_LARGE);
        }
        return raw;
    }

    /** 정의 노드 대상 공간 ID */
    static List<Long> targetSpaces(JsonNode definition) {
        Set<Long> ids = new LinkedHashSet<>();
        JsonNode nodes = definition == null ? null : definition.get("nodes");
        if (nodes != null && nodes.isArray()) {
            for (JsonNode n : nodes.values()) {
                JsonNode space = n.path("config").path("target").get("spaceId");
                if (space != null && !space.isNull()) {
                    try {
                        ids.add(Long.parseLong(space.asString("").strip()));
                    } catch (NumberFormatException ignored) {
                        // 검증에서 TARGET_MISSING
                    }
                }
            }
        }
        return List.copyOf(ids);
    }

    static String name(JsonNode node) {
        String name = node == null || node.isNull() ? "" : node.asString("").strip();
        if (name.isEmpty() || name.length() > 100) {
            throw FlowModels.invalid("name", "Size");
        }
        return name;
    }

    static String text(JsonNode node, String field, int max) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asString("").strip();
        if (value.length() > max) {
            throw FlowModels.invalid(field, "Size");
        }
        return value.isEmpty() ? null : value;
    }

    static List<String> stringList(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw FlowModels.invalid(field, "Type");
        }
        List<String> out = new ArrayList<>();
        node.values().forEach(v -> out.add(v.asString("").strip()));
        return out;
    }

    static String enumValue(String raw, Set<String> allowed, String field) {
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw FlowModels.invalid(field, "Pattern");
        }
        return value;
    }

    static long parseLong(String raw, String field) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException | NullPointerException ex) {
            throw FlowModels.invalid(field, "Pattern");
        }
    }
}
