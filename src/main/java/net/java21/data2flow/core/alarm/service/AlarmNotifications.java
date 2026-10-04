package net.java21.data2flow.core.alarm.service;

import net.java21.data2flow.contracts.alarm.AlarmSeverity;
import net.java21.data2flow.contracts.command.ActionIdempotencyKeys;
import net.java21.data2flow.contracts.command.CommandSource;
import net.java21.data2flow.contracts.message.ActionRequest;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.contracts.notification.NotificationEvents;
import net.java21.data2flow.contracts.notification.NotificationRecipient;
import net.java21.data2flow.contracts.notification.NotificationRequest;
import net.java21.data2flow.core.alarm.repository.AlarmRepository;
import net.java21.data2flow.core.alarm.repository.AlarmRepository.AlarmRow;
import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.notify.domain.PolicyMatcher;
import net.java21.data2flow.core.notify.domain.TimeWindow;
import net.java21.data2flow.core.notify.repository.PolicyRepository;
import net.java21.data2flow.core.notify.repository.PolicyRepository.PolicyRow;
import net.java21.data2flow.core.notify.repository.TemplateRepository;
import net.java21.data2flow.core.outbox.service.OutboxWriter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 알람 알림 요청(EVT-RUL-03, RUL-03.02·05.04, BR-RUL-12~14). 알람 상태가 바뀌면 정책을 평가해 {@code NotificationRequest}를
 * {@code data2flow.actions}(라우팅 키 {@code notify}) 아웃박스에 쓴다. 발송·재시도·묶기·에스컬레이션·당직·역할 수신자 펼치기·사용자별
 * 수신 설정(방해 금지·최소 심각도·채널)은 data2flow-action notification 패키지가 한다(ADR-033).
 *
 * <ul>
 *   <li>SUPPRESSED 알람은 요청하지 않는다(BR-RUL-08)</li>
 *   <li>플래핑(BR-RUL-10)·무음(BR-RUL-14)·방해 금지 판정과 SKIPPED 기록은 action이 API-RUL-41·43·42로 한다(ADR-049)</li>
 *   <li>맞는 정책이 여럿이면 수신자를 합치고 중복을 없앤다(BR-RUL-12). {@code policyId}는 가장 먼저 만든 정책(에스컬레이션 단계 기준)이고
 *       {@code variables.policyIds}에 모두 싣는다</li>
 *   <li>재발생 알림은 맞는 정책 중 가장 짧은 재알림 간격이 지나야 보낸다(BR-RUL-13). 해제 알림은 notify_on_clear 정책이 있고 전에 알렸을 때 1회</li>
 * </ul>
 */
@Component
public class AlarmNotifications {

    private final AlarmRepository alarms;
    private final PolicyRepository policies;
    private final TemplateRepository templates;
    private final OutboxWriter outbox;
    private final MessageCodec codec;
    private final JsonMapper json;
    private final Clock clock;
    private final String webBaseUrl;

    public AlarmNotifications(AlarmRepository alarms, PolicyRepository policies, TemplateRepository templates,
                              OutboxWriter outbox, MessageCodec codec, JsonMapper json, Clock clock,
                              CoreProperties properties) {
        this.alarms = alarms;
        this.policies = policies;
        this.templates = templates;
        this.outbox = outbox;
        this.codec = codec;
        this.json = json;
        this.clock = clock;
        this.webBaseUrl = properties.webBaseUrl();
    }

    public void onRaised(AlarmRow a) {
        if (!"ACTIVE".equals(a.status())) {
            return;
        }
        List<PolicyRow> matched = matched(a);
        request(a, NotificationEvents.ALARM_RAISED, matched);
    }

    public void onReraised(AlarmRow a) {
        if ("SUPPRESSED".equals(a.status())) {
            return;
        }
        List<PolicyRow> matched = matched(a);
        if (matched.isEmpty()) {
            return;
        }
        int renotify = matched.stream().mapToInt(PolicyRow::renotifyMinutes).min().orElse(30);
        Instant now = clock.instant();
        if (a.lastNotifiedAt() != null && now.isBefore(a.lastNotifiedAt().plus(Duration.ofMinutes(renotify)))) {
            return;
        }
        request(a, NotificationEvents.ALARM_RERAISED, matched);
    }

    /**
     * @param before 해제 전 행(억제 중이었으면 알리지 않음)
     */
    public void onCleared(AlarmRow a, AlarmRow before) {
        if ("SUPPRESSED".equals(before.status()) || before.lastNotifiedAt() == null) {
            return;
        }
        List<PolicyRow> matched = matched(a).stream().filter(PolicyRow::notifyOnClear).toList();
        request(a, NotificationEvents.ALARM_CLEARED, matched);
    }

