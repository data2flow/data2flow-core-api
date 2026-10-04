package net.java21.data2flow.core.rule.service;

import net.java21.data2flow.contracts.alarm.AlarmClearReason;
import net.java21.data2flow.contracts.alarm.AlarmKeys;
import net.java21.data2flow.contracts.alarm.AlarmSeverity;
import net.java21.data2flow.contracts.alarm.AlarmSourceType;
import net.java21.data2flow.core.alarm.repository.AlarmRepository;
import net.java21.data2flow.core.alarm.service.AlarmService;
import net.java21.data2flow.core.rule.domain.RuleLimits;
import net.java21.data2flow.core.rule.repository.RuleRepository;
import net.java21.data2flow.core.rule.repository.RuleRepository.RuleRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 규칙 상태 점검(RUL-06.03, BR-RUL-06·20): 대상 기기가 0대가 되거나 조건의 측정 항목이 삭제(IGNORED)되면 ERROR(NO_TARGET·METRIC_DELETED),
 * 원인이 해소되면 ACTIVE. ERROR가 되면 WARNING 시스템 알람 {@code system:RULE_ERROR:{ruleId}}로 관리자에게 알리고(알림 정책), 회복하면 해제한다.
 * 범위에서 빠진 기기의 열린 알람은 RULE_SCOPE_CHANGED로 해제한다. 대상 수(target_count)를 갱신한다.
 */
@Service
public class RuleHealthService {

    public static final String RULE_ERROR = "RULE_ERROR";

    private final RuleRepository rules;
    private final RuleService ruleService;
    private final AlarmRepository alarmRepository;
    private final AlarmService alarms;
    private final Clock clock;

    public RuleHealthService(RuleRepository rules, RuleService ruleService, AlarmRepository alarmRepository, AlarmService alarms, Clock clock) {
        this.rules = rules;
        this.ruleService = ruleService;
        this.alarmRepository = alarmRepository;
        this.alarms = alarms;
        this.clock = clock;
    }

    /** 결정(BR-RUL-20): 새 상태와 사유. 바꿀 것이 없으면 현재 값 그대로 */
    public record Decision(String status, String reason) {
    }

    /**
     * @param status        지금 상태(ACTIVE·ERROR)
     * @param reason        지금 오류 사유
     * @param targetCount   지금 대상 수
     * @param metricMissing 조건의 측정 항목이 조직에 없음
     */
    public static Decision decide(String status, String reason, int targetCount, boolean metricMissing) {
        if (metricMissing) {
            return new Decision("ERROR", "METRIC_DELETED");
        }
        if (targetCount == 0) {
            return new Decision("ERROR", "NO_TARGET");
        }
        if ("ERROR".equals(status) && ("NO_TARGET".equals(reason) || "METRIC_DELETED".equals(reason))) {
            return new Decision("ACTIVE", null);
        }
        return new Decision(status, reason);
    }

    /** 조직 하나 점검. 상태를 바꾼 규칙 수 */
    @Transactional
    public int check(long orgId) {
        Instant now = clock.instant();
        Set<String> metricKeys = new HashSet<>(rules.listMetricKeys(orgId));
        int changed = 0;
        for (RuleRow r : rules.listLive(orgId, now.minus(Duration.ofDays(7)))) {
            RuleService.RuleInput in = ruleService.input(r);
            List<Long> targets = rules.listTargetDevices(orgId, in.scopeType(), in.scopeIds(), in.includeChildren(), in.summary().metrics(),
                    RuleLimits.MAX_TARGETS + 1);
            boolean missing = !metricKeys.containsAll(in.summary().metrics());
            Decision d = decide(r.status(), r.errorReason(), targets.size(), missing);
            rules.updateTargetCount(orgId, r.id(), targets.size());
            if (!d.status().equals(r.status()) || !java.util.Objects.equals(d.reason(), r.errorReason())) {
                rules.updateStatus(orgId, r.id(), d.status(), d.reason(), null, now);
                String key = AlarmKeys.system(RULE_ERROR, Long.toString(r.id()));
                if ("ERROR".equals(d.status())) {
                    alarms.raise(new AlarmService.Raise(orgId, key, AlarmSourceType.SYSTEM, r.id(), null, null, AlarmSeverity.WARNING,
                            "규칙 오류: " + r.name() + " (" + d.reason() + ")", null, null, null, null, null, null, now, "SYSTEM", false, false));
                } else {
                    alarms.clearByKey(orgId, key, null, now, AlarmClearReason.AUTO, "SYSTEM", null);
                }
                changed++;
            }
            if (!in.summary().spaceTarget()) {
                Set<Long> inScope = new HashSet<>(targets);
                for (Map.Entry<Long, Long> e : rules.listOpenAlarmDevices(orgId, r.id())) {
                    if (!inScope.contains(e.getValue())) {
                        alarmRepository.lockById(orgId, e.getKey()).filter(AlarmRepository.AlarmRow::open)
                                .ifPresent(a -> alarms.clear(a, AlarmClearReason.RULE_SCOPE_CHANGED, null, now, "SYSTEM", null, null));
                    }
                }
            }
        }
        return changed;
    }
}
