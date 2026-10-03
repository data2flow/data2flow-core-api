package net.java21.data2flow.core.script.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 정제 스크립트 API DTO(design/api/SCR-api.md API-SCR-01~09·15·17, 내부 API-SCR-32~34). ID는 JSON 문자열 */
public final class ScriptDtos {

    private ScriptDtos() {
    }

    // ----- 요청 -----

    /** API-SCR-02 생성. bindings가 있으면 함께 연결한다(배포 전까지는 실행되지 않음) */
    public record CreateScriptRequest(@NotBlank @Size(min = 2, max = 80) String name, @NotBlank String kind,
                                      @Size(max = 500) String description, @Size(max = 60) String templateKey,
                                      @Valid @Size(max = 200) List<BindingRequest> bindings) {
    }

    /** 연결 하나. failurePolicy 기본 FAIL_OPEN(SCR-02.03), enabled 기본 true */
    public record BindingRequest(@NotBlank String targetType, @NotBlank @Size(max = 64) String targetId, String failurePolicy,
                                 Boolean enabled) {
    }

    /** API-SCR-09 연결 전체 교체 */
    public record ReplaceBindingsRequest(@NotNull @Valid @Size(max = 200) List<BindingRequest> bindings) {
    }

    /**
     * API-SCR-03 DRAFT 저장.
     *
     * @param code              비우면 copyFromVersionId 버전의 코드
     * @param baseVersionNo     화면이 본 마지막 버전 번호(다르면 409 SCRIPT_VERSION_CONFLICT)
     * @param copyFromVersionId 이 버전 코드로 DRAFT를 만든다(롤백 대신 고쳐 쓰기)
     */
    public record SaveDraftRequest(String code, @NotNull @PositiveOrZero Integer baseVersionNo, String copyFromVersionId) {
    }

    /** API-SCR-05 배포·롤백 */
    public record DeployRequest(@NotBlank String versionId, @NotBlank @Size(min = 2, max = 200) String memo, Boolean force,
                                @Size(max = 200) String forceReason, String baseActiveVersionId) {
    }

    /** API-SCR-07 정적 검사 */
    public record CheckRequest(@NotBlank String kind, @NotNull String code, List<Map<String, Object>> moduleRefs) {
    }

    /** API-SCR-08 테스트 실행. input 또는 rawMessageId 중 하나 */
    public record TestRunRequest(@NotBlank String kind, @NotNull String code, JsonNode input, String rawMessageId, JsonNode context,
                                 String scriptId) {
    }

    // ----- 응답 -----

    public record CreateScriptResponse(String id, String name, String kind, String status, String draftVersionId, int version) {
    }

    /** 정적 검사 결과 {ok, problems[]}(pipeline API-SCR-30 응답과 같음) */
    public record StaticCheck(boolean ok, List<Problem> problems) {
        public StaticCheck {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Problem(int line, int col, String severity, String code, String message) {
    }

    /** API-SCR-01 목록 행. stats24h는 pipeline 지표(SCR-03.05, M5)가 생기기 전까지 null */
    public record ScriptSummaryResponse(String id, String name, String kind, String status, String description, Integer activeVersion,
                                        boolean hasDraft, boolean checkFailed, BindingsSummary bindingsSummary, Stats24h stats24h,
                                        String lastDeployedBy, Instant lastDeployedAt) {
    }

    public record BindingsSummary(long sources, long models, long devices) {
    }

    public record Stats24h(Long processed, Double errorRate, Double p95Ms) {
    }

    /** API-SCR-02 PATCH 응답·API-SCR-04 상세 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScriptDetailResponse(String id, String name, String kind, String description, String status,
                                       String activeVersionId, String draftVersionId, Instant autoDisabledAt, String autoDisabledReason,
                                       Map<String, Object> config, Instant logCaptureUntil, int version, String updatedBy,
                                       Instant updatedAt, Instant createdAt, ActiveVersion activeVersion, Draft draft,
                                       List<VersionSummary> versions, List<BindingResponse> bindings, Usage usage) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActiveVersion(String versionId, int versionNo, String code, Instant deployedAt, String deployedBy, String deployMemo,
                                boolean forced, Applied applied) {
    }

    public record Draft(String versionId, int versionNo, String code, StaticCheck staticCheck, String savedBy, Instant savedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VersionSummary(String versionId, int versionNo, String status, String savedBy, Instant savedAt, String deployMemo,
                                 String deployedBy, Instant deployedAt, boolean forced, StaticCheck staticCheck) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BindingResponse(String id, String targetType, String targetId, String targetName, String failurePolicy,
                                  boolean enabled) {
    }

    /** API-SCR-04 usage. flowNodes는 플로우(M3·M4, SCR-04.04) 전까지 빈 목록 */
    public record Usage(List<UsageBinding> bindings, List<Map<String, Object>> flowNodes) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UsageBinding(String targetType, String targetId, String name, long deviceCount, Long processed24h,
                               String failurePolicy) {
    }

    /** API-SCR-09 응답 */
    public record BindingsResponse(String scriptId, List<BindingResponse> bindings) {
    }

    /** API-SCR-03 응답 */
    public record SaveDraftResponse(String versionId, int versionNo, StaticCheck staticCheck) {
    }

    /** API-SCR-05 응답 */
    public record DeployResponse(String activeVersionId, int versionNo, Applied applied, Map<String, Object> testResult) {
    }

    /** 인스턴스 적용 상태(API-SCR-34 보고 모음). total은 최근 24시간 안에 보고한 적이 있는 인스턴스 수 */
    public record Applied(long reported, long total, List<AppliedInstance> instances) {
    }

    public record AppliedInstance(String name, Instant appliedAt) {
    }

    /** API-SCR-06 응답 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VersionResponse(String versionId, String scriptId, int versionNo, String status, String code, String codeSha256,
                                  StaticCheck staticCheck, String deployMemo, String deployedBy, Instant deployedAt, boolean forced,
                                  String forceReason, String savedBy, Instant savedAt) {
    }

    /** API-SCR-17 응답. disable은 impact를 더한다 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StatusResponse(String id, String status, int version, Impact impact) {
    }

    public record Impact(long bindings, long deviceCount, Long processed24h) {
    }

    /** API-SCR-15 템플릿 */
    public record TemplateResponse(String key, String kind, String name, String description, String code,
                                   Map<String, Object> configDefaults) {
    }

    // ----- 내부 API -----

    /** API-SCR-32 실행 묶음 */
    public record RuntimeBundle(long bundleVersion, List<RuntimeScript> scripts, List<Map<String, Object>> modules,
                                List<Map<String, Object>> formulaMetrics) {
    }

    /** failurePolicy는 연결마다 다르므로 bindings[]에 둔다(대상마다 FAIL_OPEN·FAIL_CLOSED, SCR-02.03) */
    public record RuntimeScript(String scriptId, String organizationId, String kind, String versionId, int versionNo, String code,
                                String codeSha256, Map<String, Object> config, List<Map<String, Object>> moduleRefs,
                                List<RuntimeBinding> bindings) {
    }

    public record RuntimeBinding(String targetType, String targetId, String failurePolicy, boolean enabled) {
    }

    /** API-SCR-33 자동 비활성 요청 */
    public record AutoDisableRequest(@NotBlank String reason, Double observedRate, String window, String versionId) {
    }

    public record AutoDisableResponse(String scriptId, String status, Instant autoDisabledAt) {
    }

    /** API-SCR-34 적용 보고 */
    public record DeployAckRequest(@NotBlank @Size(max = 64) String instance, @NotBlank String scriptId, @NotBlank String versionId,
                                   Instant appliedAt) {
    }
}