    /** 이 알람에 맞는 정책(만든 순서) */
    public List<PolicyRow> matched(AlarmRow a) {
        ZoneId zone = zone(a.organizationId());
        var local = clock.instant().atZone(zone).toLocalDateTime();
        AlarmSeverity severity = AlarmSeverity.valueOf(a.severity());
        List<PolicyRow> result = new ArrayList<>();
        for (PolicyRow p : policies.listAll(a.organizationId())) {
            TimeWindow window = window(p.timeWindow());
            if (PolicyMatcher.matches(AlarmSeverity.valueOf(p.minSeverity()), p.spacePath(), p.includeChildren(), p.ruleIds(), window,
                    severity, a.spacePath(), a.ruleId(), local)) {
                result.add(p);
            }
        }
        return result;
    }

    private void request(AlarmRow a, String event, List<PolicyRow> matched) {
        if (matched.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        Set<String> seen = new LinkedHashSet<>();
        List<NotificationRecipient> recipients = new ArrayList<>();
        Map<String, String> templateKeys = new LinkedHashMap<>();
        int aggregate = 0;
        List<String> policyIds = new ArrayList<>();
        for (PolicyRow p : matched) {
            policyIds.add(Long.toString(p.id()));
            aggregate = Math.max(aggregate, p.aggregateWindowSec());
            JsonNode list = json.readTree(p.recipients());
            for (String channel : p.channels()) {
                for (JsonNode r : list.values()) {
                    NotificationRecipient.Type type = NotificationRecipient.Type.valueOf(r.path("type").asString("USER"));
                    String id = r.hasNonNull("id") ? r.get("id").asString() : null;
                    NotificationRecipient recipient = new NotificationRecipient(type, id, channel, null);
                    if (seen.add(recipient.recipientKey() + "|" + channel)) {
                        recipients.add(recipient);
                    }
                }
            }
            if (p.templates() != null) {
                for (Map.Entry<String, JsonNode> e : json.readTree(p.templates()).properties()) {
                    if (templateKeys.containsKey(e.getKey()) || !e.getValue().canConvertToLong()) {
                        continue;
                    }
                    templates.findVisible(a.organizationId(), e.getValue().asLong())
                            .ifPresent(t -> templateKeys.put(e.getKey(), t.templateKey()));
                }
            }
        }
        templateKeys.putIfAbsent(NotificationRequest.DEFAULT_TEMPLATE, NotificationEvents.defaultTemplateKey(event));
        int seq = alarms.updateNotified(a.organizationId(), a.id(), now);
        String link = webBaseUrl + "/alarms/" + a.id();
        Map<String, Object> variables = variables(a, link);
        variables.put("policyIds", policyIds);
        NotificationRequest body = new NotificationRequest(a.id(), event, (long) seq, AlarmSeverity.valueOf(a.severity()),
                matched.getFirst().id(), recipients, templateKeys, variables, aggregate, a.alarmKey(), null, link,
                a.deviceVirtual() ? Boolean.TRUE : null);
        String key = ActionIdempotencyKeys.notifyRequest(a.id(), event, seq, null);
        ActionRequest req = ActionRequest.notify(a.organizationId(), key, CommandSource.system(), null, body, clock);
        outbox.message(a.organizationId(), "NOTIFY", MessagingNames.EXCHANGE_ACTIONS, req.routingKey(), req.messageId().toString(),
                codec.writeAsString(req));
    }

    /** 템플릿 변수(TemplateVariables.KNOWN) */
    public static Map<String, Object> variables(AlarmRow a, String link) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("alarm.id", Long.toString(a.id()));
        v.put("alarm.severity", a.severity());
        v.put("alarm.title", a.title());
        v.put("alarm.status", a.status());
        v.put("device.name", a.deviceName());
        v.put("space.path", a.spaceNames());
        v.put("space.name", a.spaceName());
        v.put("value", a.lastValue());
        v.put("threshold", thresholdValue(a.threshold()));
        v.put("metric", a.metricKey());
        v.put("rule.name", a.ruleName());
        v.put("link", link);
        v.put("occurredAt", a.lastRaisedAt() == null ? null : a.lastRaisedAt().toString());
        v.put("occurrenceCount", a.occurrenceCount());
        v.values().removeIf(java.util.Objects::isNull);
        return v;
    }

    private static final JsonMapper PLAIN = JsonMapper.builder().build();

    static Double thresholdValue(String thresholdJson) {
        if (thresholdJson == null) {
            return null;
        }
        JsonNode t = PLAIN.readTree(thresholdJson);
        return t.hasNonNull("raise") ? t.get("raise").asDouble() : null;
    }

    TimeWindow window(String raw) {
        if (raw == null) {
            return null;
        }
        JsonNode w = json.readTree(raw);
        if (w == null || w.isNull() || w.isEmpty()) {
            return null;
        }
        Set<Integer> days = new LinkedHashSet<>();
        for (JsonNode d : w.path("days").values()) {
            days.add(d.asInt());
        }
        return new TimeWindow(days, TimeWindow.time(w.path("from").asString(null)), TimeWindow.time(w.path("to").asString(null)));
    }

    ZoneId zone(long organizationId) {
        try {
            return ZoneId.of(policies.findTimezone(organizationId));
        } catch (RuntimeException ex) {
            return ZoneId.of("Asia/Seoul");
        }
    }
}
