package net.java21.data2flow.core.control.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.command.ActionIdempotencyKeys;
import net.java21.data2flow.contracts.command.ActionKind;
import net.java21.data2flow.contracts.command.CommandPayload;
import net.java21.data2flow.contracts.command.CommandPriority;
import net.java21.data2flow.contracts.command.CommandSource;
import net.java21.data2flow.contracts.command.CommandTarget;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ActionRequest;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.control.domain.ControlErrorCode;
import net.java21.data2flow.core.control.domain.ScheduleTimes;
import net.java21.data2flow.core.control.dto.SafetyDtos;
import net.java21.data2flow.core.control.repository.SafetyRepository;
import net.java21.data2flow.core.control.repository.SceneScheduleRepository;
import net.java21.data2flow.core.control.repository.SceneScheduleRepository.ScheduleRow;
import net.java21.data2flow.core.control.repository.SceneScheduleRepository.ScheduleValues;
import net.java21.data2flow.core.outbox.service.OutboxWriter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 예약 제어(ACT-02.07, API-ACT-15, BR-ACT-18). 정의와 실행기 모두 core에 둔다(코디네이터 결정, ADR-049 남은 것 ③): 1분마다 실행할 때가 된
 * 예약을 {@code FOR UPDATE SKIP LOCKED}로 한 파드만 잡아(배포 조직만) 행동 요청을 {@code data2flow.actions}(라우팅 키 command) 아웃박스에
 * 쓴다. 출처는 SCHEDULE(우선순위 SCHEDULE, 비상 정지 중이면 action이 SKIPPED). 장면 대상은 kind=SCENE {sceneId}, 기기 대상은 COMMAND.
 * 멱등 키 {@code sha256("schedule", id, 예정 시각)}이라 다시 돌아도 한 번만 실행된다. SCHEDULE_MANAGE.
 * 휴일 제외(skipHolidays)는 조직 달력(DEV-12.01)으로 예정 시각의 날짜(예약 시간대)를 판정해 휴일이면 보내지 않고 {@code lastRun.status=SKIPPED}
 * ({@code holidayCheck=HOLIDAY}), 아니면 {@code holidayCheck=WORKDAY}로 남긴다(M5).
 */
@Service
public class ScheduleService {

    static final Duration VALIDITY = Duration.ofMinutes(10);

    private final SceneScheduleRepository schedules;
    private final SafetyRepository safety;
    private final OutboxWriter outbox;
    private final MessageCodec codec;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final net.java21.data2flow.core.calendar.service.HolidayLookup holidays;

    public ScheduleService(SceneScheduleRepository schedules, SafetyRepository safety, OutboxWriter outbox, MessageCodec codec,
                           RoleChecker roleChecker, Audits audits, JdbcClient jdbc, JsonMapper json, Clock clock,
                           net.java21.data2flow.core.calendar.service.HolidayLookup holidays) {
        this.schedules = schedules;
        this.safety = safety;
        this.outbox = outbox;
        this.codec = codec;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.holidays = holidays;
    }

    @Transactional(readOnly = true)
    public List<SafetyDtos.Schedule> list() {
        roleChecker.require(Permission.SCHEDULE_MANAGE);
        return schedules.listSchedules(roleChecker.currentUser().organizationId()).stream().map(this::view).toList();
    }

