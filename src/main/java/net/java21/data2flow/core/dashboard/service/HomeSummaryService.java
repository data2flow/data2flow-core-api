package net.java21.data2flow.core.dashboard.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.AlarmCounts;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.ComfortRow;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.HomeSummaryResponse;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.SourceSummary;
import net.java21.data2flow.core.dashboard.dto.DashboardDtos.TimelineItem;
import net.java21.data2flow.core.dashboard.repository.DashboardRepository;
import net.java21.data2flow.core.dashboard.repository.DashboardRepository.SourceCounts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 홈 요약(API-DSH-01, DSH-01.01·01.02). 모든 수치는 사용자 공간 범위 안에서 센다(BR-DSH-01, AT-DSH-01.5).
 *
 * <p>M2에서 원천이 있는 값: 오프라인 기기 수, 승인 대기 기기 수(DEV_PLACE), 최근 1분 수신 건수, 소스 연결 현황(SRC_READ),
 * 공간 쾌적도(상위 10 + 전체 수). M4: 심각도별 열린 알람 수(ACTIVE·ACKNOWLEDGED, DSH-01.01)와 최근 알람·제어 타임라인 20건(DSH-01.03).
 * AI 요약(M7)은 생략한다.
 */
@Service
public class HomeSummaryService {

    /** 홈 쾌적도 목록 상한(API-DSH-01 "최대 10 + total") */
    public static final int COMFORT_LIMIT = 10;

    private final RoleChecker roleChecker;
    private final DashboardRepository repository;
    private final ComfortService comfort;
    private final Clock clock;
    private final net.java21.data2flow.core.alarm.repository.AlarmRepository alarms;
    private final net.java21.data2flow.core.dashboard.repository.TimelineRepository timeline;

    /** 타임라인 건수·기간 */
    public static final int TIMELINE_LIMIT = 20;
    static final java.time.Duration TIMELINE_WINDOW = java.time.Duration.ofDays(7);

    public HomeSummaryService(RoleChecker roleChecker, DashboardRepository repository, ComfortService comfort, Clock clock,
                              net.java21.data2flow.core.alarm.repository.AlarmRepository alarms,
                              net.java21.data2flow.core.dashboard.repository.TimelineRepository timeline) {
        this.alarms = alarms;
        this.timeline = timeline;
        this.roleChecker = roleChecker;
        this.repository = repository;
        this.comfort = comfort;
        this.clock = clock;
    }

    /** API-DSH-01 GET /core/home/summary — VIEWER 이상 */
    @Transactional(readOnly = true)
    public HomeSummaryResponse summary() {
        AccessGrant grant = roleChecker.require(Permission.DASHBOARD_READ);
        return compute(roleChecker.currentUser().organizationId(), grant);
    }

    /** 권한을 이미 확인한 사용자의 요약(실시간 {@code home} 토픽이 5초 묶음마다 부른다) */
    @Transactional(readOnly = true)
    public HomeSummaryResponse compute(long organizationId, AccessGrant grant) {
        var scope = grant.spaceScope();
        ComfortService.Context ctx = comfort.load(organizationId, scope);
        List<ComfortRow> rows = comfort.rows(ctx);
        long offline = repository.countOfflineDevices(organizationId, scope);
        Long pending = grant.has(Permission.DEV_PLACE) ? repository.countPendingDevices(organizationId, scope) : null;
        SourceSummary sources = null;
        if (grant.has(Permission.SRC_READ)) {
            SourceCounts counts = repository.countSources(organizationId, scope);
            sources = new SourceSummary(counts.connected(), counts.total());
        }
        Instant minute = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        double perMinute = repository.sumReceived(organizationId, scope, minute.minus(1, ChronoUnit.MINUTES), minute);
        java.util.Collection<String> paths = scope.unrestricted() ? null : alarms.listSpacePaths(organizationId, scope.allowedSpaceIds());
        java.util.Map<String, Long> open = paths != null && paths.isEmpty() ? java.util.Map.of() : alarms.countOpenBySeverity(organizationId, paths);
        AlarmCounts counts = new AlarmCounts(n(open, "CRITICAL"), n(open, "MAJOR"), n(open, "MINOR"), n(open, "WARNING"), n(open, "INFO"));
        List<TimelineItem> items = paths != null && paths.isEmpty() ? List.of()
                : timeline.recent(organizationId, paths, clock.instant().minus(TIMELINE_WINDOW), TIMELINE_LIMIT).stream()
                .map(t -> new TimelineItem(t.type(), t.at(), t.title(), t.severity(), t.origin(), t.link())).toList();
        return new HomeSummaryResponse(counts, offline, pending, perMinute, sources,
                rows.subList(0, Math.min(COMFORT_LIMIT, rows.size())), rows.size(), items, null);
    }

    static int n(java.util.Map<String, Long> m, String key) {
        return m.getOrDefault(key, 0L).intValue();
    }
}
