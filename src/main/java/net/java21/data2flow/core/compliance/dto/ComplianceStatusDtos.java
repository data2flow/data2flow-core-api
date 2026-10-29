package net.java21.data2flow.core.compliance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.java21.data2flow.core.compliance.dto.ComplianceDtos.UserRef;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 종합 점수(API-CMP-10·27)와 준수 현황(API-CMP-13~15) 모양. 비율은 모두 %(0~100)다 */
public final class ComplianceStatusDtos {

    private ComplianceStatusDtos() {
    }

    public record BoundaryView(BigDecimal lower, BigDecimal upper) {
    }

    public record ScoreInput(String metricKey, Double avg5m, String unit, double weight, BoundaryView boundary) {
    }

    /** API-CMP-10·27 현재 점수. {@code indices}에서 최근 10분 데이터가 없는 항목은 null */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScoreView(String spaceId, Instant time, Integer score, String grade, Map<String, Integer> indices, String topPenaltyMetric,
                            Double topPenaltyValue, String basis, List<String> excludedMetrics, boolean partial, List<String> profileVersions,
                            String formulaVersion, List<ScoreInput> inputs) {
    }

    public record HistoryPointView(Instant time, Double score, String grade, String topPenaltyMetric) {
    }

    public record ScoreHistory(String spaceId, String step, List<HistoryPointView> points) {
    }

    public record WeightView(String metricKey, BigDecimal weight, BigDecimal defaultWeight) {
    }

    public record GradeBand(String grade, int minScore, int maxScore) {
    }

    /** API-CMP-10 공개 계산식. {@code version}은 가중치 저장의 baseVersion이다(명세 모양에 더한 필드) */
    public record ScoreFormula(String formulaVersion, String description, String indexRule, List<WeightView> weights, List<GradeBand> gradeBands,
                               Map<String, BoundaryView> defaultCriteria, UserRef updatedBy, Instant updatedAt, int version) {
    }

    public record WeightsRequest(Map<String, Object> weights, Integer baseVersion) {
    }

    public record WeightsView(String formulaVersion, List<WeightView> weights, int version, Instant updatedAt) {
    }

    public record SpaceRef(String id, String name, String path) {
    }

    public record ProfileRef(String id, String name, int versionNo) {
    }

    public record MetricHours(String metricKey, BigDecimal hours) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StreakView(int currentDays, Integer requiredDays, LocalDate brokenOn, String brokenMetric) {
    }

    /** API-CMP-13 한 줄(공간 × 프로필) */
    public record StatusRow(SpaceRef space, ProfileRef profile, BigDecimal ratio, List<MetricHours> exceededByMetric, BigDecimal missingRatio,
                            boolean qualityWarning, StreakView streak, Integer score) {
    }

    public record StatusSummary(BigDecimal avgRatio, BigDecimal exceededHours, int streakingSpaces, int qualityWarnings) {
    }

    public record StatusView(StatusSummary summary, List<StatusRow> rows) {
    }

    public record MetricDayView(String metricKey, BigDecimal ratio, int metMinutes, int exceededMinutes, int missingMinutes,
                                boolean qualityWarning) {
    }

    /** API-CMP-14 하루(항목 합). {@code byMetric}은 명세 모양에 더한 항목별 값 */
    public record DailyView(LocalDate day, BigDecimal ratio, int metMinutes, int exceededMinutes, int missingMinutes, boolean qualityWarning,
                            List<MetricDayView> byMetric) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExcursionView(String id, String metricKey, Instant startedAt, Instant endedAt, BigDecimal peakValue, String alarmId,
                                boolean hasAction) {
    }

    public record SpaceStatusView(List<DailyView> daily, List<ExcursionView> excursions) {
    }
}
