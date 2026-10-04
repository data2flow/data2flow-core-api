package net.java21.data2flow.core.alarm.service;

import net.java21.data2flow.contracts.alarm.AlarmClearReason;
import net.java21.data2flow.contracts.alarm.AlarmSeverity;
import net.java21.data2flow.contracts.alarm.AlarmSourceType;
import net.java21.data2flow.contracts.alarm.AlarmStatus;
import net.java21.data2flow.contracts.alarm.SuppressedReason;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.AlarmStateChanged;
import net.java21.data2flow.core.alarm.domain.AlarmStateMachine;
import net.java21.data2flow.core.alarm.domain.AlarmSuppressionPolicy;
import net.java21.data2flow.core.alarm.domain.FlappingDetector;
import net.java21.data2flow.core.alarm.domain.SpaceEventGrouper;
import net.java21.data2flow.core.alarm.repository.AlarmEventRepository;
import net.java21.data2flow.core.alarm.repository.AlarmRepository;
import net.java21.data2flow.core.alarm.repository.AlarmRepository.AlarmRow;
import net.java21.data2flow.core.alarm.repository.AlarmRepository.DeviceInfo;
import net.java21.data2flow.core.alarm.repository.AlarmRepository.NewAlarm;
import net.java21.data2flow.core.alarm.repository.SpaceEventRepository;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 알람 상태 관리(RUL-02·04, BR-RUL-02·07~11). 발생·해제 판정은 flow-engine {@code action.alarm} 노드(EVT-RUL-01)와 core 시스템 판정
 * (게이트웨이 오프라인·진동 차단·드라이버 서킷)이 내고, 이 서비스가 열린 알람 하나(alarm_key)를 만들거나 갱신한다.
 *
 * <ul>
 *   <li>발생: 열린 알람이 없으면 새 행(유지보수·상위 원인·기기 오프라인이면 SUPPRESSED, BR-RUL-08), 있으면 재발생(횟수·최고값·마지막 값만)</li>
 *   <li>해제: 열린 알람을 CLEARED(AUTO·MANUAL·PARENT_CLEARED·RULE_DELETED·RULE_SCOPE_CHANGED). 하위 알람도 함께(BR-RUL-09)</li>
 *   <li>플래핑: 30분 안에 발생 6회 이상이면 FLAPPING_ON(BR-RUL-10). 끄기는 {@link AlarmJobs}</li>
 *   <li>공간 이벤트: 같은 공간 5분 안 다른 출처 알람 2건 이상(BR-RUL-11)</li>
 *   <li>상태가 바뀔 때마다 타임라인(alarm_events)과 EVT-RUL-02({@code alarm.*}, 아웃박스)를 남기고 알림({@link AlarmNotifications})을 요청한다</li>
 * </ul>
 * 같은 트랜잭션에서 호출해야 한다(이벤트 소비자·API 서비스가 트랜잭션을 연다).
 */
@Service
public class AlarmService {

    public static final String GATEWAY_OFFLINE = "GATEWAY_OFFLINE";
    public static final String OSCILLATION = "OSCILLATION";
    public static final String DRIVER_CIRCUIT_OPEN = "DRIVER_CIRCUIT_OPEN";

    private final AlarmRepository alarms;
    private final AlarmEventRepository events;
    private final SpaceEventRepository spaceEvents;
    private final CoreEventPublisher publisher;
    private final AlarmNotifications notifications;
    private final JsonMapper json;
    private final Clock clock;

    public AlarmService(AlarmRepository alarms, AlarmEventRepository events, SpaceEventRepository spaceEvents, CoreEventPublisher publisher,
                        AlarmNotifications notifications, JsonMapper json, Clock clock) {
        this.alarms = alarms;
        this.events = events;
        this.spaceEvents = spaceEvents;
        this.publisher = publisher;
        this.notifications = notifications;
        this.json = json;
        this.clock = clock;
    }

