package net.java21.data2flow.core.control.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.EmergencyStopChanged;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.control.domain.ControlErrorCode;
import net.java21.data2flow.core.control.dto.SafetyDtos;
import net.java21.data2flow.core.control.repository.SafetyRepository;
import net.java21.data2flow.core.control.repository.SafetyRepository.InterlockRow;
import net.java21.data2flow.core.control.repository.SafetyRepository.StopRow;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 인터락(ACT-02.08·06.02, API-ACT-16, BR-ACT-11)과 자동화 비상 정지(ACT-06.03, API-ACT-20·21, BR-ACT-12). 판정은 action 제어 창구가 하고
 * core는 정의를 둔다(API-ACT-44·46으로 action이 읽고 설정 변경 INTERLOCK·EMERGENCY_STOP으로 지운다, ADR-049).
 * <ul>
 *   <li>인터락 조건: {@code {kind: metric, deviceId | spaceAgg, metric, op, value}} 또는 {@code {kind: state, deviceId | relation, capability,
 *       attribute, op, value}}, 금지: {@code {capability, command?, argsMatch?}}. 틀리면 400 INTERLOCK_INVALID. INTERLOCK_MANAGE</li>
 *   <li>비상 정지: EMERGENCY_STOP(OPERATOR 이상)이 시작, EMERGENCY_RELEASE(ADMIN·INTEGRATOR)만 해제. 같은 범위(또는 조직 전체)가 이미 정지 중이면
 *       409 EMERGENCY_STOP_ACTIVE. 시작·해제는 EVT-ACT-03 {@code control.emergency.started|released} + 설정 변경 EMERGENCY_STOP</li>
 * </ul>
 */
@Service
public class SafetyService {

    static final Set<String> OPS = Set.of(">", ">=", "<", "<=", "==", "!=");

