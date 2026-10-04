package net.java21.data2flow.core.calendar.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.calendar.domain.CalendarErrorCode;
import net.java21.data2flow.core.calendar.domain.CalendarModels;
import net.java21.data2flow.core.calendar.domain.CalendarModels.EventRow;
import net.java21.data2flow.core.calendar.dto.CalendarDtos.CalendarEventResponse;
import net.java21.data2flow.core.calendar.repository.CalendarEventRepository;
import net.java21.data2flow.core.calendar.repository.CalendarEventRepository.Values;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.repository.SpaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 조직 달력(DEV-12.01, API-DEV-100~102): 공휴일(DSC-06.03)·가져온 iCal(DSC-06.04)·직접 등록 일정을 모은다. 일정마다 적용 범위(사이트·공간,
 * 비면 조직 전체)와 유형, 운영 모드 영향(HOLIDAY/UNOCCUPIED/NONE)을 둔다. 바뀐 운영 모드는 1분 작업이 계산해 EVT-DEV-06을 낸다.
 * <ul>
 *   <li>조회 DEV_READ(VIEWER+), 등록·수정·삭제 DEV_PLACE(OPERATOR+)</li>
 *   <li>자동 생성 일정(HOLIDAY_API·ICAL)은 affectsMode만 바꿀 수 있고(바꾸면 "수동 수정"으로 남겨 원본 동기화가 덮지 않음, BR-DSC-18),
 *       원본이 지운 일정만 삭제할 수 있다</li>
 *   <li>공간 범위가 제한된 사용자는 범위 안 공간만 지정할 수 있고 조직 전체 일정은 만들 수 없다(403). 범위 밖 공간만 가리키는 일정은 보이지 않는다(404)</li>
 * </ul>
 */
@Service
public class CalendarEventService {

    static final Set<String> PATCHABLE = Set.of("title", "type", "startsOn", "endsOn", "startTime", "endTime", "scopeSpaceIds",
            "affectsMode", "baseVersion");

    private final RoleChecker roleChecker;
    private final CalendarEventRepository events;
    private final SpaceRepository spaces;
    private final Audits audits;
    private final Clock clock;

    public CalendarEventService(RoleChecker roleChecker, CalendarEventRepository events, SpaceRepository spaces, Audits audits, Clock clock) {
        this.roleChecker = roleChecker;
        this.events = events;
        this.spaces = spaces;
        this.audits = audits;
        this.clock = clock;
    }

