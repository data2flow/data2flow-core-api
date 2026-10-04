package net.java21.data2flow.core.maintenance.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.MaintenanceChanged;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.alarm.domain.AlarmErrorCode;
import net.java21.data2flow.core.alarm.service.AlarmService;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.maintenance.dto.MaintenanceDtos.ActiveWindow;
import net.java21.data2flow.core.maintenance.dto.MaintenanceDtos.Created;
import net.java21.data2flow.core.maintenance.dto.MaintenanceDtos.Window;
import net.java21.data2flow.core.maintenance.repository.MaintenanceRepository;
import net.java21.data2flow.core.maintenance.repository.MaintenanceRepository.WindowRow;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 유지보수 모드(OPS-05.01·05.02·05.03, API-OPS-20~24, BR-OPS-10·11, BR-RUL-08). 공간(하위 포함)·기기 단위, 시작·끝 예약(최대 30일).
 * 쓰기는 ALARM_HANDLE(ADMIN·INTEGRATOR·OPERATOR, 공간 범위), 조회는 ALARM_READ(VIEWER 이상, 범위).
 * <ul>
 *   <li>시작하면 범위의 열린 알람을 SUPPRESSED(MAINTENANCE)로 돌리고, 그 사이 새 알람도 SUPPRESSED로 만든다(알림 없음). 데이터 수집은 그대로</li>
 *   <li>끝나면(끝 시각·[종료]) 조건이 이어지는 열린 알람을 ACTIVE로 되돌리고 알림을 다시 평가한다</li>
 *   <li>EVT-OPS-02 {@code ops.maintenance.started|ended}(pauseAutomation 기본 true: flow-engine이 그 대상 자동 제어를 건너뜀)</li>
 *   <li>같은 대상에 겹치는 구간은 409 MAINTENANCE_OVERLAP, 기간 오류는 400 MAINTENANCE_RANGE_INVALID</li>
 * </ul>
 */
@Service
public class MaintenanceService {

    static final Duration MAX = Duration.ofDays(30);
    static final Set<String> STATUSES = Set.of("SCHEDULED", "ACTIVE", "ENDED", "CANCELED");

    private final MaintenanceRepository windows;
    private final AlarmService alarms;
    private final CoreEventPublisher publisher;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final Clock clock;

    public MaintenanceService(MaintenanceRepository windows, AlarmService alarms, CoreEventPublisher publisher, RoleChecker roleChecker,
                              Audits audits, Clock clock) {
        this.windows = windows;
        this.alarms = alarms;
        this.publisher = publisher;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.clock = clock;
    }

