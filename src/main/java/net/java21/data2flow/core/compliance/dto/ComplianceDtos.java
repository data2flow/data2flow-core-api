package net.java21.data2flow.core.compliance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 환경 기준 프로필·적용(API-CMP-01~09·26) 모양 */
public final class ComplianceDtos {

    private ComplianceDtos() {
    }

    public record UserRef(String userId, String name) {
    }

    public record Named(String id, String name) {
    }

    public record BandView(String name, BigDecimal lower, BigDecimal upper, @JsonProperty("isBase") boolean isBase) {
    }

    /** 항목 기준. {@code inclusive}는 경계값을 충족으로 보는가(이하·이상 true, 미만·초과 false) */
    public record MetricView(String metricKey, String facilityType, String evalWindow, BigDecimal lower, BigDecimal upper, boolean inclusive,
                             String unit, String requirement, String note, List<BandView> bands) {
    }

    public record CoverageView(String kind, JsonNode params) {
    }

    public record Streak(Integer days, BigDecimal dailyRatio) {
    }

    public record VersionView(int versionNo, String sourceTitle, String sourceUrl, LocalDate effectiveDate, String description, Streak streak,
                              boolean facilityTypeRequired, List<String> facilityTypes, String changeNote, boolean locked,
                              List<MetricView> metrics, List<CoverageView> coverage, UserRef createdBy, Instant createdAt) {
    }

    public record Usage(long applications, long reports) {
    }

    /** API-CMP-03 버전 목록 */
    public record VersionSummary(int versionNo, String changeNote, UserRef createdBy, Instant createdAt, boolean locked, Usage usage) {
    }

    /** API-CMP-01 목록 */
    public record ProfileSummary(String profileId, String kind, String code, String name, boolean archived, int currentVersionNo,
                                 String sourceTitle, LocalDate effectiveDate, long applicationCount, Instant updatedAt) {
    }

    public record ApplicationRef(String applicationId, String targetType, String targetSpaceId, String targetUsage) {
    }

    /** API-CMP-02 상세(현재 버전 전체) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProfileDetail(String profileId, String kind, String code, String name, boolean archived, int currentVersionNo,
                                VersionView currentVersion, List<ApplicationRef> applications, int version, Instant updatedAt) {
    }

    public record MeasurabilitySummary(int measurable, int partial, int unmeasurable) {
    }

    /** API-CMP-07 적용 요청. {@code includeDescendants} 기본 true */
    public record CreateApplicationRequest(String profileId, Integer pinnedVersionNo, String targetType, String targetSpaceId, String targetUsage,
                                           Boolean includeDescendants, String facilityType) {
    }

    /** API-CMP-06 복제 요청. {@code versionNo} 기본 현재 버전 */
    public record CloneRequest(String name, Integer versionNo) {
    }

    /** API-CMP-07 적용 */
    public record ApplicationView(String applicationId, String profileId, String profileName, Integer pinnedVersionNo, int versionNo,
                                  String targetType, String targetSpaceId, String targetName, String targetUsage, boolean includeDescendants,
                                  String facilityType, UserRef appliedBy, Instant appliedAt, MeasurabilitySummary measurability) {
    }

    /** API-CMP-08 항목별 측정 가능성 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MeasurabilityItem(String metricKey, String status, String reason, List<Named> spacesWithout, List<Named> devices) {
    }

    public record SourceView(String profileId, String profileName, int versionNo, String band) {
    }

    /** API-CMP-09 유효 기준 한 항목. 출처가 비면 공간 목표 환경에서 온 기준이다 */
    public record CriterionView(String metricKey, BigDecimal lower, Boolean lowerInclusive, BigDecimal upper, Boolean upperInclusive, String unit,
                                String evalWindow, List<SourceView> sources, SourceView strictestSource, boolean overridesTarget) {
    }

    /** {@code minInclusive}·{@code maxInclusive}는 경계값을 충족으로 보는가(RESET "&lt;"는 false). 명세 모양에 더한 필드 */
    public record InternalCriterion(String metricKey, String unit, String evaluation, BigDecimal min, Boolean minInclusive, BigDecimal max,
                                    Boolean maxInclusive, String sourceProfileId, Integer sourceProfileVersionNo) {
    }

    /** API-CMP-26 내부 유효 기준 */
    public record InternalCriteria(String spaceId, String basis, List<InternalCriterion> criteria, Instant computedAt) {
    }
}
