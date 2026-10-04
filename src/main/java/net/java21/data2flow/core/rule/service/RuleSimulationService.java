package net.java21.data2flow.core.rule.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.rule.domain.RuleErrorCode;
import net.java21.data2flow.core.rule.domain.RuleLimits;
import net.java21.data2flow.core.rule.domain.RuleSimulator;
import net.java21.data2flow.core.rule.domain.RuleSimulator.Episode;
import net.java21.data2flow.core.rule.dto.RuleDtos.Coverage;
import net.java21.data2flow.core.rule.dto.RuleDtos.DeviceCount;
import net.java21.data2flow.core.rule.dto.RuleDtos.HeatCell;
import net.java21.data2flow.core.rule.dto.RuleDtos.SimulationResult;
import net.java21.data2flow.core.rule.repository.RuleRepository;
import net.java21.data2flow.core.rule.repository.RuleRepository.RuleRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 규칙 시뮬레이션(RUL-01.11, API-RUL-06, BR-RUL-23): 저장 전(또는 저장된) 규칙을 과거 측정값(최대 30일, 기본 7일)에 적용해 예상 알람 수·기기별
 * 횟수·요일×시간 분포를 돌려준다. 알람·알림·상태를 만들지 않는다. 측정값은 pipeline 소유 {@code data2flow_pipeline.telemetry}를 읽기만 한다
 * (conventions §6). 데이터가 많아도 동기로 답한다(측정값 상한 {@value #MAX_POINTS}점, 넘으면 앞쪽만 쓰고 coverage로 알린다).
 */
@Service
public class RuleSimulationService {

    static final int MAX_POINTS = 500_000;

    private final RuleService ruleService;
    private final RuleRepository rules;
    private final RoleChecker roleChecker;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public RuleSimulationService(RuleService ruleService, RuleRepository rules, RoleChecker roleChecker, JdbcClient jdbc, JsonMapper json,
                                 Clock clock) {
        this.ruleService = ruleService;
        this.rules = rules;
        this.roleChecker = roleChecker;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /** {@code POST /core/rules/simulate} {rule, from?, to?} — RULE_WRITE */
    @Transactional(readOnly = true)
    public SimulationResult simulateDraft(JsonNode body) {
        roleChecker.require(Permission.RULE_WRITE);
        long orgId = roleChecker.currentUser().organizationId();
        JsonNode rule = body == null ? null : body.get("rule");
        RuleService.RuleInput in = ruleService.parse(orgId, rule, false);
        ruleService.checkScope(orgId, in);
        return run(orgId, in, body);
    }

    /** {@code POST /core/rules/{rule-id}/simulate} {rule?, from?, to?} — 저장된 규칙(또는 본문의 바꾼 값)으로 */
    @Transactional(readOnly = true)
    public SimulationResult simulateSaved(long ruleId, JsonNode body) {
        roleChecker.require(Permission.RULE_WRITE);
        long orgId = roleChecker.currentUser().organizationId();
        RuleRow r = ruleService.visible(orgId, ruleId);
        RuleService.RuleInput in = body != null && body.hasNonNull("rule") ? ruleService.parse(orgId, body.get("rule"), false) : ruleService.input(r);
        return run(orgId, in, body);
    }

    SimulationResult run(long orgId, RuleService.RuleInput in, JsonNode body) {
        Instant to = body != null && body.hasNonNull("to") ? instant(body.get("to"), "to") : clock.instant();
        Instant from = body != null && body.hasNonNull("from") ? instant(body.get("from"), "from") : to.minus(Duration.ofDays(7));
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(RuleLimits.MAX_SIMULATION_DAYS)) > 0) {
            throw new BusinessException(RuleErrorCode.RULE_SIMULATION_RANGE_INVALID);
        }
        List<Long> targets = rules.listTargetDevices(orgId, in.scopeType(), in.scopeIds(), in.includeChildren(), in.summary().metrics(),
                RuleLimits.MAX_TARGETS);
        Map<Long, List<RuleSimulator.Point>> series = load(orgId, targets, in.summary().metrics(), from, to);
        return result(evaluate(in.condition(), series, to), series, targets, names(orgId, targets), to, zone(orgId));
    }

    /** 측정값으로 조건 평가(튜닝 제안에서도 씀) */
    public List<Episode> evaluate(JsonNode condition, Map<Long, List<RuleSimulator.Point>> series, Instant to) {
        return RuleSimulator.run(condition, series, to);
    }

    public Map<Long, List<RuleSimulator.Point>> load(long orgId, Collection<Long> devices, Set<String> metrics, Instant from, Instant to) {
        Map<Long, List<RuleSimulator.Point>> out = new LinkedHashMap<>();
        if (devices.isEmpty()) {
            return out;
        }
        String metricFilter = metrics.isEmpty() ? "" : " AND metric_key = ANY(CAST(:metrics AS text[]))";
        jdbc.sql("SELECT device_id, metric_key, time, value FROM data2flow_pipeline.telemetry WHERE organization_id = :org"
                        + " AND device_id = ANY(CAST(:devices AS bigint[]))" + metricFilter
                        + " AND time >= :from AND time < :to ORDER BY device_id, time LIMIT :limit")
                .param("org", orgId).param("devices", Pg.bigintArray(devices)).param("metrics", Pg.textArray(metrics))
                .param("from", Pg.ts(from)).param("to", Pg.ts(to)).param("limit", MAX_POINTS)
                .query(rs -> {
                    out.computeIfAbsent(rs.getLong("device_id"), k -> new ArrayList<>())
                            .add(new RuleSimulator.Point(Pg.instant(rs, "time"), rs.getString("metric_key"), rs.getDouble("value")));
                });
        return out;
    }

    SimulationResult result(List<Episode> episodes, Map<Long, List<RuleSimulator.Point>> series, List<Long> targets, Map<Long, String> names,
                            Instant to, ZoneId zone) {
        Map<Long, long[]> byTarget = RuleSimulator.byTarget(episodes, to);
        List<DeviceCount> byDevice = new ArrayList<>();
        byTarget.forEach((id, acc) -> byDevice.add(new DeviceCount(id == RuleSimulator.SPACE_TARGET ? "space" : Long.toString(id),
                id == RuleSimulator.SPACE_TARGET ? null : names.get(id), (int) acc[0], acc[1])));
        byDevice.sort((a, b) -> Integer.compare(b.count(), a.count()));
        Map<Integer, Integer> heat = new HashMap<>();
        long total = 0;
        for (Episode e : episodes) {
            ZonedDateTime local = e.raisedAt().atZone(zone);
            heat.merge(local.getDayOfWeek().getValue() * 100 + local.getHour(), 1, Integer::sum);
            total += e.durationSec(to);
        }
        List<HeatCell> heatmap = new ArrayList<>();
        heat.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(en -> heatmap.add(new HeatCell(en.getKey() / 100, en.getKey() % 100, en.getValue())));
        long withData = targets.stream().filter(series::containsKey).count();
        double ratio = targets.isEmpty() ? 0 : (double) withData / targets.size();
        return new SimulationResult(episodes.size(), episodes.size(), episodes.isEmpty() ? 0 : total / episodes.size(), byDevice, heatmap,
                new Coverage(Math.round(ratio * 1000) / 1000.0));
    }

    Map<Long, String> names(long orgId, List<Long> devices) {
        Map<Long, String> out = new HashMap<>();
        if (devices.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT id, name FROM data2flow_core.devices WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))")
                .param("org", orgId).param("ids", Pg.bigintArray(devices)).query(rs -> {
                    out.put(rs.getLong("id"), rs.getString("name"));
                });
        return out;
    }

    ZoneId zone(long orgId) {
        String tz = jdbc.sql("SELECT coalesce((SELECT timezone FROM data2flow_core.org_settings WHERE organization_id = :org), 'Asia/Seoul')")
                .param("org", orgId).query(String.class).single();
        try {
            return ZoneId.of(tz);
        } catch (RuntimeException ex) {
            return ZoneId.of("Asia/Seoul");
        }
    }

    static Instant instant(JsonNode v, String field) {
        try {
            return Instant.parse(v.asString(""));
        } catch (RuntimeException ex) {
            throw RuleService.invalid(field, "Pattern");
        }
    }
}