    /**
     * 발생 요청.
     *
     * @param connectivity 연결 계열(무수신·오프라인) 알람인가: 상위 게이트웨이 알람에 하위로 묶일 수 있고, 기기 오프라인으로 억제하지 않는다
     * @param lowerIsWorse 낮을수록 나쁜 조건(&lt;, &lt;=): 최고값을 최솟값으로 본다
     * @param actorType    FLOW 또는 SYSTEM
     */
    public record Raise(long organizationId, String alarmKey, AlarmSourceType sourceType, Long ruleId, UUID flowId, String nodeId,
                        AlarmSeverity severity, String title, Long deviceId, Long spaceId, String metric, Double value, Double raiseThreshold,
                        Double clearThreshold, Instant at, String actorType, boolean connectivity, boolean lowerIsWorse) {
    }

    /** 발생. 만든(또는 갱신한) 알람 */
    public AlarmRow raise(Raise r) {
        Instant at = r.at() == null ? clock.instant() : r.at();
        Optional<AlarmRow> open = alarms.lockOpenByKey(r.organizationId(), r.alarmKey());
        if (open.isPresent()) {
            return reraise(open.get(), r, at);
        }
        DeviceInfo device = r.deviceId() == null ? null : alarms.findDeviceInfo(r.organizationId(), r.deviceId()).orElse(null);
        Long spaceId = r.spaceId() != null ? r.spaceId() : device == null ? null : device.spaceId();
        Long parent = null;
        if (r.connectivity() && device != null && device.gatewayEui() != null && device.sourceId() != null) {
            parent = alarms.findOpenGatewayAlarm(r.organizationId(), device.sourceId(), device.gatewayEui()).orElse(null);
        }
        boolean maintenance = alarms.isUnderMaintenance(r.organizationId(), r.deviceId(), spaceId);
        boolean offline = r.deviceId() != null && !r.connectivity() && alarms.isDeviceOffline(r.organizationId(), r.deviceId());
        SuppressedReason suppressed = AlarmSuppressionPolicy.decide(maintenance, parent != null, offline, r.connectivity());
        String status = suppressed == null ? AlarmStatus.ACTIVE.name() : AlarmStatus.SUPPRESSED.name();
        String threshold = r.raiseThreshold() == null && r.clearThreshold() == null ? null
                : json.writeValueAsString(thresholdMap(r.raiseThreshold(), r.clearThreshold()));
        String title = r.title() == null || r.title().isBlank() ? r.alarmKey() : truncate(r.title(), 200);
        AlarmSeverity severity = r.severity() == null || r.severity() == AlarmSeverity.UNKNOWN ? AlarmSeverity.WARNING : r.severity();
        Optional<Long> id = alarms.insertIfNoOpen(new NewAlarm(r.organizationId(), r.alarmKey(), r.sourceType().name(), r.ruleId(),
                r.flowId(), r.nodeId(), severity.name(), title, status, r.deviceId(), spaceId, r.metric(), r.value(), threshold, at,
                suppressed == SuppressedReason.PARENT ? parent : null, suppressed == null ? null : suppressed.name()));
        if (id.isEmpty()) {
            // 다른 소비자가 같은 키를 먼저 열었다(부분 UNIQUE): 그 알람의 재발생으로 처리한다(BR-RUL-02)
            AlarmRow existing = alarms.lockOpenByKey(r.organizationId(), r.alarmKey()).orElseThrow();
            return reraise(existing, r, at);
        }
        long alarmId = id.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("value", r.value());
        if (suppressed != null) {
            data.put("suppressedReason", suppressed.name());
        }
        events.insert(r.organizationId(), alarmId, at, suppressed == null ? "RAISED" : "SUPPRESSED", r.actorType(), null, write(data));
        if (spaceId != null) {
            groupSpaceEvent(r.organizationId(), alarmId, spaceId, r.alarmKey(), at);
        }
        AlarmRow row = flappingCheck(alarms.findById(r.organizationId(), alarmId).orElseThrow(), at);
        publish(suppressed == null ? EventType.ALARM_RAISED : EventType.ALARM_SUPPRESSED, row, actor(r.actorType()), at);
        if (r.alarmKey().startsWith("system:" + GATEWAY_OFFLINE + ":")) {
            adoptChildren(row, at);
            row = alarms.findById(r.organizationId(), alarmId).orElseThrow();
        }
        notifications.onRaised(row);
        return row;
    }

