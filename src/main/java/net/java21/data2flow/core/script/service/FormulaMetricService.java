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
import net.java21.data2flow.core.catalog.event.CatalogEvents;
import net.java21.data2flow.core.common.FailureWithResponse;
import net.java21.data2flow.core.script.domain.ScriptErrorCode;
import net.java21.data2flow.core.script.domain.ScriptM5Rules;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.FormulaError;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.FormulaPreviewRequest;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.FormulaRequest;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.FormulaResponse;
import net.java21.data2flow.core.script.repository.FormulaMetricRepository;
import net.java21.data2flow.core.script.repository.FormulaMetricRepository.FormulaFields;
import net.java21.data2flow.core.script.repository.FormulaMetricRepository.FormulaRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 수식 파생 측정 항목(SCR-01.06, BR-SCR-19, AT-SCR-06.1~3): CRUD API-SCR-20, 미리 보기 API-SCR-21. 수식은 저장 전에 pipeline이 같은
 * 샌드박스용 JS 식으로 컴파일한다(API-SCR-37, 오타·문법 → 400 SCRIPT_FORMULA_INVALID + 위치). 결과 키는 파생 측정 항목으로 등록하고,
 * 이미 있는 측정 항목·수식 키와 겹치면 409 SCRIPT_FORMULA_KEY_CONFLICT. 바뀌면 실행 묶음(API-SCR-32 formulaMetrics)을 다시 읽게
 * EVT-SCR-01을 낸다 — pipeline은 다음 메시지부터 TRANSFORM 마지막에 실행한다.
 */
@Service
public class FormulaMetricService {

    static final String AUDIT_CHANGED = "FORMULA_METRIC_CHANGED";
    static final Set<String> TARGET_TYPES = Set.of("MODEL", "DEVICE", "SPACE");
    static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");
    /** 컴파일 결과에 있으면 안 되는 식별자(샌드박스 금지 목록과 같음, SCR-api §3.4) */
    static final java.util.regex.Pattern FORBIDDEN_JS = java.util.regex.Pattern.compile(
            "\\b(require|import|fetch|XMLHttpRequest|WebSocket|eval|Function|setTimeout|setInterval|process|Java|Polyglot|globalThis)\\b");

    private final RoleChecker roleChecker;
    private final FormulaMetricRepository formulas;
    private final PipelineScriptOpsClient pipeline;
    private final ScriptSupport support;
    private final CatalogEvents catalogEvents;
    private final Audits audits;
    private final TransactionTemplate tx;
    private final Clock clock;

    public FormulaMetricService(RoleChecker roleChecker, FormulaMetricRepository formulas, PipelineScriptOpsClient pipeline,
                                ScriptSupport support, CatalogEvents catalogEvents, Audits audits, TransactionTemplate tx, Clock clock) {
        this.roleChecker = roleChecker;
        this.formulas = formulas;
        this.pipeline = pipeline;
        this.support = support;
        this.catalogEvents = catalogEvents;
        this.audits = audits;
        this.tx = tx;
        this.clock = clock;
    }

    public ListApiResponse<FormulaResponse> list(String targetType, String targetId, Integer page, Integer size) {
        roleChecker.require(Permission.SCRIPT_READ);
        long org = roleChecker.currentUser().organizationId();
        String type = targetType == null || targetType.isBlank() ? null : targetType.strip().toUpperCase(Locale.ROOT);
        if (type != null && !TARGET_TYPES.contains(type)) {
            throw invalid("targetType", "Enum");
        }
        String target = targetId == null || targetId.isBlank() ? null : resolveTarget(org, type == null ? "DEVICE" : type, targetId, false);
        PageParams params = PageParams.of(page, size);
        return ListApiResponse.of(params, formulas.list(org, type, target, params.size(), params.offset()).stream()
                .map(FormulaMetricService::toResponse).toList(), formulas.count(org, type, target));
    }

