package net.java21.data2flow.core.ingest.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.ingest.domain.IngestErrorCode;
import net.java21.data2flow.core.ingest.dto.IngestDtos.Evidence;
import net.java21.data2flow.core.ingest.dto.IngestDtos.GapItem;
import net.java21.data2flow.core.ingest.dto.IngestDtos.QualityItem;
import net.java21.data2flow.core.ingest.repository.DataQualityRepository;
import net.java21.data2flow.core.ingest.repository.DataQualityRepository.GapFilter;
import net.java21.data2flow.core.ingest.repository.DataQualityRepository.GroupBy;
import net.java21.data2flow.core.ingest.repository.DataQualityRepository.QualityFilter;
import net.java21.data2flow.core.ingest.repository.IngestMonitorRepository;
import net.java21.data2flow.core.telemetry.repository.TelemetryQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * 데이터 품질(API-ING-13, ING-06.01·06.02)과 수신 공백(API-ING-15, ING-06.05). pipeline이 매일 계산한 점수·공백을 읽어 보여 준다.
 * 품질은 INGEST_READ 또는 ANALYTICS_READ, 공백은 INGEST_READ. 공간 범위 밖 기기는 결과에서 빠진다(IAM-04.06).
 * 하루(day)는 조직 시간대 기준이고 기본은 어제다.
 */
@Service
public class DataQualityService {

    static final Duration GAP_MAX_SPAN = Duration.ofDays(90);

    private final DataQualityRepository repository;
    private final IngestMonitorRepository monitor;
    private final TelemetryQueryRepository telemetry;
    private final RoleChecker roleChecker;
    private final Clock clock;

    public DataQualityService(DataQualityRepository repository, IngestMonitorRepository monitor, TelemetryQueryRepository telemetry,
                              RoleChecker roleChecker, Clock clock) {
        this.repository = repository;
        this.monitor = monitor;
        this.telemetry = telemetry;
        this.roleChecker = roleChecker;
        this.clock = clock;
    }

    /** API-ING-13 데이터 품질(groupBy device 기본·space·model, 점수 낮은 순) */
    @Transactional(readOnly = true)
    public ListApiResponse<QualityItem> quality(LocalDate dayParam, String groupByParam, Long spaceId, Integer page, Integer size) {
        if (!roleChecker.has(Permission.INGEST_READ)) {
            roleChecker.require(Permission.ANALYTICS_READ);
        }
        long orgId = roleChecker.currentUser().organizationId();
        GroupBy groupBy;
        try {
            groupBy = groupByParam == null || groupByParam.isBlank() ? GroupBy.DEVICE : GroupBy.valueOf(groupByParam.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw IngestMonitorService.invalid("groupBy");
        }
        ZoneId zone = zone(monitor.findOrganizationTimezone(orgId));
        LocalDate day = dayParam != null ? dayParam : LocalDate.ofInstant(clock.instant(), zone).minusDays(1);
        String path = null;
        if (spaceId != null) {
            var space = telemetry.findSpace(orgId, spaceId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            roleChecker.requireSpace(space.id(), CommonErrorCode.RESOURCE_NOT_FOUND);
            path = space.path();
        }
        QualityFilter filter = new QualityFilter(orgId, day, day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant(),
                groupBy, path, roleChecker.spaceScope());
        PageParams params = PageParams.of(page, size);
        List<QualityItem> items = repository.findPage(filter, params.offset(), params.size()).stream()
                .map(r -> new QualityItem(r.targetId() == null ? null : r.targetId().toString(), r.targetName(), r.score(), r.completeness(),
                        r.timeliness(), r.validity(), r.stability(), r.gaps(), r.clockSkewSuspect(),
                        new Evidence(r.expected(), r.received(), r.late(), r.outOfRange(), r.suspect())))
                .toList();
        return ListApiResponse.of(params, items, repository.count(filter));
    }

    /** API-ING-15 수신 공백(기간 기본 최근 7일, 최대 90일) */
    @Transactional(readOnly = true)
    public ListApiResponse<GapItem> gaps(Long deviceId, Instant fromParam, Instant toParam, Integer page, Integer size) {
        roleChecker.require(Permission.INGEST_READ);
        long orgId = roleChecker.currentUser().organizationId();
        Instant to = toParam == null ? clock.instant() : toParam;
        Instant from = fromParam == null ? to.minus(Duration.ofDays(7)) : fromParam;
        if (!from.isBefore(to)) {
            throw IngestMonitorService.invalid("from");
        }
        if (Duration.between(from, to).compareTo(GAP_MAX_SPAN) > 0) {
            throw new BusinessException(IngestErrorCode.ING_QUERY_RANGE_TOO_LARGE);
        }
        if (deviceId != null) {
            var device = telemetry.findDevice(orgId, deviceId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            roleChecker.requireSpace(device.spaceId(), CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        GapFilter filter = new GapFilter(orgId, deviceId == null ? 0 : deviceId, from, to, roleChecker.spaceScope());
        PageParams params = PageParams.of(page, size);
        List<GapItem> items = repository.findGaps(filter, params.offset(), params.size()).stream()
                .map(g -> new GapItem(Long.toString(g.deviceId()), g.deviceName(), g.from(), g.to(), g.expectedCount())).toList();
        return ListApiResponse.of(params, items, repository.countGaps(filter));
    }

    private static ZoneId zone(String raw) {
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException | NullPointerException ex) {
            return ZoneId.of("Asia/Seoul");
        }
    }
}
