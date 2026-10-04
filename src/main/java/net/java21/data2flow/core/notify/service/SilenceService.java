package net.java21.data2flow.core.notify.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.alarm.domain.AlarmErrorCode;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.notify.domain.SilenceMatcher;
import net.java21.data2flow.core.notify.domain.TimeWindow;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Silence;
import net.java21.data2flow.core.notify.dto.NotifyDtos.SilenceTarget;
import net.java21.data2flow.core.notify.dto.NotifyDtos.UserRef;
import net.java21.data2flow.core.notify.repository.PolicyRepository;
import net.java21.data2flow.core.notify.repository.SilenceRepository;
import net.java21.data2flow.core.notify.repository.SilenceRepository.SilenceRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 무음(RUL-02.07, API-RUL-25, BR-RUL-14). ALARM_HANDLE. 일회(시작·끝, 끝 배타)와 반복({@code {days[1~7], from, to}} 또는
 * {@code {dateFrom, dateTo}}). 대상은 RULE·DEVICE·SPACE(하위 포함)·ALARM이고 같은 조직·사용자 공간 범위 안이어야 한다. 기간이 틀리면 400
 * SILENCE_RANGE_INVALID(일회 무음은 최대 30일). 저장·삭제는 설정 변경 SILENCE(action 캐시).
 */
@Service
public class SilenceService {

    static final Set<String> TARGETS = Set.of("RULE", "DEVICE", "SPACE", "ALARM");

