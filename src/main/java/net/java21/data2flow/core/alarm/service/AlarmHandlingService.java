package net.java21.data2flow.core.alarm.service;

import net.java21.data2flow.contracts.alarm.AlarmClearReason;
import net.java21.data2flow.contracts.alarm.AlarmStatus;
import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.PermissionLookup;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.alarm.domain.AlarmErrorCode;
import net.java21.data2flow.core.alarm.domain.AlarmStateMachine;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.AssigneeResult;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.BulkItem;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.BulkResult;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.HandleResult;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.NoteResult;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.UserRef;
import net.java21.data2flow.core.alarm.repository.AlarmRepository;
import net.java21.data2flow.core.alarm.repository.AlarmRepository.AlarmRow;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.notify.repository.OnCallRepository;
import net.java21.data2flow.core.rule.domain.RuleLimits;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 알람 처리(API-RUL-12·13, RUL-02.02·02.04·02.06). ALARM_HANDLE + 공간 범위(남의 것·범위 밖 404, 역할 부족 403).
 * <ul>
 *   <li>확인: ACTIVE → ACKNOWLEDGED, 확인하지 않은 CLEARED는 확인 기록만. 이미 확인한 알람을 단건으로 다시 확인하면 409 ALARM_STATE_CONFLICT,
 *       일괄 확인에서는 {@code ok:true, alreadyAcked:true}(API-RUL-12)</li>
 *   <li>해제: 열린 알람을 MANUAL로. 하위 알람도 함께(BR-RUL-09)</li>
 *   <li>일괄 확인: 200건까지(넘으면 400 ALARM_BULK_LIMIT_EXCEEDED), 건별 결과</li>
 *   <li>메모·조치(≤2,000자)·담당자 지정은 타임라인에 작성자와 함께</li>
 * </ul>
 * 감사: ALARM_ACKED·ALARM_CLEARED·ALARM_ASSIGNED.
 */
@Service
public class AlarmHandlingService {

    static final Set<String> ACTION_TYPES = Set.of("ONSITE", "CONFIG_CHANGE", "DEVICE_REPLACED", "OTHER");

    private final AlarmRepository alarms;
    private final AlarmService service;
    private final AlarmQueryService query;
    private final OnCallRepository users;
    private final PermissionLookup permissions;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final Clock clock;

    public AlarmHandlingService(AlarmRepository alarms, AlarmService service, AlarmQueryService query, OnCallRepository users,
                                PermissionLookup permissions, RoleChecker roleChecker, Audits audits, Clock clock) {
        this.alarms = alarms;
        this.service = service;
        this.query = query;
        this.users = users;
        this.permissions = permissions;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.clock = clock;
    }

    @Transactional
    public HandleResult ack(long alarmId) {
        roleChecker.require(Permission.ALARM_HANDLE);
        CurrentUser user = roleChecker.currentUser();
        AlarmRow a = lockVisible(user.organizationId(), alarmId);
        AlarmRow after = service.ack(a, user.userId(), "USER", clock.instant());
        audits.record(audits.event(user.organizationId(), "ALARM_ACKED").actor(user).target("ALARM", Long.toString(alarmId)));
        return new HandleResult(true, Long.toString(alarmId), after.status(), null, after.ackedAt(), after.clearedAt(), after.clearReason());
    }

    @Transactional
    public HandleResult clear(long alarmId, JsonNode body) {
        roleChecker.require(Permission.ALARM_HANDLE);
        CurrentUser user = roleChecker.currentUser();
        String note = body == null || !body.hasNonNull("note") ? null : body.get("note").asString("").strip();
        if (note != null && note.length() > RuleLimits.MAX_NOTE) {
            throw invalid("note", "Size");
        }
        AlarmRow a = lockVisible(user.organizationId(), alarmId);
        AlarmRow after = service.clear(a, AlarmClearReason.MANUAL, null, clock.instant(), "USER", user.userId(),
                note == null || note.isEmpty() ? null : note);
        audits.record(audits.event(user.organizationId(), "ALARM_CLEARED").actor(user).target("ALARM", Long.toString(alarmId)));
        return new HandleResult(true, Long.toString(alarmId), after.status(), null, after.ackedAt(), after.clearedAt(), after.clearReason());
    }

