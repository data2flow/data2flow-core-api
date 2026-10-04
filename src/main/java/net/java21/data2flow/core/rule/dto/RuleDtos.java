package net.java21.data2flow.core.rule.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** 규칙 API(API-RUL-01~08) 응답. ID는 문자열 */
public final class RuleDtos {

    private RuleDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserRef(String userId, String name) {
    }

    public record Scope(String type, List<String> ids, boolean includeChildren, int targetCount) {
    }

    public record Stats7d(long raised) {
    }

    /** API-RUL-01 목록 항목 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleSummary(String ruleId, String name, String status, String errorReason, String conditionSummary, Scope scope,
                              String severity, String templateKey, Stats7d stats7d, long openAlarms, UserRef updatedBy, Instant updatedAt) {
    }

    /** 규칙 상세(API-RUL-02 형식 + 상태) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleDetail(String ruleId, String name, String templateKey, String status, String errorReason, Scope scope,
                             JsonNode condition, String conditionSummary, JsonNode timeCondition, String severity, String titleTemplate,
                             boolean autoClear, String policyId, String flowId, int version, Stats7d stats7d, long openAlarms,
                             UserRef updatedBy, Instant createdAt, Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Warning(String code, String field, String message) {
    }

    /** API-RUL-02·03 응답 */
    public record SaveResult(String ruleId, int version, String status, String errorReason, int targetCount, String flowId,
                             List<Warning> warnings) {
    }

    public record StatusResult(String ruleId, String status, String errorReason) {
    }

    public record ConvertResult(String flowId) {
    }

    public record TemplateDefaults(JsonNode condition, String severity, String titleTemplate) {
    }

    /** API-RUL-05 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleTemplate(String key, String name, String description, String category, TemplateDefaults defaults,
                               JsonNode paramsSchema, List<String> requiredMetrics, boolean builtin) {
    }

    /** API-RUL-07 규칙 폼 기본값(저장하지 않음) */
    public record Draft(String name, String templateKey, Scope scope, JsonNode condition, String severity, String titleTemplate,
                        boolean autoClear) {
    }

    public record DeviceCount(String deviceId, String name, int count, long longestSec) {
    }

    public record HeatCell(int dow, int hour, int count) {
    }

    public record Coverage(double dataRatio) {
    }

    /** API-RUL-06 */
    public record SimulationResult(int alarms, int notifications, long avgDurationSec, List<DeviceCount> byDevice, List<HeatCell> heatmap,
                                   Coverage coverage) {
    }

    public record SimulationCompare(int alarmsBefore, int alarmsAfter) {
    }

    /** API-RUL-08 */
    public record TuningSuggestion(String tuningSuggestionId, String ruleId, String ruleName, String problem, JsonNode current,
                                   JsonNode proposed, SimulationCompare simulation, String status, Instant createdAt) {
    }

    public record TuningApplied(String tuningSuggestionId, String status, String ruleId, int ruleVersion) {
    }
}
