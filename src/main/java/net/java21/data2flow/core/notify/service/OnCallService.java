package net.java21.data2flow.core.notify.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.notify.domain.OnCallResolver;
import net.java21.data2flow.core.notify.domain.TimeWindow;
import net.java21.data2flow.core.notify.dto.NotifyDtos.OnCall;
import net.java21.data2flow.core.notify.dto.NotifyDtos.OnCallCurrent;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Override;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Shift;
import net.java21.data2flow.core.notify.dto.NotifyDtos.UserRef;
import net.java21.data2flow.core.notify.repository.OnCallRepository;
import net.java21.data2flow.core.notify.repository.OnCallRepository.OverrideRow;
import net.java21.data2flow.core.notify.repository.OnCallRepository.ScheduleRow;
import net.java21.data2flow.core.notify.repository.OnCallRepository.ShiftRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 당직(RUL-05.03, API-RUL-26, BR-RUL-19). 조회 ALARM_READ, 근무표 저장·대리 근무 NOTIFY_POLICY_WRITE. v1은 조직당 일정 하나.
 * 현재 당직자는 대체 근무 > 주간 교대 순서이고 비어 있으면 {@code userId} 없이 돌려준다(발송 쪽이 정책의 다른 수신자·ADMIN으로).
 */
@Service
public class OnCallService {

    private final OnCallRepository onCall;
    private final CoreEventPublisher publisher;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final Clock clock;

