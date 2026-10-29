package net.java21.data2flow.core.compliance.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.compliance.domain.ComplianceErrorCode;
import net.java21.data2flow.core.compliance.domain.DailyComplianceEvaluator;
import net.java21.data2flow.core.compliance.domain.ProfileBodyReader;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.DailyView;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.ExcursionView;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.MetricDayView;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.MetricHours;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.ProfileRef;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.SpaceRef;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.SpaceStatusView;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.StatusRow;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.StatusSummary;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.StatusView;
import net.java21.data2flow.core.compliance.dto.ComplianceStatusDtos.StreakView;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.SpaceRow;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository.DailyRow;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository.ScoreRow;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository.StreakRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 준수 현황(CMP-02.01, API-CMP-13~15). 일 집계를 기간으로 더해 (공간, 프로필) 한 줄로 보여 준다. 비율은 모두 %다.
 *
 * <ul>
 *   <li>충족률 = Σ충족 분 / (Σ충족 + Σ이탈 분), 결측률 = Σ결측 분 / Σ(충족 + 이탈 + 결측 분). 오늘 행은 지난 분만 센다</li>
 *   <li>품질 경고는 결측률 10% 초과. 연속 달성은 저장된 값(어제까지)이고 요구 일수는 프로필 현재 버전의 값이다</li>
 *   <li>공간 범위가 제한된 사용자에게는 범위 밖 공간 줄을 빼고, 범위 밖 siteId·spaceId는 404다. {@code band}는 아직 받기만 한다</li>
 * </ul>
 */
@Service
public class ComplianceStatusService {

    static final int MAX_DAYS = 400;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
    static final String CSV_HEADER = "spaceId,spaceName,spacePath,profileId,profileName,versionNo,ratio,exceededHours,missingRatio,"
            + "qualityWarning,streakDays,requiredDays,brokenOn,brokenMetric,score\r\n";

    private final ComplianceApplicationRepository applications;
    private final ComplianceStatusRepository status;
    private final RoleChecker roleChecker;
    private final Clock clock;

    public ComplianceStatusService(ComplianceApplicationRepository applications, ComplianceStatusRepository status, RoleChecker roleChecker,
                                   Clock clock) {
        this.applications = applications;
        this.status = status;
        this.roleChecker = roleChecker;
        this.clock = clock;
    }