    private AlarmRow reraise(AlarmRow open, Raise r, Instant at) {
        alarms.updateReraised(open.organizationId(), open.id(), r.value(), r.lowerIsWorse(), at);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("value", r.value());
        data.put("occurrenceCount", open.occurrenceCount() + 1);
        events.insert(open.organizationId(), open.id(), at, "RERAISED", r.actorType(), null, write(data));
        AlarmRow row = flappingCheck(alarms.findById(open.organizationId(), open.id()).orElseThrow(), at);
        publish(EventType.ALARM_RERAISED, row, actor(r.actorType()), at);
        notifications.onReraised(row);
        return row;
    }

    /** 키로 해제(조건 해소). 열린 알람이 없으면 빈 값 */
    public Optional<AlarmRow> clearByKey(long organizationId, String alarmKey, Double value, Instant at, AlarmClearReason reason,
                                         String actorType, Long actorId) {
        Optional<AlarmRow> open = alarms.lockOpenByKey(organizationId, alarmKey);
        return open.map(a -> clear(a, reason, value, at == null ? clock.instant() : at, actorType, actorId, null));
    }

    /** 열린 알람 해제. 하위 알람은 PARENT_CLEARED(BR-RUL-09) */
    public AlarmRow clear(AlarmRow a, AlarmClearReason reason, Double value, Instant at, String actorType, Long actorId, String note) {
        AlarmStateMachine.clear(AlarmStatus.valueOf(a.status()));
        alarms.updateCleared(a.organizationId(), a.id(), reason.name(), value, at);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", reason.name());
        if (value != null) {
            data.put("value", value);
        }
        if (note != null) {
            data.put("note", note);
        }
        events.insert(a.organizationId(), a.id(), at, "CLEARED", actorType, actorId, write(data));
        AlarmRow row = alarms.findById(a.organizationId(), a.id()).orElseThrow();
        publish(EventType.ALARM_CLEARED, row, actor(actorType, actorId), at);
        for (Long child : alarms.listOpenChildren(a.organizationId(), a.id())) {
            alarms.lockById(a.organizationId(), child)
                    .filter(AlarmRow::open)
                    .ifPresent(c -> clear(c, AlarmClearReason.PARENT_CLEARED, null, at, "SYSTEM", null, null));
        }
        notifications.onCleared(row, a);
        return row;
    }

    /** 확인(단건 엄격·일괄 관대) — 호출자가 권한을 본다 */
    public AlarmRow ack(AlarmRow a, long userId, String actorType, Instant at) {
        AlarmStatus next = AlarmStateMachine.ackStrict(AlarmStatus.valueOf(a.status()), a.ackedAt() != null);
        alarms.updateAcked(a.organizationId(), a.id(), next.name(), userId, at);
        events.insert(a.organizationId(), a.id(), at, "ACKED", actorType, userId, null);
        AlarmRow row = alarms.findById(a.organizationId(), a.id()).orElseThrow();
        publish(EventType.ALARM_ACKED, row, actor(actorType, userId), at);
        return row;
    }

    /** 유지보수 시작: 범위의 열린 ACTIVE·ACKNOWLEDGED 알람을 SUPPRESSED(MAINTENANCE)로 */
    public int suppressForMaintenance(long organizationId, Long deviceId, String spacePathPrefix, Instant at) {
        int n = 0;
        for (AlarmRow a : alarms.listOpenInScope(organizationId, deviceId, spacePathPrefix)) {
            if (!"SUPPRESSED".equals(a.status())) {
                alarms.lockById(organizationId, a.id()).ifPresent(locked -> {
                    AlarmStateMachine.suppress(AlarmStatus.valueOf(locked.status()));
                    alarms.updateStatus(organizationId, locked.id(), "SUPPRESSED", SuppressedReason.MAINTENANCE.name(), at);
                    events.insert(organizationId, locked.id(), at, "SUPPRESSED", "SYSTEM", null,
                            write(Map.of("suppressedReason", SuppressedReason.MAINTENANCE.name())));
                    publish(EventType.ALARM_SUPPRESSED, alarms.findById(organizationId, locked.id()).orElseThrow(), null, at);
                });
                n++;
            }
        }
        return n;
    }