    @Transactional
    public SafetyDtos.Schedule create(JsonNode body) {
        roleChecker.require(Permission.SCHEDULE_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ScheduleValues v = values(orgId, body);
        if (schedules.existsScheduleName(orgId, v.name(), null)) {
            throw invalid("name");
        }
        long id;
        try {
            id = schedules.insertSchedule(v, user.userId(), clock.instant());
        } catch (DuplicateKeyException ex) {
            throw invalid("name");
        }
        audits.record(audits.event(orgId, "SCHEDULE_CREATED").actor(user).target("SCHEDULE", Long.toString(id)).detail("name", v.name()));
        return view(schedules.findSchedule(orgId, id).orElseThrow());
    }

    @Transactional
    public SafetyDtos.Schedule update(long id, JsonNode body) {
        roleChecker.require(Permission.SCHEDULE_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        require(orgId, id);
        ScheduleValues v = values(orgId, body);
        if (schedules.existsScheduleName(orgId, v.name(), id)) {
            throw invalid("name");
        }
        schedules.updateSchedule(id, v, user.userId(), clock.instant());
        audits.record(audits.event(orgId, "SCHEDULE_UPDATED").actor(user).target("SCHEDULE", Long.toString(id)));
        return view(schedules.findSchedule(orgId, id).orElseThrow());
    }

    @Transactional
    public void delete(long id) {
        roleChecker.require(Permission.SCHEDULE_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        require(orgId, id);
        schedules.deleteSchedule(orgId, id);
        audits.record(audits.event(orgId, "SCHEDULE_DELETED").actor(user).target("SCHEDULE", Long.toString(id)));
    }

    @Transactional
    public SafetyDtos.Schedule enable(long id, boolean enabled) {
        roleChecker.require(Permission.SCHEDULE_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        ScheduleRow s = require(orgId, id);
        Instant now = clock.instant();
        schedules.updateEnabled(orgId, id, enabled, enabled ? next(orgId, s, now) : null, now);
        audits.record(audits.event(orgId, enabled ? "SCHEDULE_ENABLED" : "SCHEDULE_DISABLED").actor(user).target("SCHEDULE", Long.toString(id)));
        return view(schedules.findSchedule(orgId, id).orElseThrow());
    }

    /** 실행기 한 번(1분마다, AutomationJobs). 보낸 예약 수 */
    @Transactional
    public int runDue(List<Long> organizations) {
        Instant now = clock.instant();
        int sent = 0;
        for (ScheduleRow s : schedules.lockDue(organizations, now, 100)) {
            Instant planned = s.nextRunAt();
            JsonNode target = json.readTree(s.target());
            String holidayCheck = null;
            if (s.skipHolidays()) {
                boolean holiday = holidays.isHoliday(s.organizationId(), scheduleSpace(s, target),
                        java.time.LocalDate.ofInstant(planned, zone(s.timezone())));
                holidayCheck = holiday ? "HOLIDAY" : "WORKDAY";
                if (holiday) {
                    Map<String, Object> skipped = new LinkedHashMap<>();
                    skipped.put("at", now.toString());
                    skipped.put("plannedAt", planned.toString());
                    skipped.put("status", "SKIPPED");
                    skipped.put("holidayCheck", holidayCheck);
                    schedules.updateRun(s.organizationId(), s.id(), next(s.organizationId(), s, now), json.writeValueAsString(skipped));
                    continue;
                }
            }
            String key = ActionIdempotencyKeys.of("schedule", Long.toString(s.id()), Long.toString(planned.getEpochSecond()));
            CommandSource source = CommandSource.schedule(s.id());
            ActionRequest req;
            if (target.hasNonNull("sceneId")) {
                req = ActionRequest.of(ActionKind.SCENE, s.organizationId(), key, source, CommandPriority.SCHEDULE, now.plus(VALIDITY),
                        json.createObjectNode().put("sceneId", target.get("sceneId").asLong()), clock);
            } else {
                Map<String, Object> args = json.convertValue(target.path("args"), new TypeReference<Map<String, Object>>() { });
                req = ActionRequest.command(s.organizationId(), key, source, now.plus(VALIDITY), new CommandPayload(CommandTarget.device(
                        target.get("deviceId").asLong()), target.path("capability").asString(), target.path("command").asString(),
                        args == null ? Map.of() : args, false), clock);
            }
            outbox.message(s.organizationId(), "COMMAND", MessagingNames.EXCHANGE_ACTIONS, req.routingKey(), req.messageId().toString(),
                    codec.writeAsString(req));
            Map<String, Object> last = new LinkedHashMap<>();
            last.put("at", now.toString());
            last.put("plannedAt", planned.toString());
            last.put("status", "SENT");
            last.put("messageId", req.messageId().toString());
            if (holidayCheck != null) {
                last.put("holidayCheck", holidayCheck);
            }
            // 멈춰 있던 동안 놓친 회차는 한 번만 실행하고 다음은 지금 이후로(밀린 실행을 몰아서 하지 않음)
            schedules.updateRun(s.organizationId(), s.id(), next(s.organizationId(), s, now), json.writeValueAsString(last));
            sent++;
        }
        return sent;
    }

    /** 휴일 판정 공간: 공간 운영 시간 기준 예약이면 그 공간, 기기 대상이면 기기 공간, 장면은 조직 전체(null) */
    private Long scheduleSpace(ScheduleRow s, JsonNode target) {
        JsonNode sh = s.spaceHours() == null ? null : json.readTree(s.spaceHours());
        if (sh != null && sh.path("spaceId").asLong(0) > 0) {
            return sh.path("spaceId").asLong();
        }
        if (target.hasNonNull("deviceId")) {
            return safety.findDeviceSpace(s.organizationId(), target.get("deviceId").asLong()).filter(id -> id > 0).orElse(null);
        }
        return null;
    }

    Instant next(long orgId, ScheduleRow s, Instant after) {
        ZoneId zone = zone(s.timezone());
        JsonNode sh = s.spaceHours() == null ? null : json.readTree(s.spaceHours());
        ScheduleTimes.Hours[] hours = new ScheduleTimes.Hours[0];
        if (sh != null) {
            hours = schedules.listSpaceHours(orgId, sh.path("spaceId").asLong()).stream()
                    .map(h -> new ScheduleTimes.Hours(h.dayOfWeek(), h.start(), h.end())).toArray(ScheduleTimes.Hours[]::new);
        }
        return ScheduleTimes.next(s.kind(), s.runAt(), s.cron(), hours, sh == null ? 0 : sh.path("offsetMinutes").asInt(0),
                sh == null ? null : sh.path("edge").asString("START"), s.validFrom(), s.validTo(), zone, after);
    }

    ScheduleValues values(long orgId, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw invalid("body");
        }
        String name = body.path("name").asString("").strip();
        if (name.isEmpty() || name.length() > 100) {
            throw invalid("name");
        }
        JsonNode t = body.path("target");
        SpaceScope scope = roleChecker.spaceScope();
        String target;
        if (t.hasNonNull("sceneId")) {
            long sceneId = SafetyService.parseId(t.get("sceneId").asString(""), "target.sceneId");
            if (schedules.findScene(orgId, sceneId).isEmpty()) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
            target = json.writeValueAsString(Map.of("sceneId", sceneId));
        } else if (t.hasNonNull("deviceId")) {
            long deviceId = SafetyService.parseId(t.get("deviceId").asString(""), "target.deviceId");
            Long space = safety.findDeviceSpace(orgId, deviceId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            if (!scope.unrestricted() && (space == 0 || !scope.includes(space))) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
            if (t.path("capability").asString("").isBlank() || t.path("command").asString("").isBlank()
                    || (t.has("args") && !t.get("args").isObject())) {
                throw invalid("target.capability");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", deviceId);
            m.put("capability", t.get("capability").asString());
            m.put("command", t.get("command").asString());
            m.put("args", t.has("args") ? t.get("args") : json.createObjectNode());
            target = json.writeValueAsString(m);
        } else {
            throw invalid("target");
        }
        String kind = body.path("kind").asString("").toUpperCase(Locale.ROOT);
        Instant at = null;
        String cron = null;
        String spaceHours = null;
        try {
            switch (kind) {
                case "ONCE" -> at = Instant.parse(body.path("at").asString(""));
                case "RECURRING" -> {
                    cron = body.path("cron").asString("").strip();
                    ScheduleTimes.cron(cron);
                }
                case "SPACE_HOURS" -> {
                    JsonNode sh = body.path("spaceHours");
                    long spaceId = SafetyService.parseId(sh.path("spaceId").asString(""), "spaceHours.spaceId");
                    if (safety.findSpacePath(orgId, spaceId).isEmpty()) {
                        throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
                    }
                    String edge = sh.path("edge").asString("START").toUpperCase(Locale.ROOT);
                    int offset = sh.path("offsetMinutes").asInt(0);
                    if (!edge.equals("START") && !edge.equals("END") || Math.abs(offset) > 720) {
                        throw invalid("spaceHours");
                    }
                    spaceHours = json.writeValueAsString(Map.of("spaceId", spaceId, "edge", edge, "offsetMinutes", offset));
                }
                default -> throw invalid("kind");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw invalid("cron");
        }
        LocalDate from;
        LocalDate to;
        try {
            from = body.hasNonNull("validFrom") ? LocalDate.parse(body.get("validFrom").asString()) : null;
            to = body.hasNonNull("validTo") ? LocalDate.parse(body.get("validTo").asString()) : null;
        } catch (RuntimeException ex) {
            throw invalid("validFrom");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw invalid("validTo");
        }
        String tz = body.hasNonNull("timezone") ? body.get("timezone").asString() : timezone(orgId);
        try {
            ZoneId.of(tz);
        } catch (RuntimeException ex) {
            throw invalid("timezone");
        }
        boolean skip = body.path("skipHolidays").asBoolean(false);
        ScheduleRow draft = new ScheduleRow(0, orgId, name, target, kind, at, cron, spaceHours, from, to, skip, tz, true, null, null, 0, null);
        Instant next = next(orgId, draft, clock.instant());
        return new ScheduleValues(orgId, name, target, kind, at, cron, spaceHours, from, to, skip, tz, next);
    }

    String timezone(long orgId) {
        return jdbc.sql("SELECT coalesce((SELECT timezone FROM data2flow_core.org_settings WHERE organization_id = :org), 'Asia/Seoul')")
                .param("org", orgId).query(String.class).single();
    }

    ScheduleRow require(long orgId, long id) {
        return schedules.findSchedule(orgId, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    SafetyDtos.Schedule view(ScheduleRow s) {
        return new SafetyDtos.Schedule(Long.toString(s.id()), s.name(), json.readTree(s.target()), s.kind(), s.runAt(), s.cron(),
                s.spaceHours() == null ? null : json.readTree(s.spaceHours()), s.validFrom() == null ? null : s.validFrom().toString(),
                s.validTo() == null ? null : s.validTo().toString(), s.skipHolidays(), s.timezone(), s.enabled(), s.nextRunAt(),
                s.lastRun() == null ? null : json.readTree(s.lastRun()), s.version(), s.updatedAt());
    }

    static ZoneId zone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (RuntimeException ex) {
            return ZoneId.of("Asia/Seoul");
        }
    }

    static BusinessException invalid(String field) {
        return new BusinessException(ControlErrorCode.SCHEDULE_INVALID, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