    /** API-DEV-101 [from, to] 날짜와 겹치는 일정(기간 최대 366일). spaceId면 그 공간·상위 공간·조직 전체 일정만 */
    @Transactional(readOnly = true)
    public ListApiResponse<CalendarEventResponse> list(String from, String to, String spaceId, Integer page, Integer size) {
        roleChecker.require(Permission.DEV_READ);
        long org = roleChecker.currentUser().organizationId();
        List<FieldErrorDetail> errors = new ArrayList<>();
        LocalDate f = date(from, "from", errors, true);
        LocalDate t = date(to, "to", errors, true);
        if (errors.isEmpty() && (t.isBefore(f) || ChronoUnit.DAYS.between(f, t) >= CalendarModels.MAX_DAYS)) {
            errors.add(new FieldErrorDetail("to", "RANGE", "1~366일"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        List<Long> chain = null;
        if (spaceId != null && !spaceId.isBlank()) {
            Space space = visibleSpace(org, parseId(spaceId, "spaceId"));
            chain = space.pathIds();
        }
        SpaceScope scope = roleChecker.spaceScope();
        List<Long> filterChain = chain;
        List<CalendarEventResponse> all = events.listOverlapping(org, f, t).stream()
                .filter(e -> filterChain == null || e.appliesTo(filterChain))
                .filter(e -> visible(e, scope))
                .map(CalendarEventService::toResponse).toList();
        PageParams params = PageParams.of(page, size == null ? 100 : size);
        int fromIndex = (int) Math.min(all.size(), params.offset());
        int toIndex = Math.min(all.size(), fromIndex + params.size());
        return ListApiResponse.of(params, all.subList(fromIndex, toIndex), all.size());
    }

    @Transactional(readOnly = true)
    public CalendarEventResponse get(long id) {
        roleChecker.require(Permission.DEV_READ);
        return toResponse(load(id));
    }

    /** API-DEV-100 */
    @Transactional
    public CalendarEventResponse create(JsonNode body) {
        roleChecker.require(Permission.DEV_PLACE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        Values v = values(org, body, null);
        Instant now = clock.instant();
        long id = events.insert(org, v, CalendarModels.MANUAL, null, null, user.userId(), now);
        audits.record(audits.event(org, "CALENDAR_EVENT_CREATED").actor(user).target("CALENDAR_EVENT", Long.toString(id))
                .detail("title", v.title()).detail("type", v.type()).detail("startsOn", v.startsOn().toString())
                .detail("endsOn", v.endsOn().toString()).detail("affectsMode", v.affectsMode()));
        return toResponse(events.findById(org, id).orElseThrow());
    }

    /** API-DEV-102 PATCH(온 키만, baseVersion). 자동 생성 일정은 affectsMode만 */
    @Transactional
    public CalendarEventResponse update(long id, JsonNode body) {
        roleChecker.require(Permission.DEV_PLACE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        int base = (int) VersionCheck.baseVersion(body);
        EventRow before = load(id);
        VersionCheck.require(base, before.version());
        List<FieldErrorDetail> errors = new ArrayList<>();
        for (String key : body.propertyNames()) {
            if (!PATCHABLE.contains(key)) {
                errors.add(new FieldErrorDetail(key, "UNKNOWN_FIELD", null));
            } else if (!CalendarModels.MANUAL.equals(before.origin()) && !"affectsMode".equals(key) && !"baseVersion".equals(key)) {
                errors.add(new FieldErrorDetail(key, "AUTO_GENERATED", null));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CalendarErrorCode.CALENDAR_EVENT_INVALID, errors);
        }
        Values v = values(org, body, before);
        boolean modified = before.locallyModified() || !CalendarModels.MANUAL.equals(before.origin());
        VersionCheck.requireUpdated(events.update(org, id, base, v, modified, user.userId(), clock.instant()));
        audits.record(audits.event(org, "CALENDAR_EVENT_UPDATED").actor(user).target("CALENDAR_EVENT", Long.toString(id))
                .detail("fields", new java.util.TreeSet<>(body.propertyNames())).detail("affectsMode", v.affectsMode()));
        return toResponse(events.findById(org, id).orElseThrow());
    }

    /** API-DEV-102 DELETE → 204. 자동 생성 일정은 원본에서 사라진 것만 지울 수 있다 */
    @Transactional
    public void delete(long id) {
        roleChecker.require(Permission.DEV_PLACE);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        EventRow e = load(id);
        if (!CalendarModels.MANUAL.equals(e.origin()) && e.originDeletedAt() == null) {
            throw new BusinessException(CalendarErrorCode.CALENDAR_EVENT_INVALID, List.of(new FieldErrorDetail("origin", "AUTO_GENERATED", null)));
        }
        events.delete(org, id);
        audits.record(audits.event(org, "CALENDAR_EVENT_DELETED").actor(user).target("CALENDAR_EVENT", Long.toString(id))
                .detail("title", e.title()).detail("origin", e.origin()));
    }

    private EventRow load(long id) {
        long org = roleChecker.currentUser().organizationId();
        EventRow e = events.findById(org, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!visible(e, roleChecker.spaceScope())) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return e;
    }

    /** 조직 전체 일정은 모두에게, 공간 일정은 범위 안 공간이 하나라도 있으면 보인다 */
    static boolean visible(EventRow e, SpaceScope scope) {
        return scope.unrestricted() || e.scopeSpaceIds().isEmpty() || e.scopeSpaceIds().stream().anyMatch(scope::includes);
    }

    /** 요청 본문 → 값. before가 있으면 온 키만 바꾼다 */
    Values values(long org, JsonNode b, EventRow before) {
        if (b == null || !b.isObject()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("body", "Type", null)));
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        String title = before == null || b.has("title") ? b.path("title").asString("").strip() : before.title();
        if (title.isEmpty() || title.length() > 150) {
            errors.add(new FieldErrorDetail("title", "Size", "1~150"));
        }
        String type = before == null || b.has("type") ? b.path("type").asString("").toUpperCase(Locale.ROOT) : before.type();
        if (!CalendarModels.TYPES.contains(type)) {
            errors.add(new FieldErrorDetail("type", "INVALID", null));
        }
        LocalDate startsOn = before == null || b.has("startsOn") ? date(b.path("startsOn").asString(null), "startsOn", errors, true)
                : before.startsOn();
        LocalDate endsOn = before == null || b.has("endsOn") ? date(b.path("endsOn").asString(null), "endsOn", errors, before != null)
                : before.endsOn();
        if (endsOn == null) {
            endsOn = startsOn;
        }
        LocalTime startTime = before == null || b.has("startTime") ? time(b.get("startTime"), "startTime", errors) : before.startTime();
        LocalTime endTime = before == null || b.has("endTime") ? time(b.get("endTime"), "endTime", errors) : before.endTime();
        String affects;
        if (b.has("affectsMode") && !b.get("affectsMode").isNull()) {
            affects = b.get("affectsMode").asString("").toUpperCase(Locale.ROOT);
            if (!CalendarModels.AFFECTS.contains(affects)) {
                errors.add(new FieldErrorDetail("affectsMode", "INVALID", null));
            }
        } else if (before != null && !b.has("type")) {
            affects = before.affectsMode();
        } else {
            affects = CalendarModels.TYPES.contains(type) ? CalendarModels.defaultAffects(type) : "NONE";
        }
        List<Long> scope = before == null || b.has("scopeSpaceIds") ? scope(org, b.get("scopeSpaceIds"), errors) : before.scopeSpaceIds();
        if (startsOn != null && endsOn != null) {
            if (endsOn.isBefore(startsOn)) {
                errors.add(new FieldErrorDetail("endsOn", "BEFORE_START", null));
            } else if (ChronoUnit.DAYS.between(startsOn, endsOn) >= CalendarModels.MAX_DAYS) {
                errors.add(new FieldErrorDetail("endsOn", "RANGE", "≤366일"));
            }
        }
        if ((startTime == null) != (endTime == null)) {
            errors.add(new FieldErrorDetail(startTime == null ? "startTime" : "endTime", "NotNull", null));
        } else if (startTime != null && startsOn != null && startsOn.equals(endsOn) && !startTime.isBefore(endTime)) {
            errors.add(new FieldErrorDetail("endTime", "BEFORE_START", null));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CalendarErrorCode.CALENDAR_EVENT_INVALID, errors);
        }
        return new Values(title, type, startsOn, endsOn, startTime, endTime, scope, affects);
    }

    /** 적용 범위: 공간이 있고 보여야 한다(없거나 범위 밖은 404). 범위가 제한된 사용자는 조직 전체 일정을 만들 수 없다(403) */
    private List<Long> scope(long org, JsonNode node, List<FieldErrorDetail> errors) {
        Set<Long> ids = new LinkedHashSet<>();
        if (node != null && !node.isNull()) {
            if (!node.isArray() || node.size() > 100) {
                errors.add(new FieldErrorDetail("scopeSpaceIds", "Type", "≤100"));
                return List.of();
            }
            for (JsonNode n : node) {
                String raw = n.isNumber() ? Long.toString(n.asLong()) : n.asString("");
                if (!raw.matches("\\d{1,18}")) {
                    errors.add(new FieldErrorDetail("scopeSpaceIds", "INVALID", raw));
                    return List.of();
                }
                ids.add(Long.parseLong(raw));
            }
        }
        if (!errors.isEmpty()) {
            return List.of(ids.toArray(Long[]::new));
        }
        for (long id : ids) {
            visibleSpace(org, id);
        }
        if (ids.isEmpty() && !roleChecker.spaceScope().unrestricted()) {
            throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
        }
        return List.copyOf(ids);
    }

    private Space visibleSpace(long org, long id) {
        Space s = spaces.findById(org, id).orElseThrow(() -> new BusinessException(CalendarErrorCode.SPACE_NOT_FOUND));
        roleChecker.requireSpace(id, CalendarErrorCode.SPACE_NOT_FOUND);
        return s;
    }

    static LocalDate date(String raw, String field, List<FieldErrorDetail> errors, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                errors.add(new FieldErrorDetail(field, "NotNull", null));
            }
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException ex) {
            errors.add(new FieldErrorDetail(field, "INVALID", "yyyy-MM-dd"));
            return null;
        }
    }

    static LocalTime time(JsonNode node, String field, List<FieldErrorDetail> errors) {
        if (node == null || node.isNull() || node.asString("").isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(node.asString().strip());
        } catch (DateTimeParseException ex) {
            errors.add(new FieldErrorDetail(field, "INVALID", "HH:mm"));
            return null;
        }
    }

    static long parseId(String raw, String field) {
        if (raw == null || !raw.strip().matches("\\d{1,18}")) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
        }
        return Long.parseLong(raw.strip());
    }

    public static CalendarEventResponse toResponse(EventRow e) {
        return new CalendarEventResponse(Long.toString(e.id()), e.title(), e.type(), e.startsOn(), e.endsOn(), hhmm(e.startTime()),
                hhmm(e.endTime()), e.scopeSpaceIds().stream().map(String::valueOf).toList(), e.origin(), e.affectsMode(),
                e.sourceId() == null ? null : Long.toString(e.sourceId()), e.locallyModified(), e.originDeletedAt() != null, e.version(),
                e.updatedAt());
    }

    static String hhmm(LocalTime t) {
        return t == null ? null : String.format("%02d:%02d", t.getHour(), t.getMinute());
    }
}
