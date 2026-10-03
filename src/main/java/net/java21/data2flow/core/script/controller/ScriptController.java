package net.java21.data2flow.core.script.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.BindingsResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.CheckRequest;
import net.java21.data2flow.core.script.dto.ScriptDtos.CreateScriptRequest;
import net.java21.data2flow.core.script.dto.ScriptDtos.CreateScriptResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.DeployRequest;
import net.java21.data2flow.core.script.dto.ScriptDtos.DeployResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.ReplaceBindingsRequest;
import net.java21.data2flow.core.script.dto.ScriptDtos.SaveDraftRequest;
import net.java21.data2flow.core.script.dto.ScriptDtos.SaveDraftResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.ScriptDetailResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.ScriptSummaryResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.StaticCheck;
import net.java21.data2flow.core.script.dto.ScriptDtos.StatusResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.TemplateResponse;
import net.java21.data2flow.core.script.dto.ScriptDtos.TestRunRequest;
import net.java21.data2flow.core.script.dto.ScriptDtos.VersionResponse;
import net.java21.data2flow.core.script.service.ScriptBindingService;
import net.java21.data2flow.core.script.service.ScriptService;
import net.java21.data2flow.core.script.service.ScriptTestRunService;
import net.java21.data2flow.core.script.service.ScriptVersionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/**
 * 정제 스크립트 API(design/api/SCR-api.md §1). 외부 경로 {@code /api/v1/core/scripts/**} → gateway가 {@code /api/v1}을 떼어
 * {@code /core/scripts/**}로 온다. 조회 SCRIPT_READ(OPERATOR+), 쓰기 SCRIPT_WRITE(INTEGRATOR+), 강제 배포 ADMIN.
 */
@RestController
public class ScriptController {

    private final ScriptService scripts;
    private final ScriptVersionService versions;
    private final ScriptBindingService bindings;
    private final ScriptTestRunService testRuns;

    public ScriptController(ScriptService scripts, ScriptVersionService versions, ScriptBindingService bindings,
                            ScriptTestRunService testRuns) {
        this.scripts = scripts;
        this.versions = versions;
        this.bindings = bindings;
        this.testRuns = testRuns;
    }

    /** API-SCR-01 스크립트 목록(SCR-01, SCR-04.03) — SCRIPT_READ, 200 */
    @GetMapping("/core/scripts")
    public ListApiResponse<ScriptSummaryResponse> list(@RequestParam(required = false) String kind,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) Boolean checkFailed,
                                                       @RequestParam(required = false) String targetType,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer size) {
        return scripts.list(kind, status, checkFailed, targetType, keyword, page, size);
    }

    /** API-SCR-15 템플릿 목록(SCR-01.05) — SCRIPT_READ, 200 */
    @GetMapping("/core/scripts/templates")
    public ListApiResponse<TemplateResponse> templates(@RequestParam(required = false) String kind) {
        return scripts.templates(kind);
    }

    /** API-SCR-02 생성(SCR-01.01·01.02, AT-SCR-01.1) — SCRIPT_WRITE, 201 + Location */
    @PostMapping("/core/scripts")
    @Idempotent
    public ResponseEntity<ApiResponse<CreateScriptResponse>> create(@Valid @RequestBody CreateScriptRequest request) {
        CreateScriptResponse created = scripts.create(request);
        return ResponseEntity.created(URI.create("/api/v1/core/scripts/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-SCR-07 정적 검사(SCR-04.05) — SCRIPT_WRITE, 200. pipeline API-SCR-30 */
    @PostMapping("/core/scripts/check")
    public ApiResponse<StaticCheck> check(@Valid @RequestBody CheckRequest request) {
        return ApiResponse.success(versions.check(request));
    }

    /** API-SCR-08 테스트 실행(SCR-03.02) — SCRIPT_WRITE, 200. pipeline API-SCR-31, 아무것도 저장하지 않음 */
    @PostMapping("/core/scripts/test-run")
    public ApiResponse<JsonNode> testRun(@Valid @RequestBody TestRunRequest request) {
        return ApiResponse.success(testRuns.testRun(request));
    }

    /** API-SCR-04 상세 — SCRIPT_READ, 200 */
    @GetMapping("/core/scripts/{script-id}")
    public ApiResponse<ScriptDetailResponse> detail(@PathVariable("script-id") long scriptId,
                                                    @RequestParam(required = false) String include) {
        return ApiResponse.success(scripts.detail(scriptId, include));
    }

    /** API-SCR-02 수정(이름·설명, baseVersion) — SCRIPT_WRITE, 200 */
    @PatchMapping("/core/scripts/{script-id}")
    public ApiResponse<ScriptDetailResponse> update(@PathVariable("script-id") long scriptId, @RequestBody JsonNode body) {
        return ApiResponse.success(scripts.update(scriptId, body));
    }

    /** API-SCR-02 삭제(BR-SCR-15) — SCRIPT_WRITE, 204 */
    @DeleteMapping("/core/scripts/{script-id}")
    public ResponseEntity<Void> delete(@PathVariable("script-id") long scriptId) {
        scripts.delete(scriptId);
        return ResponseEntity.noContent().build();
    }

    /** API-SCR-03 DRAFT 저장(SCR-03.04, SCR-04.03·04.05) — SCRIPT_WRITE, 200 */
    @PutMapping("/core/scripts/{script-id}/draft")
    public ApiResponse<SaveDraftResponse> saveDraft(@PathVariable("script-id") long scriptId,
                                                    @Valid @RequestBody SaveDraftRequest request) {
        return ApiResponse.success(versions.saveDraft(scriptId, request));
    }

    /** API-SCR-05 배포·롤백(SCR-03.04) — SCRIPT_WRITE(force는 ADMIN), 사람만, 200 */
    @PostMapping("/core/scripts/{script-id}/deploy")
    @Idempotent
    public ApiResponse<DeployResponse> deploy(@PathVariable("script-id") long scriptId, @Valid @RequestBody DeployRequest request) {
        return ApiResponse.success(versions.deploy(scriptId, request));
    }

    /** API-SCR-06 버전 코드(비교용) — SCRIPT_READ, 200 */
    @GetMapping("/core/scripts/{script-id}/versions/{version-id}")
    public ApiResponse<VersionResponse> version(@PathVariable("script-id") long scriptId,
                                                @PathVariable("version-id") long versionId) {
        return ApiResponse.success(scripts.version(scriptId, versionId));
    }

    /** API-SCR-09 연결·실패 정책(SCR-01.01·01.02·02.03) — SCRIPT_WRITE, 200 */
    @PutMapping("/core/scripts/{script-id}/bindings")
    public ApiResponse<BindingsResponse> replaceBindings(@PathVariable("script-id") long scriptId,
                                                         @Valid @RequestBody ReplaceBindingsRequest request) {
        return ApiResponse.success(bindings.replace(scriptId, request));
    }

    /** API-SCR-17 활성화(자동 비활성 해제 포함) — SCRIPT_WRITE, 200 */
    @PostMapping("/core/scripts/{script-id}/enable")
    @Idempotent
    public ApiResponse<StatusResponse> enable(@PathVariable("script-id") long scriptId) {
        return ApiResponse.success(scripts.enable(scriptId));
    }

    /** API-SCR-17 비활성화 — SCRIPT_WRITE, 200(영향 범위 포함) */
    @PostMapping("/core/scripts/{script-id}/disable")
    @Idempotent
    public ApiResponse<StatusResponse> disable(@PathVariable("script-id") long scriptId) {
        return ApiResponse.success(scripts.disable(scriptId));
    }
}
