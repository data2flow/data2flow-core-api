package net.java21.data2flow.core.catalog.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 기기 모델(API-DEV-40~47)·측정 항목(API-DEV-50~56)·내부 API(API-DEV-123·124) 요청·응답. ID는 JSON 문자열 */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    // ---- 기기 모델

    /** 목록 항목(API-DEV-46) */
    public record ModelSummaryResponse(String id, String code, String vendor, String name, String protocol, String kind,
                                       boolean builtin, String status, long deviceCount, long metricCount, Instant updatedAt) {
    }

    public record ModelMetricDto(@NotBlank String key, Boolean required) {
    }

    public record CapabilityDto(@NotBlank @Size(max = 60) String capability, JsonNode constraints) {
    }

    /** 모델 패키지(API-DEV-42) 응답 조각 */
    public record PackageDto(String transformScriptId, String decodeScriptId, String driverKey, String defaultDashboardId,
                             List<String> defaultRuleTemplateIds, JsonNode attributeSchema, String driverId, String encoderScriptId) {
        public PackageDto(String transformScriptId, String decodeScriptId, String driverKey, String defaultDashboardId,
                          List<String> defaultRuleTemplateIds, JsonNode attributeSchema) {
            this(transformScriptId, decodeScriptId, driverKey, defaultDashboardId, defaultRuleTemplateIds, attributeSchema, null, null);
        }
    }

    /** 모델 상세(API-DEV-40·41·46·47) */
    public record ModelResponse(String id, String code, String vendor, String name, String protocol, String kind,
                                int defaultIntervalSec, double defaultOfflineMultiplier, String description, String imageUrl,
                                boolean builtin, String status, List<ModelMetricDto> metrics, List<CapabilityDto> capabilities,
                                @JsonProperty("package") PackageDto modelPackage, JsonNode attributeSchema, long deviceCount,
                                int version, Instant updatedAt) {
    }

    /** API-DEV-40 생성 */
    public record CreateModelRequest(@NotBlank String code, @NotBlank @Size(max = 100) String vendor,
                                     @NotBlank @Size(max = 100) String name, @NotBlank String protocol, @NotBlank String kind,
                                     Integer defaultIntervalSec, Double defaultOfflineMultiplier,
                                     @Size(max = 500) String description, @Valid List<ModelMetricDto> metrics,
                                     @Valid List<CapabilityDto> capabilities) {
    }

    /** API-DEV-42 패키지 저장(ID는 문자열 또는 숫자) */
    public record PackageRequest(String transformScriptId, String decodeScriptId, @Size(max = 60) String driverKey,
                                 String defaultDashboardId, List<String> defaultRuleTemplateIds, JsonNode attributeSchema,
                                 Integer baseVersion) {
    }

    public record PackageResponse(String modelId, String transformScriptId, String decodeScriptId, String driverKey,
                                  String defaultDashboardId, List<String> defaultRuleTemplateIds, JsonNode attributeSchema,
                                  int version) {
    }

    /** API-DEV-47 복제 */
    public record CloneModelRequest(@NotBlank String newCode, @Size(max = 100) String name) {
    }

    // ---- 측정 항목

    public record FirstSeen(Instant at, String deviceId) {
    }

    public record SampleDto(String deviceId, double value, Instant at) {
    }

    /** 측정 항목(API-DEV-50~52) */
    public record MetricResponse(String id, String key, String displayName, String unit, String valueType, JsonNode enumMap,
                                 Double validMin, Double validMax, int precision, String aggDefault, boolean stateType,
                                 String semantic, String status, boolean builtin, List<String> aliases, FirstSeen firstSeen,
                                 List<SampleDto> sample, int version, Instant updatedAt) {
    }

    /** 무시·복원(API-DEV-54) */
    public record MetricStatusResponse(String id, String key, String status, int version) {
    }

    /** API-DEV-51 생성 */
    public record CreateMetricRequest(@NotBlank String key, @NotBlank @Size(max = 50) String displayName,
                                      @Size(max = 16) String unit, String valueType, JsonNode enumMap, Double validMin,
                                      Double validMax, Integer precision, String aggDefault, Boolean stateType,
                                      @Size(max = 32) String semantic) {
    }

    /** API-DEV-53 별칭으로 연결 */
    public record AliasToRequest(@NotBlank String targetKey, Boolean remapHistory) {
    }

    public record AliasResponse(String id, String alias, String metricKey, String metricId, Instant createdAt) {
    }

    public record AliasToResponse(AliasResponse alias, String remapJobId) {
    }

    /** API-DEV-55 별칭 추가 */
    public record CreateAliasRequest(@NotBlank String alias, @NotBlank String metricKey) {
    }

    /** API-DEV-56 재매핑 진행률 */
    public record RemapJobResponse(String id, String alias, String targetKey, String status, long processed, long total,
                                   String error, Instant createdAt, Instant updatedAt) {
    }

    // ---- 내부 API

    /** API-DEV-123 항목. decimals = precision(소수 자릿수) */
    public record InternalMetric(String id, String key, String displayName, String unit, String valueType, JsonNode enumMap,
                                 Double validMin, Double validMax, int decimals, String aggDefault, boolean stateType,
                                 String status) {
    }

    /** API-DEV-123 응답(새 버전일 때만) */
    public record InternalMetricsResponse(String organizationId, long version, List<InternalMetric> metrics,
                                          Map<String, String> aliases) {
    }

    public record UnverifiedKey(@NotBlank String key, Long deviceId, JsonNode sampleValue) {
    }

    /** API-DEV-124 요청 */
    public record RegisterUnverifiedRequest(@NotNull Long organizationId,
                                            @NotEmpty @Size(max = 500) @Valid List<UnverifiedKey> keys) {
    }

    public record RegisteredKey(String key, String metricId, String status) {
    }

    /** 이미 있는 키. 별칭이면 aliasOf에 표준 키 */
    public record ExistingKey(String key, String metricId, String status, String aliasOf) {
    }

    /** API-DEV-124 응답. invalid는 키 형식이 틀려 등록하지 않은 키 */
    public record RegisterUnverifiedResponse(List<RegisteredKey> registered, List<ExistingKey> existing, List<String> invalid) {
    }
}
