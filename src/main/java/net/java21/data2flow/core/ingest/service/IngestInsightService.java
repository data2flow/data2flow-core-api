package net.java21.data2flow.core.ingest.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.ingest.domain.IngestErrorCode;
import net.java21.data2flow.core.ingest.dto.IngestDtos.QualityItem;
import net.java21.data2flow.core.ingest.dto.IngestInsightDtos.DeviceCompleteness;
import net.java21.data2flow.core.ingest.dto.IngestInsightDtos.GapEntry;
import net.java21.data2flow.core.ingest.dto.IngestInsightDtos.GapsResponse;
import net.java21.data2flow.core.ingest.dto.IngestInsightDtos.IssueDistribution;
import net.java21.data2flow.core.ingest.dto.IngestInsightDtos.QualitySummary;
import net.java21.data2flow.core.ingest.dto.IngestInsightDtos.QualityTrend;
import net.java21.data2flow.core.ingest.dto.IngestInsightDtos.ReprocessJobItem;
import net.java21.data2flow.core.ingest.dto.IngestInsightDtos.TrendPoint;
import net.java21.data2flow.core.ingest.repository.IngestInsightRepository;
import net.java21.data2flow.core.ingest.repository.IngestInsightRepository.Distribution;
import net.java21.data2flow.core.ingest.repository.IngestInsightRepository.GapDevice;
import net.java21.data2flow.core.ingest.repository.IngestInsightRepository.GapSpan;
import net.java21.data2flow.core.ingest.repository.IngestInsightRepository.JobRow;
import net.java21.data2flow.core.ingest.repository.IngestMonitorRepository;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * M5 수집 관리 조회.
 * <ul>
 *   <li>ING-01.04 재처리 작업 목록·상세(API-ING-14): INGEST_REPROCESS(INTEGRATOR+). 공간 범위가 제한되면 대상 기기가 모두 범위 안인 작업만.
 *       없거나 안 보이면 404</li>
 *   <li>ING-06.02 품질 추이(대상 하나의 일별 점수, 최대 31일, 넘으면 400 ING_QUERY_RANGE_TOO_LARGE)와 요약(최하위 10개·문제 유형 분포):
 *       INGEST_READ 또는 ANALYTICS_READ</li>
 *   <li>TSD-02.04 공백·완전성(API-TSD-09): TS_READ. 기간 기본 최근 7일·최대 90일. 예상 건수 = 기간 ÷ 기기 예상 주기, 빠진 건수 = 기간 안 공백
 *       길이 ÷ 주기(AT-TSD-14.1: 1분 주기 30분 공백 → 30)</li>
 * </ul>
 */
@Service
public class IngestInsightService {

    static final Set<String> JOB_STATUSES = Set.of("PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED");
    static final Set<String> GROUPS = Set.of("DEVICE", "SPACE", "MODEL");
    static final int TREND_MAX_DAYS = 31;
    static final Duration GAP_MAX_SPAN = Duration.ofDays(90);

    private final RoleChecker roleChecker;
    private final IngestInsightRepository repository;
    private final IngestMonitorRepository monitor;
    private final TelemetryQueryRepository telemetry;
    private final DataQualityService quality;
    private final Clock clock;

    public IngestInsightService(RoleChecker roleChecker, IngestInsightRepository repository, IngestMonitorRepository monitor,
                                TelemetryQueryRepository telemetry, DataQualityService quality, Clock clock) {
        this.roleChecker = roleChecker;
        this.repository = repository;
        this.monitor = monitor;
        this.telemetry = telemetry;
        this.quality = quality;
        this.clock = clock;
    }

