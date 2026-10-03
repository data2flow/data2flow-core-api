package net.java21.data2flow.core.catalog.domain;

import java.time.Instant;
import java.util.List;

/** 기기 모델(DEV-03)·측정 항목(DEV-04) 저장 모양. JSON 열은 문자열로 들고 서비스가 해석한다 */
public final class CatalogModels {

    /** 모델 상태(DEPRECATED는 새 기기에 지정할 수 없다, BR-DEV-15) */
    public static final String MODEL_ACTIVE = "ACTIVE";
    public static final String MODEL_DEPRECATED = "DEPRECATED";

    /** 측정 항목 상태(domain-model §3.4) */
    public static final String VERIFIED = "VERIFIED";
    public static final String UNVERIFIED = "UNVERIFIED";
    public static final String IGNORED = "IGNORED";

    public static final List<String> PROTOCOLS = List.of("LORAWAN", "MQTT", "HTTP", "VIRTUAL", "OTHER");
    public static final List<String> KINDS = List.of("SENSOR", "ACTUATOR", "GATEWAY", "HYBRID");
    public static final List<String> VALUE_TYPES = List.of("NUMBER", "BOOLEAN", "ENUM");
    public static final List<String> AGGREGATIONS = List.of("AVG", "SUM", "MAX", "MIN", "LAST", "COUNT");
    public static final List<String> METRIC_STATUSES = List.of(VERIFIED, UNVERIFIED, IGNORED);

    /** 모델 코드 형식(device_models.ck_device_models_code) */
    public static final String MODEL_CODE_PATTERN = "^[A-Z0-9][A-Z0-9._-]{1,49}$";
    /** 측정 키 형식. 대소문자를 섞을 수 있다(센서가 보내는 키 그대로, 예: LAeq — 2026-10-04 결정) */
    public static final String METRIC_KEY_PATTERN = "^[A-Za-z][A-Za-z0-9_]{0,63}$";

    private CatalogModels() {
    }

    public record DeviceModel(long id, long organizationId, String code, String vendor, String name, String protocol, String kind,
                              int defaultIntervalSec, double defaultOfflineMultiplier, String description,
                              String imageObjectKey, boolean builtin, Long transformScriptId, Long decodeScriptId,
                              String driverKey, Long defaultDashboardId, List<Long> defaultRuleTemplateIds,
                              String attributeSchemaJson, String capabilitiesJson, String status, int version,
                              Instant updatedAt) {
    }

    /** 모델이 내는 측정 항목(model_metrics) */
    public record ModelMetric(String key, boolean required) {
    }

    /** 모델 기본 정보(생성·수정 값) */
    public record ModelFields(String vendor, String name, String protocol, String kind, int defaultIntervalSec,
                              double defaultOfflineMultiplier, String description, String capabilitiesJson) {
    }

    /** 모델 패키지(API-DEV-42) */
    public record ModelPackage(Long transformScriptId, Long decodeScriptId, String driverKey, Long defaultDashboardId,
                               List<Long> defaultRuleTemplateIds, String attributeSchemaJson) {
    }

    public record Metric(long id, long organizationId, String key, String displayName, String unit, String valueType,
                         String enumMapJson, Double validMin, Double validMax, int precision, String aggDefault,
                         boolean stateType, String semantic, String status, Instant firstSeenAt, Long firstSeenDeviceId,
                         boolean builtin, int version, Instant updatedAt) {
    }

    /** 측정 항목 정의 값(생성·수정·표준 등록) */
    public record MetricFields(String displayName, String unit, String valueType, String enumMapJson, Double validMin,
                               Double validMax, int precision, String aggDefault, boolean stateType, String semantic) {
    }

    public record MetricAlias(long id, long organizationId, String alias, String metricKey, Long metricId, Long createdBy,
                              Instant createdAt) {
    }

    /** 최근값 예시(UI-DEV-09 미검증 탭) */
    public record Sample(long deviceId, double value, Instant at) {
    }

    /** 과거 시계열 키 재매핑 작업(BR-DEV-16) */
    public record RemapJob(long id, long organizationId, String alias, String targetKey, String status, long total,
                           long processed, String pipelineJobId, String error, Instant createdAt, Instant updatedAt) {
    }
}
