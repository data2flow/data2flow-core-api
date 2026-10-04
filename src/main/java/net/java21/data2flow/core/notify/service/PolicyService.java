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
import net.java21.data2flow.core.notify.domain.TimeWindow;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Policy;
import net.java21.data2flow.core.notify.dto.NotifyDtos.PolicySummary;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Recipient;
import net.java21.data2flow.core.notify.dto.NotifyDtos.Step;
import net.java21.data2flow.core.notify.repository.PolicyRepository;
import net.java21.data2flow.core.notify.repository.PolicyRepository.PolicyRow;
import net.java21.data2flow.core.notify.repository.PolicyRepository.PolicyValues;
import net.java21.data2flow.core.notify.repository.TemplateRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 알림 정책(RUL-03.02·03.03, API-RUL-21, BR-RUL-12·13·16). 조회 RULE_READ, 쓰기 NOTIFY_POLICY_WRITE.
 * <ul>
 *   <li>최소 심각도·공간(하위 포함)·규칙·요일·시간대 조건, 수신자 {@code [{type: USER|ROLE|ON_CALL, id}]} 1명 이상</li>
 *   <li>채널: WEB과 켜진 알림 채널의 유형만(아니면 409 CHANNEL_NOT_CONFIGURED)</li>
 *   <li>재알림 10~1440분(기본 30), 묶기 창 0 또는 60~600초, 해제 알림(기본 켬), 에스컬레이션 단계 1~3(단계 번호 1부터 이어서)</li>
 *   <li>쓰는 규칙이 있으면 삭제 409 POLICY_IN_USE. 저장·삭제는 설정 변경 NOTIFICATION_POLICY(action 캐시)</li>
 * </ul>
 */
@Service
public class PolicyService {

    static final Set<String> SEVERITIES = Set.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO");
    static final Set<String> RECIPIENT_TYPES = Set.of("USER", "ROLE", "ON_CALL");
    static final Set<String> ROLES = Set.of("ADMIN", "INTEGRATOR", "OPERATOR", "ANALYST", "VIEWER");

