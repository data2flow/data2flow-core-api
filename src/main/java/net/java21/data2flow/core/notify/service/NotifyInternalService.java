package net.java21.data2flow.core.notify.service;

import net.java21.data2flow.contracts.alarm.AlarmSnapshot;
import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.PermissionLookup;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.alarm.domain.AlarmErrorCode;
import net.java21.data2flow.core.alarm.repository.AlarmRepository;
import net.java21.data2flow.core.alarm.repository.AlarmRepository.AlarmRow;
import net.java21.data2flow.core.alarm.service.AlarmService;
import net.java21.data2flow.core.alarm.service.AlarmViews;
import net.java21.data2flow.core.common.InternalOrganizations;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Policy;
import net.java21.data2flow.core.notify.repository.ChannelRepository;
import net.java21.data2flow.core.notify.repository.NotifyPrefRepository;
import net.java21.data2flow.core.notify.repository.NotifyPrefRepository.PrefRow;
import net.java21.data2flow.core.notify.repository.NotifyPrefRepository.UserProfile;
import net.java21.data2flow.core.notify.repository.OnCallRepository;
import net.java21.data2flow.core.notify.repository.PolicyRepository;
import net.java21.data2flow.core.notify.repository.SilenceRepository;
import net.java21.data2flow.core.notify.repository.SilenceRepository.SilenceRow;
import net.java21.data2flow.core.notify.repository.TemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * action notification 패키지가 부르는 core 내부 API(RUL-api §5.1 API-RUL-40~50, OPS-api API-OPS-35, ADR-049). 토큰 없음, 내부망 신뢰
 * (ADR-021). 정책·알람·수신자(수신 설정·연결 계정·공간 범위)·무음·당직·템플릿·채널(비밀값 복호화)을 주고, 에스컬레이션 기록을 받는다.
 */
@Service
public class NotifyInternalService {

    static final Set<String> TIMELINE_TYPES = Set.of("ESCALATED", "NOTIFIED");

    private final PolicyRepository policies;
    private final PolicyService policyService;
    private final OnCallRepository onCall;
    private final NotifyPrefRepository prefs;
    private final SilenceRepository silences;
    private final TemplateRepository templates;
    private final ChannelRepository channels;
    private final ChannelService channelService;
    private final AlarmRepository alarms;
    private final AlarmService alarmService;
    private final PermissionLookup permissions;
    private final InternalOrganizations organizations;
    private final JsonMapper json;
    private final Clock clock;

    public NotifyInternalService(PolicyRepository policies, PolicyService policyService, OnCallRepository onCall, NotifyPrefRepository prefs,
                                 SilenceRepository silences, TemplateRepository templates, ChannelRepository channels,
                                 ChannelService channelService, AlarmRepository alarms, AlarmService alarmService, PermissionLookup permissions,
                                 InternalOrganizations organizations, JsonMapper json, Clock clock) {
        this.policies = policies;
        this.policyService = policyService;
        this.onCall = onCall;
        this.prefs = prefs;
        this.silences = silences;
        this.templates = templates;
        this.channels = channels;
        this.channelService = channelService;
        this.alarms = alarms;
        this.alarmService = alarmService;
        this.permissions = permissions;
        this.organizations = organizations;
        this.json = json;
        this.clock = clock;
    }

    /** API-RUL-40 정책(API-RUL-21 모양 + organizationId) */
    @Transactional(readOnly = true)
    public Map<String, Object> policy(long policyId) {
        long orgId = policies.findOrganization(policyId).filter(this::deployed)
                .orElseThrow(() -> new BusinessException(AlarmErrorCode.POLICY_NOT_FOUND));
        Policy p = policyService.view(policies.findById(orgId, policyId).orElseThrow());
        Map<String, Object> out = json.convertValue(p, new TypeReference<LinkedHashMap<String, Object>>() { });
        out.put("organizationId", orgId);
        return out;
    }

    /** API-RUL-41 알람: AlarmSnapshot + {@code space.pathIds}(루트부터 공간 ID) */
    @Transactional(readOnly = true)
    public JsonNode alarm(long alarmId) {
        long orgId = alarms.findOrganization(alarmId).filter(this::deployed)
                .orElseThrow(() -> new BusinessException(AlarmErrorCode.ALARM_NOT_FOUND));
        AlarmRow a = alarms.findById(orgId, alarmId).orElseThrow(() -> new BusinessException(AlarmErrorCode.ALARM_NOT_FOUND));
        AlarmSnapshot snapshot = AlarmViews.snapshot(a);
        ObjectNode node = json.valueToTree(snapshot);
        node.put("organizationId", orgId);
        node.put("virtual", a.deviceVirtual());
        if (a.spacePath() != null && node.get("space") instanceof ObjectNode space) {
            var ids = space.putArray("pathIds");
            for (String part : a.spacePath().split("/")) {
                if (!part.isBlank()) {
                    ids.add(Long.parseLong(part));
                }
            }
        }
        return node;
    }