    private final SafetyRepository repository;
    private final CoreEventPublisher publisher;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public SafetyService(SafetyRepository repository, CoreEventPublisher publisher, RoleChecker roleChecker, Audits audits, JsonMapper json,
                         Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 인터락

    @Transactional(readOnly = true)
    public List<SafetyDtos.Interlock> interlocks() {
        roleChecker.require(Permission.INTERLOCK_MANAGE);
        SpaceScope scope = roleChecker.spaceScope();
        return repository.listInterlocks(roleChecker.currentUser().organizationId()).stream()
                .filter(i -> scope.unrestricted() || (i.spaceId() != null && scope.includes(i.spaceId()))).map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public SafetyDtos.Interlock interlock(long id) {
        roleChecker.require(Permission.INTERLOCK_MANAGE);
        return view(visibleInterlock(roleChecker.currentUser().organizationId(), id));
    }

    @Transactional
    public SafetyDtos.Interlock createInterlock(JsonNode body) {
        roleChecker.require(Permission.INTERLOCK_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        Values v = interlockValues(orgId, body);
        if (repository.existsInterlockName(orgId, v.name(), null)) {
            throw new BusinessException(ControlErrorCode.INTERLOCK_INVALID, List.of(new FieldErrorDetail("name", "Duplicated", null)));
        }
        long id = repository.insertInterlock(orgId, v.name(), v.spaceId(), v.includeChildren(), v.enabled(), v.condition(), v.forbid(),
                v.message(), user.userId(), clock.instant());
        publisher.configChanged(ConfigChangedMessage.EntityType.INTERLOCK, id, 1, orgId);
        audits.record(audits.event(orgId, "INTERLOCK_CREATED").actor(user).target("INTERLOCK", Long.toString(id)).detail("name", v.name()));
        return view(repository.findInterlock(orgId, id).orElseThrow());
    }

    @Transactional
    public SafetyDtos.Interlock updateInterlock(long id, JsonNode body) {
        roleChecker.require(Permission.INTERLOCK_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        InterlockRow current = visibleInterlock(orgId, id);
        Values v = interlockValues(orgId, body);
        if (repository.existsInterlockName(orgId, v.name(), id)) {
            throw new BusinessException(ControlErrorCode.INTERLOCK_INVALID, List.of(new FieldErrorDetail("name", "Duplicated", null)));
        }
        Integer base = body.hasNonNull("baseVersion") ? body.get("baseVersion").asInt() : null;
        if (repository.updateInterlock(orgId, id, base, v.name(), v.spaceId(), v.includeChildren(), v.enabled(), v.condition(), v.forbid(),
                v.message(), user.userId(), clock.instant()) == 0) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        publisher.configChanged(ConfigChangedMessage.EntityType.INTERLOCK, id, current.version() + 1L, orgId);
        audits.record(audits.event(orgId, "INTERLOCK_UPDATED").actor(user).target("INTERLOCK", Long.toString(id)));
        return view(repository.findInterlock(orgId, id).orElseThrow());
    }

    @Transactional
    public void deleteInterlock(long id) {
        roleChecker.require(Permission.INTERLOCK_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        InterlockRow current = visibleInterlock(orgId, id);
        repository.deleteInterlock(orgId, id);
        publisher.configDeleted(ConfigChangedMessage.EntityType.INTERLOCK, id, current.version() + 1L, orgId);
        audits.record(audits.event(orgId, "INTERLOCK_DELETED").actor(user).target("INTERLOCK", Long.toString(id)));
    }

    /** API-ACT-44 기기에 걸리는 켜진 인터락 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> interlocksForDevice(long orgId, long deviceId) {
        return repository.listInterlocksForDevice(orgId, deviceId).stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("interlockId", i.id());
            m.put("name", i.name());
            m.put("spaceId", i.spaceId());
            m.put("includeChildren", i.includeChildren());
            m.put("condition", json.readTree(i.condition()));
            m.put("forbid", json.readTree(i.forbid()));
            m.put("message", i.message());
            JsonNode stale = json.readTree(i.condition()).get("staleAfterSec");
            m.put("staleAfterSec", stale == null || !stale.isIntegralNumber() ? null : stale.asInt());
            return m;
        }).toList();
    }

    /** API-ACT-45 측정값: 기기면 마지막 값, 공간이면 측정 기기 평균. 없으면 404 */
    @Transactional(readOnly = true)
    public Map<String, Object> metricValue(String metric, Long deviceId, Long spaceId, Instant at) {
        if (metric == null || metric.isBlank() || (deviceId == null) == (spaceId == null)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("metric", "NotNull", null)));
        }
        Instant when = at == null ? clock.instant() : at;
        SafetyRepository.MetricValueRow row = (deviceId != null ? repository.findDeviceValue(deviceId, metric, when)
                : repository.findSpaceAverage(spaceId, metric, when))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", metric);
        m.put("value", row.value());
        m.put("measuredAt", row.measuredAt());
        m.put("reportIntervalSec", row.reportIntervalSec());
        return m;
    }

    record Values(String name, Long spaceId, boolean includeChildren, boolean enabled, String condition, String forbid, String message) {
    }

    Values interlockValues(long orgId, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw invalid("body");
        }
        String name = body.path("name").asString("").strip();
        if (name.isEmpty() || name.length() > 100) {
            throw invalid("name");
        }
        Long spaceId = null;
        if (body.hasNonNull("spaceId")) {
            spaceId = parseId(body.get("spaceId").asString(""), "spaceId");
            if (repository.findSpacePath(orgId, spaceId).isEmpty() || !roleChecker.spaceScope().includes(spaceId)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
        } else if (!roleChecker.spaceScope().unrestricted()) {
            throw invalid("spaceId");
        }
        JsonNode c = body.get("condition");
        if (c == null || !c.isObject()) {
            throw invalid("condition");
        }
        String kind = c.path("kind").asString("").toLowerCase(Locale.ROOT);
        String op = c.path("op").asString("");
        if (!OPS.contains(op) || !c.has("value")) {
            throw invalid("condition.op");
        }
        switch (kind) {
            case "metric" -> {
                if (c.path("metric").asString("").isBlank() || (!c.hasNonNull("deviceId") && !c.path("spaceAgg").asBoolean(false))) {
                    throw invalid("condition.metric");
                }
            }
            case "state" -> {
                if (c.path("capability").asString("").isBlank() || c.path("attribute").asString("").isBlank()
                        || (!c.hasNonNull("deviceId") && !c.hasNonNull("relation"))) {
                    throw invalid("condition.capability");
                }
            }
            default -> throw invalid("condition.kind");
        }
        if (c.hasNonNull("deviceId")) {
            long device = parseId(c.get("deviceId").asString(""), "condition.deviceId");
            if (repository.findDeviceSpace(orgId, device).isEmpty()) {
                throw invalid("condition.deviceId");
            }
        }
        ObjectNode condition = (ObjectNode) c.deepCopy();
        condition.put("kind", kind);
        JsonNode f = body.get("forbid");
        if (f == null || !f.isObject() || f.path("capability").asString("").isBlank()
                || (f.has("argsMatch") && !f.get("argsMatch").isObject())) {
            throw invalid("forbid.capability");
        }
        String message = body.path("message").asString("").strip();
        if (message.isEmpty() || message.length() > 200) {
            throw invalid("message");
        }
        boolean children = !body.has("includeChildren") || body.get("includeChildren").asBoolean(true);
        boolean enabled = !body.has("enabled") || body.get("enabled").asBoolean(true);
        return new Values(name, spaceId, children, enabled, json.writeValueAsString(condition), json.writeValueAsString(f), message);
    }

    InterlockRow visibleInterlock(long orgId, long id) {
        InterlockRow row = repository.findInterlock(orgId, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        SpaceScope scope = roleChecker.spaceScope();
        if (!scope.unrestricted() && (row.spaceId() == null || !scope.includes(row.spaceId()))) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return row;
    }

    SafetyDtos.Interlock view(InterlockRow i) {
        return new SafetyDtos.Interlock(Long.toString(i.id()), i.name(), i.spaceId() == null ? null : Long.toString(i.spaceId()), i.spaceName(),
                i.includeChildren(), i.enabled(), json.readTree(i.condition()), json.readTree(i.forbid()), i.message(), i.version(),
                i.updatedAt());
    }

    // ------------------------------------------------------------------ 비상 정지

    /** API-ACT-20 {scope:{type: ORG} | {type: SPACE, spaceId, includeChildren}, reason} → 201 */
    @Transactional
    public SafetyDtos.EmergencyStop start(JsonNode body) {
        roleChecker.require(Permission.EMERGENCY_STOP);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        JsonNode s = body == null ? null : body.get("scope");
        String type = s == null ? "" : s.path("type").asString("").toUpperCase(Locale.ROOT);
        EmergencyStopChanged.Scope scope;
        if ("ORG".equals(type)) {
            if (!roleChecker.spaceScope().unrestricted()) {
                throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
            }
            scope = EmergencyStopChanged.Scope.organization();
        } else if ("SPACE".equals(type)) {
            long spaceId = parseId(s.path("spaceId").asString(""), "scope.spaceId");
            if (repository.findSpacePath(orgId, spaceId).isEmpty() || !roleChecker.spaceScope().includes(spaceId)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
            scope = new EmergencyStopChanged.Scope(EmergencyStopChanged.Scope.Type.SPACE, spaceId,
                    !s.has("includeChildren") || s.get("includeChildren").asBoolean(true));
        } else {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("scope.type", "Pattern", null)));
        }
        String reason = body.path("reason").asString("").strip();
        if (reason.isEmpty() || reason.length() > 200) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("reason", "Size", null)));
        }
        repository.lockOrganization(orgId);
        for (StopRow open : repository.listStops(orgId, true, 100)) {
            JsonNode o = json.readTree(open.scope());
            boolean sameOrg = "ORG".equals(o.path("type").asString());
            boolean sameSpace = scope.spaceId() != null && "SPACE".equals(o.path("type").asString()) && o.path("spaceId").asLong() == scope.spaceId();
            if (sameOrg || sameSpace) {
                throw new BusinessException(ControlErrorCode.EMERGENCY_STOP_ACTIVE);
            }
        }
        Instant now = clock.instant();
        long id = repository.insertStop(orgId, json.writeValueAsString(scope), reason, user.userId(), now);
        publisher.configChanged(ConfigChangedMessage.EntityType.EMERGENCY_STOP, id, 1, orgId);
        publisher.event(EventType.CONTROL_EMERGENCY_STARTED, orgId, new EmergencyStopChanged(id, scope, reason, user.userId(), now));
        audits.record(audits.event(orgId, "EMERGENCY_STOP_STARTED").actor(user).target("EMERGENCY_STOP", Long.toString(id))
                .detail("scope", json.writeValueAsString(scope)).detail("reason", reason));
        return stopView(repository.lockStop(orgId, id).orElseThrow());
    }