    /** 일괄 확인 {alarmIds[≤200]} */
    @Transactional
    public BulkResult bulkAck(JsonNode body) {
        roleChecker.require(Permission.ALARM_HANDLE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (body == null || !body.path("alarmIds").isArray() || body.path("alarmIds").isEmpty()) {
            throw invalid("alarmIds", "NotEmpty");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode v : body.path("alarmIds").values()) {
            ids.add(AlarmQueryService.id(v.asString(""), "alarmIds"));
        }
        if (!RuleLimits.bulkWithin(ids.size())) {
            throw new BusinessException(AlarmErrorCode.ALARM_BULK_LIMIT_EXCEEDED);
        }
        Instant now = clock.instant();
        List<BulkItem> results = new ArrayList<>();
        int acked = 0;
        for (long id : ids) {
            AlarmRow a = alarms.lockById(orgId, id).filter(x -> AlarmQueryService.visibleTo(roleChecker.spaceScope(), x)).orElse(null);
            if (a == null) {
                results.add(new BulkItem(Long.toString(id), false, AlarmErrorCode.ALARM_NOT_FOUND.code(), null));
                continue;
            }
            AlarmStateMachine.AckOutcome outcome;
            try {
                outcome = AlarmStateMachine.ack(AlarmStatus.valueOf(a.status()), a.ackedAt() != null);
            } catch (BusinessException ex) {
                results.add(new BulkItem(Long.toString(id), false, ex.getErrorCode().code(), null));
                continue;
            }
            if (outcome.alreadyAcked()) {
                results.add(new BulkItem(Long.toString(id), true, null, true));
                continue;
            }
            service.ack(a, user.userId(), "USER", now);
            acked++;
            results.add(new BulkItem(Long.toString(id), true, null, null));
        }
        audits.record(audits.event(orgId, "ALARM_ACKED").actor(user).target("ALARM", "bulk").detail("count", acked)
                .detail("requested", ids.size()));
        return new BulkResult(results);
    }

    /** 메모·조치 {text(≤2000), actionType?} */
    @Transactional
    public NoteResult note(long alarmId, JsonNode body) {
        roleChecker.require(Permission.ALARM_HANDLE);
        CurrentUser user = roleChecker.currentUser();
        String text = body == null ? null : body.path("text").asString("").strip();
        if (!RuleLimits.noteWithin(text)) {
            throw invalid("text", "Size");
        }
        String actionType = body.hasNonNull("actionType") ? AlarmQueryService.one(body.get("actionType").asString(""), ACTION_TYPES,
                "actionType") : null;
        AlarmRow a = lockVisible(user.organizationId(), alarmId);
        Instant now = clock.instant();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", text);
        if (actionType != null) {
            data.put("actionType", actionType);
        }
        String type = actionType == null ? "NOTE" : "ACTION";
        long eventId = service.timeline(user.organizationId(), a.id(), type, "USER", user.userId(), data, now);
        return new NoteResult(Long.toString(eventId), Long.toString(alarmId), type, text, actionType,
                new UserRef(Long.toString(user.userId()), users.findUserName(user.organizationId(), user.userId()).orElse(null)), now);
    }

    /** 담당자 지정 {userId} (null이면 해제). 대상은 같은 조직의 활성 사용자 */
    @Transactional
    public AssigneeResult assign(long alarmId, JsonNode body) {
        roleChecker.require(Permission.ALARM_HANDLE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        Long assignee = body == null || !body.hasNonNull("userId") ? null : AlarmQueryService.id(body.get("userId").asString(""), "userId");
        if (assignee != null && !users.existsActiveUser(orgId, assignee)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        AlarmRow a = lockVisible(orgId, alarmId);
        Instant now = clock.instant();
        alarms.updateAssignee(orgId, a.id(), assignee, now);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assigneeId", assignee == null ? null : Long.toString(assignee));
        service.timeline(orgId, a.id(), "ASSIGNED", "USER", user.userId(), data, now);
        audits.record(audits.event(orgId, "ALARM_ASSIGNED").actor(user).target("ALARM", Long.toString(alarmId))
                .detail("assigneeId", assignee == null ? null : Long.toString(assignee)));
        AlarmRow after = alarms.findById(orgId, a.id()).orElseThrow();
        return new AssigneeResult(Long.toString(alarmId), assignee == null ? null
                : new UserRef(Long.toString(assignee), after.assigneeName()), after.version(), after.updatedAt());
    }

    /**
     * 메신저 버튼 응답 처리(RUL-05.02, BR-RUL-18): 연결된 플랫폼 계정의 권한(ALARM_HANDLE + 공간 범위)으로 확인한다. 권한이 없거나 범위 밖이면
     * 403·404, 이미 확인한 알람은 {@code alreadyAcked}.
     */
    @Transactional
    public HandleResult ackAs(long orgId, long userId, long alarmId) {
        AccessGrant grant = permissions.find(orgId, userId);
        if (grant == null || !grant.has(Permission.ALARM_HANDLE)) {
            throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
        }
        AlarmRow a = alarms.lockById(orgId, alarmId)
                .filter(x -> AlarmQueryService.visibleTo(grant.spaceScope(), x))
                .orElseThrow(() -> new BusinessException(AlarmErrorCode.ALARM_NOT_FOUND));
        AlarmStateMachine.AckOutcome outcome = AlarmStateMachine.ack(AlarmStatus.valueOf(a.status()), a.ackedAt() != null);
        if (outcome.alreadyAcked()) {
            return new HandleResult(true, Long.toString(alarmId), a.status(), true, a.ackedAt(), a.clearedAt(), a.clearReason());
        }
        AlarmRow after = service.ack(a, userId, "MESSENGER", clock.instant());
        audits.record(audits.event(orgId, "ALARM_ACKED").actor(net.java21.data2flow.contracts.audit.AuditActorType.USER, Long.toString(userId), null).target("ALARM", Long.toString(alarmId))
                .detail("via", "MESSENGER"));
        return new HandleResult(true, Long.toString(alarmId), after.status(), null, after.ackedAt(), after.clearedAt(), after.clearReason());
    }

    /** 메신저 무음 등: 연결 사용자가 이 알람을 처리할 수 있는가(ALARM_HANDLE + 공간 범위). 아니면 403·404 */
    @Transactional(readOnly = true)
    public AlarmRow requireHandleAs(long orgId, long userId, long alarmId) {
        AccessGrant grant = permissions.find(orgId, userId);
        if (grant == null || !grant.has(Permission.ALARM_HANDLE)) {
            throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
        }
        return alarms.findById(orgId, alarmId).filter(x -> AlarmQueryService.visibleTo(grant.spaceScope(), x))
                .orElseThrow(() -> new BusinessException(AlarmErrorCode.ALARM_NOT_FOUND));
    }

    private AlarmRow lockVisible(long orgId, long alarmId) {
        query.visible(orgId, alarmId);
        return alarms.lockById(orgId, alarmId).orElseThrow(() -> new BusinessException(AlarmErrorCode.ALARM_NOT_FOUND));
    }

    static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