    /** API-CMP-13 현황판 */
    @Transactional(readOnly = true)
    public StatusView status(String siteId, String spaceId, String profileId, String from, String to) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        LocalDate[] period = period(from, to);
        List<Long> spaces = narrow(orgId, narrow(orgId, null, id(siteId, "siteId")), id(spaceId, "spaceId"));
        SpaceScope scope = roleChecker.spaceScope();
        List<DailyRow> rows = status.daily(orgId, period[0], period[1], spaces, id(profileId, "profileId")).stream()
                .filter(r -> scope.includes(r.spaceId())).toList();
        return build(orgId, rows);
    }

    /** API-CMP-15 현황판 CSV(UTF-8 BOM, API-CMP-13의 줄과 같은 열) */
    @Transactional(readOnly = true)
    public String csv(String siteId, String spaceId, String profileId, String from, String to) {
        StatusView view = status(siteId, spaceId, profileId, from, to);
        StringBuilder csv = new StringBuilder("﻿").append(CSV_HEADER);
        for (StatusRow r : view.rows()) {
            BigDecimal hours = r.exceededByMetric().stream().map(MetricHours::hours).reduce(BigDecimal.ZERO, BigDecimal::add);
            csv.append(String.join(",", cell(r.space().id()), cell(r.space().name()), cell(r.space().path()), cell(r.profile().id()),
                    cell(r.profile().name()), cell(Integer.toString(r.profile().versionNo())), cell(text(r.ratio())), cell(hours.toPlainString()),
                    cell(text(r.missingRatio())), cell(Boolean.toString(r.qualityWarning())), cell(Integer.toString(r.streak().currentDays())),
                    cell(r.streak().requiredDays() == null ? null : r.streak().requiredDays().toString()),
                    cell(r.streak().brokenOn() == null ? null : r.streak().brokenOn().toString()), cell(r.streak().brokenMetric()),
                    cell(r.score() == null ? null : r.score().toString()))).append("\r\n");
        }
        return csv.toString();
    }

    /** API-CMP-14 공간 하나의 일별 값과 이탈 구간 */
    @Transactional(readOnly = true)
    public SpaceStatusView space(long spaceId, String profileId, String from, String to) {
        roleChecker.require(Permission.CMP_READ);
        long orgId = roleChecker.currentUser().organizationId();
        applications.space(orgId, spaceId).orElseThrow(() -> new BusinessException(ComplianceErrorCode.SPACE_NOT_FOUND));
        roleChecker.requireSpace(spaceId, ComplianceErrorCode.SPACE_NOT_FOUND);
        LocalDate[] period = period(from, to);
        Long profile = id(profileId, "profileId");
        Map<LocalDate, List<DailyRow>> byDay = new TreeMap<>();
        for (DailyRow r : status.daily(orgId, period[0], period[1], List.of(spaceId), profile)) {
            byDay.computeIfAbsent(r.day(), k -> new ArrayList<>()).add(r);
        }
        List<DailyView> daily = new ArrayList<>();
        byDay.forEach((day, rows) -> {
            Sums s = Sums.of(rows);
            List<MetricDayView> metrics = rows.stream().map(r -> new MetricDayView(r.metricKey(), r.dailyRatio(), r.metMinutes(),
                    r.exceededMinutes(), r.missingMinutes(), r.qualityWarning())).toList();
            daily.add(new DailyView(day, s.ratio(), s.met, s.exceeded, s.missing, rows.stream().anyMatch(DailyRow::qualityWarning), metrics));
        });
        ZoneId zone = ZoneId.of(status.timezone(orgId));
        List<ExcursionView> excursions = status.excursions(orgId, spaceId, profile, period[0].atStartOfDay(zone).toInstant(),
                        period[1].plusDays(1).atStartOfDay(zone).toInstant()).stream()
                .map(x -> new ExcursionView(Long.toString(x.id()), x.metricKey(), x.startedAt(), x.endedAt(), x.peakValue(),
                        x.alarmId() == null ? null : Long.toString(x.alarmId()), false)).toList();
        return new SpaceStatusView(daily, excursions);
    }

    /** 공간과 하위 공간으로 좁힌다(null이면 그대로) */
    private List<Long> narrow(long orgId, List<Long> current, Long spaceId) {
        if (spaceId == null) {
            return current;
        }
        SpaceRow s = applications.space(orgId, spaceId).orElseThrow(() -> new BusinessException(ComplianceErrorCode.SPACE_NOT_FOUND));
        roleChecker.requireSpace(spaceId, ComplianceErrorCode.SPACE_NOT_FOUND);
        List<Long> subtree = status.subtreeIds(orgId, s.path());
        return current == null ? subtree : current.stream().filter(subtree::contains).toList();
    }

    private StatusView build(long orgId, List<DailyRow> rows) {
        Map<String, List<DailyRow>> groups = new LinkedHashMap<>();
        for (DailyRow r : rows) {
            groups.computeIfAbsent(r.spaceId() + ":" + r.profileId(), k -> new ArrayList<>()).add(r);
        }
        Set<Long> spaceIds = new LinkedHashSet<>();
        Set<Long> profileIds = new LinkedHashSet<>();
        rows.forEach(r -> {
            spaceIds.add(r.spaceId());
            profileIds.add(r.profileId());
        });
        Map<Long, SpaceRow> spaces = status.spaces(orgId, spaceIds);
        Map<Long, Integer> required = status.requiredDays(profileIds);
        Map<String, StreakRow> streaks = new HashMap<>();
        status.streaks(orgId).forEach(s -> streaks.put(s.spaceId() + ":" + s.profileId(), s));
        Map<Long, ScoreRow> scores = status.latestScores(orgId, clock.instant().minus(Duration.ofDays(1)));
        List<StatusRow> out = new ArrayList<>();
        for (Map.Entry<String, List<DailyRow>> g : groups.entrySet()) {
            List<DailyRow> group = g.getValue();
            DailyRow last = group.getLast();
            SpaceRow space = spaces.get(last.spaceId());
            Sums s = Sums.of(group);
            Map<String, Integer> exceededByMetric = new TreeMap<>();
            group.forEach(r -> exceededByMetric.merge(r.metricKey(), r.exceededMinutes(), Integer::sum));
            List<MetricHours> hours = exceededByMetric.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                    .map(e -> new MetricHours(e.getKey(), hours(e.getValue()))).toList();
            BigDecimal missingRatio = s.missingRatio();
            StreakRow streak = streaks.get(g.getKey());
            ScoreRow score = scores.get(last.spaceId());
            out.add(new StatusRow(
                    new SpaceRef(Long.toString(last.spaceId()), space == null ? null : space.name(), space == null ? null : space.path()),
                    new ProfileRef(Long.toString(last.profileId()), last.profileName(), last.versionNo()), s.ratio(), hours, missingRatio,
                    missingRatio != null && missingRatio.compareTo(DailyComplianceEvaluator.QUALITY_MISSING_LIMIT.multiply(HUNDRED)) > 0,
                    new StreakView(streak == null ? 0 : streak.currentDays(), required.get(last.profileId()),
                            streak == null ? null : streak.brokenOn(), streak == null ? null : streak.brokenMetric()),
                    score == null ? null : score.score()));
        }
        List<BigDecimal> ratios = out.stream().map(StatusRow::ratio).filter(r -> r != null).toList();
        BigDecimal avg = ratios.isEmpty() ? null
                : ratios.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(ratios.size()), 2, RoundingMode.HALF_UP);
        int exceeded = rows.stream().mapToInt(DailyRow::exceededMinutes).sum();
        int streaking = (int) out.stream().filter(r -> r.streak().currentDays() > 0).map(r -> r.space().id()).distinct().count();
        int warnings = (int) out.stream().filter(StatusRow::qualityWarning).count();
        return new StatusView(new StatusSummary(avg, hours(exceeded), streaking, warnings), out);
    }

    /** 일 집계 합 */
    private record Sums(int met, int exceeded, int missing) {

        static Sums of(Collection<DailyRow> rows) {
            int met = 0;
            int exceeded = 0;
            int missing = 0;
            for (DailyRow r : rows) {
                met += r.metMinutes();
                exceeded += r.exceededMinutes();
                missing += r.missingMinutes();
            }
            return new Sums(met, exceeded, missing);
        }

        BigDecimal ratio() {
            return met + exceeded == 0 ? null
                    : BigDecimal.valueOf(met).multiply(HUNDRED).divide(BigDecimal.valueOf(met + exceeded), 2, RoundingMode.HALF_UP);
        }

        BigDecimal missingRatio() {
            int all = met + exceeded + missing;
            return all == 0 ? null : BigDecimal.valueOf(missing).multiply(HUNDRED).divide(BigDecimal.valueOf(all), 2, RoundingMode.HALF_UP);
        }
    }

    /** 기간 [from, to](YYYY-MM-DD). 둘 다 있어야 하고 from ≤ to, 400일 이하 */
    static LocalDate[] period(String from, String to) {
        if (from == null || from.isBlank()) {
            throw ProfileBodyReader.invalid("from", "NotBlank");
        }
        if (to == null || to.isBlank()) {
            throw ProfileBodyReader.invalid("to", "NotBlank");
        }
        LocalDate f;
        LocalDate t;
        try {
            f = LocalDate.parse(from.strip());
            t = LocalDate.parse(to.strip());
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ComplianceErrorCode.CMP_REPORT_PERIOD_INVALID);
        }
        if (t.isBefore(f) || ChronoUnit.DAYS.between(f, t) + 1 > MAX_DAYS) {
            throw new BusinessException(ComplianceErrorCode.CMP_REPORT_PERIOD_INVALID);
        }
        return new LocalDate[]{f, t};
    }

    private static BigDecimal hours(int minutes) {
        return BigDecimal.valueOf(minutes).divide(SIXTY, 1, RoundingMode.HALF_UP);
    }

    private static String text(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    /** CSV 칸: 따옴표로 감싸고, 수식으로 읽힐 첫 글자는 '를 붙인다(감사 로그 CSV와 같은 규칙) */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static Long id(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException ex) {
            throw ProfileBodyReader.invalid(field, "Pattern");
        }
    }
}