    public OnCallService(OnCallRepository onCall, CoreEventPublisher publisher, RoleChecker roleChecker, Audits audits, Clock clock) {
        this.onCall = onCall;
        this.publisher = publisher;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OnCall get() {
        roleChecker.require(Permission.ALARM_READ);
        return view(roleChecker.currentUser().organizationId());
    }

    /** 근무표 저장 {name, timezone, shifts[{dayOfWeek 1~7, from, to, userId}], baseVersion}. 처음이면 baseVersion 0 */
    @Transactional
    public OnCall put(JsonNode body) {
        roleChecker.require(Permission.NOTIFY_POLICY_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (body == null || !body.isObject()) {
            throw invalid("body", "NotNull");
        }
        String name = body.path("name").asString("당직").strip();
        if (name.isEmpty() || name.length() > 100) {
            throw invalid("name", "Size");
        }
        String tz = body.path("timezone").asString("Asia/Seoul");
        try {
            ZoneId.of(tz);
        } catch (RuntimeException ex) {
            throw invalid("timezone", "Pattern");
        }
        List<ShiftRow> shifts = new ArrayList<>();
        int i = 0;
        for (JsonNode s : body.path("shifts").values()) {
            String field = "shifts[" + i++ + "]";
            int day = s.path("dayOfWeek").asInt(0);
            if (day < 1 || day > 7) {
                throw invalid(field + ".dayOfWeek", "Range");
            }
            LocalTime from;
            LocalTime to;
            try {
                from = TimeWindow.time(s.path("from").asString(""));
                to = TimeWindow.time(s.path("to").asString(""));
            } catch (IllegalArgumentException ex) {
                throw invalid(field, "Pattern");
            }
            if (from == null || to == null) {
                throw invalid(field, "NotNull");
            }
            long userId = PolicyService.id(s.path("userId").asString(""), field + ".userId");
            if (!onCall.existsActiveUser(orgId, userId)) {
                throw invalid(field + ".userId", "NotFound");
            }
            shifts.add(new ShiftRow(0, day, from, to, userId, null));
        }
        int base = body.path("baseVersion").asInt(0);
        Instant now = clock.instant();
        Optional<Long> locked = onCall.lockSchedule(orgId);
        long scheduleId;
        if (locked.isEmpty()) {
            if (base != 0) {
                throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
            }
            scheduleId = onCall.insertSchedule(orgId, name, tz, now);
        } else {
            scheduleId = locked.get();
            if (onCall.updateSchedule(orgId, scheduleId, base, name, tz, now) == 0) {
                throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
            }
        }
        onCall.replaceShifts(orgId, scheduleId, shifts);
        publisher.configChanged(ConfigChangedMessage.EntityType.ON_CALL, scheduleId, base + 1L, orgId);
        audits.record(audits.event(orgId, "ON_CALL_UPDATED").actor(user).target("ON_CALL", Long.toString(scheduleId))
                .detail("shifts", shifts.size()));
        return view(orgId);
    }

    /** 대리 근무 추가 {startsAt, endsAt, originalUserId, substituteUserId} */
    @Transactional
    public OnCall addOverride(JsonNode body) {
        roleChecker.require(Permission.NOTIFY_POLICY_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        long scheduleId = onCall.findSchedule(orgId).map(ScheduleRow::id)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Instant starts;
        Instant ends;
        try {
            starts = Instant.parse(body.path("startsAt").asString(""));
            ends = Instant.parse(body.path("endsAt").asString(""));
        } catch (RuntimeException ex) {
            throw invalid("startsAt", "Pattern");
        }
        if (!ends.isAfter(starts)) {
            throw invalid("endsAt", "Range");
        }
        long original = PolicyService.id(body.path("originalUserId").asString(""), "originalUserId");
        long substitute = PolicyService.id(body.path("substituteUserId").asString(""), "substituteUserId");
        if (!onCall.existsActiveUser(orgId, substitute)) {
            throw invalid("substituteUserId", "NotFound");
        }
        long id = onCall.insertOverride(orgId, scheduleId, starts, ends, original, substitute, user.userId(), clock.instant());
        publisher.configChanged(ConfigChangedMessage.EntityType.ON_CALL, scheduleId, id, orgId);
        audits.record(audits.event(orgId, "ON_CALL_OVERRIDE_ADDED").actor(user).target("ON_CALL", Long.toString(scheduleId))
                .detail("overrideId", Long.toString(id)));
        return view(orgId);
    }

    @Transactional
    public void deleteOverride(long overrideId) {
        roleChecker.require(Permission.NOTIFY_POLICY_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (onCall.deleteOverride(orgId, overrideId) == 0) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        onCall.findSchedule(orgId).ifPresent(s -> publisher.configChanged(ConfigChangedMessage.EntityType.ON_CALL, s.id(), overrideId, orgId));
        audits.record(audits.event(orgId, "ON_CALL_OVERRIDE_DELETED").actor(user).target("ON_CALL", Long.toString(overrideId)));
    }

    @Transactional(readOnly = true)
    public OnCallCurrent current() {
        roleChecker.require(Permission.ALARM_READ);
        return current(roleChecker.currentUser().organizationId(), clock.instant());
    }

    /** 지금 당직자(내부 API API-RUL-42에서도 씀) */
    @Transactional(readOnly = true)
    public OnCallCurrent current(long orgId, Instant at) {
        Optional<ScheduleRow> schedule = onCall.findSchedule(orgId);
        if (schedule.isEmpty()) {
            return new OnCallCurrent(null, null, null, false);
        }
        ZoneId zone = ZoneId.of(schedule.get().timezone());
        List<OnCallResolver.Shift> shifts = onCall.listShifts(orgId, schedule.get().id()).stream()
                .map(s -> new OnCallResolver.Shift(s.dayOfWeek(), s.from(), s.to(), s.userId())).toList();
        List<OnCallResolver.Override> overrides = onCall.listOverrides(orgId, schedule.get().id(), at).stream()
                .map(o -> new OnCallResolver.Override(o.startsAt(), o.endsAt(), o.originalUserId(), o.substituteUserId())).toList();
        return OnCallResolver.resolve(shifts, overrides, at, zone)
                .map(c -> new OnCallCurrent(Long.toString(c.userId()), onCall.findUserName(orgId, c.userId()).orElse(null), c.until(),
                        c.substitute()))
                .orElse(new OnCallCurrent(null, null, null, false));
    }

    OnCall view(long orgId) {
        Optional<ScheduleRow> schedule = onCall.findSchedule(orgId);
        if (schedule.isEmpty()) {
            return new OnCall(null, null, "Asia/Seoul", List.of(), List.of(), 0);
        }
        ScheduleRow s = schedule.get();
        List<Shift> shifts = onCall.listShifts(orgId, s.id()).stream()
                .map(r -> new Shift(r.dayOfWeek(), r.from().toString(), r.to().toString(), Long.toString(r.userId()), r.userName())).toList();
        List<Override> overrides = new ArrayList<>();
        for (OverrideRow o : onCall.listOverrides(orgId, s.id(), clock.instant())) {
            overrides.add(new Override(Long.toString(o.id()), o.startsAt(), o.endsAt(),
                    new UserRef(Long.toString(o.originalUserId()), o.originalUserName()),
                    new UserRef(Long.toString(o.substituteUserId()), o.substituteUserName())));
        }
        return new OnCall(Long.toString(s.id()), s.name(), s.timezone(), shifts, overrides, s.version());
    }

    static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
