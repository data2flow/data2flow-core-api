package net.java21.data2flow.core.compliance.service;

import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.ComplianceExcursionChanged;
import net.java21.data2flow.contracts.message.event.ComplianceStreakBroken;
import net.java21.data2flow.core.compliance.domain.DailyComplianceEvaluator;
import net.java21.data2flow.core.compliance.domain.DailyComplianceEvaluator.DayStats;
import net.java21.data2flow.core.compliance.domain.DailyComplianceEvaluator.Limit;
import net.java21.data2flow.core.compliance.domain.DailyComplianceEvaluator.Run;
import net.java21.data2flow.core.compliance.domain.ProfileModels.MetricRule;
import net.java21.data2flow.core.compliance.domain.StreakCalculator;
import net.java21.data2flow.core.compliance.domain.StreakCalculator.Broken;
import net.java21.data2flow.core.compliance.domain.StreakCalculator.DayOutcome;
import net.java21.data2flow.core.compliance.domain.StreakCalculator.MetricDay;
import net.java21.data2flow.core.compliance.domain.StreakCalculator.State;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.ApplicationRow;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.SpaceRow;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository.VersionRow;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository.ExcursionRow;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository.StreakRow;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 준수 추적(CMP-02.01): 조직마다 어제·오늘(조직 시간대)을 다시 계산해 일 집계를 덮어쓰고, 이탈 구간을 맞추고(EVT-CMP-04), 닫힌 어제를 연속 달성에
 * 반영한다(EVT-CMP-05). 같은 시각에 두 번 돌아도 결과가 같다.
 *
 * <ul>
 *   <li>대상: 적용이 덮는 말단 공간마다 그 공간에 걸린 모든 적용의 프로필 버전 유지기준(시설 유형으로 거른 LIMIT)을 (버전, 항목)별로 본다</li>
 *   <li>이탈 구간은 (공간, 버전, 항목, 시작 시각)으로 찾는다. 계산 창(어제 0시)보다 먼저 시작한 진행 중 구간은 창 첫 구간이 충족 없이 이어지면
 *       그 구간으로 이어 붙이고, 아니면 창 시작에서 닫는다</li>
 *   <li>연속 달성은 버전에 연속 조건(일수·일 충족률)이 있는 프로필만 센다</li>
 * </ul>
 */
@Service
public class ComplianceTracker {

    private static final Duration LOOKBACK = Duration.ofHours(8);

    private final ComplianceApplicationRepository applications;
    private final ComplianceProfileRepository profiles;
    private final ComplianceStatusRepository status;
    private final CoreEventPublisher publisher;

    public ComplianceTracker(ComplianceApplicationRepository applications, ComplianceProfileRepository profiles, ComplianceStatusRepository status,
                             CoreEventPublisher publisher) {
        this.applications = applications;
        this.profiles = profiles;
        this.status = status;
        this.publisher = publisher;
    }

    /** 공간에서 추적할 기준 하나 */
    record Tracked(ApplicationRow application, VersionRow version, MetricRule rule) {
    }

