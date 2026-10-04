package net.java21.data2flow.core.script.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 스크립트 M5 API(design/api/SCR-api.md API-SCR-10~14·18~22) */
public final class ScriptM5Dtos {

    private ScriptM5Dtos() {
    }

    // ----- 테스트 케이스(API-SCR-10·11) -----

    /** API-SCR-10 본문 */
    public record TestCaseRequest(String name, JsonNode input, JsonNode context, JsonNode expected, String compareMode,
                                  List<String> compareFields, Double tolerance) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TestCaseResponse(String id, String name, JsonNode input, JsonNode context, JsonNode expected, String compareMode,
                                   List<String> compareFields, Double tolerance, JsonNode lastResult, Instant updatedAt) {
    }

    /** API-SCR-11 요청(생략 시 DRAFT, DRAFT가 없으면 ACTIVE) */
    public record RunCasesRequest(String versionId, String code) {
    }

    /** API-SCR-11 응답·배포 testResult */
    public record RunCasesResponse(int passed, int failed, List<JsonNode> results) {
    }

    // ----- 공유 모듈(API-SCR-18·19) -----

    public record ModuleRequest(String name, String description, String code) {
    }

    public record ModuleSummary(String id, String name, String description, Integer latestVersionNo, long usedBy, Instant updatedAt) {
    }

    public record ModuleVersionSummary(int versionNo, String status, Instant releasedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ModuleResponse(String id, String name, String description, String draftCode, Integer latestVersionNo,
                                 List<ModuleVersionSummary> versions, Instant updatedAt) {
    }

    public record ModuleUsageScript(String scriptId, String scriptName, int versionNo) {
    }

    public record ModuleUsageResponse(String moduleId, List<ModuleUsageScript> scripts) {
    }

    public record ReleaseResponse(String moduleId, int versionNo, Instant releasedAt) {
    }

    // ----- 수식 항목(API-SCR-20·21) -----

    public record FormulaRequest(String resultKey, String displayName, String unit, String expression, String targetType, String targetId,
                                 String status, Integer baseVersion) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FormulaResponse(String id, String resultKey, String displayName, String unit, String expression, String targetType,
                                  String targetId, String targetName, String status, int version, Instant updatedAt) {
    }

    public record FormulaPreviewRequest(String expression, String targetType, String targetId, Integer hours) {
    }

    /** 수식 오류 위치(400 SCRIPT_FORMULA_INVALID의 response) */
    public record FormulaError(Integer line, Integer col, String message) {
    }

    // ----- 운영(API-SCR-12~14·22) -----

    public record ConfigRequest(JsonNode config, Integer baseVersion) {
    }

    public record ConfigResponse(String scriptId, Map<String, Object> config, int version, int configRevision) {
    }

    public record LogCaptureRequest(Boolean enabled) {
    }

    public record LogCaptureResponse(boolean enabled, Instant until) {
    }

    public record DeployMark(int versionNo, Instant at) {
    }

    /** API-SCR-12 응답(pipeline API-SCR-36 + deployMarks) */
    public record StatsResponse(JsonNode points, JsonNode warnings, List<DeployMark> deployMarks) {
    }

    /** API-SCR-13 항목. inputSnapshot은 SCRIPT_WRITE만 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorSnapshot(String id, Instant occurredAt, int versionNo, String errorCode, String message, Integer line, Integer col,
                                String deviceId, JsonNode inputSnapshot) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LogLine(Instant at, int versionNo, String deviceId, String message) {
    }

    /** 배포 응답 reprocessSuggestion(SCR-03.06): 그대로 API-ING-10 재처리 작업 생성 본문으로 쓸 수 있다 */
    public record ReprocessSuggestion(Instant from, Instant to, List<ReprocessRequestBody> requests) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReprocessRequestBody(Long sourceId, List<Long> deviceIds, Instant from, Instant to, String memo) {
    }
}
