package net.java21.data2flow.core.script.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ConfigRequest;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ConfigResponse;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ErrorSnapshot;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.FormulaPreviewRequest;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.FormulaRequest;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.FormulaResponse;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.LogCaptureRequest;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.LogCaptureResponse;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.LogLine;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ModuleRequest;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ModuleResponse;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ModuleSummary;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ModuleUsageResponse;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.ReleaseResponse;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.RunCasesRequest;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.RunCasesResponse;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.StatsResponse;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.TestCaseRequest;
import net.java21.data2flow.core.script.dto.ScriptM5Dtos.TestCaseResponse;
import net.java21.data2flow.core.script.service.FormulaMetricService;
import net.java21.data2flow.core.script.service.ScriptModuleService;
import net.java21.data2flow.core.script.service.ScriptOpsService;
import net.java21.data2flow.core.script.service.ScriptTestCaseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * 스크립트 M5 API(design/api/SCR-api.md): 테스트 케이스 API-SCR-10·11, 운영 지표·오류·로그 API-SCR-12~14, 공유 모듈 API-SCR-18·19,
 * 수식 항목 API-SCR-20·21, 설정값 API-SCR-22.
 */
@RestController
public class ScriptM5Controller {

    private final ScriptTestCaseService testCases;
    private final ScriptModuleService modules;
    private final FormulaMetricService formulas;
    private final ScriptOpsService ops;

    public ScriptM5Controller(ScriptTestCaseService testCases, ScriptModuleService modules, FormulaMetricService formulas,
                              ScriptOpsService ops) {
        this.testCases = testCases;
        this.modules = modules;
        this.formulas = formulas;
        this.ops = ops;
    }

    // ----- API-SCR-10·11 -----

    @GetMapping("/core/scripts/{script-id}/test-cases")
    public ListApiResponse<TestCaseResponse> testCases(@PathVariable("script-id") long scriptId) {
        List<TestCaseResponse> list = testCases.list(scriptId);
        return ListApiResponse.of(PageParams.of(1, 100), list, list.size());
    }

    @PostMapping("/core/scripts/{script-id}/test-cases")
    public ResponseEntity<ApiResponse<TestCaseResponse>> createTestCase(@PathVariable("script-id") long scriptId,
                                                                        @RequestBody(required = false) TestCaseRequest request) {
        TestCaseResponse created = testCases.create(scriptId, request);
        return ResponseEntity.created(URI.create("/api/v1/core/scripts/" + scriptId + "/test-cases/" + created.id()))
                .body(ApiResponse.success(created));
    }

    @PutMapping("/core/scripts/{script-id}/test-cases/{case-id}")
    public ApiResponse<TestCaseResponse> updateTestCase(@PathVariable("script-id") long scriptId, @PathVariable("case-id") long caseId,
                                                        @RequestBody(required = false) TestCaseRequest request) {
        return ApiResponse.success(testCases.update(scriptId, caseId, request));
    }

