package net.java21.data2flow.core.alarm.event;

import net.java21.data2flow.contracts.alarm.AlarmClearReason;
import net.java21.data2flow.contracts.alarm.AlarmKeys;
import net.java21.data2flow.contracts.alarm.AlarmSeverity;
import net.java21.data2flow.contracts.alarm.AlarmSourceType;
import net.java21.data2flow.contracts.audit.AuditActorType;
import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.AlarmSignal;
import net.java21.data2flow.contracts.message.event.CommandNoEffect;
import net.java21.data2flow.contracts.message.event.DeviceConnectivityChanged;
import net.java21.data2flow.contracts.message.event.DriverCircuitChanged;
import net.java21.data2flow.contracts.message.event.GatewayConnectivityChanged;
import net.java21.data2flow.contracts.message.event.NotificationDeliveryResult;
import net.java21.data2flow.contracts.message.event.OscillationBlocked;
import net.java21.data2flow.core.alarm.repository.AlarmEventRepository;
import net.java21.data2flow.core.alarm.repository.AlarmRepository;
import net.java21.data2flow.core.alarm.repository.AlarmRepository.DeviceInfo;
import net.java21.data2flow.core.alarm.service.AlarmService;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.messaging.service.CoreEventHandler;
import net.java21.data2flow.core.notify.domain.TemplateVariables;
import net.java21.data2flow.core.rule.repository.RuleRepository;
import net.java21.data2flow.core.rule.repository.RuleRepository.RuleRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 알람 입력({@code core.events}, 한 트랜잭션·messageId 멱등):
 * <ul>
 *   <li>EVT-RUL-01 {@code alarm.signal}: RAISE → 발생·재발생, CLEAR → 해제(규칙의 auto_clear가 꺼져 있으면 해제하지 않음). 규칙 알람의 제목은
 *       규칙 제목 템플릿을 core가 채운다({@code {{device.name}}} 등). 비활성·삭제·변환 전 규칙의 늦은 RAISE는 버린다</li>
 *   <li>EVT-DEV-08 게이트웨이 오프라인 → MAJOR 시스템 알람 {@code system:GATEWAY_OFFLINE:{id}}, 하위 기기 연결 알람을 묶음(RUL-04.01·DEV-05.03).
 *       온라인 → 해제(하위도 함께)</li>
 *   <li>EVT-ACT-08 진동 차단 → WARNING {@code system:OSCILLATION:{deviceId}:{capability}}(ADR-043 ③)</li>
 *   <li>EVT-ACT-05 드라이버 서킷 열림·닫힘 → MAJOR {@code system:DRIVER_CIRCUIT_OPEN:{driverId}} 발생·해제</li>
 *   <li>EVT-RUL-04 알림 발송 결과 → 알람 타임라인 NOTIFIED(같은 발송·상태는 한 번)</li>
 *   <li>EVT-ACT-04 효과 없음 → 기기 이력(감사 {@code COMMAND_NO_EFFECT}, DEV-02.07)</li>
 * </ul>
 */
@Component
public class AlarmEventHandler implements CoreEventHandler {

    private static final Logger log = LoggerFactory.getLogger(AlarmEventHandler.class);
    static final Set<String> RULE_LIVE = Set.of("ACTIVE", "ERROR");