    /** API-RUL-42 수신자 펼치기: 사용자 ID·역할 → 활성 여부·언어·시간대·수신 설정·방해 금지·연결 계정·공간 범위 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recipients(long orgId, List<Long> userIds, List<String> roles) {
        List<String> upperRoles = roles.stream().map(r -> r.strip().toUpperCase(Locale.ROOT)).toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserProfile u : prefs.listProfiles(orgId, userIds, upperRoles)) {
            PrefRow p = prefs.findPref(orgId, u.userId()).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", Long.toString(u.userId()));
            m.put("role", u.role());
            m.put("active", "ACTIVE".equals(u.status()));
            m.put("locale", p == null ? u.locale() : p.locale());
            m.put("timezone", u.timezone());
            m.put("minSeverity", p == null ? null : p.minSeverity());
            m.put("channels", p == null ? List.of("WEB", "TELEGRAM") : p.channels());
            Map<String, Object> dnd = new LinkedHashMap<>();
            dnd.put("from", p == null || p.dndFrom() == null ? null : p.dndFrom().toString());
            dnd.put("to", p == null || p.dndTo() == null ? null : p.dndTo().toString());
            dnd.put("allowCritical", p == null || p.dndAllowCritical());
            m.put("dnd", dnd);
            Map<String, Object> links = new LinkedHashMap<>();
            prefs.listLinks(orgId, u.userId()).forEach(l -> links.put(l.channel(), l.externalUserId()));
            m.put("links", links);
            AccessGrant grant = permissions.find(orgId, u.userId());
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("unrestricted", grant != null && grant.spaceScope().unrestricted());
            scope.put("allowedSpaceIds", grant == null ? List.of() : grant.spaceScope().allowedSpaceIds().stream().sorted().map(String::valueOf).toList());
            m.put("spaceScope", scope);
            m.put("alarmRead", grant != null && grant.has(net.java21.data2flow.contracts.authz.Permission.ALARM_READ));
            out.add(m);
        }
        return out;
    }

    /** API-RUL-43 무음(끝나지 않은 것). 반복은 {@code {daysOfWeek, from, to, timezone}} 또는 {@code {dateFrom, dateTo, timezone}} */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> silences(long orgId, boolean activeOnly) {
        String tz = policies.findTimezone(orgId);
        Instant now = clock.instant();
        List<Map<String, Object>> out = new ArrayList<>();
        for (SilenceRow s : silences.listCurrent(orgId, now)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("silenceId", Long.toString(s.id()));
            m.put("kind", s.kind());
            m.put("target", Map.of("type", s.targetType(), "id", Long.toString(s.targetId())));
            if (s.targetSpacePath() != null) {
                m.put("targetSpacePath", s.targetSpacePath());
            }
            m.put("startsAt", s.startsAt());
            m.put("endsAt", s.endsAt());
            if (s.recurrence() != null) {
                JsonNode r = json.readTree(s.recurrence());
                Map<String, Object> rec = new LinkedHashMap<>();
                if (r.has("days")) {
                    List<Integer> days = new ArrayList<>();
                    r.path("days").values().forEach(d -> days.add(d.asInt()));
                    rec.put("daysOfWeek", days);
                }
                for (String f : List.of("from", "to", "dateFrom", "dateTo")) {
                    if (r.hasNonNull(f)) {
                        rec.put(f, r.get(f).asString());
                    }
                }
                rec.put("timezone", tz);
                m.put("recurrence", rec);
            }
            out.add(m);
        }
        return out;
    }

