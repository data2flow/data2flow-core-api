package net.java21.data2flow.core.rule.service;

import net.java21.data2flow.contracts.alarm.AlarmClearReason;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.alarm.domain.AlarmErrorCode;
import net.java21.data2flow.core.alarm.repository.AlarmRepository;
import net.java21.data2flow.core.alarm.service.AlarmService;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.CountedListResponse;
import net.java21.data2flow.core.flow.domain.FlowValidator;
import net.java21.data2flow.core.flow.repository.FlowRepository;
import net.java21.data2flow.core.flow.repository.FlowRepository.FlowRow;
import net.java21.data2flow.core.flow.repository.FlowVersionRepository;
import net.java21.data2flow.core.flow.service.FlowApplyService;
import net.java21.data2flow.core.flow.service.FlowSupport;
import net.java21.data2flow.core.rule.domain.RuleCondition;
import net.java21.data2flow.core.rule.domain.RuleErrorCode;
import net.java21.data2flow.core.rule.domain.RuleLimits;
import net.java21.data2flow.core.rule.dto.RuleDtos.ConvertResult;
import net.java21.data2flow.core.rule.dto.RuleDtos.Draft;
import net.java21.data2flow.core.rule.dto.RuleDtos.RuleDetail;
import net.java21.data2flow.core.rule.dto.RuleDtos.RuleSummary;
import net.java21.data2flow.core.rule.dto.RuleDtos.RuleTemplate;
import net.java21.data2flow.core.rule.dto.RuleDtos.SaveResult;
import net.java21.data2flow.core.rule.dto.RuleDtos.Scope;
import net.java21.data2flow.core.rule.dto.RuleDtos.Stats7d;
import net.java21.data2flow.core.rule.dto.RuleDtos.StatusResult;
import net.java21.data2flow.core.rule.dto.RuleDtos.TemplateDefaults;
import net.java21.data2flow.core.rule.dto.RuleDtos.UserRef;
import net.java21.data2flow.core.rule.dto.RuleDtos.Warning;
import net.java21.data2flow.core.rule.repository.RuleRepository;
import net.java21.data2flow.core.rule.repository.RuleRepository.RuleRow;
import net.java21.data2flow.core.rule.repository.RuleRepository.RuleValues;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 규칙 CRUD·상태·변환(RUL-01·06, API-RUL-01~05·07, BR-RUL-01·06·20·21·24).
 *
 * <p>저장 = 규칙 행 → 표준 플로우 컴파일(flow-engine API-FLW-86, ADR-051) → 내부 플로우(kind=RULE)의 새 버전 → 원자적 적용. 규칙 버전과 플로우 버전은
 * 같은 번호다(1:1). 적용이 검증에서 실패하면 규칙은 ERROR(FLOW_ERROR), 대상 기기가 0대면 ERROR(NO_TARGET)로 저장하고 플로우는 그대로 적용한다
 * (범위에 기기가 들어오면 자동으로 대상이 된다, BR-RUL-06).
 */
@Service
public class RuleService {

    static final Set<String> SCOPE_TYPES = Set.of("DEVICE", "SPACE", "MODEL", "TAG");
    static final Set<String> SEVERITIES = Set.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO");
    static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE", "ERROR");

    private final RuleRepository rules;
    private final FlowRepository flows;
    private final FlowVersionRepository versions;
    private final FlowApplyService apply;
    private final FlowSupport flowSupport;
    private final AlarmRepository alarmRepository;
    private final AlarmService alarms;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;
    private final net.java21.data2flow.core.flow.service.FlowEngineClient engine;