    /** 한 조직을 now 기준으로 계산한다. 계산한 (공간, 버전, 항목) 수 */
    public int track(long orgId, Instant now) {
        ZoneId zone = ZoneId.of(status.timezone(orgId));
        Instant until = now.truncatedTo(ChronoUnit.MINUTES);
        LocalDate today = LocalDate.ofInstant(until, zone);
        LocalDate yesterday = today.minusDays(1);
        Instant windowStart = yesterday.atStartOfDay(zone).toInstant();
        int n = 0;
        for (Map.Entry<SpaceRow, List<Tracked>> e : targets(orgId).entrySet()) {
            SpaceRow space = e.getKey();
            Set<String> metrics = new LinkedHashSet<>();
            e.getValue().forEach(t -> metrics.add(t.rule().metricKey()));
            Map<String, NavigableMap<Instant, Double>> series = status.minuteSeries(orgId, space.id(), metrics, windowStart.minus(LOOKBACK), until);
            Map<Long, Map<String, MetricDay>> closedDay = new LinkedHashMap<>();
            Map<Long, VersionRow> profileVersion = new HashMap<>();
            for (Tracked t : e.getValue()) {
                MetricRule rule = t.rule();
                Limit limit = new Limit(rule.lower(), rule.inclusive(), rule.upper(), rule.inclusive());
                DailyComplianceEvaluator.Result r = DailyComplianceEvaluator.evaluate(series.getOrDefault(rule.metricKey(), new TreeMap<>()),
                        rule.evalWindow(), limit, List.of(yesterday, today), zone, until);
                for (DayStats d : r.days()) {
                    status.upsertDaily(orgId, space.id(), t.version().id(), rule.metricKey(), d, now);
                }
                reconcile(orgId, space.id(), t.version().id(), rule.metricKey(), limit, r.runs(), windowStart, now);
                DayStats y = r.days().getFirst();
                closedDay.computeIfAbsent(t.version().profileId(), k -> new LinkedHashMap<>()).put(rule.metricKey(),
                        new MetricDay(y.dailyRatio(), y.exceededMinutes(), y.missingMinutes(), y.qualityWarning()));
                profileVersion.put(t.version().profileId(), t.version());
                n++;
            }
            for (Map.Entry<Long, Map<String, MetricDay>> p : closedDay.entrySet()) {
                streak(orgId, space.id(), profileVersion.get(p.getKey()), new DayOutcome(yesterday, p.getValue()), now);
            }
        }
        return n;
    }

    /** 적용이 덮는 말단 공간 → 추적 기준((버전, 항목)마다 하나) */
    Map<SpaceRow, List<Tracked>> targets(long orgId) {
        Map<Long, SpaceRow> spaces = new LinkedHashMap<>();
        for (ApplicationRow a : applications.list(orgId, null, null)) {
            for (SpaceRow s : ComplianceApplicationService.leaves(applications.coveredSpaces(orgId, a))) {
                spaces.putIfAbsent(s.id(), s);
            }
        }
        Map<String, Optional<VersionRow>> versions = new HashMap<>();
        Map<Long, List<MetricRule>> rules = new HashMap<>();
        Map<SpaceRow, List<Tracked>> out = new LinkedHashMap<>();
        for (SpaceRow space : spaces.values()) {
            Map<String, Tracked> byKey = new LinkedHashMap<>();
            for (ApplicationRow a : applications.forSpace(orgId, space)) {
                int no = a.effectiveVersionNo();
                Optional<VersionRow> v = versions.computeIfAbsent(a.profileId() + ":" + no, k -> profiles.version(orgId, a.profileId(), no));
                if (v.isEmpty()) {
                    continue;
                }
                for (MetricRule r : rules.computeIfAbsent(v.get().id(), profiles::rules)) {
                    if (r.appliesTo(a.facilityType()) && "LIMIT".equals(r.requirement()) && (r.lower() != null || r.upper() != null)) {
                        byKey.putIfAbsent(v.get().id() + ":" + r.metricKey(), new Tracked(a, v.get(), r));
                    }
                }
            }
            if (!byKey.isEmpty()) {
                out.put(space, new ArrayList<>(byKey.values()));
            }
        }
        return out;
    }

