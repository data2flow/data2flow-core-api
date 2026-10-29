package net.java21.data2flow.core.compliance.domain;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** 기준 프로필 버전의 내용(CMP-01.01, API-CMP §1.1). 저장·비교·복제에 같은 모양을 쓴다 */
public final class ProfileModels {

    public static final Set<String> EVAL_WINDOWS = Set.of("INSTANT", "HOUR_1", "HOUR_8", "DAY_1");
    public static final Set<String> REQUIREMENTS = Set.of("LIMIT", "CONTINUOUS_MONITORING_ONLY", "RECOMMENDED");
    public static final Set<String> COVERAGE_KINDS = Set.of("DEVICE_DENSITY", "PUBLIC_DISPLAY_DENSITY", "CALIBRATION_INTERVAL", "DATA_QUALITY");
    public static final String CONTINUOUS = "CONTINUOUS_MONITORING_ONLY";

    private ProfileModels() {
    }

    /** 등급 구간. 기준 등급(isBase)은 규칙당 하나이고 규칙의 기준값과 같다 */
    public record Band(String name, BigDecimal lower, BigDecimal upper, boolean base) {
    }

    /**
     * 항목 기준. {@code inclusive}는 경계값을 충족으로 보는가(국내법 "이하"는 true, RESET "&lt;"는 false). 연속 측정만 요구하는 항목은
     * 기준값과 등급이 없다
     */
    public record MetricRule(String metricKey, String facilityType, String evalWindow, BigDecimal lower, BigDecimal upper, boolean inclusive,
                             String unit, String requirement, String note, List<Band> bands) {

        public MetricRule {
            bands = bands == null ? List.of() : List.copyOf(bands);
        }

        public boolean continuousOnly() {
            return CONTINUOUS.equals(requirement);
        }

        /** 이 시설 유형에 쓰는 규칙인가(null 규칙은 모든 유형) */
        public boolean appliesTo(String facility) {
            return facilityType == null || facilityType.equals(facility);
        }
    }

    public record Coverage(String kind, JsonNode params) {
    }

    /** 버전 하나의 내용 */
    public record VersionSpec(String sourceTitle, String sourceUrl, LocalDate effectiveDate, String description, Integer streakDays,
                              BigDecimal streakDailyRatio, boolean facilityTypeRequired, String changeNote, List<MetricRule> metrics,
                              List<Coverage> coverage) {

        public VersionSpec {
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
            coverage = coverage == null ? List.of() : List.copyOf(coverage);
        }

        public VersionSpec withChangeNote(String note) {
            return new VersionSpec(sourceTitle, sourceUrl, effectiveDate, description, streakDays, streakDailyRatio, facilityTypeRequired, note,
                    metrics, coverage);
        }

        /** 이 버전이 정한 시설 유형(국내법: 시설 묶음) */
        public List<String> facilityTypes() {
            return metrics.stream().map(MetricRule::facilityType).filter(java.util.Objects::nonNull).distinct().toList();
        }
    }
}