    private final PolicyRepository policies;
    private final TemplateRepository templates;
    private final ChannelService channels;
    private final CoreEventPublisher publisher;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public PolicyService(PolicyRepository policies, TemplateRepository templates, ChannelService channels, CoreEventPublisher publisher,
                         RoleChecker roleChecker, Audits audits, JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.policies = policies;
        this.templates = templates;
        this.channels = channels;
        this.publisher = publisher;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ListApiResponse<PolicySummary> list(Integer page, Integer size) {
        roleChecker.require(Permission.RULE_READ);
        long orgId = roleChecker.currentUser().organizationId();
        PageParams params = PageParams.of(page, size);
        List<PolicySummary> items = policies.list(orgId, params.size(), params.offset()).stream().map(p -> {
            Policy v = view(p);
            return new PolicySummary(v.notificationPolicyId(), v.name(), v.minSeverity(), v.spaceId(), v.channels(), v.recipients().size(),
                    v.steps().size(), v.updatedAt());
        }).toList();
        return ListApiResponse.of(params, items, policies.count(orgId));
    }

    @Transactional(readOnly = true)
    public Policy get(long id) {
        roleChecker.require(Permission.RULE_READ);
        return view(require(roleChecker.currentUser().organizationId(), id));
    }

    @Transactional
    public Policy create(JsonNode body) {
        roleChecker.require(Permission.NOTIFY_POLICY_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        Parsed in = parse(orgId, body, false);
        if (policies.existsName(orgId, in.values().name(), null)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "Duplicated", null)));
        }
        long id;
        try {
            id = policies.insert(in.values(), user.userId(), clock.instant());
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "Duplicated", null)));
        }
        policies.replaceSteps(orgId, id, in.steps());
        publisher.configChanged(ConfigChangedMessage.EntityType.NOTIFICATION_POLICY, id, 0, orgId);
        audits.record(audits.event(orgId, "NOTIFICATION_POLICY_CREATED").actor(user).target("NOTIFICATION_POLICY", Long.toString(id)));
        return view(policies.findById(orgId, id).orElseThrow());
    }

    @Transactional
    public Policy update(long id, JsonNode body) {
        roleChecker.require(Permission.NOTIFY_POLICY_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        PolicyRow current = require(orgId, id);
        Parsed in = parse(orgId, body, true);
        if (in.baseVersion() != current.version()) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        if (policies.existsName(orgId, in.values().name(), id)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "Duplicated", null)));
        }
        if (policies.update(id, in.baseVersion(), in.values(), user.userId(), clock.instant()) == 0) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        policies.replaceSteps(orgId, id, in.steps());
        publisher.configChanged(ConfigChangedMessage.EntityType.NOTIFICATION_POLICY, id, current.version() + 1L, orgId);
        audits.record(audits.event(orgId, "NOTIFICATION_POLICY_UPDATED").actor(user).target("NOTIFICATION_POLICY", Long.toString(id)));
        return view(policies.findById(orgId, id).orElseThrow());
    }

    @Transactional
    public void delete(long id) {
        roleChecker.require(Permission.NOTIFY_POLICY_WRITE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        PolicyRow current = require(orgId, id);
        if (policies.countRulesUsing(orgId, id) > 0) {
            throw new BusinessException(AlarmErrorCode.POLICY_IN_USE);
        }
        policies.delete(orgId, id);
        publisher.configDeleted(ConfigChangedMessage.EntityType.NOTIFICATION_POLICY, id, current.version() + 1L, orgId);
        audits.record(audits.event(orgId, "NOTIFICATION_POLICY_DELETED").actor(user).target("NOTIFICATION_POLICY", Long.toString(id)));
    }

    record Parsed(PolicyValues values, List<PolicyRepository.Step> steps, int baseVersion) {
    }

    Parsed parse(long orgId, JsonNode body, boolean update) {
        if (body == null || !body.isObject()) {
            throw invalid("body", "NotNull");
        }
        String name = body.path("name").asString("").strip();
        if (name.isEmpty() || name.length() > 100) {
            throw invalid("name", "Size");
        }
        String min = body.path("minSeverity").asString("").strip().toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(min)) {
            throw invalid("minSeverity", "Pattern");
        }
        Long spaceId = null;
        if (body.hasNonNull("spaceId")) {
            spaceId = id(body.get("spaceId").asString(""), "spaceId");
            boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id)")
                    .param("org", orgId).param("id", spaceId).query(Boolean.class).single();
            if (!exists || !roleChecker.spaceScope().includes(spaceId)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
        }
        boolean children = !body.has("includeChildren") || body.get("includeChildren").asBoolean(true);
        List<Long> ruleIds = new ArrayList<>();
        for (JsonNode r : body.path("ruleIds").values()) {
            ruleIds.add(id(r.asString(""), "ruleIds"));
        }
        String window = null;
        JsonNode tw = body.get("timeWindow");
        if (tw != null && !tw.isNull() && !tw.isEmpty()) {
            try {
                Set<Integer> days = new LinkedHashSet<>();
                tw.path("days").values().forEach(d -> days.add(d.asInt()));
                new TimeWindow(days, TimeWindow.time(tw.path("from").asString(null)), TimeWindow.time(tw.path("to").asString(null)));
            } catch (IllegalArgumentException ex) {
                throw invalid("timeWindow", "Pattern");
            }
            window = json.writeValueAsString(tw);
        }
        List<Recipient> recipients = recipients(orgId, body.get("recipients"), "recipients");
        if (recipients.isEmpty()) {
            throw invalid("recipients", "NotEmpty");
        }
        List<String> chs = new ArrayList<>();
        for (JsonNode c : body.path("channels").values()) {
            String ch = c.asString("").strip().toUpperCase(Locale.ROOT);
            if (!ch.matches("[A-Z][A-Z0-9_]{1,19}")) {
                throw invalid("channels", "Pattern");
            }
            if (!chs.contains(ch)) {
                chs.add(ch);
            }
        }
        if (chs.isEmpty()) {
            throw invalid("channels", "NotEmpty");
        }
        channels.requireConfigured(orgId, chs);
        String templatesJson = null;
        JsonNode tpl = body.get("templates");
        if (tpl != null && !tpl.isNull() && !tpl.isEmpty()) {
            for (var e : tpl.properties()) {
                long templateId = id(e.getValue().asString(""), "templates." + e.getKey());
                if (templates.findVisible(orgId, templateId).isEmpty()) {
                    throw invalid("templates." + e.getKey(), "NotFound");
                }
            }
            templatesJson = json.writeValueAsString(tpl);
        }
        int renotify = body.path("renotifyMinutes").asInt(30);
        if (renotify < 10 || renotify > 1440) {
            throw invalid("renotifyMinutes", "Range");
        }
        int aggregate = body.path("aggregateWindowSec").asInt(0);
        if (aggregate != 0 && (aggregate < 60 || aggregate > 600)) {
            throw invalid("aggregateWindowSec", "Range");
        }
        boolean onClear = !body.has("notifyOnClear") || body.get("notifyOnClear").asBoolean(true);
        List<PolicyRepository.Step> steps = new ArrayList<>();
        int expected = 1;
        for (JsonNode s : body.path("steps").values()) {
            int no = s.path("stepNo").asInt(expected);
            int wait = s.path("waitMinutes").asInt(0);
            if (no != expected || no > 3) {
                throw invalid("steps", "Range");
            }
            if (wait < 1 || wait > 1440) {
                throw invalid("steps[" + (no - 1) + "].waitMinutes", "Range");
            }
            List<Recipient> stepRecipients = recipients(orgId, s.get("recipients"), "steps[" + (no - 1) + "].recipients");
            if (stepRecipients.isEmpty()) {
                throw invalid("steps[" + (no - 1) + "].recipients", "NotEmpty");
            }
            steps.add(new PolicyRepository.Step(no, wait, json.writeValueAsString(stepRecipients)));
            expected++;
        }
        int base = -1;
        if (update) {
            if (!body.hasNonNull("baseVersion")) {
                throw invalid("baseVersion", "NotNull");
            }
            base = body.get("baseVersion").asInt();
        }
        return new Parsed(new PolicyValues(orgId, name, min, spaceId, children, ruleIds.isEmpty() ? null : ruleIds, window,
                json.writeValueAsString(recipients), chs, templatesJson, renotify, aggregate, onClear), steps, base);
    }

    List<Recipient> recipients(long orgId, JsonNode list, String field) {
        List<Recipient> out = new ArrayList<>();
        if (list == null || !list.isArray()) {
            return out;
        }
        for (JsonNode r : list.values()) {
            String type = r.path("type").asString("").toUpperCase(Locale.ROOT);
            if (!RECIPIENT_TYPES.contains(type)) {
                throw invalid(field, "Pattern");
            }
            String id = r.hasNonNull("id") ? r.get("id").asString("").strip() : null;
            switch (type) {
                case "USER" -> {
                    long userId = id(id, field);
                    boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core.app_users WHERE organization_id = :org AND id = :id)")
                            .param("org", orgId).param("id", userId).query(Boolean.class).single();
                    if (!exists) {
                        throw invalid(field, "NotFound");
                    }
                }
                case "ROLE" -> {
                    if (id == null || !ROLES.contains(id.toUpperCase(Locale.ROOT))) {
                        throw invalid(field, "Pattern");
                    }
                    id = id.toUpperCase(Locale.ROOT);
                }
                default -> id = null;
            }
            Recipient recipient = new Recipient(type, id);
            if (!out.contains(recipient)) {
                out.add(recipient);
            }
        }
        return out;
    }

    PolicyRow require(long orgId, long id) {
        return policies.findById(orgId, id).orElseThrow(() -> new BusinessException(AlarmErrorCode.POLICY_NOT_FOUND));
    }

    public Policy view(PolicyRow p) {
        List<Recipient> recipients = json.readValue(p.recipients(), new TypeReference<List<Recipient>>() { });
        List<Step> steps = new ArrayList<>();
        for (JsonNode s : json.readTree(p.steps()).values()) {
            steps.add(new Step(s.path("stepNo").asInt(), s.path("waitMinutes").asInt(),
                    json.convertValue(s.path("recipients"), new TypeReference<List<Recipient>>() { })));
        }
        return new Policy(Long.toString(p.id()), p.name(), p.minSeverity(), p.spaceId() == null ? null : Long.toString(p.spaceId()),
                p.includeChildren(), p.ruleIds().stream().map(String::valueOf).toList(), p.timeWindow() == null ? null : json.readTree(p.timeWindow()),
                recipients, p.channels(), p.templates() == null ? null : json.readTree(p.templates()), p.renotifyMinutes(),
                p.aggregateWindowSec(), p.notifyOnClear(), steps, p.version(), p.createdAt(), p.updatedAt());
    }

    static long id(String raw, String field) {
        try {
            long v = Long.parseLong(raw == null ? "" : raw.strip());
            if (v < 1) {
                throw invalid(field, "Pattern");
            }
            return v;
        } catch (NumberFormatException ex) {
            throw invalid(field, "Pattern");
        }
    }

    static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