    /** API-OPS-20 {targetType, targetId, startsAt?, endsAt?, pauseAutomation=true, excludeFromAnalytics=true, reason} → 201 {id, status} */
    @Transactional
    public Created create(JsonNode body) {
        roleChecker.require(Permission.ALARM_HANDLE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (body == null || !body.isObject()) {
            throw invalid("body", "NotNull");
        }
        String type = body.path("targetType").asString("").toUpperCase(Locale.ROOT);
        if (!type.equals("SPACE") && !type.equals("DEVICE")) {
            throw invalid("targetType", "Pattern");
        }
        long targetId = id(body.path("targetId").asString(""), "targetId");
        Long space = windows.findTargetSpace(orgId, type, targetId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!roleChecker.spaceScope().unrestricted() && (space == 0 || !roleChecker.spaceScope().includes(space))) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        Instant now = clock.instant();
        Instant starts = body.hasNonNull("startsAt") ? instant(body.get("startsAt")) : now;
        Instant ends = body.hasNonNull("endsAt") ? instant(body.get("endsAt")) : null;
        if (ends != null && (!ends.isAfter(starts) || Duration.between(starts, ends).compareTo(MAX) > 0)) {
            throw new BusinessException(AlarmErrorCode.MAINTENANCE_RANGE_INVALID);
        }
        String reason = body.path("reason").asString("").strip();
        if (reason.isEmpty() || reason.length() > 200) {
            throw invalid("reason", "Size");
        }
        boolean pause = !body.has("pauseAutomation") || body.get("pauseAutomation").asBoolean(true);
        boolean exclude = !body.has("excludeFromAnalytics") || body.get("excludeFromAnalytics").asBoolean(true);
        if (windows.existsOverlap(orgId, type, targetId, starts, ends)) {
            throw new BusinessException(AlarmErrorCode.MAINTENANCE_OVERLAP);
        }
        String status = starts.isAfter(now) ? "SCHEDULED" : "ACTIVE";
        long id = windows.insert(orgId, type, targetId, starts, ends, pause, exclude, reason, status, user.userId(), now);
        audits.record(audits.event(orgId, "MAINTENANCE_CREATED").actor(user).target(type, Long.toString(targetId))
                .detail("windowId", Long.toString(id)).detail("status", status));
        if ("ACTIVE".equals(status)) {
            started(windows.findById(orgId, id).orElseThrow(), now);
        }
        return new Created(Long.toString(id), status);
    }

    /** API-OPS-21 종료(ACTIVE → ENDED, SCHEDULED는 취소로) */
    @Transactional
    public void end(long id) {
        roleChecker.require(Permission.ALARM_HANDLE);
        CurrentUser user = roleChecker.currentUser();
        WindowRow w = lockVisible(user.organizationId(), id);
        Instant now = clock.instant();
        switch (w.status()) {
            case "ACTIVE" -> {
                windows.updateStatus(w.organizationId(), id, "ENDED", now, user.userId(), now);
                ended(w, now);
            }
            case "SCHEDULED" -> windows.updateStatus(w.organizationId(), id, "CANCELED", null, user.userId(), now);
            default -> throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        audits.record(audits.event(user.organizationId(), "MAINTENANCE_ENDED").actor(user).target(w.targetType(), Long.toString(w.targetId()))
                .detail("windowId", Long.toString(id)));
    }

    /** API-OPS-22 취소(SCHEDULED만) */
    @Transactional
    public void cancel(long id) {
        roleChecker.require(Permission.ALARM_HANDLE);
        CurrentUser user = roleChecker.currentUser();
        WindowRow w = lockVisible(user.organizationId(), id);
        if (!"SCHEDULED".equals(w.status())) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        windows.updateStatus(w.organizationId(), id, "CANCELED", null, user.userId(), clock.instant());
        audits.record(audits.event(user.organizationId(), "MAINTENANCE_CANCELED").actor(user).target(w.targetType(), Long.toString(w.targetId()))
                .detail("windowId", Long.toString(id)));
    }

    /** API-OPS-23 목록(이력 포함, OPS-05.03) */
    @Transactional(readOnly = true)
    public ListApiResponse<Window> list(String status, String targetId, Integer page, Integer size) {
        roleChecker.require(Permission.ALARM_READ);
        long orgId = roleChecker.currentUser().organizationId();
        List<String> statuses = null;
        if (status != null && !status.isBlank()) {
            statuses = new ArrayList<>();
            for (String s : status.split(",")) {
                String v = s.strip().toUpperCase(Locale.ROOT);
                if (!STATUSES.contains(v)) {
                    throw invalid("status", "Pattern");
                }
                statuses.add(v);
            }
        }
        Long target = targetId == null || targetId.isBlank() ? null : id(targetId, "targetId");
        PageParams params = PageParams.of(page, size);
        var scope = roleChecker.spaceScope();
        List<Window> items = windows.list(orgId, statuses, target, params.size(), params.offset()).stream()
                .filter(w -> scope.unrestricted() || (w.targetSpaceId() != null && scope.includes(w.targetSpaceId())))
                .map(MaintenanceService::view).toList();
        return ListApiResponse.of(params, items, scope.unrestricted() ? windows.count(orgId, statuses, target) : items.size());
    }

    /** API-OPS-24 내부: 지금 ACTIVE 구간(공간이면 하위 펼침) */
    @Transactional(readOnly = true)
    public List<ActiveWindow> active(long orgId) {
        List<ActiveWindow> out = new ArrayList<>();
        for (WindowRow w : windows.list(orgId, List.of("ACTIVE"), null, 1000, 0)) {
            out.add(new ActiveWindow(Long.toString(w.id()), w.targetType(), Long.toString(w.targetId()), descendants(w).stream()
                    .map(String::valueOf).toList(), w.pauseAutomation(), w.excludeFromAnalytics(), w.startsAt(), w.endsAt()));
        }
        return out;
    }

    /** 예약 시작·끝 처리(1분마다, {@code AutomationJobs}). 바꾼 구간 수 */
    @Transactional
    public int tick(long orgId) {
        Instant now = clock.instant();
        int n = 0;
        for (WindowRow w : windows.listDue(orgId, now)) {
            boolean over = w.endsAt() != null && !w.endsAt().isAfter(now);
            if ("SCHEDULED".equals(w.status()) && !over) {
                windows.updateStatus(orgId, w.id(), "ACTIVE", null, null, now);
                started(w, now);
            } else if ("SCHEDULED".equals(w.status())) {
                windows.updateStatus(orgId, w.id(), "ENDED", null, null, now);
            } else {
                windows.updateStatus(orgId, w.id(), "ENDED", null, null, now);
                ended(w, now);
            }
            n++;
        }
        return n;
    }

    private void started(WindowRow w, Instant now) {
        alarms.suppressForMaintenance(w.organizationId(), "DEVICE".equals(w.targetType()) ? w.targetId() : null,
                "SPACE".equals(w.targetType()) ? w.targetPath() : null, now);
        publisher.event(EventType.OPS_MAINTENANCE_STARTED, w.organizationId(), changed(w));
    }

    private void ended(WindowRow w, Instant now) {
        alarms.releaseMaintenance(w.organizationId(), "DEVICE".equals(w.targetType()) ? w.targetId() : null,
                "SPACE".equals(w.targetType()) ? w.targetPath() : null, now);
        publisher.event(EventType.OPS_MAINTENANCE_ENDED, w.organizationId(), changed(w));
    }

    MaintenanceChanged changed(WindowRow w) {
        return new MaintenanceChanged(w.id(), MaintenanceChanged.TargetType.valueOf(w.targetType()), w.targetId(), descendants(w),
                w.pauseAutomation(), w.excludeFromAnalytics(), w.startsAt(), w.endsAt());
    }

    List<Long> descendants(WindowRow w) {
        return "SPACE".equals(w.targetType()) && w.targetPath() != null ? windows.listDescendants(w.organizationId(), w.targetPath()) : List.of();
    }

    private WindowRow lockVisible(long orgId, long id) {
        WindowRow w = windows.lockById(orgId, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        var scope = roleChecker.spaceScope();
        if (!scope.unrestricted() && (w.targetSpaceId() == null || !scope.includes(w.targetSpaceId()))) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return w;
    }

    static Window view(WindowRow w) {
        return new Window(Long.toString(w.id()), w.targetType(), Long.toString(w.targetId()), w.targetName(), w.startsAt(), w.endsAt(),
                w.pauseAutomation(), w.excludeFromAnalytics(), w.reason(), w.status(), w.version(), Long.toString(w.createdBy()), w.createdAt());
    }

    static Instant instant(JsonNode v) {
        try {
            return Instant.parse(v.asString(""));
        } catch (RuntimeException ex) {
            throw new BusinessException(AlarmErrorCode.MAINTENANCE_RANGE_INVALID);
        }
    }

    static long id(String raw, String field) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException ex) {
            throw invalid(field, "Pattern");
        }
    }

    static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
