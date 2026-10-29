package net.java21.data2flow.core.compliance.domain;

import net.java21.data2flow.core.compliance.domain.ProfileModels.MetricRule;
import net.java21.data2flow.core.compliance.domain.ReportModels.DataRange;
import net.java21.data2flow.core.compliance.domain.ReportModels.DeviceItem;
import net.java21.data2flow.core.compliance.domain.ReportModels.ExcursionItem;
import net.java21.data2flow.core.compliance.domain.ReportModels.MetricStat;
import net.java21.data2flow.core.compliance.domain.ReportModels.Model;
import net.java21.data2flow.core.compliance.domain.ReportModels.ProfileItem;
import net.java21.data2flow.core.compliance.domain.ReportModels.SpaceItem;
import net.java21.data2flow.core.compliance.domain.ReportModels.Summary;
import net.java21.data2flow.core.compliance.domain.ReportModels.Unmeasurable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 국내 실내공기질 자가 점검 리포트 모델(CMP-02.02, TC-CMP-045·047·048). 수치는 일 집계(compliance_daily)와 이탈 구간에서만 계산하므로 같은 조건이면
 * 언제 만들어도 같다(BR-CMP-12). 입력 목록의 순서와 상관없이 결과 순서는 공간 경로 → 항목 키 → 시각으로 정한다.
 *
 * <ul>
 *   <li>평균 = Σ(일 평균 × 값이 있던 분) / Σ값이 있던 분, 최대 = 일 최대의 최대. 값이 있던 분 = 충족 + 이탈 분</li>
 *   <li>기준 초과 횟수 = 기간 안에 시작한 이탈 구간 수. 충족률·결측률은 현황판과 같은 식(BR-CMP-10)</li>
 *   <li>교정 기한 = 마지막 교정일 + 주기. 기한 30일 전부터 DUE_SOON, 넘으면 OVERDUE, 교정 기록이 없으면 NO_RECORD(BR-CMP-21)</li>
 * </ul>
 */
public final class KrSelfCheckReportBuilder {

    static final int DUE_SOON_DAYS = 30;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

    private KrSelfCheckReportBuilder() {
    }

    public record Request(String title, String facilityType, LocalDate periodFrom, LocalDate periodTo, ZoneId zone) {
    }

    /** 일 집계를 (공간, 버전, 항목)으로 더한 값. weightedAvg = Σ(avg × (met + exceeded)) */
    public record Aggregate(long spaceId, long versionId, String metricKey, long met, long exceeded, long missing, BigDecimal max,
                            BigDecimal weightedAvg, int days) {
    }

    public record Version(long profileId, String profileName, long versionId, int versionNo, String sourceTitle, LocalDate effectiveDate,
                          List<MetricRule> rules) {
    }

    public record Excursion(long spaceId, long versionId, String metricKey, Instant startedAt, Instant endedAt, BigDecimal peakValue) {
    }

    public record Device(long id, String name, String spacePath, String model, List<String> metrics, LocalDate lastCalibratedOn,
                         int intervalDays) {
    }

    public record Rows(long rows, int excludedVirtualDevices, Long previousRows) {
    }