    private final SilenceRepository silences;
    private final PolicyRepository policies;
    private final CoreEventPublisher publisher;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public SilenceService(SilenceRepository silences, PolicyRepository policies, CoreEventPublisher publisher, RoleChecker roleChecker,
                          Audits audits, JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.silences = silences;
        this.policies = policies;
        this.publisher = publisher;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ListApiResponse<Silence> list(Boolean includeEnded, Integer page, Integer size) {
        roleChecker.require(Permission.ALARM_HANDLE);
        long orgId = roleChecker.currentUser().organizationId();
        PageParams params = PageParams.of(page, size);
        Instant now = clock.instant();
        boolean all = Boolean.TRUE.equals(includeEnded);
        ZoneId zone = zone(orgId);
        return ListApiResponse.of(params, silences.list(orgId, all, now, params.size(), params.offset()).stream()
                .map(s -> view(s, now, zone)).toList(), silences.count(orgId, all, now));
    }

    @Transactional
    public Silence create(JsonNode body) {
        roleChecker.require(Permission.ALARM_HANDLE);
        CurrentUser user = roleChecker.currentUser();
        Silence s = create(user.organizationId(), user.userId(), body, "USER");
        return s;
    }

    /** 생성 본체(메신저 30분 무음에서도 씀) */
    @Transactional
    public Silence create(long orgId, long userId, JsonNode body, String via) {
        if (body == null || !body.isObject()) {
            throw invalid("body", "NotNull");
        }
        String kind = body.path("kind").asString("ONE_TIME").toUpperCase(Locale.ROOT);
        if (!kind.equals("ONE_TIME") && !kind.equals("RECURRING")) {
            throw invalid("kind", "Pattern");
        }
        JsonNode target = body.path("target");
        String type = target.path("type").asString("").toUpperCase(Locale.ROOT);
        if (!TARGETS.contains(type)) {
            throw invalid("target.type", "Pattern");
        }
        long targetId = PolicyService.id(target.path("id").asString(""), "target.id");
        checkTarget(orgId, type, targetId);
        Instant starts = null;
        Instant ends = null;
        String recurrence = null;
        if (kind.equals("ONE_TIME")) {
            starts = body.hasNonNull("startsAt") ? instant(body.get("startsAt")) : clock.instant();
            ends = body.hasNonNull("endsAt") ? instant(body.get("endsAt")) : null;
            if (ends == null || !ends.isAfter(starts) || Duration.between(starts, ends).compareTo(Duration.ofDays(30)) > 0) {
                throw new BusinessException(AlarmErrorCode.SILENCE_RANGE_INVALID);
            }
        } else {
            JsonNode r = body.get("recurrence");
            if (r == null || !r.isObject()) {
                throw new BusinessException(AlarmErrorCode.SILENCE_RANGE_INVALID);
            }
            try {
                if (r.hasNonNull("dateFrom") || r.hasNonNull("dateTo")) {
                    LocalDate from = LocalDate.parse(r.path("dateFrom").asString(""));
                    LocalDate to = LocalDate.parse(r.path("dateTo").asString(""));
                    if (to.isBefore(from)) {
                        throw new BusinessException(AlarmErrorCode.SILENCE_RANGE_INVALID);
                    }
                } else {
                    Set<Integer> days = new LinkedHashSet<>();
                    r.path("days").values().forEach(d -> days.add(d.asInt()));
                    new TimeWindow(days, TimeWindow.time(r.path("from").asString(null)), TimeWindow.time(r.path("to").asString(null)));
                }
            } catch (RuntimeException ex) {
                if (ex instanceof BusinessException be) {
                    throw be;
                }
                throw new BusinessException(AlarmErrorCode.SILENCE_RANGE_INVALID);
            }
            recurrence = json.writeValueAsString(r);
        }
        String reason = body.hasNonNull("reason") ? body.get("reason").asString("").strip() : null;
        if (reason != null && reason.length() > 200) {
            throw invalid("reason", "Size");
        }
        Instant now = clock.instant();
        long id = silences.insert(orgId, kind, type, targetId, starts, ends, recurrence, reason, userId, now);
        publisher.configChanged(ConfigChangedMessage.EntityType.SILENCE, id, 1, orgId);
        audits.record(audits.event(orgId, "SILENCE_CREATED").actor(net.java21.data2flow.contracts.audit.AuditActorType.USER,
                Long.toString(userId), null).target(type, Long.toString(targetId)).detail("silenceId", Long.toString(id)).detail("via", via));
        if ("ALARM".equals(type)) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.alarm_events (organization_id, alarm_id, at, type, actor_type, actor_id, data)
                            VALUES (:org, :alarm, :at, 'SILENCED', :actorType, :user, CAST(:data AS jsonb))""")
                    .param("org", orgId).param("alarm", targetId).param("at", net.java21.data2flow.core.common.Pg.ts(now))
                    .param("actorType", "MESSENGER".equals(via) ? "MESSENGER" : "USER").param("user", userId)
                    .param("data", json.writeValueAsString(java.util.Map.of("silenceId", Long.toString(id),
                            "endsAt", ends == null ? "" : ends.toString()))).update();
        }
        return view(silences.findById(orgId, id).orElseThrow(), now, zone(orgId));
    }

    @Transactional
    public void delete(long id) {
        roleChecker.require(Permission.ALARM_HANDLE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        SilenceRow s = silences.findById(orgId, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        checkTarget(orgId, s.targetType(), s.targetId());
        silences.delete(orgId, id);
        publisher.configDeleted(ConfigChangedMessage.EntityType.SILENCE, id, 2, orgId);
        audits.record(audits.event(orgId, "SILENCE_DELETED").actor(user).target(s.targetType(), Long.toString(s.targetId()))
                .detail("silenceId", Long.toString(id)));
    }

    /** 대상이 이 조직에 있고(공간 범위 안) 보이는가. 아니면 404 */
    void checkTarget(long orgId, String type, long id) {
        String sql = switch (type) {
            case "RULE" -> "SELECT NULL::bigint AS space_id FROM data2flow_core.rules WHERE organization_id = :org AND id = :id AND status <> 'DELETED'";
            case "DEVICE" -> "SELECT space_id FROM data2flow_core.devices WHERE organization_id = :org AND id = :id AND status <> 'DELETED'";
            case "SPACE" -> "SELECT id AS space_id FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id";
            default -> "SELECT space_id FROM data2flow_core.alarms WHERE organization_id = :org AND id = :id";
        };
        List<Long> found = jdbc.sql(sql).param("org", orgId).param("id", id)
                .query((rs, n) -> net.java21.data2flow.core.common.Pg.longOrNull(rs, "space_id")).list();
        if (found.isEmpty()) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        var scope = net.java21.data2flow.contracts.identity.CurrentUserHolder.find().isPresent() ? roleChecker.spaceScope() : null;
        if (scope != null && !scope.unrestricted() && (found.getFirst() == null || !scope.includes(found.getFirst()))) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    Silence view(SilenceRow s, Instant now, ZoneId zone) {
        boolean active = SilenceMatcher.active(SilenceEvaluator.period(s, json), now, zone);
        return new Silence(Long.toString(s.id()), s.kind(), new SilenceTarget(s.targetType(), Long.toString(s.targetId()), s.targetName()),
                s.startsAt(), s.endsAt(), s.recurrence() == null ? null : json.readTree(s.recurrence()), s.reason(), active,
                new UserRef(Long.toString(s.createdBy()), s.createdByName()), s.createdAt());
    }

    ZoneId zone(long orgId) {
        try {
            return ZoneId.of(policies.findTimezone(orgId));
        } catch (RuntimeException ex) {
            return ZoneId.of("Asia/Seoul");
        }
    }

    static Instant instant(JsonNode v) {
        try {
            return Instant.parse(v.asString(""));
        } catch (RuntimeException ex) {
            throw new BusinessException(AlarmErrorCode.SILENCE_RANGE_INVALID);
        }
    }

    static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
