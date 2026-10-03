package net.java21.data2flow.core.device.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 기기 API 요청·응답(design/api/DEV-api.md §2). ID는 JSON 문자열, 시각은 UTC ISO-8601 */
public final class DeviceDtos {

    private DeviceDtos() {
    }

    // ---------- 응답 ----------

    public record ModelSummary(String id, String code, String name) {
    }

    public record ModelDetail(String id, String code, String name, String vendor, String kind, List<String> capabilities) {
    }

    /** path는 루트부터 이 공간까지의 이름 */
    public record SpaceSummary(String id, String name, List<String> path) {
    }

    public record SourceSummary(String id, String name, String type) {
    }

    /**
     * API-DEV-11 목록 항목. 문서 필드 + 화면이 쓰는 {@code firstSeenAt}·{@code metrics}(최근값·수신한 측정 항목 키)·{@code sourceMeta}
     * ·{@code suggestedSpaceId}(ING-03.03 추천 공간)
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DeviceSummaryResponse(String id, String name, String externalId, String kind, String status, String connectivity,
                                        Instant lastSeenAt, Instant firstSeenAt, BigDecimal battery, BigDecimal rssi,
                                        ModelSummary model, SpaceSummary space, SourceSummary source, List<String> tags,
                                        boolean virtual, boolean onboardingComplete, List<String> metrics, JsonNode sourceMeta,
                                        String suggestedSpaceId, boolean autoRegistered, int version, Instant createdAt) {
    }

    public record StateView(String connectivity, Instant lastSeenAt, Instant lastMeasuredAt, BigDecimal battery, BigDecimal rssi,
                            BigDecimal snr, String bestGatewayEui, Integer msgCount24h) {
    }

    public record LatestValue(String metricKey, String displayName, String unit, Double value, Instant measuredAt, Integer quality) {
    }

    public record EffectiveView(int expectedIntervalSec, BigDecimal offlineMultiplier, String inheritedFrom) {
    }

    public record RelationView(String spaceId, String relation, boolean auto) {
    }

    public record OnboardingView(boolean firstData, boolean model, boolean space, boolean decodeOk, boolean rulesApplied,
                                 boolean complete) {
    }

    public record GroupRef(String id, String name) {
    }

    public record LorawanView(String devEui, String joinEui, String applicationId, String deviceProfileId) {
    }

    /** API-DEV-23 기기 상세(API-DEV-12·13 응답과 같음) */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DeviceDetailResponse(String id, String name, String externalId, String kind, String status, boolean virtual,
                                       int version, SourceSummary source, ModelDetail model, SpaceSummary space, StateView state,
                                       List<LatestValue> latest, EffectiveView effective, List<RelationView> relations,
                                       OnboardingView onboarding, List<String> tags, List<GroupRef> groups, String logicalDeviceId,
                                       String replacedBy, LorawanView lorawan, JsonNode sourceMeta, Integer expectedIntervalSec,
                                       BigDecimal offlineMultiplier, Instant firstSeenAt, String suggestedSpaceId,
                                       boolean autoRegistered, Instant approvedAt, Instant createdAt, Instant updatedAt) {
    }

    /** API-DEV-16 응답 */
    public record DeviceStatusResponse(String id, String status, int version) {
    }

    // ---------- 요청 ----------

    /** API-DEV-12 수동 등록 */
    public record CreateDeviceRequest(
            @NotBlank @Pattern(regexp = "\\d{1,18}") String sourceId,
            @NotBlank @Size(max = 128) String externalId,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "SENSOR|ACTUATOR|GATEWAY|HYBRID") String kind,
            @NotBlank @Pattern(regexp = "\\d{1,18}") String modelId,
            @NotBlank @Pattern(regexp = "\\d{1,18}") String spaceId,
            @Min(10) @Max(86400) Integer expectedIntervalSec,
            @DecimalMin("1.5") @DecimalMax("10") BigDecimal offlineMultiplier,
            @Size(max = 20) List<String> tags,
            Boolean virtual) {
    }

    /** 활성·비활성(API-DEV-16) */
    public record BaseVersionRequest(@NotNull Integer baseVersion) {
    }

    public record ApproveItem(@NotBlank @Pattern(regexp = "\\d{1,18}") String deviceId, @NotNull Integer baseVersion) {
    }

    /** API-DEV-15 일괄 승인 */
    public record ApproveRequest(@NotEmpty @Size(max = 200) List<@Valid ApproveItem> items,
                                 @Pattern(regexp = "\\d{1,18}") String modelId,
                                 @Pattern(regexp = "\\d{1,18}") String spaceId,
                                 @Size(max = 100) String name,
                                 @Size(max = 20) List<String> tags,
                                 Boolean applyModelPackage) {
    }

    /**
     * 승인 결과 항목. 플랫폼 브로커 기기는 승인 때 서명 키를 한 번만 돌려준다({@code signingKey}, DSC-03.05, ADR-031)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApproveResult(String deviceId, boolean ok, String errorCode, String signingKey) {
    }

    public record ApproveResponse(List<ApproveResult> results) {
    }

    /** API-DEV-28 거부 */
    public record RejectRequest(@NotEmpty @Size(max = 200) List<@Pattern(regexp = "\\d{1,18}") String> deviceIds,
                                Boolean addToIgnoreList) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RejectResult(String deviceId, boolean ok, String errorCode, boolean ignored) {
    }

    public record RejectResponse(List<RejectResult> results) {
    }

    /** API-DEV-29 모델 추천 */
    public record ModelSuggestion(String modelId, String modelCode, String modelName, List<String> matchedMetrics, double score) {
    }

    /** API-DEV-21 태그 일괄 */
    public record TagRequest(@NotEmpty @Size(max = 1000) List<@Pattern(regexp = "\\d{1,18}") String> deviceIds,
                             @Size(max = 20) List<String> add, @Size(max = 20) List<String> remove) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TagResult(String deviceId, boolean ok, String errorCode, List<String> tags) {
    }

    public record TagResponse(List<TagResult> results) {
    }

    /** API-DEV-19 가져오기 결과 */
    public record ImportRow(int line, boolean ok, String errorCode, String message) {
    }

    public record ImportReport(int total, int succeeded, int failed, List<ImportRow> rows) {
    }
}
