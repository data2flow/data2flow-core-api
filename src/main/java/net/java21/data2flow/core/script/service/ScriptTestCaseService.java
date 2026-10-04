package net.java21.data2flow.core.script.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.FailureWithResponse;
import net.java21.data2flow.core.script.domain.ScriptErrorCode;
import net.java21.data2flow.core.script.domain.ScriptM5Rules;
import net.java21.data2flow.core.script.domain.ScriptModels;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.RunCasesRequest;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.RunCasesResponse;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.TestCaseRequest;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.TestCaseResponse;
import net.java21.data2flow.core.script.repository.ScriptRepository.ScriptRow;
import net.java21.data2flow.core.script.repository.ScriptTestCaseRepository;
import net.java21.data2flow.core.script.repository.ScriptTestCaseRepository.TestCaseFields;
import net.java21.data2flow.core.script.repository.ScriptTestCaseRepository.TestCaseRow;
import net.java21.data2flow.core.script.repository.ScriptVersionRepository;
import net.java21.data2flow.core.script.repository.ScriptVersionRepository.VersionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 테스트 케이스(SCR-03.03, AT-SCR-07.1~3): CRUD API-SCR-10, 일괄 실행 API-SCR-11(pipeline API-SCR-35, 비교는 pipeline이 같은 샌드박스
 * 결과로 한다), 배포 전 자동 확인(API-SCR-05). 실행은 저장·발행하지 않고(BR-SCR-08) 케이스마다 마지막 결과만 남긴다.
 */
@Service
public class ScriptTestCaseService {

    static final Set<String> COMPARE_MODES = Set.of("EXACT", "FIELDS", "TOLERANCE");
    static final String AUDIT_CHANGED = "SCRIPT_TEST_CASE_CHANGED";

    private final RoleChecker roleChecker;
    private final ScriptSupport support;
    private final ScriptTestCaseRepository cases;
    private final ScriptVersionRepository versions;
    private final PipelineScriptOpsClient pipeline;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public ScriptTestCaseService(RoleChecker roleChecker, ScriptSupport support, ScriptTestCaseRepository cases,
                                 ScriptVersionRepository versions, PipelineScriptOpsClient pipeline, Audits audits, JsonMapper json,
                                 Clock clock) {
        this.roleChecker = roleChecker;
        this.support = support;
        this.cases = cases;
        this.versions = versions;
        this.pipeline = pipeline;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-SCR-10 목록 */
    @Transactional(readOnly = true)
    public List<TestCaseResponse> list(long scriptId) {
        roleChecker.require(Permission.SCRIPT_READ);
        long org = roleChecker.currentUser().organizationId();
        support.require(org, scriptId);
        return cases.listByScript(org, scriptId).stream().map(this::toResponse).toList();
    }

    /** 상세(API-SCR-04 include=tests) — 권한 확인은 호출자가 했다 */
    @Transactional(readOnly = true)
    public List<TestCaseResponse> listForDetail(long organizationId, long scriptId) {
        return cases.listByScript(organizationId, scriptId).stream().map(this::toResponse).toList();
    }

    /** API-SCR-10 생성 — 50개 초과 409 SCRIPT_QUOTA_EXCEEDED, 같은 이름 400 errors[name] */
    @Transactional
    public TestCaseResponse create(long scriptId, TestCaseRequest request) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        support.lock(org, scriptId);
        TestCaseFields fields = validate(request);
        if (cases.countByScript(org, scriptId) >= ScriptM5Rules.MAX_TEST_CASES) {
            throw new BusinessException(ScriptErrorCode.SCRIPT_QUOTA_EXCEEDED);
        }
        if (cases.existsName(org, scriptId, fields.name(), null)) {
            throw duplicateName();
        }
        long id = cases.insert(org, scriptId, fields, clock.instant());
        audit(org, user, scriptId, "CREATED", id, fields.name());
        return toResponse(cases.findById(org, scriptId, id).orElseThrow());
    }

    /** API-SCR-10 수정(지난 결과는 지움) */
    @Transactional
    public TestCaseResponse update(long scriptId, long caseId, TestCaseRequest request) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        support.lock(org, scriptId);
        cases.findById(org, scriptId, caseId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        TestCaseFields fields = validate(request);
        if (cases.existsName(org, scriptId, fields.name(), caseId)) {
            throw duplicateName();
        }
        cases.update(org, scriptId, caseId, fields, clock.instant());
        audit(org, user, scriptId, "UPDATED", caseId, fields.name());
        return toResponse(cases.findById(org, scriptId, caseId).orElseThrow());
    }