    public static Model build(Request req, List<SpaceItem> spaces, List<Version> versions, List<Aggregate> aggregates, List<Excursion> excursions,
                              List<Device> devices, List<Unmeasurable> unmeasurable, Rows rows, LocalDate today, Map<String, String> units) {
        Map<Long, SpaceItem> spaceById = new HashMap<>();
        spaces.forEach(s -> spaceById.put(s.id(), s));
        Map<Long, Version> versionById = new HashMap<>();
        versions.forEach(v -> versionById.put(v.versionId(), v));
        Map<String, Integer> exceedances = new HashMap<>();
        Instant from = req.periodFrom().atStartOfDay(req.zone()).toInstant();
        Instant to = req.periodTo().plusDays(1).atStartOfDay(req.zone()).toInstant();
        List<ExcursionItem> excursionItems = new ArrayList<>();
        for (Excursion x : excursions) {
            if (x.startedAt().isBefore(from) || !x.startedAt().isBefore(to)) {
                continue;
            }
            exceedances.merge(x.spaceId() + ":" + x.versionId() + ":" + x.metricKey(), 1, Integer::sum);
            SpaceItem s = spaceById.get(x.spaceId());
            excursionItems.add(new ExcursionItem(x.spaceId(), s == null ? null : s.name(), x.metricKey(), x.startedAt(), x.endedAt(),
                    scale1(x.peakValue()), null));
        }
        excursionItems.sort(Comparator.comparing((ExcursionItem e) -> path(spaceById, e.spaceId())).thenComparing(ExcursionItem::metricKey)
                .thenComparing(ExcursionItem::startedAt));

        List<MetricStat> metrics = new ArrayList<>();
        long met = 0;
        long exceeded = 0;
        long missing = 0;
        for (Aggregate a : aggregates) {
            Version v = versionById.get(a.versionId());
            MetricRule rule = v == null ? null : v.rules().stream().filter(r -> r.metricKey().equals(a.metricKey())).findFirst().orElse(null);
            SpaceItem s = spaceById.get(a.spaceId());
            long counted = a.met() + a.exceeded();
            BigDecimal average = counted == 0 || a.weightedAvg() == null ? null
                    : a.weightedAvg().divide(BigDecimal.valueOf(counted), 1, RoundingMode.HALF_UP);
            String unit = rule != null && rule.unit() != null ? rule.unit() : units.get(a.metricKey());
            metrics.add(new MetricStat(a.spaceId(), s == null ? null : s.name(), a.metricKey(), unit, rule == null ? null : rule.evalWindow(),
                    rule == null ? null : rule.lower(), rule == null ? null : rule.upper(), average, scale1(a.max()),
                    exceedances.getOrDefault(a.spaceId() + ":" + a.versionId() + ":" + a.metricKey(), 0),
                    BigDecimal.valueOf(a.exceeded()).divide(SIXTY, 1, RoundingMode.HALF_UP), ratio(a.met(), a.exceeded()),
                    missingRatio(a.met(), a.exceeded(), a.missing()), a.days()));
            met += a.met();
            exceeded += a.exceeded();
            missing += a.missing();
        }
        metrics.sort(Comparator.comparing((MetricStat m) -> path(spaceById, m.spaceId())).thenComparing(MetricStat::metricKey));
        BigDecimal missingRatio = missingRatio(met, exceeded, missing);
        Summary summary = new Summary(ratio(met, exceeded), missingRatio,
                missingRatio != null && missingRatio.compareTo(DailyComplianceEvaluator.QUALITY_MISSING_LIMIT.multiply(HUNDRED)) > 0,
                excursionItems.size());

        List<DeviceItem> deviceItems = devices.stream().sorted(Comparator.comparing(Device::spacePath).thenComparing(Device::id))
                .map(d -> device(d, today)).toList();
        List<ProfileItem> profiles = versions.stream()
                .sorted(Comparator.comparing(Version::profileName).thenComparing(Version::versionNo))
                .map(v -> new ProfileItem(v.profileId(), v.profileName(), v.versionId(), v.versionNo(), v.sourceTitle(), v.effectiveDate()))
                .toList();
        List<SpaceItem> sortedSpaces = spaces.stream().sorted(Comparator.comparing(SpaceItem::path)).toList();
        List<Unmeasurable> sortedUnmeasurable = unmeasurable.stream().sorted(Comparator.comparing(Unmeasurable::metricKey)).toList();
        long late = rows.previousRows() == null ? 0 : Math.max(0, rows.rows() - rows.previousRows());
        return new Model(ReportModels.KR_SELF_CHECK, req.title(), req.facilityType(), req.periodFrom(), req.periodTo(), req.zone().getId(),
                sortedSpaces, profiles, summary, metrics, deviceItems, sortedUnmeasurable, excursionItems,
                new DataRange(from, to, rows.rows(), missingRatio, rows.excludedVirtualDevices(), late));
    }

    /** 교정 기한과 상태(BR-CMP-21) */
    static DeviceItem device(Device d, LocalDate today) {
        if (d.lastCalibratedOn() == null) {
            return new DeviceItem(d.id(), d.name(), d.spacePath(), d.model(), d.metrics(), null, d.intervalDays(), null, "NO_RECORD");
        }
        LocalDate due = d.lastCalibratedOn().plusDays(d.intervalDays());
        String status = today.isAfter(due) ? "OVERDUE" : today.isBefore(due.minusDays(DUE_SOON_DAYS)) ? "OK" : "DUE_SOON";
        return new DeviceItem(d.id(), d.name(), d.spacePath(), d.model(), d.metrics(), d.lastCalibratedOn(), d.intervalDays(), due, status);
    }

    static BigDecimal ratio(long met, long exceeded) {
        return met + exceeded == 0 ? null
                : BigDecimal.valueOf(met).multiply(HUNDRED).divide(BigDecimal.valueOf(met + exceeded), 2, RoundingMode.HALF_UP);
    }

    static BigDecimal missingRatio(long met, long exceeded, long missing) {
        long all = met + exceeded + missing;
        return all == 0 ? null : BigDecimal.valueOf(missing).multiply(HUNDRED).divide(BigDecimal.valueOf(all), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale1(BigDecimal v) {
        return v == null ? null : v.setScale(1, RoundingMode.HALF_UP);
    }

    private static String path(Map<Long, SpaceItem> spaces, long id) {
        SpaceItem s = spaces.get(id);
        return s == null || s.path() == null ? "" : s.path();
    }
}