    /** API-RUL-44 당직표 */
    @Transactional(readOnly = true)
    public Map<String, Object> onCall(long orgId) {
        Map<String, Object> out = new LinkedHashMap<>();
        var schedule = onCall.findSchedule(orgId);
        out.put("timezone", schedule.map(OnCallRepository.ScheduleRow::timezone).orElse(policies.findTimezone(orgId)));
        List<Map<String, Object>> shifts = new ArrayList<>();
        List<Map<String, Object>> overrides = new ArrayList<>();
        schedule.ifPresent(s -> {
            onCall.listShifts(orgId, s.id()).forEach(r -> shifts.add(Map.of("dayOfWeek", r.dayOfWeek(), "from", r.from().toString(),
                    "to", r.to().toString(), "userId", Long.toString(r.userId()))));
            onCall.listOverrides(orgId, s.id(), clock.instant()).forEach(o -> overrides.add(Map.of("startsAt", o.startsAt(),
                    "endsAt", o.endsAt(), "originalUserId", Long.toString(o.originalUserId()), "substituteUserId",
                    Long.toString(o.substituteUserId()))));
        });
        out.put("shifts", shifts);
        out.put("overrides", overrides);
        return out;
    }

    /** API-RUL-45 템플릿 고르기: 조직 행 → 기본 행, 언어는 요청 → (ja·zh) en → ko(ADR-037). 없으면 404 */
    @Transactional(readOnly = true)
    public Map<String, Object> template(long orgId, String key, String channel, String locale) {
        String ch = channel == null ? "WEB" : channel.strip().toUpperCase(Locale.ROOT);
        List<String> chain = new ArrayList<>();
        if (locale != null && !locale.isBlank()) {
            chain.add(locale.strip().toLowerCase(Locale.ROOT));
        }
        chain.add("en");
        chain.add("ko");
        for (String lc : chain) {
            var row = templates.findOrgRow(orgId, key, ch, lc).or(() -> templates.findBuiltin(key, ch, lc, orgId));
            if (row.isPresent()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("subject", row.get().subject());
                out.put("body", row.get().body());
                out.put("locale", lc);
                return out;
            }
        }
        throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    /** API-RUL-46 메신저 연결 확인 */
    @Transactional(readOnly = true)
    public Map<String, Object> messengerLink(String channel, String externalUserId) {
        var link = prefs.findLinkByExternalAnyOrganization(channel.strip().toUpperCase(Locale.ROOT), externalUserId.strip())
                .filter(l -> deployed(l.organizationId()))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return Map.of("userId", Long.toString(link.userId()), "organizationId", link.organizationId());
    }

    /** API-OPS-35 채널(비밀값 복호화). type으로 거를 수 있다 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> channels(long orgId, String type) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var c : channels.list(orgId)) {
            if (type == null || type.isBlank() || c.type().equalsIgnoreCase(type.strip())) {
                out.add(channel(c));
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> channel(long orgId, long id) {
        return channel(channels.findById(orgId, id).orElseThrow(() -> new BusinessException(AlarmErrorCode.CHANNEL_NOT_FOUND)));
    }

    Map<String, Object> channel(ChannelRepository.ChannelRow c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channelId", Long.toString(c.id()));
        m.put("organizationId", c.organizationId());
        m.put("name", c.name());
        m.put("type", c.type());
        m.put("config", json.readTree(c.config()));
        m.put("secrets", channelService.secret(c));
        m.put("rateLimitPerMin", c.rateLimitPerMin());
        m.put("digestWindowSec", c.digestWindowSec());
        m.put("enabled", c.enabled());
        m.put("version", c.version());
        return m;
    }

    /** API-RUL-50 타임라인 한 줄 {type: ESCALATED|NOTIFIED, at?, data} */
    @Transactional
    public void timeline(long alarmId, JsonNode body) {
        long orgId = alarms.findOrganization(alarmId).filter(this::deployed)
                .orElseThrow(() -> new BusinessException(AlarmErrorCode.ALARM_NOT_FOUND));
        String type = body == null ? "" : body.path("type").asString("").toUpperCase(Locale.ROOT);
        if (!TIMELINE_TYPES.contains(type)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("type", "Pattern", null)));
        }
        Instant at = clock.instant();
        if (body.hasNonNull("at")) {
            try {
                at = Instant.parse(body.get("at").asString(""));
            } catch (RuntimeException ex) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("at", "Pattern", null)));
            }
        }
        Map<String, Object> data = body.has("data") ? json.convertValue(body.get("data"), new TypeReference<Map<String, Object>>() { }) : null;
        alarmService.timeline(orgId, alarmId, type, "SYSTEM", null, data, at);
    }

    boolean deployed(long orgId) {
        try {
            organizations.resolve(orgId);
            return true;
        } catch (BusinessException ex) {
            return false;
        }
    }

    static ZoneId zone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (RuntimeException ex) {
            return ZoneId.of("Asia/Seoul");
        }
    }
}