    /** 유지보수 종료: MAINTENANCE로 억제된 열린 알람을 ACTIVE로(조건이 계속되는 알람만 열려 있으므로, 상태 기계 SUPPRESSED → ACTIVE) */
    public int releaseMaintenance(long organizationId, Long deviceId, String spacePathPrefix, Instant at) {
        int n = 0;
        for (AlarmRow a : alarms.listOpenInScope(organizationId, deviceId, spacePathPrefix)) {
            if ("SUPPRESSED".equals(a.status()) && SuppressedReason.MAINTENANCE.name().equals(a.suppressedReason())
                    && !alarms.isUnderMaintenance(organizationId, a.deviceId(), a.spaceId())) {
                AlarmStatus next = AlarmStateMachine.unsuppress(AlarmStatus.SUPPRESSED);
                alarms.updateStatus(organizationId, a.id(), next.name(), null, at);
                events.insert(organizationId, a.id(), at, "RAISED", "SYSTEM", null, write(Map.of("unsuppressed", "MAINTENANCE")));
                AlarmRow row = alarms.findById(organizationId, a.id()).orElseThrow();
                publish(EventType.ALARM_RAISED, row, null, at);
                notifications.onRaised(row);
                n++;
            }
        }
        return n;
    }

    /** 플래핑 끄기(30분 동안 상태 변화 없음). 끈 알람 수 */
    public int settleFlapping(long organizationId, Instant now) {
        int n = 0;
        for (Map.Entry<Long, Instant> e : alarms.listFlappingLastChange(organizationId).entrySet()) {
            if (FlappingDetector.settled(e.getValue(), now)) {
                alarms.updateFlapping(organizationId, e.getKey(), false, now);
                events.insert(organizationId, e.getKey(), now, "FLAPPING_OFF", "SYSTEM", null, null);
                publish(EventType.ALARM_FLAPPING, alarms.findById(organizationId, e.getKey()).orElseThrow(), null, now);
                n++;
            }
        }
        return n;
    }

    /** 타임라인 한 줄(메모·조치·담당·알림 결과·에스컬레이션) */
    public long timeline(long organizationId, long alarmId, String type, String actorType, Long actorId, Map<String, ?> data, Instant at) {
        return events.insert(organizationId, alarmId, at, type, actorType, actorId, data == null ? null : write(data));
    }

    private AlarmRow flappingCheck(AlarmRow row, Instant at) {
        if (row.flapping()) {
            return row;
        }
        List<Instant> raises = alarms.listRaiseTimes(row.organizationId(), row.alarmKey(), at.minus(FlappingDetector.WINDOW));
        if (FlappingDetector.flapping(raises, at)) {
            alarms.updateFlapping(row.organizationId(), row.id(), true, at);
            events.insert(row.organizationId(), row.id(), at, "FLAPPING_ON", "SYSTEM", null, write(Map.of("raises", raises.size())));
            AlarmRow updated = alarms.findById(row.organizationId(), row.id()).orElseThrow();
            publish(EventType.ALARM_FLAPPING, updated, null, at);
            return updated;
        }
        return row;
    }