    /** API-SCR-20 생성 — 201 */
    public FormulaResponse create(FormulaRequest r) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        Validated v = validate(org, r, null);
        String compiled = compile(org, v.expression());
        Instant now = clock.instant();
        long id = tx.execute(status -> {
            if (formulas.countByOrganization(org) >= ScriptM5Rules.MAX_FORMULAS) {
                throw new BusinessException(ScriptErrorCode.SCRIPT_QUOTA_EXCEEDED);
            }
            requireKeyFree(org, v.resultKey(), null);
            long created = formulas.insert(org, fields(v, compiled), user.userId(), now);
            formulas.insertDerivedMetric(org, v.resultKey(), v.displayName(), v.unit(), user.userId(), now)
                    .ifPresent(metricId -> catalogEvents.metricChanged(org, metricId, 0));
            support.runtimeChanged(org, created, 0, false);
            audit(org, user, created, "CREATED", v);
            return created;
        });
        return toResponse(formulas.findById(org, id).orElseThrow());
    }

    /** API-SCR-20 수정(baseVersion 필수, 다르면 409 VERSION_CONFLICT) */
    public FormulaResponse update(long formulaId, FormulaRequest r) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        FormulaRow before = load(org, formulaId);
        if (r == null || r.baseVersion() == null) {
            throw invalid("baseVersion", "NotNull");
        }
        Validated v = validate(org, r, before);
        String compiled = v.expression().equals(before.expression()) ? before.compiledJs() : compile(org, v.expression());
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            if (!v.resultKey().equals(before.resultKey())) {
                requireKeyFree(org, v.resultKey(), formulaId);
            }
            int version = formulas.update(org, formulaId, r.baseVersion(), fields(v, compiled), user.userId(), now)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.VERSION_CONFLICT));
            if (!v.resultKey().equals(before.resultKey())) {
                formulas.insertDerivedMetric(org, v.resultKey(), v.displayName(), v.unit(), user.userId(), now)
                        .ifPresent(metricId -> catalogEvents.metricChanged(org, metricId, 0));
            }
            support.runtimeChanged(org, formulaId, version, false);
            audit(org, user, formulaId, "UPDATED", v);
        });
        return toResponse(formulas.findById(org, formulaId).orElseThrow());
    }

    /** API-SCR-20 삭제 — 204. 이미 저장된 파생 값과 측정 항목은 남긴다 */
    public void delete(long formulaId) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        FormulaRow before = load(org, formulaId);
        tx.executeWithoutResult(status -> {
            formulas.delete(org, formulaId);
            support.runtimeChanged(org, formulaId, before.version() + 1L, true);
            audits.record(audits.event(org, AUDIT_CHANGED).actor(user).target("FORMULA_METRIC", Long.toString(formulaId))
                    .detail("op", "DELETED").detail("resultKey", before.resultKey()));
        });
    }

    /**
     * API-SCR-21 미리 보기: 대상의 대표 기기(DEVICE는 그 기기, MODEL·SPACE는 가장 최근에 수신한 기기)의 지난 N시간(1~24)에 수식을 적용한
     * 결과(pipeline API-SCR-38, 저장 없음). 수식 오류는 400 SCRIPT_FORMULA_INVALID(pipeline 응답 그대로)
     */
    public JsonNode preview(FormulaPreviewRequest r) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        long org = roleChecker.currentUser().organizationId();
        if (r == null || r.expression() == null || r.expression().isBlank() || r.expression().length() > 500) {
            throw invalid("expression", "Size");
        }
        int hours = r.hours() == null ? 24 : r.hours();
        if (hours < 1 || hours > 24) {
            throw invalid("hours", "Range");
        }
        String type = r.targetType() == null ? "" : r.targetType().strip().toUpperCase(Locale.ROOT);
        if (!TARGET_TYPES.contains(type)) {
            throw invalid("targetType", "Enum");
        }
        long target = Long.parseLong(resolveTarget(org, type, r.targetId(), true));
        long device = formulas.findSampleDevice(org, type, target).orElseThrow(() -> invalid("targetId", "NO_DEVICE"));
        return pipeline.previewFormula(org, r.expression().strip(), device, hours);
    }

    private String compile(long org, String expression) {
        JsonNode result = pipeline.compileFormula(org, expression);
        if (!result.path("ok").asBoolean(false) || !result.hasNonNull("compiledJs")) {
            JsonNode e = result.path("error");
            FormulaError error = new FormulaError(e.hasNonNull("line") ? e.get("line").asInt() : null,
                    e.hasNonNull("col") ? e.get("col").asInt() : null, e.path("message").asString(null));
            throw new FailureWithResponse(ScriptErrorCode.SCRIPT_FORMULA_INVALID,
                    List.of(new FieldErrorDetail("expression", "SCRIPT_FORMULA_INVALID", error.message())), error);
        }
        String js = result.get("compiledJs").asString();
        if (FORBIDDEN_JS.matcher(js).find()) {
            // 컴파일 결과는 __m·__roll·U.(ctx.util)만 써야 한다(SCR-api §3.5). 금지 API가 보이면 저장하지 않는다
            throw new FailureWithResponse(ScriptErrorCode.SCRIPT_FORMULA_INVALID,
                    List.of(new FieldErrorDetail("expression", "SCRIPT_FORBIDDEN_API", null)), new FormulaError(null, null, "SCRIPT_FORBIDDEN_API"));
        }
        return js;
    }

    /** 결과 키가 다른 수식이나 (수식이 만들지 않은) 측정 항목과 겹치면 409 */
    private void requireKeyFree(long org, String key, Long excludeFormulaId) {
        if (formulas.existsResultKey(org, key, excludeFormulaId)) {
            throw new BusinessException(ScriptErrorCode.SCRIPT_FORMULA_KEY_CONFLICT);
        }
        if (!formulas.findMetricDerived(org, key).orElse(true)) {
            throw new BusinessException(ScriptErrorCode.SCRIPT_FORMULA_KEY_CONFLICT);
        }
    }

    private Validated validate(long org, FormulaRequest r, FormulaRow before) {
        if (r == null) {
            throw invalid("resultKey", "NotBlank");
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        String key = pick(r.resultKey(), before == null ? null : before.resultKey());
        if (!ScriptM5Rules.validResultKey(key)) {
            errors.add(new FieldErrorDetail("resultKey", "Pattern", "^[A-Za-z][A-Za-z0-9_]{0,63}$"));
        }
        String name = pick(r.displayName(), before == null ? null : before.displayName());
        if (name == null) {
            name = key;
        }
        if (name != null && name.length() > 100) {
            errors.add(new FieldErrorDetail("displayName", "Size", "1~100"));
        }
        String unit = r.unit() == null ? (before == null ? null : before.unit()) : (r.unit().isBlank() ? null : r.unit().strip());
        if (unit != null && unit.length() > 16) {
            errors.add(new FieldErrorDetail("unit", "Size", "16"));
        }
        String expression = pick(r.expression(), before == null ? null : before.expression());
        if (expression == null || expression.length() > 500) {
            errors.add(new FieldErrorDetail("expression", "Size", "1~500"));
        }
        String type = pick(r.targetType(), before == null ? null : before.targetType());
        type = type == null ? null : type.toUpperCase(Locale.ROOT);
        if (type == null || !TARGET_TYPES.contains(type)) {
            errors.add(new FieldErrorDetail("targetType", "Enum", "MODEL|DEVICE|SPACE"));
        }
        String status = pick(r.status(), before == null ? "ACTIVE" : before.status()).toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            errors.add(new FieldErrorDetail("status", "Enum", "ACTIVE|DISABLED"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        String rawTarget = pick(r.targetId(), before == null ? null : before.targetId());
        String target = resolveTarget(org, type, rawTarget, true);
        return new Validated(key, name, unit, expression, type, target, status);
    }

    /** 대상 ID 확인. MODEL은 코드도 받아 ID로 바꾼다(pipeline이 숫자 ID로 맞춘다). 없으면 400 errors[targetId] */
    private String resolveTarget(long org, String type, String raw, boolean mustExist) {
        if (raw == null || raw.isBlank()) {
            throw invalid("targetId", "NotBlank");
        }
        String v = raw.strip();
        Long id = v.matches("\\d{1,18}") ? Long.valueOf(v) : null;
        if (id == null && "MODEL".equals(type)) {
            id = formulas.findModelIdByCode(org, v).orElse(null);
        }
        if (id == null || (mustExist && !formulas.existsTarget(org, type, id))) {
            throw invalid("targetId", "NOT_FOUND");
        }
        return Long.toString(id);
    }

    private FormulaRow load(long org, long id) {
        return formulas.findById(org, id).orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_FORMULA_NOT_FOUND));
    }

    private void audit(long org, CurrentUser user, long id, String op, Validated v) {
        audits.record(audits.event(org, AUDIT_CHANGED).actor(user).target("FORMULA_METRIC", Long.toString(id)).detail("op", op)
                .detail("resultKey", v.resultKey()).detail("expression", v.expression()).detail("targetType", v.targetType())
                .detail("targetId", v.targetId()).detail("status", v.status()));
    }

    private static FormulaFields fields(Validated v, String compiled) {
        return new FormulaFields(v.resultKey(), v.displayName(), v.unit(), v.expression(), compiled, v.targetType(), v.targetId(), v.status());
    }

    static FormulaResponse toResponse(FormulaRow f) {
        return new FormulaResponse(Long.toString(f.id()), f.resultKey(), f.displayName(), f.unit(), f.expression(), f.targetType(),
                f.targetId(), f.targetName(), f.status(), f.version(), f.updatedAt());
    }

    private static String pick(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }

    private record Validated(String resultKey, String displayName, String unit, String expression, String targetType, String targetId,
                             String status) {
    }
}