    private final AlarmService alarms;
    private final AlarmRepository alarmRepository;
    private final AlarmEventRepository events;
    private final RuleRepository rules;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public AlarmEventHandler(AlarmService alarms, AlarmRepository alarmRepository, AlarmEventRepository events, RuleRepository rules,
                             Audits audits, JsonMapper json, Clock clock) {
        this.alarms = alarms;
        this.alarmRepository = alarmRepository;
        this.events = events;
        this.rules = rules;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Set<EventType> types() {
        return Set.of(EventType.ALARM_SIGNAL, EventType.GATEWAY_CONNECTIVITY_CHANGED, EventType.CONTROL_OSCILLATION_BLOCKED,
                EventType.DRIVER_CIRCUIT_OPENED, EventType.DRIVER_CIRCUIT_CLOSED, EventType.NOTIFICATION_DELIVERED,
                EventType.NOTIFICATION_FAILED, EventType.COMMAND_NO_EFFECT);
    }

    @Override
    public void handle(DomainEvent<?> event) {
        long org = event.organizationId();
        Instant at = event.occurredAt() == null ? clock.instant() : event.occurredAt();
        switch (event.payload()) {
            case AlarmSignal s -> signal(org, s);
            case GatewayConnectivityChanged g -> gateway(org, g, at);
            case OscillationBlocked o -> alarms.raise(new AlarmService.Raise(org,
                    AlarmKeys.system(AlarmService.OSCILLATION, Long.toString(o.deviceId()), o.capability()), AlarmSourceType.SYSTEM, null,
                    flowId(o.source() == null ? null : o.source().flowId()), null, AlarmSeverity.WARNING,
                    "제어 진동 차단: " + o.capability() + " (" + o.flips() + "회/" + o.windowSec() + "초)", o.deviceId(), o.spaceId(), null,
                    (double) o.flips(), null, null, o.at() == null ? at : o.at(), "SYSTEM", false, false));
            case DriverCircuitChanged d -> driver(org, d, event.eventType(), at);
            case NotificationDeliveryResult n -> delivery(org, n, at);
            case CommandNoEffect c -> noEffect(org, c, at);
            default -> log.trace("처리하지 않는 이벤트 {}", event.type());
        }
    }

    private void signal(long org, AlarmSignal s) {
        RuleRow rule = null;
        if (s.ruleId() != null) {
            rule = rules.findById(org, s.ruleId(), s.measuredAt()).orElse(null);
            if (rule == null) {
                log.debug("없는 규칙 {}의 알람 신호는 버립니다", s.ruleId());
                return;
            }
        }
        UUID flowId = flowId(s.flowId());
        if (s.signal() == AlarmSignal.Signal.CLEAR) {
            if (rule != null && !rule.autoClear()) {
                return;
            }
            alarms.clearByKey(org, s.alarmKey(), s.value(), s.measuredAt(), AlarmClearReason.AUTO, actorType(s), null);
            return;
        }
        if (s.signal() != AlarmSignal.Signal.RAISE) {
            return;
        }
        if (rule != null && !RULE_LIVE.contains(rule.status()) && !"CONVERTED".equals(rule.status())) {
            log.debug("비활성 규칙 {}의 늦은 발생 신호는 버립니다", s.ruleId());
            return;
        }
        Double raise = s.threshold() == null ? null : s.threshold().raise();
        Double clear = s.threshold() == null ? null : s.threshold().clear();
        boolean lower = raise != null && clear != null && clear > raise;
        String title = s.title();
        AlarmSeverity severity = s.severity();
        if (rule != null) {
            title = render(org, rule.titleTemplate(), s);
            severity = AlarmSeverity.valueOf(rule.severity());
            lower = lower || rule.condition().contains("\"op\":\"<");
        }
        alarms.raise(new AlarmService.Raise(org, s.alarmKey(), s.sourceType() == AlarmSourceType.UNKNOWN ? AlarmKeys.sourceOf(s.alarmKey())
                : s.sourceType(), s.ruleId(), flowId, s.nodeId(), severity, title, s.deviceId(), s.spaceId(), s.metric(), s.value(), raise, clear,
                s.measuredAt(), actorType(s), s.metric() == null, lower));
    }

    /** 규칙 제목 템플릿 채우기({{device.name}}·{{space.name}}·{{value}}·{{metric}}·{{threshold}}) */
    String render(long org, String template, AlarmSignal s) {
        if (template == null || !template.contains("{{")) {
            return template;
        }
        Map<String, Object> values = new HashMap<>();
        DeviceInfo device = s.deviceId() == null ? null : alarmRepository.findDeviceInfo(org, s.deviceId()).orElse(null);
        values.put("device.name", device == null ? null : device.name());
        Long spaceId = s.spaceId() != null ? s.spaceId() : device == null ? null : device.spaceId();
        values.put("space.name", spaceId == null ? null : alarmRepository.findSpaceName(org, spaceId).orElse(null));
        values.put("value", s.value());
        values.put("metric", s.metric());
        values.put("threshold", s.threshold() == null ? null : s.threshold().raise());
        return TemplateVariables.render(template, values).strip();
    }

    private void gateway(long org, GatewayConnectivityChanged g, Instant at) {
        String key = AlarmKeys.system(AlarmService.GATEWAY_OFFLINE, Long.toString(g.gatewayId()));
        if (g.to() == DeviceConnectivityChanged.Connectivity.OFFLINE) {
            AlarmRepository.GatewayRef ref = alarmRepository.findGateway(org, g.gatewayId()).orElse(null);
            String name = ref == null || ref.name() == null ? g.gatewayEui() : ref.name();
            alarms.raise(new AlarmService.Raise(org, key, AlarmSourceType.SYSTEM, null, null, null, AlarmSeverity.MAJOR,
                    "게이트웨이 오프라인: " + name, null, ref == null ? null : ref.spaceId(), null, null, null, null, at, "SYSTEM", false, false));
        } else if (g.to() == DeviceConnectivityChanged.Connectivity.ONLINE) {
            alarms.clearByKey(org, key, null, at, AlarmClearReason.AUTO, "SYSTEM", null);
        }
    }

    private void driver(long org, DriverCircuitChanged d, EventType type, Instant at) {
        String key = AlarmKeys.system(AlarmService.DRIVER_CIRCUIT_OPEN, Long.toString(d.driverId()));
        if (type == EventType.DRIVER_CIRCUIT_OPENED) {
            alarms.raise(new AlarmService.Raise(org, key, AlarmSourceType.SYSTEM, null, null, null, AlarmSeverity.MAJOR,
                    "드라이버 장애(서킷 열림): " + d.type() + " #" + d.driverId(), null, null, null, d.failureRate(), null, null,
                    d.at() == null ? at : d.at(), "SYSTEM", false, false));
        } else {
            alarms.clearByKey(org, key, d.failureRate(), d.at() == null ? at : d.at(), AlarmClearReason.AUTO, "SYSTEM", null);
        }
    }

    private void delivery(long org, NotificationDeliveryResult n, Instant at) {
        if (n.alarmId() == null || alarmRepository.findById(org, n.alarmId()).isEmpty()
                || events.existsNotified(org, n.alarmId(), n.deliveryId().toString(), n.status().name())) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deliveryId", n.deliveryId().toString());
        data.put("channel", n.channel());
        data.put("recipient", n.recipient());
        data.put("status", n.status().name());
        data.put("attempts", n.attempts());
        if (n.error() != null) {
            data.put("error", n.error());
        }
        events.insert(org, n.alarmId(), n.at() == null ? at : n.at(), "NOTIFIED", "SYSTEM", null, json.writeValueAsString(data));
    }

    private void noEffect(long org, CommandNoEffect c, Instant at) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("commandId", c.commandId().toString());
        detail.put("capability", c.capability());
        detail.put("metric", c.expected().metric());
        detail.put("expected", c.expected().direction() == null ? null : c.expected().direction().name());
        detail.put("withinMinutes", c.expected().withinMinutes());
        if (c.observed() != null) {
            detail.put("observedDelta", c.observed().delta());
        }
        audits.record(audits.event(org, "COMMAND_NO_EFFECT").occurredAt(c.at() == null ? at : c.at())
                .actor(AuditActorType.SYSTEM, "data2flow-action", null).target("DEVICE", Long.toString(c.deviceId())).detail(detail));
    }

    static String actorType(AlarmSignal s) {
        return s.flowId() == null ? "SYSTEM" : "FLOW";
    }

    static UUID flowId(String raw) {
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