    /** API-ING-14 목록(최근 순) */
    @Transactional(readOnly = true)
    public ListApiResponse<ReprocessJobItem> jobs(String statusParam, Long sourceId, Integer page, Integer size) {
        roleChecker.require(Permission.INGEST_REPROCESS);
        long org = roleChecker.currentUser().organizationId();
        String status = statusParam == null || statusParam.isBlank() ? null : statusParam.strip().toUpperCase(Locale.ROOT);
        if (status != null && !JOB_STATUSES.contains(status)) {
            throw IngestMonitorService.invalid("status");
        }
        SpaceScope scope = roleChecker.spaceScope();
        PageParams params = PageParams.of(page, size);
        List<ReprocessJobItem> items = repository.findJobs(org, status, sourceId, scope, params.size(), params.offset()).stream()
                .map(IngestInsightService::item).toList();
        return ListApiResponse.of(params, items, repository.countJobs(org, status, sourceId, scope));
    }

    /** API-ING-14 상세 */
    @Transactional(readOnly = true)
    public ReprocessJobItem job(long jobId) {
        roleChecker.require(Permission.INGEST_REPROCESS);
        long org = roleChecker.currentUser().organizationId();
        return repository.findJob(org, jobId, roleChecker.spaceScope()).map(IngestInsightService::item)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /** ING-06.02 품질 추이(기본 최근 30일, 어제까지) */
    @Transactional(readOnly = true)
    public QualityTrend trend(String groupByParam, Long targetId, LocalDate fromParam, LocalDate toParam) {
        requireQualityRead();
        long org = roleChecker.currentUser().organizationId();
        String groupBy = groupByParam == null || groupByParam.isBlank() ? "DEVICE" : groupByParam.strip().toUpperCase(Locale.ROOT);
        if (!GROUPS.contains(groupBy)) {
            throw IngestMonitorService.invalid("groupBy");
        }
        if (targetId == null) {
            throw IngestMonitorService.invalid("targetId");
        }
        ZoneId zone = zone(monitor.findOrganizationTimezone(org));
        LocalDate to = toParam != null ? toParam : LocalDate.ofInstant(clock.instant(), zone).minusDays(1);
        LocalDate from = fromParam != null ? fromParam : to.minusDays(29);
        if (from.isAfter(to)) {
            throw IngestMonitorService.invalid("from");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > TREND_MAX_DAYS) {
            throw new BusinessException(IngestErrorCode.ING_QUERY_RANGE_TOO_LARGE);
        }
        String path = null;
        if ("SPACE".equals(groupBy)) {
            var space = telemetry.findSpace(org, targetId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            roleChecker.requireSpace(space.id(), CommonErrorCode.RESOURCE_NOT_FOUND);
            path = space.path();
        } else if ("DEVICE".equals(groupBy)) {
            var device = telemetry.findDevice(org, targetId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            roleChecker.requireSpace(device.spaceId(), CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        List<TrendPoint> points = repository.findQualityTrend(org, groupBy, targetId, path, from, to, roleChecker.spaceScope()).stream()
                .map(r -> new TrendPoint(r.day(), r.score(), r.completeness(), r.timeliness(), r.validity(), r.stability(), r.devices())).toList();
        return new QualityTrend(groupBy, Long.toString(targetId), from, to, points);
    }

    /** ING-06.02 요약: 최하위 10개(API-ING-13과 같은 묶음) + 문제 유형 분포 */
    @Transactional(readOnly = true)
    public QualitySummary summary(LocalDate dayParam, String groupBy, Long spaceId) {
        requireQualityRead();
        long org = roleChecker.currentUser().organizationId();
        ZoneId zone = zone(monitor.findOrganizationTimezone(org));
        LocalDate day = dayParam != null ? dayParam : LocalDate.ofInstant(clock.instant(), zone).minusDays(1);
        List<QualityItem> bottom = quality.quality(day, groupBy, spaceId, 1, 10).responses();
        String path = spaceId == null ? null : telemetry.findSpace(org, spaceId).map(s -> s.path()).orElse(null);
        Distribution d = repository.findDistribution(org, day, day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant(),
                path, roleChecker.spaceScope());
        return new QualitySummary(day, d.devices(), d.averageScore(), bottom,
                new IssueDistribution(d.gaps(), d.outOfRange(), d.suspect(), d.late(), d.expected(), d.received()));
    }

    /** API-TSD-09 공백·완전성 */
    @Transactional(readOnly = true)
    public GapsResponse gaps(Long deviceId, Long spaceId, Instant fromParam, Instant toParam) {
        roleChecker.require(Permission.TS_READ);
        long org = roleChecker.currentUser().organizationId();
        Instant to = toParam == null ? clock.instant() : toParam;
        Instant from = fromParam == null ? to.minus(Duration.ofDays(7)) : fromParam;
        if (!from.isBefore(to)) {
            throw IngestMonitorService.invalid("from");
        }
        if (Duration.between(from, to).compareTo(GAP_MAX_SPAN) > 0) {
            throw new BusinessException(net.java21.data2flow.core.telemetry.domain.TelemetryErrorCode.TSD_RANGE_TOO_LARGE);
        }
        String path = null;
        if (deviceId != null) {
            var device = telemetry.findDevice(org, deviceId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            roleChecker.require(Permission.TS_READ, device.spaceId() == null && !roleChecker.spaceScope().unrestricted() ? Long.valueOf(-1L)
                    : device.spaceId(), CommonErrorCode.RESOURCE_NOT_FOUND);
        } else if (spaceId != null) {
            var space = telemetry.findSpace(org, spaceId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            roleChecker.requireSpace(space.id(), CommonErrorCode.RESOURCE_NOT_FOUND);
            path = space.path();
        }
        List<GapDevice> devices = repository.findGapDevices(org, deviceId, path, roleChecker.spaceScope());
        List<GapSpan> spans = repository.findGapSpans(org, devices.stream().map(GapDevice::id).toList(), from, to);
        Map<Long, Long> missingSec = new HashMap<>();
        List<GapEntry> gaps = new ArrayList<>();
        for (GapSpan g : spans) {
            gaps.add(new GapEntry(Long.toString(g.deviceId()), g.gapStart(), g.gapEnd(), g.expectedCount()));
            missingSec.merge(g.deviceId(), Duration.between(g.from(), g.to()).toSeconds(), Long::sum);
        }
        long spanSec = Duration.between(from, to).toSeconds();
        List<DeviceCompleteness> completeness = devices.stream().map(d -> {
            int interval = Math.max(1, d.intervalSec());
            long expected = spanSec / interval;
            long missing = Math.min(expected, missingSec.getOrDefault(d.id(), 0L) / interval);
            double percent = expected == 0 ? 100.0 : Math.round(1000.0 * (expected - missing) / expected) / 10.0;
            return new DeviceCompleteness(Long.toString(d.id()), d.name(), interval, expected, missing, percent);
        }).toList();
        return new GapsResponse(from, to, gaps, completeness);
    }

    private void requireQualityRead() {
        if (!roleChecker.has(Permission.INGEST_READ)) {
            roleChecker.require(Permission.ANALYTICS_READ);
        }
    }

    static ReprocessJobItem item(JobRow j) {
        double progress = j.total() <= 0 ? ("COMPLETED".equals(j.status()) ? 100.0 : 0.0)
                : Math.min(100.0, Math.round(1000.0 * j.processed() / j.total()) / 10.0);
        return new ReprocessJobItem(Long.toString(j.id()), Long.toString(j.sourceId()), j.sourceName(),
                j.deviceIds().stream().map(String::valueOf).toList(), j.from(), j.to(), j.status(), j.total(), j.processed(), j.failed(),
                j.skipped(), progress, j.onlyFailed(), Long.toString(j.requestedBy()), j.requestedByName(), j.memo(), j.error(), j.createdAt(),
                j.startedAt(), j.finishedAt());
    }

    private static ZoneId zone(String raw) {
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException | NullPointerException ex) {
            return ZoneId.of("Asia/Seoul");
        }
    }
}