    /** API-ACT-21 해제 {note?} — EMERGENCY_RELEASE */
    @Transactional
    public SafetyDtos.EmergencyStop release(long id, JsonNode body) {
        roleChecker.require(Permission.EMERGENCY_RELEASE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        StopRow row = repository.lockStop(orgId, id).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (row.releasedAt() != null) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        String note = body == null || !body.hasNonNull("note") ? null : body.get("note").asString("").strip();
        if (note != null && note.length() > 500) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("note", "Size", null)));
        }
        Instant now = clock.instant();
        repository.updateReleased(orgId, id, user.userId(), note, now);
        EmergencyStopChanged.Scope scope = json.readValue(row.scope(), EmergencyStopChanged.Scope.class);
        publisher.configChanged(ConfigChangedMessage.EntityType.EMERGENCY_STOP, id, 2, orgId);
        publisher.event(EventType.CONTROL_EMERGENCY_RELEASED, orgId, new EmergencyStopChanged(id, scope, row.reason(), user.userId(), now));
        audits.record(audits.event(orgId, "EMERGENCY_STOP_RELEASED").actor(user).target("EMERGENCY_STOP", Long.toString(id)));
        return stopView(repository.lockStop(orgId, id).orElseThrow());
    }

    /** API-ACT-21 목록 — 로그인 사용자 누구나(전역 배너) */
    @Transactional(readOnly = true)
    public List<SafetyDtos.EmergencyStop> stops(Boolean active) {
        roleChecker.currentUser();
        return repository.listStops(roleChecker.currentUser().organizationId(), Boolean.TRUE.equals(active), 100).stream()
                .map(this::stopView).toList();
    }

    /** API-ACT-46 배포 조직 전체의 진행 중 비상 정지 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> activeStops(List<Long> organizations) {
        return repository.listActiveStops(organizations).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("emergencyStopId", r.id());
            m.put("organizationId", r.organizationId());
            m.put("scope", json.readTree(r.scope()));
            m.put("reason", r.reason());
            m.put("startedAt", r.startedAt());
            return m;
        }).toList();
    }

    SafetyDtos.EmergencyStop stopView(StopRow r) {
        return new SafetyDtos.EmergencyStop(Long.toString(r.id()), json.readTree(r.scope()), r.reason(), Long.toString(r.startedBy()),
                r.startedAt(), r.releasedBy() == null ? null : Long.toString(r.releasedBy()), r.releasedAt(), r.releaseNote(), null);
    }

    static long parseId(String raw, String field) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException ex) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "Pattern", null)));
        }
    }

    static BusinessException invalid(String field) {
        return new BusinessException(ControlErrorCode.INTERLOCK_INVALID, List.of(new FieldErrorDetail(field, "INVALID", null)));
    }
}