    private void groupSpaceEvent(long organizationId, long alarmId, long spaceId, String alarmKey, Instant at) {
        Instant since = at.minus(SpaceEventGrouper.WINDOW);
        Optional<SpaceEventRepository.OpenEvent> latest = spaceEvents.findLatest(organizationId, spaceId);
        String prefix = sourcePrefix(alarmKey);
        Instant other = alarms.findRecentOtherSource(organizationId, spaceId, prefix, alarmId, since).orElse(null);
        SpaceEventGrouper.Decision d = SpaceEventGrouper.decide(latest.map(SpaceEventRepository.OpenEvent::openedAt).orElse(null), other, at);
        switch (d) {
            case JOIN -> {
                alarms.updateSpaceEvent(organizationId, alarmId, latest.get().id());
                spaceEvents.updateCount(organizationId, latest.get().id());
            }
            case OPEN -> {
                long eventId = spaceEvents.insert(organizationId, spaceId, other);
                for (Long id : alarms.listRecentInSpace(organizationId, spaceId, since)) {
                    alarms.updateSpaceEvent(organizationId, id, eventId);
                }
                spaceEvents.updateCount(organizationId, eventId);
            }
            case NONE -> {
                // 묶지 않음
            }
        }
    }

    /** 같은 출처를 가리는 키 접두어: rule:{id}: · flow:{flowId}:{nodeId}: · system:{code}: */
    static String sourcePrefix(String alarmKey) {
        String[] parts = alarmKey.split(":");
        if (alarmKey.startsWith("flow:") && parts.length >= 3) {
            return parts[0] + ":" + parts[1] + ":" + parts[2] + ":";
        }
        return parts.length >= 2 ? parts[0] + ":" + parts[1] + ":" : alarmKey;
    }

    /** 게이트웨이 알람 발생: 그 아래 기기의 열린 연결 계열 알람을 하위로 묶고 SUPPRESSED(PARENT)(RUL-04.01) */
    private void adoptChildren(AlarmRow gatewayAlarm, Instant at) {
        String gatewayId = gatewayAlarm.alarmKey().substring(("system:" + GATEWAY_OFFLINE + ":").length());
        alarms.findGateway(gatewayAlarm.organizationId(), Long.parseLong(gatewayId)).ifPresent(g -> {
            List<Long> devices = alarms.listDevicesUnderGateway(gatewayAlarm.organizationId(), g.sourceId(), g.gatewayEui());
            for (Long childId : alarms.listOpenConnectivityByDevices(gatewayAlarm.organizationId(), devices)) {
                alarms.lockById(gatewayAlarm.organizationId(), childId).filter(c -> c.parentAlarmId() == null && c.id() != gatewayAlarm.id())
                        .ifPresent(c -> {
                            alarms.updateParent(c.organizationId(), c.id(), gatewayAlarm.id(), at);
                            if (!"SUPPRESSED".equals(c.status())) {
                                alarms.updateStatus(c.organizationId(), c.id(), "SUPPRESSED", SuppressedReason.PARENT.name(), at);
                                events.insert(c.organizationId(), c.id(), at, "SUPPRESSED", "SYSTEM", null,
                                        write(Map.of("suppressedReason", "PARENT", "parentAlarmId", gatewayAlarm.id())));
                                publish(EventType.ALARM_SUPPRESSED, alarms.findById(c.organizationId(), c.id()).orElseThrow(), null, at);
                            }
                        });
            }
        });
    }

    void publish(EventType type, AlarmRow row, AlarmStateChanged.Actor actor, Instant at) {
        if (type == null) {
            return;
        }
        publisher.event(type, row.organizationId(), new AlarmStateChanged(AlarmViews.snapshot(row), actor, at));
    }

    static AlarmStateChanged.Actor actor(String actorType) {
        return actor(actorType, null);
    }

    static AlarmStateChanged.Actor actor(String actorType, Long actorId) {
        if (actorType == null) {
            return null;
        }
        AlarmStateChanged.Actor.Type type;
        try {
            type = AlarmStateChanged.Actor.Type.valueOf(actorType);
        } catch (IllegalArgumentException ex) {
            type = AlarmStateChanged.Actor.Type.SYSTEM;
        }
        return new AlarmStateChanged.Actor(type, actorId == null ? null : Long.toString(actorId));
    }

    static Map<String, Object> thresholdMap(Double raise, Double clear) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("raise", raise);
        m.put("clear", clear == null ? raise : clear);
        return m;
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    String write(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    /** 조회용 */
    @Transactional(readOnly = true)
    public Optional<AlarmRow> find(long organizationId, long alarmId) {
        return alarms.findById(organizationId, alarmId);
    }
}