    /** 계산한 이탈 구간을 저장된 구간과 맞춘다. 새 구간은 STARTED, 닫힌 구간은 ENDED를 낸다 */
    void reconcile(long orgId, long spaceId, long versionId, String metric, Limit limit, List<Run> runs, Instant windowStart, Instant now) {
        Optional<ExcursionRow> open = status.openExcursion(spaceId, versionId, metric);
        boolean openHandled = false;
        for (Run run : runs) {
            BigDecimal peak = BigDecimal.valueOf(run.peakValue());
            if (open.isPresent() && !openHandled && open.get().startedAt().isBefore(windowStart) && run.fromStart()) {
                ExcursionRow o = open.get();
                BigDecimal merged = limit.deviation(o.peakValue().doubleValue()) >= limit.deviation(run.peakValue()) ? o.peakValue() : peak;
                status.updateExcursion(o.id(), run.endedAt(), merged, now);
                if (run.endedAt() != null) {
                    publish(EventType.COMPLIANCE_EXCURSION_ENDED, orgId, o.id(), spaceId, metric, versionId, o.startedAt(), run.endedAt(), merged);
                }
                openHandled = true;
                continue;
            }
            Optional<ExcursionRow> existing = status.excursionStartedAt(spaceId, versionId, metric, run.startedAt());
            if (existing.isPresent()) {
                ExcursionRow x = existing.get();
                status.updateExcursion(x.id(), run.endedAt(), peak, now);
                if (x.endedAt() == null && run.endedAt() != null) {
                    publish(EventType.COMPLIANCE_EXCURSION_ENDED, orgId, x.id(), spaceId, metric, versionId, x.startedAt(), run.endedAt(), peak);
                }
                if (open.isPresent() && open.get().id() == x.id()) {
                    openHandled = true;
                }
                continue;
            }
            if (run.endedAt() == null && open.isPresent() && !openHandled) {
                // 늦게 들어온 값으로 시작 시각이 바뀌었다: 남아 있는 진행 중 구간을 새 구간 시작에서 닫는다
                closeStale(orgId, open.get(), run.startedAt(), metric, versionId, now);
                openHandled = true;
            }
            long id = status.insertExcursion(orgId, spaceId, versionId, metric, run.startedAt(), run.endedAt(), peak, now);
            publish(EventType.COMPLIANCE_EXCURSION_STARTED, orgId, id, spaceId, metric, versionId, run.startedAt(), null, peak);
            if (run.endedAt() != null) {
                publish(EventType.COMPLIANCE_EXCURSION_ENDED, orgId, id, spaceId, metric, versionId, run.startedAt(), run.endedAt(), peak);
            }
        }
        if (open.isPresent() && !openHandled && open.get().startedAt().isBefore(windowStart)) {
            // 창 첫 분부터 충족이었다: 창 시작에서 닫는다
            closeStale(orgId, open.get(), windowStart, metric, versionId, now);
        }
    }

    private void closeStale(long orgId, ExcursionRow o, Instant at, String metric, long versionId, Instant now) {
        Instant end = at.isAfter(o.startedAt()) ? at : o.startedAt().plus(Duration.ofMinutes(1));
        status.updateExcursion(o.id(), end, o.peakValue(), now);
        publish(EventType.COMPLIANCE_EXCURSION_ENDED, orgId, o.id(), o.spaceId(), metric, versionId, o.startedAt(), end, o.peakValue());
    }

    /** 닫힌 날 하나를 연속 달성에 반영한다(이미 반영한 날이면 건너뛴다) */
    void streak(long orgId, long spaceId, VersionRow version, DayOutcome day, Instant now) {
        if (version.streakDays() == null || version.streakDailyRatio() == null) {
            return;
        }
        Optional<StreakRow> current = status.streak(spaceId, version.profileId());
        if (current.isPresent() && !current.get().evaluatedOn().isBefore(day.day())) {
            return;
        }
        State start = current.map(s -> new State(s.currentDays(), s.startedOn(), s.brokenOn(), s.brokenMetric())).orElse(State.EMPTY);
        StreakCalculator.Step step = StreakCalculator.advance(start, List.of(day), version.streakDailyRatio());
        for (Broken b : step.broken()) {
            publisher.event(EventType.COMPLIANCE_STREAK_BROKEN, orgId,
                    new ComplianceStreakBroken(spaceId, version.profileId(), b.day(), b.metric(), b.previousDays()));
        }
        State s = step.state();
        status.saveStreak(orgId, new StreakRow(spaceId, version.profileId(), s.currentDays(), s.startedOn(), s.brokenOn(), s.brokenMetric(),
                day.day()), now);
    }

    private void publish(EventType type, long orgId, long id, long spaceId, String metric, long versionId, Instant startedAt, Instant endedAt,
                         BigDecimal peak) {
        publisher.event(type, orgId, new ComplianceExcursionChanged(id, spaceId, metric, versionId, startedAt, endedAt, peak));
    }
}
