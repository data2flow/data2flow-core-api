package net.java21.data2flow.core.compliance.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.compliance.domain.ComplianceErrorCode;
import net.java21.data2flow.core.compliance.domain.CorrectiveActions;
import net.java21.data2flow.core.compliance.domain.CorrectiveActions.Summary;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Aggregate;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Device;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Excursion;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Request;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Rows;
import net.java21.data2flow.core.compliance.domain.KrSelfCheckReportBuilder.Version;
import net.java21.data2flow.core.compliance.domain.MeasurabilityEvaluator;
import net.java21.data2flow.core.compliance.domain.ProfileModels.Coverage;
import net.java21.data2flow.core.compliance.domain.ProfileModels.MetricRule;
import net.java21.data2flow.core.compliance.domain.ProfileModels.VersionSpec;
import net.java21.data2flow.core.compliance.domain.ReportHash;
import net.java21.data2flow.core.compliance.domain.ReportHash.Sealed;
import net.java21.data2flow.core.compliance.domain.ReportModels.Meta;
import net.java21.data2flow.core.compliance.domain.ReportModels.Model;
import net.java21.data2flow.core.compliance.domain.ReportModels.SpaceItem;
import net.java21.data2flow.core.compliance.domain.ReportModels.Unmeasurable;
import net.java21.data2flow.core.compliance.domain.ReportPdfRenderer;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.ApplicationRow;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.DeviceRow;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.SpaceRow;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceProfileRepository.VersionRow;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository.Claimed;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository.DeviceCalibration;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository.Expired;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository.ReportRow;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository.RevisionRow;
import net.java21.data2flow.core.compliance.repository.ComplianceReportRepository.VersionInfo;
import net.java21.data2flow.core.compliance.repository.ComplianceStatusRepository;
import net.java21.data2flow.core.compliance.repository.CorrectiveActionRepository;
import net.java21.data2flow.core.compliance.repository.CorrectiveActionRepository.ActionRow;
import net.java21.data2flow.core.filestore.service.StoredFiles;
import net.java21.data2flow.core.filestore.service.StoredFiles.Stored;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 리포트 판 생성(CMP-02.02, BR-CMP-12·16·21). 요청은 판을 GENERATING으로 넣기만 하고, 이 작업이 하나씩 잡아(SKIP LOCKED) 모델을 만들고 PDF를
 * 그려 해시를 넣은 뒤 DRAFT로 바꾼다. 실패하면 FAILED와 사유.
 * <ul>
 *   <li>대상: 고른 공간과 그 하위 공간. 적용은 그 공간들에 걸린 적용 중 시설 유형이 맞는 것(유형 없는 적용은 늘 포함)</li>
 *   <li>버전: 기간의 일 집계에 쓰인 버전과 지금 적용 버전. 규칙은 시설 유형 전용 규칙이 일반 규칙보다 먼저다</li>
 *   <li>교정 주기: 지금 적용 버전의 CALIBRATION_INTERVAL 중 가장 짧은 값, 없으면 365일</li>
 *   <li>{@link #runQueued} 10초마다, {@link #housekeeping} 1시간마다(7일 지난 초안 삭제, domain-model §3)</li>
 * </ul>
 */
@Component
public class ComplianceReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(ComplianceReportGenerator.class);
    static final String PURPOSE = "CMP_REPORT";
    static final int DEFAULT_CALIBRATION_DAYS = 365;
    static final Duration STALE = Duration.ofMinutes(10);
    static final Duration DRAFT_RETENTION = Duration.ofDays(7);

    private final ComplianceReportRepository reports;
    private final ComplianceApplicationRepository applications;
    private final ComplianceApplicationService applicationService;
    private final ComplianceProfileRepository profiles;
    private final ComplianceStatusRepository status;
    private final CorrectiveActionRepository actions;
    private final StoredFiles files;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ComplianceReportGenerator(ComplianceReportRepository reports, ComplianceApplicationRepository applications,
                                     ComplianceApplicationService applicationService, ComplianceProfileRepository profiles,
                                     ComplianceStatusRepository status, CorrectiveActionRepository actions, StoredFiles files, JsonMapper json,
                                     PlatformTransactionManager txManager, Clock clock) {
        this.reports = reports;
        this.applications = applications;
        this.applicationService = applicationService;
        this.profiles = profiles;
        this.status = status;
        this.actions = actions;
        this.files = files;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** 리포트 대상: 공간(고른 공간 + 하위, 경로 순), 시설 유형, 걸리는 적용 */
    public record Target(List<SpaceRow> spaces, String facilityType, List<ApplicationRow> applications) {

        public List<Long> spaceIds() {
            return spaces.stream().map(SpaceRow::id).toList();
        }
    }

    /**
     * 대상을 정한다. 시설 유형을 주지 않으면 적용들의 유형이 하나일 때 그 값을 쓴다. 유형이 필요한 프로필인데 정할 수 없으면
     * CMP_FACILITY_TYPE_REQUIRED, 걸리는 적용이 없으면 CMP_PROFILE_NOT_APPLIED
     */
    public Target target(long orgId, Collection<Long> spaceIds, String facilityType) {
        Map<Long, SpaceRow> spaces = new LinkedHashMap<>();
        for (long id : spaceIds) {
            SpaceRow s = applications.space(orgId, id).orElseThrow(() -> new BusinessException(ComplianceErrorCode.SPACE_NOT_FOUND));
            spaces.putAll(status.spaces(orgId, status.subtreeIds(orgId, s.path())));
            spaces.putIfAbsent(s.id(), s);
        }
        List<SpaceRow> sorted = spaces.values().stream().sorted(java.util.Comparator.comparing(SpaceRow::path)).toList();
        Map<Long, ApplicationRow> found = new LinkedHashMap<>();
        for (SpaceRow s : sorted) {
            applications.forSpace(orgId, s).forEach(a -> found.putIfAbsent(a.id(), a));
        }
        String facility = facilityType;
        if (facility == null) {
            Set<String> types = new TreeSet<>();
            found.values().stream().map(ApplicationRow::facilityType).filter(Objects::nonNull).forEach(types::add);
            if (types.size() == 1) {
                facility = types.iterator().next();
            }
        }
        String chosen = facility;
        List<ApplicationRow> matched = found.values().stream()
                .filter(a -> a.facilityType() == null || a.facilityType().equals(chosen)).toList();
        if (matched.isEmpty()) {
            if (chosen == null && !found.isEmpty()) {
                throw new BusinessException(ComplianceErrorCode.CMP_FACILITY_TYPE_REQUIRED);
            }
            throw new BusinessException(ComplianceErrorCode.CMP_PROFILE_NOT_APPLIED);
        }
        if (chosen == null) {
            for (ApplicationRow a : matched) {
                if (profiles.version(orgId, a.profileId(), a.effectiveVersionNo()).map(VersionRow::facilityTypeRequired).orElse(false)) {
                    throw new BusinessException(ComplianceErrorCode.CMP_FACILITY_TYPE_REQUIRED);
                }
            }
        }
        return new Target(sorted, chosen, matched);
    }

    /** 대기 판을 모두 만든다(정해진 수까지). 만든 수 */
    public int runQueued() {
        int n = 0;
        while (n < 20) {
            Instant now = clock.instant();
            Optional<Claimed> next = tx.execute(s -> reports.claimNext(now, now.minus(STALE)));
            if (next == null || next.isEmpty()) {
                break;
            }
            generate(next.get());
            n++;
        }
        return n;
    }

    /** 판 하나를 만든다. 결과 상태(DRAFT·FAILED) */
    String generate(Claimed c) {
        long org = c.organizationId();
        try {
            ReportRow report = reports.find(org, c.reportId()).orElseThrow(() -> new IllegalStateException("리포트가 없습니다"));
            RevisionRow revision = reports.revisionById(org, c.revisionId()).orElseThrow(() -> new IllegalStateException("판이 없습니다"));
            Model model = model(org, report, c.revisionNo());
            tx.executeWithoutResult(s -> reports.progress(org, c.revisionId(), 60, clock.instant()));
            Instant now = clock.instant();
            byte[] pdf = ReportPdfRenderer.render(model, new Meta(report.id(), c.revisionNo(), now, revision.generatedByName(), null, null, true));
            Sealed sealed = ReportHash.seal(pdf);
            List<Long> versionIds = model.profiles().stream().map(p -> p.versionId()).toList();
            Boolean done = tx.execute(s -> {
                Stored stored = files.storeGenerated(org, PURPOSE, fileName(report.id(), c.revisionNo()), "application/pdf", sealed.pdf(),
                        revision.generatedBy());
                if (!reports.markDraft(org, c.revisionId(), stored.key(), sealed.sha256(), stored.sizeBytes(), json.writeValueAsString(model.dataRange()),
                        versionIds, json.writeValueAsString(model), now)) {
                    s.setRollbackOnly();
                    return false;
                }
                reports.touchReportUpdated(org, report.id(), now);
                return true;
            });
            return Boolean.TRUE.equals(done) ? "DRAFT" : "FAILED";
        } catch (RuntimeException ex) {
            log.warn("리포트 판 {} 생성 실패: {}", c.revisionId(), ex.toString());
            String message = ex instanceof BusinessException b ? b.getErrorCode().toString()
                    : ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            tx.executeWithoutResult(s -> reports.markFailed(org, c.revisionId(), "CMP_REPORT_GENERATION_FAILED: " + message, clock.instant()));
            return "FAILED";
        }
    }

    /** 모델(BR-CMP-12: 일 집계·이탈 구간·기기·교정 이력으로만) */
    Model model(long org, ReportRow report, int revisionNo) {
        ZoneId zone = ZoneId.of(status.timezone(org));
        Target target = target(org, report.spaceIds(), report.facilityType());
        List<Long> spaceIds = target.spaceIds();
        String facility = target.facilityType();
        Set<Long> profileIds = new LinkedHashSet<>();
        List<VersionRow> current = new ArrayList<>();
        for (ApplicationRow a : target.applications()) {
            profileIds.add(a.profileId());
            profiles.version(org, a.profileId(), a.effectiveVersionNo()).ifPresent(current::add);
        }
        List<Aggregate> aggregates = reports.aggregates(org, spaceIds, profileIds, report.periodFrom(), report.periodTo());
        Set<Long> versionIds = new LinkedHashSet<>();
        current.forEach(v -> versionIds.add(v.id()));
        aggregates.forEach(a -> versionIds.add(a.versionId()));
        List<Version> versions = new ArrayList<>();
        for (VersionInfo v : reports.versions(org, versionIds)) {
            versions.add(new Version(v.profileId(), v.profileName(), v.versionId(), v.versionNo(), v.sourceTitle(), v.effectiveDate(),
                    rules(profiles.rules(v.versionId()), facility)));
        }
        Instant from = report.periodFrom().atStartOfDay(zone).toInstant();
        Instant to = report.periodTo().plusDays(1).atStartOfDay(zone).toInstant();
        List<Excursion> excursions = withActions(org, reports.excursions(org, spaceIds, versionIds, from, to), includeActions(report), zone);

        int interval = calibrationDays(current);
        List<DeviceRow> deviceRows = applications.activeRealDevices(org, spaceIds);
        Map<Long, DeviceCalibration> calibrations = new HashMap<>();
        reports.calibrations(org, deviceRows.stream().map(DeviceRow::id).toList(), zone.getId()).forEach(d -> calibrations.put(d.deviceId(), d));
        Map<String, String> pathNames = new HashMap<>();
        target.spaces().forEach(s -> pathNames.put(s.path(), s.name()));
        List<Device> devices = deviceRows.stream().map(d -> {
            DeviceCalibration cal = calibrations.get(d.id());
            return new Device(d.id(), d.name(), pathNames.getOrDefault(d.spacePath(), d.spacePath()), cal == null ? null : cal.model(),
                    List.copyOf(d.metricKeys()), cal == null ? null : cal.lastCalibratedOn(), interval);
        }).toList();

        long rows = reports.telemetryRows(org, spaceIds, from, to);
        int virtual = reports.deviceCounts(org, spaceIds)[1];
        Long previous = reports.previousRows(org, report.id(), revisionNo).orElse(null);
        List<SpaceItem> spaces = target.spaces().stream().map(s -> new SpaceItem(s.id(), s.name(), s.path())).toList();
        return KrSelfCheckReportBuilder.build(new Request(report.title(), facility, report.periodFrom(), report.periodTo(), zone), spaces, versions,
                aggregates, excursions, devices, unmeasurable(org, target), new Rows(rows, virtual, previous), LocalDate.now(clock.withZone(zone)),
                applications.metricUnits(org));
    }

    /** 이탈 구간마다 개선 조치 문구를 붙인다(CMP-02.05, AT-CMP-10.1). 옵션 includeActions=false면 붙이지 않는다 */
    private List<Excursion> withActions(long org, List<Excursion> excursions, boolean include, ZoneId zone) {
        if (!include || excursions.isEmpty()) {
            return excursions;
        }
        Map<Long, List<Summary>> byExcursion = new HashMap<>();
        for (ActionRow a : actions.byExcursions(org, excursions.stream().map(Excursion::id).toList())) {
            byExcursion.computeIfAbsent(a.excursion().id(), k -> new ArrayList<>()).add(new Summary(a.cause(), a.causeNote(), a.actionText(),
                    a.actorName(), a.actedAt(), a.resultMode(), a.resultRatio24h(), a.resultNote(), a.resultCheckedAt()));
        }
        return excursions.stream().map(x -> x.withAction(CorrectiveActions.text(byExcursion.get(x.id()), zone))).toList();
    }

    private boolean includeActions(ReportRow report) {
        if (report.optionsJson() == null) {
            return true;
        }
        JsonNode n = json.readTree(report.optionsJson()).get("includeActions");
        return n == null || !n.isBoolean() || n.booleanValue();
    }

    /** 시설 유형에 맞는 규칙만, 항목마다 하나(유형 전용 규칙 우선) */
    static List<MetricRule> rules(List<MetricRule> all, String facility) {
        Map<String, MetricRule> byKey = new LinkedHashMap<>();
        for (MetricRule r : all) {
            if (!r.appliesTo(facility)) {
                continue;
            }
            MetricRule prev = byKey.get(r.metricKey());
            if (prev == null || prev.facilityType() == null && r.facilityType() != null) {
                byKey.put(r.metricKey(), r);
            }
        }
        return List.copyOf(byKey.values());
    }

    private int calibrationDays(List<VersionRow> versions) {
        int days = Integer.MAX_VALUE;
        for (VersionRow v : versions) {
            for (Coverage c : profiles.coverage(v.id())) {
                JsonNode n = c.params() == null ? null : c.params().get("defaultIntervalDays");
                if ("CALIBRATION_INTERVAL".equals(c.kind()) && n != null && n.isNumber() && n.intValue() > 0) {
                    days = Math.min(days, n.intValue());
                }
            }
        }
        return days == Integer.MAX_VALUE ? DEFAULT_CALIBRATION_DAYS : days;
    }

    /** 측정 불가·일부만 측정되는 항목(적용마다 평가해 항목으로 모은다. 측정 불가가 일부보다 앞선다) */
    private List<Unmeasurable> unmeasurable(long org, Target target) {
        Map<String, String> statuses = new LinkedHashMap<>();
        Map<String, Set<String>> without = new LinkedHashMap<>();
        for (ApplicationRow a : target.applications()) {
            for (MeasurabilityEvaluator.Result r : applicationService.evaluate(org, a)) {
                if (r.status() == MeasurabilityEvaluator.Status.MEASURABLE) {
                    continue;
                }
                String s = r.status().name();
                statuses.merge(r.metricKey(), s, (x, y) -> "NOT_MEASURABLE".equals(x) || "NOT_MEASURABLE".equals(y) ? "NOT_MEASURABLE" : x);
                Set<String> names = without.computeIfAbsent(r.metricKey(), k -> new TreeSet<>());
                r.spacesWithout().forEach(n -> names.add(n.name()));
            }
        }
        List<Unmeasurable> out = new ArrayList<>();
        statuses.forEach((key, s) -> out.add(new Unmeasurable(key, s, List.copyOf(without.get(key)))));
        return out;
    }

    /** 7일 지난 초안 삭제. 지운 수 */
    public int housekeeping() {
        int n = 0;
        for (Expired e : reports.expiredDrafts(clock.instant().minus(DRAFT_RETENTION))) {
            Boolean deleted = tx.execute(s -> {
                if (!reports.deleteRevision(e.organizationId(), e.revisionId())) {
                    return false;
                }
                List<String> keys = new ArrayList<>();
                if (e.pdfObjectKey() != null) {
                    keys.add(e.pdfObjectKey());
                }
                if (e.csvObjectKey() != null) {
                    keys.add(e.csvObjectKey());
                }
                files.delete(e.organizationId(), keys);
                reports.deleteIfEmpty(e.organizationId(), e.reportId());
                return true;
            });
            if (Boolean.TRUE.equals(deleted)) {
                n++;
            }
        }
        return n;
    }

    static String fileName(long reportId, int revisionNo) {
        return "data2flow_report_" + reportId + "_r" + revisionNo + ".pdf";
    }

    /** 스케줄. 테스트는 {@code data2flow.core.jobs.enabled=false}로 끄고 직접 부른다 */
    @Component
    @ConditionalOnProperty(prefix = "data2flow.core.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final ComplianceReportGenerator generator;

        Schedule(ComplianceReportGenerator generator) {
            this.generator = generator;
        }

        @Scheduled(fixedDelay = 10_000, initialDelay = 15_000)
        void run() {
            generator.runQueued();
        }

        @Scheduled(cron = "0 17 * * * *")
        void housekeeping() {
            generator.housekeeping();
        }
    }
}
