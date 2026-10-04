package net.java21.data2flow.core.rule.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.rule.domain.RuleCondition;
import net.java21.data2flow.core.rule.domain.RuleLimits;
import net.java21.data2flow.core.rule.domain.RuleSimulator;
import net.java21.data2flow.core.rule.dto.RuleDtos.RuleDetail;
import net.java21.data2flow.core.rule.dto.RuleDtos.SaveResult;
import net.java21.data2flow.core.rule.dto.RuleDtos.SimulationCompare;
import net.java21.data2flow.core.rule.dto.RuleDtos.TuningApplied;
import net.java21.data2flow.core.rule.dto.RuleDtos.TuningSuggestion;
import net.java21.data2flow.core.rule.repository.RuleRepository;
import net.java21.data2flow.core.rule.repository.RuleRepository.RuleRow;
import net.java21.data2flow.core.rule.repository.TuningRepository;
import net.java21.data2flow.core.rule.repository.TuningRepository.RuleAlarmStats;
import net.java21.data2flow.core.rule.repository.TuningRepository.TuningRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 규칙 튜닝 제안(RUL-06.02, API-RUL-08, BR-RUL-22). 지난 7일 알람으로 문제 규칙을 찾는다:
 * <ul>
 *   <li>TOO_FREQUENT: 하루 평균 20회 이상</li>
 *   <li>FLAPPING: 플래핑이 난 알람이 있음(RUL-04.02)</li>
 *   <li>UNACKNOWLEDGED: 5건 이상 발생했는데 아무도 확인하지 않음</li>
 * </ul>
 * 임계값 규칙이면 지속 시간을 3배(최소 15분)로 늘린 조정안을 같은 7일 데이터로 시뮬레이션해(RUL-01.11) 전·후 알람 수를 붙인다. 제안은 적용 전까지
 * 규칙을 바꾸지 않고, 적용하면 새 규칙 버전(= 새 플로우 버전)을 만든다.
 */
@Service
public class RuleTuningService {

    static final int TOO_FREQUENT_PER_DAY = 20;
    static final int UNACKED_MIN = 5;

    private final TuningRepository tuning;
    private final RuleRepository rules;
    private final RuleService ruleService;
    private final RuleSimulationService simulation;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public RuleTuningService(TuningRepository tuning, RuleRepository rules, RuleService ruleService, RuleSimulationService simulation,
                             RoleChecker roleChecker, Audits audits, JsonMapper json, Clock clock) {
        this.tuning = tuning;
        this.rules = rules;
        this.ruleService = ruleService;
        this.simulation = simulation;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ListApiResponse<TuningSuggestion> list(String status, Integer page, Integer size) {
        roleChecker.require(Permission.RULE_READ);
        long orgId = roleChecker.currentUser().organizationId();
        String st = status == null || status.isBlank() ? null : RuleService.enumValue(status, Set.of("OPEN", "APPLIED", "DISMISSED"), "status");
        PageParams params = PageParams.of(page, size);
        return ListApiResponse.of(params, tuning.list(orgId, st, params.size(), params.offset()).stream().map(this::view).toList(),
                tuning.count(orgId, st));
    }

    /** 적용: 제안 조건으로 규칙을 저장(새 버전). OPEN만 */
    @Transactional
    public TuningApplied apply(long id) {
        roleChecker.require(Permission.RULE_WRITE);
        long orgId = roleChecker.currentUser().organizationId();
        TuningRow t = tuning.findById(orgId, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!"OPEN".equals(t.status())) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        RuleDetail rule = ruleService.get(t.ruleId());
        ObjectNode body = json.valueToTree(rule);
        body.set("condition", json.readTree(t.proposed()));
        body.put("baseVersion", rule.version());
        SaveResult saved = ruleService.update(t.ruleId(), body);
        tuning.updateStatus(orgId, id, "OPEN", "APPLIED", clock.instant());
        audits.record(audits.event(orgId, "RULE_TUNING_APPLIED").actor(roleChecker.currentUser()).target("RULE", Long.toString(t.ruleId()))
                .detail("suggestionId", Long.toString(id)).detail("version", saved.version()));
        return new TuningApplied(Long.toString(id), "APPLIED", Long.toString(t.ruleId()), saved.version());
    }

    @Transactional
    public void dismiss(long id) {
        roleChecker.require(Permission.RULE_WRITE);
        long orgId = roleChecker.currentUser().organizationId();
        tuning.findById(orgId, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (tuning.updateStatus(orgId, id, "OPEN", "DISMISSED", clock.instant()) == 0) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
    }

    /** 제안 만들기(하루 한 번, {@link RuleJobs}). 새로 만든 제안 수 */
    @Transactional
    public int generate(long orgId) {
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofDays(7));
        int created = 0;
        Map<Long, RuleRow> live = new java.util.HashMap<>();
        rules.listLive(orgId, since).forEach(r -> live.put(r.id(), r));
        for (RuleAlarmStats s : tuning.listRuleStats(orgId, since)) {
            RuleRow r = live.get(s.ruleId());
            if (r == null) {
                continue;
            }
            String problem = s.raised() >= TOO_FREQUENT_PER_DAY * 7L ? "TOO_FREQUENT"
                    : s.flapping() > 0 ? "FLAPPING"
                    : s.raised() >= UNACKED_MIN && s.acked() == 0 ? "UNACKNOWLEDGED" : null;
            if (problem == null) {
                continue;
            }
            JsonNode current = json.readTree(r.condition());
            JsonNode proposed = propose(current);
            SimulationCompare compare = compare(orgId, r, current, proposed, since, now);
            if (tuning.insertIfAbsent(orgId, r.id(), problem, json.writeValueAsString(current), json.writeValueAsString(proposed),
                    json.writeValueAsString(compare), now).isPresent()) {
                created++;
            }
        }
        return created;
    }

    /** 조정안: 임계값 규칙은 지속 시간 3배(최소 15분, 최대 6시간), 그 밖은 그대로 */
    static JsonNode propose(JsonNode current) {
        ObjectNode proposed = (ObjectNode) current.deepCopy();
        if ("threshold".equals(current.path("kind").asString())) {
            Duration hold = RuleCondition.parseDuration(current.path("for").asString(null));
            Duration next = hold == null ? Duration.ofMinutes(15) : hold.multipliedBy(3);
            if (next.compareTo(Duration.ofMinutes(15)) < 0) {
                next = Duration.ofMinutes(15);
            }
            if (next.compareTo(Duration.ofHours(6)) > 0) {
                next = Duration.ofHours(6);
            }
            proposed.put("for", next.toString());
        }
        return proposed;
    }

    SimulationCompare compare(long orgId, RuleRow r, JsonNode current, JsonNode proposed, Instant from, Instant to) {
        RuleService.RuleInput in = ruleService.input(r);
        List<Long> targets = rules.listTargetDevices(orgId, in.scopeType(), in.scopeIds(), in.includeChildren(), in.summary().metrics(),
                RuleLimits.MAX_TARGETS);
        Map<Long, List<RuleSimulator.Point>> series = simulation.load(orgId, targets, in.summary().metrics(), from, to);
        return new SimulationCompare(simulation.evaluate(current, series, to).size(), simulation.evaluate(proposed, series, to).size());
    }

    TuningSuggestion view(TuningRow t) {
        SimulationCompare sim = t.simulation() == null ? null : json.readValue(t.simulation(), SimulationCompare.class);
        return new TuningSuggestion(Long.toString(t.id()), Long.toString(t.ruleId()), t.ruleName(), t.problem(), json.readTree(t.current()),
                json.readTree(t.proposed()), sim, t.status(), t.createdAt());
    }
}