    @DeleteMapping("/core/scripts/{script-id}/test-cases/{case-id}")
    public ResponseEntity<Void> deleteTestCase(@PathVariable("script-id") long scriptId, @PathVariable("case-id") long caseId) {
        testCases.delete(scriptId, caseId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/core/scripts/{script-id}/test-cases/run")
    public ApiResponse<RunCasesResponse> runTestCases(@PathVariable("script-id") long scriptId,
                                                      @RequestBody(required = false) RunCasesRequest request) {
        return ApiResponse.success(testCases.run(scriptId, request));
    }

    // ----- API-SCR-12~14·22 -----

    @GetMapping("/core/scripts/{script-id}/stats")
    public ApiResponse<StatsResponse> stats(@PathVariable("script-id") long scriptId, @RequestParam(required = false) Instant from,
                                            @RequestParam(required = false) Instant to, @RequestParam(required = false) String step) {
        return ApiResponse.success(ops.stats(scriptId, from, to, step));
    }

    @GetMapping("/core/scripts/{script-id}/errors")
    public ListApiResponse<ErrorSnapshot> errors(@PathVariable("script-id") long scriptId, @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        return ops.errors(scriptId, page, size);
    }

    @PostMapping("/core/scripts/{script-id}/log-capture")
    public ApiResponse<LogCaptureResponse> logCapture(@PathVariable("script-id") long scriptId,
                                                      @RequestBody(required = false) LogCaptureRequest request) {
        return ApiResponse.success(ops.logCapture(scriptId, request));
    }

    @GetMapping("/core/scripts/{script-id}/logs")
    public ListApiResponse<LogLine> logs(@PathVariable("script-id") long scriptId, @RequestParam(required = false) Instant from,
                                         @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ops.logs(scriptId, from, page, size);
    }

    @PutMapping("/core/scripts/{script-id}/config")
    public ApiResponse<ConfigResponse> config(@PathVariable("script-id") long scriptId, @RequestBody(required = false) ConfigRequest request) {
        return ApiResponse.success(ops.updateConfig(scriptId, request));
    }

    // ----- API-SCR-18·19 -----

    @GetMapping("/core/script-modules")
    public ListApiResponse<ModuleSummary> modules(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return modules.list(page, size);
    }

    @PostMapping("/core/script-modules")
    public ResponseEntity<ApiResponse<ModuleResponse>> createModule(@RequestBody(required = false) ModuleRequest request) {
        ModuleResponse created = modules.create(request);
        return ResponseEntity.created(URI.create("/api/v1/core/script-modules/" + created.id())).body(ApiResponse.success(created));
    }

    @GetMapping("/core/script-modules/{module-id}")
    public ApiResponse<ModuleResponse> module(@PathVariable("module-id") long moduleId) {
        return ApiResponse.success(modules.get(moduleId));
    }

    @PutMapping("/core/script-modules/{module-id}")
    public ApiResponse<ModuleResponse> saveModule(@PathVariable("module-id") long moduleId, @RequestBody(required = false) ModuleRequest request) {
        return ApiResponse.success(modules.saveDraft(moduleId, request));
    }

    @GetMapping("/core/script-modules/{module-id}/usage")
    public ApiResponse<ModuleUsageResponse> moduleUsage(@PathVariable("module-id") long moduleId) {
        return ApiResponse.success(modules.usage(moduleId));
    }

    @PostMapping("/core/script-modules/{module-id}/release")
    public ResponseEntity<ApiResponse<ReleaseResponse>> release(@PathVariable("module-id") long moduleId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(modules.release(moduleId)));
    }

    @DeleteMapping("/core/script-modules/{module-id}/versions/{version-no}")
    public ResponseEntity<Void> deleteModuleVersion(@PathVariable("module-id") long moduleId, @PathVariable("version-no") int versionNo) {
        modules.deleteVersion(moduleId, versionNo);
        return ResponseEntity.noContent().build();
    }

    // ----- API-SCR-20·21 -----

    @GetMapping("/core/formula-metrics")
    public ListApiResponse<FormulaResponse> formulas(@RequestParam(required = false) String targetType,
                                                     @RequestParam(required = false) String targetId,
                                                     @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return formulas.list(targetType, targetId, page, size);
    }

    @PostMapping("/core/formula-metrics")
    public ResponseEntity<ApiResponse<FormulaResponse>> createFormula(@RequestBody(required = false) FormulaRequest request) {
        FormulaResponse created = formulas.create(request);
        return ResponseEntity.created(URI.create("/api/v1/core/formula-metrics/" + created.id())).body(ApiResponse.success(created));
    }

    @PutMapping("/core/formula-metrics/{formula-metric-id}")
    public ApiResponse<FormulaResponse> updateFormula(@PathVariable("formula-metric-id") long id,
                                                      @RequestBody(required = false) FormulaRequest request) {
        return ApiResponse.success(formulas.update(id, request));
    }

    @DeleteMapping("/core/formula-metrics/{formula-metric-id}")
    public ResponseEntity<Void> deleteFormula(@PathVariable("formula-metric-id") long id) {
        formulas.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/core/formula-metrics/preview")
    public ApiResponse<JsonNode> previewFormula(@RequestBody(required = false) FormulaPreviewRequest request) {
        return ApiResponse.success(formulas.preview(request));
    }
}