    /** API-SCR-10 삭제 — 204 */
    @Transactional
    public void delete(long scriptId, long caseId) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        support.require(org, scriptId);
        TestCaseRow row = cases.findById(org, scriptId, caseId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        cases.delete(org, scriptId, caseId);
        audit(org, user, scriptId, "DELETED", caseId, row.name());
    }

    /** API-SCR-11 일괄 실행: code → versionId → DRAFT → ACTIVE 순으로 실행할 코드를 고른다 */
    public RunCasesResponse run(long scriptId, RunCasesRequest request) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        long org = roleChecker.currentUser().organizationId();
        ScriptRow script = support.require(org, scriptId);
        String code;
        if (request != null && request.code() != null) {
            code = ScriptModels.requireCodeSize(request.code());
        } else if (request != null && request.versionId() != null && !request.versionId().isBlank()) {
            long versionId = ScriptSupport.parseId(request.versionId(), "versionId");
            code = versions.findById(org, scriptId, versionId).map(VersionRow::code)
                    .orElseThrow(() -> new BusinessException(ScriptErrorCode.SCRIPT_NOT_FOUND));
        } else {
            code = versions.findDraft(org, scriptId).map(VersionRow::code)
                    .or(() -> script.activeVersionId() == null ? java.util.Optional.empty()
                            : versions.findById(org, scriptId, script.activeVersionId()).map(VersionRow::code))
                    .orElseThrow(() -> ScriptModels.invalid("code", "NotNull"));
        }
        return execute(org, scriptId, script.kind(), code);
    }

    /**
     * 배포 전 자동 확인(API-SCR-05, AT-SCR-07.1·07.3): 케이스가 없으면 통과(0/0). 하나라도 실패하면 400 SCRIPT_TEST_FAILED(response에
     * 실패 케이스와 차이). force(ADMIN)면 실패해도 통과하고 결과를 배포 기록에 남긴다. pipeline이 응답하지 않으면 503(force면 건너뜀)
     */
    public Map<String, Object> guardDeploy(long organizationId, long scriptId, String kind, String code, boolean force) {
        if (cases.countByScript(organizationId, scriptId) == 0) {
            return result(0, 0, List.of());
        }
        RunCasesResponse r;
        try {
            r = execute(organizationId, scriptId, kind, code);
        } catch (BusinessException ex) {
            if (force && ex.getErrorCode() == CommonErrorCode.SERVICE_UNAVAILABLE) {
                Map<String, Object> skipped = result(0, 0, List.of());
                skipped.put("skipped", "PIPELINE_UNAVAILABLE");
                return skipped;
            }
            throw ex;
        }
        List<JsonNode> failedResults = r.results().stream().filter(n -> !n.path("passed").asBoolean(false)).toList();
        if (r.failed() > 0 && !force) {
            throw new FailureWithResponse(ScriptErrorCode.SCRIPT_TEST_FAILED,
                    List.of(new FieldErrorDetail("testCases", "FAILED", Integer.toString(r.failed()))),
                    result(r.passed(), r.failed(), failedResults));
        }
        return result(r.passed(), r.failed(), failedResults);
    }

    private RunCasesResponse execute(long organizationId, long scriptId, String kind, String code) {
        List<TestCaseRow> rows = cases.listByScript(organizationId, scriptId);
        if (rows.isEmpty()) {
            return new RunCasesResponse(0, 0, List.of());
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (TestCaseRow c : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", Long.toString(c.id()));
            item.put("name", c.name());
            item.put("input", read(c.input()));
            item.put("context", read(c.context()));
            item.put("expected", c.expected() == null ? NullNode.getInstance() : read(c.expected()));
            item.put("compareMode", c.compareMode());
            item.put("compareFields", c.compareFields().isEmpty() ? null : c.compareFields());
            item.put("tolerance", c.tolerance());
            list.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId);
        body.put("kind", kind);
        body.put("code", code);
        body.put("scriptId", scriptId);
        body.put("moduleRefs", ScriptM5Rules.moduleRefs(code));
        body.put("cases", list);
        JsonNode response = pipeline.runTestCases(body);
        List<JsonNode> results = new ArrayList<>();
        Instant now = clock.instant();
        for (JsonNode r : response.path("results")) {
            results.add(r);
            String caseId = r.path("caseId").asString(null);
            if (caseId != null && caseId.matches("\\d{1,18}")) {
                ObjectNode last = json.createObjectNode();
                last.put("passed", r.path("passed").asBoolean(false));
                last.put("at", now.toString());
                if (r.has("diff")) {
                    last.set("diff", r.get("diff"));
                }
                if (r.hasNonNull("error")) {
                    last.set("error", r.get("error"));
                }
                cases.updateLastResult(organizationId, Long.parseLong(caseId), json.writeValueAsString(last));
            }
        }
        return new RunCasesResponse(response.path("passed").asInt(0), response.path("failed").asInt(0), results);
    }

    private static Map<String, Object> result(int passed, int failed, List<JsonNode> results) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", passed);
        out.put("failed", failed);
        out.put("results", results);
        return out;
    }

    /** 본문 검증: 이름 1~80자, input·expected 필수(expected는 JSON null 가능 = null 출력 기대), 각 256KB, 비교 방식별 필수 값 */
    TestCaseFields validate(TestCaseRequest r) {
        if (r == null) {
            throw ScriptModels.invalid("name", "NotBlank");
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        String name = r.name() == null ? "" : r.name().strip();
        if (name.isEmpty() || name.length() > 80) {
            errors.add(new FieldErrorDetail("name", "Size", "1~80"));
        }
        if (r.input() == null || r.input().isNull()) {
            errors.add(new FieldErrorDetail("input", "NotNull", null));
        }
        if (r.expected() == null) {
            errors.add(new FieldErrorDetail("expected", "NotNull", null));
        }
        String mode = r.compareMode() == null || r.compareMode().isBlank() ? "EXACT" : r.compareMode().strip().toUpperCase(Locale.ROOT);
        if (!COMPARE_MODES.contains(mode)) {
            errors.add(new FieldErrorDetail("compareMode", "Enum", "EXACT|FIELDS|TOLERANCE"));
        }
        List<String> fields = r.compareFields() == null ? null : r.compareFields().stream().filter(f -> f != null && !f.isBlank())
                .map(String::strip).toList();
        if ("FIELDS".equals(mode) && (fields == null || fields.isEmpty() || fields.size() > 50
                || fields.stream().anyMatch(f -> f.length() > 200))) {
            errors.add(new FieldErrorDetail("compareFields", "NotEmpty", null));
        }
        if ("TOLERANCE".equals(mode) && (r.tolerance() == null || r.tolerance() < 0 || r.tolerance().isNaN())) {
            errors.add(new FieldErrorDetail("tolerance", "Min", "0"));
        }
        String input = r.input() == null ? null : json.writeValueAsString(r.input());
        String context = r.context() == null || r.context().isNull() ? null : json.writeValueAsString(r.context());
        String expected = r.expected() == null ? null : json.writeValueAsString(r.expected());
        for (Map.Entry<String, String> e : Map.of("input", String.valueOf(input), "context", String.valueOf(context),
                "expected", String.valueOf(expected)).entrySet()) {
            if (e.getValue().getBytes(StandardCharsets.UTF_8).length > ScriptModels.MAX_TEST_INPUT_BYTES) {
                errors.add(new FieldErrorDetail(e.getKey(), "Size", "256KB"));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        return new TestCaseFields(name, input, context, expected, mode, "FIELDS".equals(mode) ? fields : null,
                "TOLERANCE".equals(mode) ? r.tolerance() : null);
    }

    private TestCaseResponse toResponse(TestCaseRow c) {
        return new TestCaseResponse(Long.toString(c.id()), c.name(), read(c.input()), read(c.context()), read(c.expected()),
                c.compareMode(), c.compareFields().isEmpty() ? null : c.compareFields(), c.tolerance(), read(c.lastResult()),
                c.updatedAt());
    }

    private JsonNode read(String raw) {
        return raw == null ? null : json.readTree(raw);
    }

    private void audit(long org, CurrentUser user, long scriptId, String op, long caseId, String name) {
        audits.record(audits.event(org, AUDIT_CHANGED).actor(user).target("SCRIPT", Long.toString(scriptId)).detail("op", op)
                .detail("caseId", Long.toString(caseId)).detail("name", name));
    }

    private static BusinessException duplicateName() {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "DUPLICATE", null)));
    }
}
