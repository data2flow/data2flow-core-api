package net.java21.data2flow.core.rule.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.common.CountedListResponse;
import net.java21.data2flow.core.common.ItemsResponse;
import net.java21.data2flow.core.rule.dto.RuleDtos.ConvertResult;
import net.java21.data2flow.core.rule.dto.RuleDtos.Draft;
import net.java21.data2flow.core.rule.dto.RuleDtos.RuleDetail;
import net.java21.data2flow.core.rule.dto.RuleDtos.RuleSummary;
import net.java21.data2flow.core.rule.dto.RuleDtos.RuleTemplate;
import net.java21.data2flow.core.rule.dto.RuleDtos.SaveResult;
import net.java21.data2flow.core.rule.dto.RuleDtos.SimulationResult;
import net.java21.data2flow.core.rule.dto.RuleDtos.StatusResult;
import net.java21.data2flow.core.rule.dto.RuleDtos.TuningApplied;
import net.java21.data2flow.core.rule.dto.RuleDtos.TuningSuggestion;
import net.java21.data2flow.core.rule.service.RuleService;
import net.java21.data2flow.core.rule.service.RuleSimulationService;
import net.java21.data2flow.core.rule.service.RuleTuningService;
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

/** 규칙(RUL-01·06, API-RUL-01~08). 외부 {@code /api/v1/core/rules/**} → gateway stripPrefix(2) → {@code /core/rules/**} */
@RestController
public class RuleController {

    private final RuleService rules;
    private final RuleSimulationService simulation;
    private final RuleTuningService tuning;

    public RuleController(RuleService rules, RuleSimulationService simulation, RuleTuningService tuning) {
        this.rules = rules;
        this.simulation = simulation;
        this.tuning = tuning;
    }

    @GetMapping("/core/rules")
    public CountedListResponse<RuleSummary> list(@RequestParam(required = false) String q, @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String status, @RequestParam(required = false) String severity,
                                                 @RequestParam(required = false) String spaceId, @RequestParam(required = false) String templateKey,
                                                 @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return rules.list(q != null ? q : keyword, status, severity, spaceId, templateKey, page, size);
    }

    @PostMapping("/core/rules")
    @Idempotent
    public ResponseEntity<ApiResponse<SaveResult>> create(@RequestBody JsonNode body) {
        SaveResult saved = rules.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/rules/" + saved.ruleId())).body(ApiResponse.success(saved));
    }

    @GetMapping("/core/rules/{rule-id}")
    public ApiResponse<RuleDetail> get(@PathVariable("rule-id") long ruleId) {
        return ApiResponse.success(rules.get(ruleId));
    }

    @PutMapping("/core/rules/{rule-id}")
    public ApiResponse<SaveResult> update(@PathVariable("rule-id") long ruleId, @RequestBody JsonNode body) {
        return ApiResponse.success(rules.update(ruleId, body));
    }

    @PostMapping("/core/rules/{rule-id}/activate")
    public ApiResponse<StatusResult> activate(@PathVariable("rule-id") long ruleId) {
        return ApiResponse.success(rules.activate(ruleId));
    }

    @PostMapping("/core/rules/{rule-id}/deactivate")
    public ApiResponse<StatusResult> deactivate(@PathVariable("rule-id") long ruleId) {
        return ApiResponse.success(rules.deactivate(ruleId));
    }

    @DeleteMapping("/core/rules/{rule-id}")
    public ResponseEntity<Void> delete(@PathVariable("rule-id") long ruleId, @RequestParam(required = false) Boolean clearOpenAlarms) {
        rules.delete(ruleId, clearOpenAlarms);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/core/rules/{rule-id}/convert-to-flow")
    public ApiResponse<ConvertResult> convert(@PathVariable("rule-id") long ruleId) {
        return ApiResponse.success(rules.convert(ruleId));
    }

    @GetMapping("/core/rule-templates")
    public ItemsResponse<RuleTemplate> templates() {
        return ItemsResponse.of(rules.templates());
    }

    @PostMapping("/core/rules/simulate")
    public ApiResponse<SimulationResult> simulateDraft(@RequestBody JsonNode body) {
        return ApiResponse.success(simulation.simulateDraft(body));
    }

    @PostMapping("/core/rules/{rule-id}/simulate")
    public ApiResponse<SimulationResult> simulate(@PathVariable("rule-id") long ruleId, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(simulation.simulateSaved(ruleId, body));
    }

    @PostMapping("/core/rules/draft-from-chart")
    public ApiResponse<Draft> draft(@RequestBody JsonNode body) {
        return ApiResponse.success(rules.draftFromChart(body));
    }

    @GetMapping("/core/rule-tuning-suggestions")
    public ListApiResponse<TuningSuggestion> suggestions(@RequestParam(required = false) String status,
                                                         @RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size) {
        return tuning.list(status, page, size);
    }

    @PostMapping("/core/rule-tuning-suggestions/{tuning-suggestion-id}/apply")
    public ApiResponse<TuningApplied> apply(@PathVariable("tuning-suggestion-id") long id) {
        return ApiResponse.success(tuning.apply(id));
    }

    @PostMapping("/core/rule-tuning-suggestions/{tuning-suggestion-id}/dismiss")
    public ResponseEntity<Void> dismiss(@PathVariable("tuning-suggestion-id") long id) {
        tuning.dismiss(id);
        return ResponseEntity.noContent().build();
    }
}