    public RuleService(RuleRepository rules, FlowRepository flows, FlowVersionRepository versions, FlowApplyService apply,
                       FlowSupport flowSupport, AlarmRepository alarmRepository, AlarmService alarms, RoleChecker roleChecker,
                       Audits audits, JsonMapper json, Clock clock, net.java21.data2flow.core.flow.service.FlowEngineClient engine) {
        this.engine = engine;
        this.rules = rules;
        this.flows = flows;
        this.versions = versions;
        this.apply = apply;
        this.flowSupport = flowSupport;
        this.alarmRepository = alarmRepository;
        this.alarms = alarms;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** 요청 본문을 읽은 값 */
    public record RuleInput(String name, String templateKey, String scopeType, List<String> scopeIds, boolean includeChildren,
                            JsonNode condition, JsonNode timeCondition, String severity, String titleTemplate, boolean autoClear,
                            Long policyId, Integer baseVersion, RuleCondition.Summary summary) {
    }

    // ------------------------------------------------------------------ 조회

    /** API-RUL-01 — RULE_READ. 오류 규칙이 위에, counts {total, limit} */
    @Transactional(readOnly = true)
    public CountedListResponse<RuleSummary> list(String keyword, String status, String severity, String spaceId, String templateKey,
                                                 Integer page, Integer size) {
        roleChecker.require(Permission.RULE_READ);
        long orgId = roleChecker.currentUser().organizationId();
        List<String> statuses = status == null || status.isBlank() ? null : splitEnum(status, STATUSES, "status");
        String sev = severity == null || severity.isBlank() ? null : enumValue(severity, SEVERITIES, "severity");
        Long space = spaceId == null || spaceId.isBlank() ? null : parseId(spaceId, "spaceId");
        SpaceScope scope = roleChecker.spaceScope();
        Instant since = clock.instant().minus(Duration.ofDays(7));
        RuleRepository.Search search = new RuleRepository.Search(orgId, keyword == null || keyword.isBlank() ? null : keyword.strip(),
                statuses, sev, templateKey == null || templateKey.isBlank() ? null : templateKey.strip(), space, since,
                scope.unrestricted() ? null : scope.allowedSpaceIds());
        PageParams params = PageParams.of(page, size);
        List<RuleSummary> items = rules.search(search, params.size(), params.offset()).stream().map(this::summary).toList();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("total", rules.countLive(orgId));
        counts.put("limit", RuleLimits.MAX_RULES);
        return CountedListResponse.of(ListApiResponse.of(params, items, rules.count(search)), counts);
    }

    @Transactional(readOnly = true)
    public RuleDetail get(long ruleId) {
        roleChecker.require(Permission.RULE_READ);
        return detail(visible(roleChecker.currentUser().organizationId(), ruleId));
    }

    /** API-RUL-05 — RULE_READ */
    @Transactional(readOnly = true)
    public List<RuleTemplate> templates() {
        roleChecker.require(Permission.RULE_READ);
        long orgId = roleChecker.currentUser().organizationId();
        return rules.listTemplates(orgId).stream().map(t -> {
            JsonNode condition = json.readTree(t.conditionTemplate());
            Set<String> metrics = new LinkedHashSet<>(RuleCondition.validate(condition).metrics());
            return new RuleTemplate(t.templateKey(), t.name(), t.description(), t.category(),
                    new TemplateDefaults(condition, t.defaultSeverity(), t.name()), json.readTree(t.paramsSchema()), List.copyOf(metrics),
                    t.builtin());
        }).toList();
    }

    // ------------------------------------------------------------------ 저장

    /** API-RUL-02 — RULE_WRITE. 201 */
    @Transactional
    public SaveResult create(JsonNode body) {
        roleChecker.require(Permission.RULE_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        RuleInput in = parse(orgId, body, false);
        checkScope(orgId, in);
        if (rules.existsName(orgId, in.name(), null)) {
            throw new BusinessException(RuleErrorCode.RULE_NAME_DUPLICATED);
        }
        if (!RuleLimits.canCreate(rules.countLive(orgId))) {
            throw new BusinessException(RuleErrorCode.RULE_LIMIT_EXCEEDED);
        }
        List<Long> targets = targets(orgId, in);
        Instant now = clock.instant();
        UUID flowId = UUID.randomUUID();
        insertFlow(flowId, orgId, in, user.userId(), now);
        long ruleId;
        try {
            ruleId = rules.insert(values(orgId, in, targets.size()), flowId, "ACTIVE", null, user.userId(), now);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(RuleErrorCode.RULE_NAME_DUPLICATED);
        }
        flows.linkRule(orgId, flowId, ruleId);
        Outcome outcome = compileAndApply(orgId, ruleId, 1, flowId, in, targets.size(), user.userId(), true);
        audits.record(audits.event(orgId, "RULE_CREATED").actor(user).target("RULE", Long.toString(ruleId)).detail("name", in.name())
                .detail("status", outcome.status()).detail("targetCount", targets.size()));
        return new SaveResult(Long.toString(ruleId), 1, outcome.status(), outcome.reason(), targets.size(), flowId.toString(),
                outcome.warnings());
    }

    /** API-RUL-03 — RULE_WRITE, baseVersion 필수(다르면 409 VERSION_CONFLICT). CONVERTED는 409 RULE_STATE_CONFLICT */
    @Transactional
    public SaveResult update(long ruleId, JsonNode body) {
        roleChecker.require(Permission.RULE_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        RuleRow current = visible(orgId, ruleId);
        rules.lockById(orgId, ruleId);
        current = visible(orgId, ruleId);
        if ("CONVERTED".equals(current.status())) {
            throw new BusinessException(RuleErrorCode.RULE_STATE_CONFLICT);
        }
        RuleInput in = parse(orgId, body, true);
        checkScope(orgId, in);
        if (in.baseVersion() != current.version()) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        if (rules.existsName(orgId, in.name(), ruleId)) {
            throw new BusinessException(RuleErrorCode.RULE_NAME_DUPLICATED);
        }
        List<Long> before = rules.listTargetDevices(orgId, current.scopeType(), ids(current.scopeIds()), current.includeChildren(),
                metricsOf(current.condition()), RuleLimits.MAX_TARGETS + 1);
        List<Long> targets = targets(orgId, in);
        Instant now = clock.instant();
        int changed;
        try {
            changed = rules.update(ruleId, in.baseVersion(), values(orgId, in, targets.size()), user.userId(), now);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(RuleErrorCode.RULE_NAME_DUPLICATED);
        }
        if (changed == 0) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        int version = current.version() + 1;
        flows.updateRuleFlowName(orgId, current.flowId(), flowName(in.name()), relatedSpaces(in));
        boolean applyNow = !"INACTIVE".equals(current.status());
        Outcome outcome = compileAndApply(orgId, ruleId, version, current.flowId(), in, targets.size(), user.userId(), applyNow);
        if (!applyNow) {
            rules.updateStatus(orgId, ruleId, "INACTIVE", null, user.userId(), now);
            outcome = new Outcome("INACTIVE", null, outcome.warnings());
        }
        Set<Long> remaining = new HashSet<>(targets);
        List<Long> removed = before.stream().filter(d -> !remaining.contains(d)).toList();
        clearRemovedTargets(orgId, ruleId, removed, now);
        audits.record(audits.event(orgId, "RULE_UPDATED").actor(user).target("RULE", Long.toString(ruleId)).detail("version", version)
                .detail("status", outcome.status()));
        return new SaveResult(Long.toString(ruleId), version, outcome.status(), outcome.reason(), targets.size(),
                current.flowId().toString(), outcome.warnings());
    }

    /** API-FLW-86 {@code rule} = API-RUL-02 요청 모양 */
    Map<String, Object> compileRequest(RuleInput in) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("name", in.name());
        rule.put("templateKey", in.templateKey());
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("type", in.scopeType());
        scope.put("ids", in.scopeIds());
        scope.put("includeChildren", in.includeChildren());
        rule.put("scope", scope);
        rule.put("condition", in.condition());
        rule.put("timeCondition", in.timeCondition());
        rule.put("severity", in.severity());
        rule.put("titleTemplate", in.titleTemplate());
        rule.put("autoClear", in.autoClear());
        return rule;
    }

    /** 결과 상태·사유·경고 */
    public record Outcome(String status, String reason, List<Warning> warnings) {
    }

    /** 컴파일 → 플로우 새 버전(번호 = 규칙 버전) → 적용. 적용 실패면 ERROR(FLOW_ERROR), 대상 0대면 ERROR(NO_TARGET) */
    Outcome compileAndApply(long orgId, long ruleId, int version, UUID flowId, RuleInput in, int targetCount, long userId, boolean applyNow) {
        JsonNode compiled = engine.compileRule(orgId, ruleId, compileRequest(in));
        JsonNode definition = compiled == null ? null : compiled.get("definition");
        if (definition == null || !definition.isObject()) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        String raw = json.writeValueAsString(definition);
        Instant now = clock.instant();
        int versionNo = Math.max(version, versions.maxVersionNo(orgId, flowId) + 1);
        versions.insert(orgId, flowId, versionNo, "DRAFT", null, raw, FlowSupport.hash(raw), null, false, userId, now);
        List<Warning> warnings = new ArrayList<>();
        if (!applyNow) {
            return new Outcome("INACTIVE", null, warnings);
        }
        List<FlowValidator.Issue> errors = apply.applyRuleVersion(orgId, flowId, versionNo, userId);
        String status = "ACTIVE";
        String reason = null;
        if (!errors.isEmpty()) {
            status = "ERROR";
            reason = "FLOW_ERROR";
            errors.forEach(e -> warnings.add(new Warning(e.code(), e.field(), e.message())));
        } else if (targetCount == 0) {
            status = "ERROR";
            reason = "NO_TARGET";
            warnings.add(new Warning("NO_TARGET", "scope", "범위에 조건의 측정 항목을 내는 기기가 없습니다"));
        }
        rules.updateStatus(orgId, ruleId, status, reason, null, now);
        return new Outcome(status, reason, warnings);
    }

    // ------------------------------------------------------------------ 상태·삭제·변환(API-RUL-04)

    @Transactional
    public StatusResult activate(long ruleId) {
        roleChecker.require(Permission.RULE_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        RuleRow r = lockedVisible(orgId, ruleId);
        if (!Set.of("INACTIVE", "ERROR").contains(r.status())) {
            throw new BusinessException(RuleErrorCode.RULE_STATE_CONFLICT);
        }
        RuleInput in = input(r);
        List<Long> targets = targets(orgId, in);
        rules.updateTargetCount(orgId, ruleId, targets.size());
        FlowRow flow = flows.findById(orgId, r.flowId()).orElseThrow();
        Outcome outcome;
        if (flow.activeVersion() != null && flow.activeVersion() == r.version()) {
            flows.updateStatus(orgId, flow.id(), "ACTIVE", null, user.userId(), clock.instant());
            flowSupport.flowChanged(orgId, flow.id(), flow.activeVersion(), false);
            outcome = targets.isEmpty() ? new Outcome("ERROR", "NO_TARGET", List.of()) : new Outcome("ACTIVE", null, List.of());
            rules.updateStatus(orgId, ruleId, outcome.status(), outcome.reason(), user.userId(), clock.instant());
        } else {
            outcome = compileAndApply(orgId, ruleId, r.version(), r.flowId(), in, targets.size(), user.userId(), true);
        }
        audits.record(audits.event(orgId, "RULE_ACTIVATED").actor(user).target("RULE", Long.toString(ruleId)));
        return new StatusResult(Long.toString(ruleId), outcome.status(), outcome.reason());
    }

    /** 비활성화: 새 알람 없음, 열린 알람은 그대로(TC-RUL-108). 플로우는 DISABLED */
    @Transactional
    public StatusResult deactivate(long ruleId) {
        roleChecker.require(Permission.RULE_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        RuleRow r = lockedVisible(orgId, ruleId);
        if (!Set.of("ACTIVE", "ERROR").contains(r.status())) {
            throw new BusinessException(RuleErrorCode.RULE_STATE_CONFLICT);
        }
        disableFlow(orgId, r.flowId(), user.userId(), false);
        rules.updateStatus(orgId, ruleId, "INACTIVE", null, user.userId(), clock.instant());
        audits.record(audits.event(orgId, "RULE_DEACTIVATED").actor(user).target("RULE", Long.toString(ruleId)));
        return new StatusResult(Long.toString(ruleId), "INACTIVE", null);
    }

    /** 삭제(DELETED). clearOpenAlarms(기본 true)면 열린 알람을 RULE_DELETED로 해제 */
    @Transactional
    public void delete(long ruleId, Boolean clearOpenAlarms) {
        roleChecker.require(Permission.RULE_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        RuleRow r = lockedVisible(orgId, ruleId);
        if ("CONVERTED".equals(r.status())) {
            throw new BusinessException(RuleErrorCode.RULE_STATE_CONFLICT);
        }
        disableFlow(orgId, r.flowId(), user.userId(), true);
        Instant now = clock.instant();
        rules.updateStatus(orgId, ruleId, "DELETED", null, user.userId(), now);
        if (clearOpenAlarms == null || clearOpenAlarms) {
            for (Long alarmId : alarmRepository.listOpenByRule(orgId, ruleId)) {
                alarmRepository.lockById(orgId, alarmId).filter(AlarmRepository.AlarmRow::open)
                        .ifPresent(a -> alarms.clear(a, AlarmClearReason.RULE_DELETED, null, now, "USER", user.userId(), null));
            }
        }
        audits.record(audits.event(orgId, "RULE_DELETED").actor(user).target("RULE", Long.toString(ruleId)).detail("name", r.name()));
    }

    /** 플로우로 변환(BR-RUL-24): 같은 동작의 일반 플로우(kind=FLOW), 규칙은 CONVERTED(목록에서 숨김). 열린 알람은 그대로 이어진다 */
    @Transactional
    public ConvertResult convert(long ruleId) {
        roleChecker.require(Permission.RULE_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        RuleRow r = lockedVisible(orgId, ruleId);
        if ("CONVERTED".equals(r.status())) {
            throw new BusinessException(RuleErrorCode.RULE_STATE_CONFLICT);
        }
        Instant now = clock.instant();
        flows.updateKind(orgId, r.flowId(), "FLOW", user.userId(), now);
        rules.updateStatus(orgId, ruleId, "CONVERTED", null, user.userId(), now);
        flows.findById(orgId, r.flowId()).filter(f -> f.activeVersion() != null)
                .ifPresent(f -> flowSupport.flowChanged(orgId, f.id(), f.activeVersion(), false));
        audits.record(audits.event(orgId, "RULE_CONVERTED").actor(user).target("RULE", Long.toString(ruleId))
                .detail("flowId", r.flowId().toString()));
        return new ConvertResult(r.flowId().toString());
    }

    /** API-RUL-07 차트 기준선 → 규칙 폼 기본값(저장하지 않음) — RULE_WRITE */
    @Transactional(readOnly = true)
    public Draft draftFromChart(JsonNode body) {
        roleChecker.require(Permission.RULE_WRITE);
        long orgId = roleChecker.currentUser().organizationId();
        if (body == null || !body.isObject()) {
            throw invalid("body", "NotNull");
        }
        String metric = body.path("metric").asString("").strip();
        if (metric.isEmpty()) {
            throw invalid("metric", "NotBlank");
        }
        if (!body.path("value").isNumber()) {
            throw invalid("value", "NotNull");
        }
        String op = body.path("op").asString(">");
        if (!Set.of(">", ">=", "<", "<=").contains(op)) {
            throw invalid("op", "Pattern");
        }
        JsonNode target = body.path("target");
        Scope scope;
        if (target.path("deviceIds").isArray() && !target.path("deviceIds").isEmpty()) {
            List<String> ids = new ArrayList<>();
            target.path("deviceIds").values().forEach(v -> ids.add(v.asString()));
            Map<Long, Long> spaces = rules.findDeviceSpaces(orgId, ids.stream().map(s -> parseId(s, "target.deviceIds")).toList());
            if (spaces.size() != ids.size() || spaces.values().stream().anyMatch(s -> !roleChecker.spaceScope().includes(s))) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
            scope = new Scope("DEVICE", ids, true, ids.size());
        } else if (target.hasNonNull("spaceId")) {
            long space = parseId(target.get("spaceId").asString(), "target.spaceId");
            if (rules.listExistingSpaces(orgId, List.of(space)).isEmpty() || !roleChecker.spaceScope().includes(space)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
            scope = new Scope("SPACE", List.of(Long.toString(space)), true, 0);
        } else {
            throw invalid("target", "NotNull");
        }
        ObjectNode condition = json.createObjectNode();
        condition.put("kind", "threshold");
        condition.put("metric", metric);
        condition.put("op", op);
        condition.set("value", body.get("value"));
        condition.put("for", "PT5M");
        String severity = "MAJOR";
        return new Draft(metric + " " + op + " " + body.get("value").asString(), null, scope, condition, severity,
                "{{space.name}} " + metric + " " + op + " " + body.get("value").asString(), true);
    }

    // ------------------------------------------------------------------ 내부

    private void disableFlow(long orgId, UUID flowId, long userId, boolean delete) {
        flows.findById(orgId, flowId).ifPresent(f -> {
            Instant now = clock.instant();
            if (Set.of("ACTIVE", "PAUSED", "DEGRADED").contains(f.status())) {
                flows.updateStatus(orgId, f.id(), "DISABLED", null, userId, now);
            }
            if (delete) {
                flows.updateStatus(orgId, f.id(), "DELETED", null, userId, now);
            }
            if (f.activeVersion() != null) {
                flowSupport.flowChanged(orgId, f.id(), f.activeVersion(), delete);
            }
        });
    }

    private void clearRemovedTargets(long orgId, long ruleId, List<Long> removed, Instant now) {
        if (removed.isEmpty()) {
            return;
        }
        Set<Long> gone = new HashSet<>(removed);
        for (Map.Entry<Long, Long> e : rules.listOpenAlarmDevices(orgId, ruleId)) {
            if (gone.contains(e.getValue())) {
                alarmRepository.lockById(orgId, e.getKey()).filter(AlarmRepository.AlarmRow::open)
                        .ifPresent(a -> alarms.clear(a, AlarmClearReason.RULE_SCOPE_CHANGED, null, now, "SYSTEM", null, null));
            }
        }
    }

    private void insertFlow(UUID flowId, long orgId, RuleInput in, long userId, Instant now) {
        try {
            flows.insertRuleFlow(flowId, orgId, flowName(in.name()), relatedSpaces(in), userId, now);
        } catch (DuplicateKeyException ex) {
            flows.insertRuleFlow(flowId, orgId, flowName(in.name() + " " + flowId.toString().substring(0, 8)), relatedSpaces(in), userId, now);
        }
    }

    static String flowName(String ruleName) {
        String name = "[규칙] " + ruleName;
        return name.length() <= 100 ? name : name.substring(0, 100);
    }

    static List<Long> relatedSpaces(RuleInput in) {
        if (!"SPACE".equals(in.scopeType())) {
            return List.of();
        }
        return in.scopeIds().stream().map(Long::parseLong).toList();
    }

    /** 범위 대상(한도 5,000 넘으면 400) */
    List<Long> targets(long orgId, RuleInput in) {
        List<Long> devices = rules.listTargetDevices(orgId, in.scopeType(), in.scopeIds(), in.includeChildren(), in.summary().metrics(),
                RuleLimits.MAX_TARGETS + 1);
        if (!RuleLimits.targetsWithin(devices.size())) {
            throw new BusinessException(RuleErrorCode.RULE_TARGET_LIMIT_EXCEEDED);
        }
        return devices;
    }

    /** 범위 ID가 이 조직에 있고 사용자 공간 범위 안인가(아니면 404). MODEL·TAG 범위는 공간 범위가 제한된 사용자에게 허용하지 않는다(403) */
    void checkScope(long orgId, RuleInput in) {
        SpaceScope scope = roleChecker.spaceScope();
        switch (in.scopeType()) {
            case "SPACE" -> {
                List<Long> ids = in.scopeIds().stream().map(s -> parseId(s, "scope.ids")).toList();
                List<Long> existing = rules.listExistingSpaces(orgId, ids);
                if (existing.size() != new HashSet<>(ids).size() || ids.stream().anyMatch(id -> !scope.includes(id))) {
                    throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
                }
            }
            case "DEVICE" -> {
                List<Long> ids = in.scopeIds().stream().map(s -> parseId(s, "scope.ids")).toList();
                Map<Long, Long> spaces = rules.findDeviceSpaces(orgId, ids);
                if (spaces.size() != new HashSet<>(ids).size()
                        || (!scope.unrestricted() && spaces.values().stream().anyMatch(s -> s == null || !scope.includes(s)))) {
                    throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
                }
            }
            default -> {
                if (!scope.unrestricted()) {
                    throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
                }
            }
        }
        if (in.policyId() != null && !rules.existsPolicy(orgId, in.policyId())) {
            throw new BusinessException(AlarmErrorCode.POLICY_NOT_FOUND);
        }
    }

    RuleInput parse(long orgId, JsonNode body, boolean update) {
        if (body == null || !body.isObject()) {
            throw invalid("body", "NotNull");
        }
        String name = body.path("name").asString("").strip();
        if (name.isEmpty() || name.length() > 100) {
            throw invalid("name", "Size");
        }
        String templateKey = body.hasNonNull("templateKey") ? body.get("templateKey").asString("").strip() : null;
        if (templateKey != null && (templateKey.isEmpty() || templateKey.length() > 64)) {
            throw invalid("templateKey", "Size");
        }
        JsonNode scope = body.path("scope");
        String scopeType = scope.path("type").asString("").toUpperCase(Locale.ROOT);
        if (!SCOPE_TYPES.contains(scopeType)) {
            throw invalid("scope.type", "Pattern");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode v : scope.path("ids").values()) {
            String id = v.asString("").strip();
            if (id.isEmpty() || id.length() > 64) {
                throw invalid("scope.ids", "Size");
            }
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            throw invalid("scope.ids", "NotEmpty");
        }
        if (ids.size() > RuleLimits.MAX_TARGETS) {
            throw new BusinessException(RuleErrorCode.RULE_TARGET_LIMIT_EXCEEDED);
        }
        boolean includeChildren = !scope.has("includeChildren") || scope.get("includeChildren").asBoolean(true);
        JsonNode condition = body.get("condition");
        RuleCondition.Summary summary = RuleCondition.validate(condition);
        JsonNode timeCondition = body.get("timeCondition");
        if (timeCondition != null && !timeCondition.isNull()) {
            validateTime(timeCondition);
        } else {
            timeCondition = null;
        }
        String severity = enumValue(body.path("severity").asString(""), SEVERITIES, "severity");
        String title = body.path("titleTemplate").asString("").strip();
        if (title.isEmpty() || title.length() > 200) {
            throw invalid("titleTemplate", "Size");
        }
        boolean autoClear = !body.has("autoClear") || body.get("autoClear").asBoolean(true);
        Long policyId = body.hasNonNull("policyId") ? parseId(body.get("policyId").asString(), "policyId") : null;
        Integer baseVersion = null;
        if (update) {
            if (!body.hasNonNull("baseVersion") || !body.get("baseVersion").isIntegralNumber()) {
                throw invalid("baseVersion", "NotNull");
            }
            baseVersion = body.get("baseVersion").asInt();
        }
        return new RuleInput(name, templateKey, scopeType, List.copyOf(ids), includeChildren, condition, timeCondition, severity, title,
                autoClear, policyId, baseVersion, summary);
    }

    static void validateTime(JsonNode t) {
        if (!t.isObject()) {
            throw invalid("timeCondition", "Type");
        }
        for (JsonNode d : t.path("days").values()) {
            if (!d.isIntegralNumber() || d.asInt() < 1 || d.asInt() > 7) {
                throw invalid("timeCondition.days", "Range");
            }
        }
        for (String f : List.of("from", "to")) {
            if (t.hasNonNull(f) && !t.get(f).asString("").matches("([01]\\d|2[0-3]):[0-5]\\d|24:00")) {
                throw invalid("timeCondition." + f, "Pattern");
            }
        }
        if (t.hasNonNull("spaceSchedule") && !Set.of("INSIDE", "OUTSIDE").contains(t.get("spaceSchedule").asString("").toUpperCase(Locale.ROOT))) {
            throw invalid("timeCondition.spaceSchedule", "Pattern");
        }
    }

    RuleInput input(RuleRow r) {
        JsonNode condition = json.readTree(r.condition());
        return new RuleInput(r.name(), r.templateKey(), r.scopeType(), ids(r.scopeIds()), r.includeChildren(), condition,
                r.timeCondition() == null ? null : json.readTree(r.timeCondition()), r.severity(), r.titleTemplate(), r.autoClear(),
                r.policyId(), r.version(), RuleCondition.validate(condition));
    }

    RuleValues values(long orgId, RuleInput in, int targetCount) {
        return new RuleValues(orgId, in.name(), in.templateKey(), in.scopeType(), json.writeValueAsString(in.scopeIds()),
                in.includeChildren(), json.writeValueAsString(in.condition()),
                in.timeCondition() == null ? null : json.writeValueAsString(in.timeCondition()), in.severity(), in.titleTemplate(),
                in.autoClear(), in.policyId(), targetCount);
    }

    List<String> ids(String raw) {
        List<String> out = new ArrayList<>();
        json.readTree(raw).values().forEach(v -> out.add(v.asString()));
        return out;
    }

    Set<String> metricsOf(String condition) {
        try {
            return RuleCondition.validate(json.readTree(condition)).metrics();
        } catch (BusinessException ex) {
            return Set.of();
        }
    }

    /** 보이는 규칙(삭제·남의 것·범위 밖은 404 RULE_NOT_FOUND) */
    RuleRow visible(long orgId, long ruleId) {
        RuleRow r = rules.findById(orgId, ruleId, clock.instant().minus(Duration.ofDays(7)))
                .orElseThrow(() -> new BusinessException(RuleErrorCode.RULE_NOT_FOUND));
        SpaceScope scope = roleChecker.spaceScope();
        if (scope.unrestricted()) {
            return r;
        }
        List<String> ids = ids(r.scopeIds());
        boolean ok = switch (r.scopeType()) {
            case "SPACE" -> ids.stream().anyMatch(id -> scope.includes(Long.parseLong(id)));
            case "DEVICE" -> {
                Map<Long, Long> spaces = rules.findDeviceSpaces(orgId, ids.stream().map(Long::parseLong).toList());
                yield !spaces.isEmpty() && spaces.values().stream().allMatch(s -> s != null && scope.includes(s));
            }
            default -> false;
        };
        if (!ok) {
            throw new BusinessException(RuleErrorCode.RULE_NOT_FOUND);
        }
        return r;
    }

    private RuleRow lockedVisible(long orgId, long ruleId) {
        visible(orgId, ruleId);
        rules.lockById(orgId, ruleId);
        return visible(orgId, ruleId);
    }

    RuleSummary summary(RuleRow r) {
        return new RuleSummary(Long.toString(r.id()), r.name(), r.status(), r.errorReason(), RuleCondition.summary(json.readTree(r.condition())),
                new Scope(r.scopeType(), ids(r.scopeIds()), r.includeChildren(), r.targetCount()), r.severity(), r.templateKey(),
                new Stats7d(r.raised7d()), r.openAlarms(), user(r.updatedBy(), r.updatedByName()), r.updatedAt());
    }

    RuleDetail detail(RuleRow r) {
        JsonNode condition = json.readTree(r.condition());
        return new RuleDetail(Long.toString(r.id()), r.name(), r.templateKey(), r.status(), r.errorReason(),
                new Scope(r.scopeType(), ids(r.scopeIds()), r.includeChildren(), r.targetCount()), condition, RuleCondition.summary(condition),
                r.timeCondition() == null ? null : json.readTree(r.timeCondition()), r.severity(), r.titleTemplate(), r.autoClear(),
                r.policyId() == null ? null : Long.toString(r.policyId()), r.flowId().toString(), r.version(), new Stats7d(r.raised7d()),
                r.openAlarms(), user(r.updatedBy(), r.updatedByName()), r.createdAt(), r.updatedAt());
    }

    static UserRef user(long id, String name) {
        return id == 0 ? null : new UserRef(Long.toString(id), name);
    }

    static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }

    static String enumValue(String raw, Set<String> allowed, String field) {
        String v = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            throw invalid(field, "Pattern");
        }
        return v;
    }

    static List<String> splitEnum(String raw, Set<String> allowed, String field) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                out.add(enumValue(part, allowed, field));
            }
        }
        return out;
    }

    static long parseId(String raw, String field) {
        try {
            long v = Long.parseLong(raw == null ? "" : raw.strip());
            if (v < 1) {
                throw invalid(field, "Pattern");
            }
            return v;
        } catch (NumberFormatException ex) {
            throw invalid(field, "Pattern");
        }
    }
}
