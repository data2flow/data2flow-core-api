package net.java21.data2flow.core.alarm.service;

import net.java21.data2flow.contracts.alarm.AlarmClearReason;
import net.java21.data2flow.contracts.alarm.AlarmSeverity;
import net.java21.data2flow.contracts.alarm.AlarmSnapshot;
import net.java21.data2flow.contracts.alarm.AlarmSourceType;
import net.java21.data2flow.contracts.alarm.AlarmStatus;
import net.java21.data2flow.contracts.alarm.SuppressedReason;
import net.java21.data2flow.core.alarm.dto.AlarmDtos;
import net.java21.data2flow.core.alarm.repository.AlarmRepository.AlarmRow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 알람 행 → API 모양(API-RUL-10 Alarm)·이벤트 모양(contracts {@link AlarmSnapshot}, ID는 숫자·공간 경로는 ID 경로) */
public final class AlarmViews {

    private AlarmViews() {
    }

    public static AlarmDtos.Alarm view(AlarmRow a, JsonMapper json) {
        AlarmDtos.Threshold threshold = null;
        if (a.threshold() != null) {
            JsonNode t = json.readTree(a.threshold());
            threshold = new AlarmDtos.Threshold(t.hasNonNull("raise") ? t.get("raise").asDouble() : null,
                    t.hasNonNull("clear") ? t.get("clear").asDouble() : null);
        }
        return new AlarmDtos.Alarm(Long.toString(a.id()), a.severity(), a.status(), a.flapping(), a.title(),
                new AlarmDtos.Source(a.sourceType(), str(a.ruleId()), a.ruleName(), a.flowId() == null ? null : a.flowId().toString(),
                        a.nodeId()),
                a.deviceId() == null ? null : new AlarmDtos.DeviceRef(Long.toString(a.deviceId()), a.deviceName()),
                a.spaceId() == null ? null : new AlarmDtos.SpaceRef(Long.toString(a.spaceId()), a.spaceName(), a.spaceNames()),
                a.metricKey(), a.triggerValue(), a.peakValue(), a.lastValue(), threshold, a.occurrenceCount(), a.raisedAt(),
                a.lastRaisedAt(), user(a.ackedBy(), a.ackedByName()), a.ackedAt(), a.clearedAt(), a.clearReason(),
                user(a.assigneeId(), a.assigneeName()), str(a.parentAlarmId()), a.childCount(), str(a.spaceEventId()),
                a.suppressedReason(), a.deviceVirtual(), a.version());
    }

    /** EVT-RUL-02 {@code alarm}: ID는 JSON 숫자, 공간 경로는 끝 '/' 없는 ID 경로 */
    public static AlarmSnapshot snapshot(AlarmRow a) {
        String path = a.spacePath() == null ? null
                : a.spacePath().endsWith("/") ? a.spacePath().substring(0, a.spacePath().length() - 1) : a.spacePath();
        return new AlarmSnapshot(a.id(), a.alarmKey(), AlarmSeverity.valueOf(a.severity()), AlarmStatus.valueOf(a.status()), a.flapping(),
                a.title(), new AlarmSnapshot.Source(AlarmSourceType.valueOf(a.sourceType()), a.ruleId(),
                a.flowId() == null ? null : a.flowId().toString(), a.nodeId()),
                a.deviceId() == null ? null : new AlarmSnapshot.Ref(a.deviceId(), a.deviceName()),
                a.spaceId() == null ? null : new AlarmSnapshot.SpaceRef(a.spaceId(), path), a.metricKey(), a.triggerValue(),
                a.peakValue(), a.lastValue(), a.occurrenceCount(), a.raisedAt(), a.lastRaisedAt(),
                a.ackedBy() == null ? null : new AlarmSnapshot.UserRef(a.ackedBy(), a.ackedByName()), a.ackedAt(), a.clearedAt(),
                a.clearReason() == null ? null : AlarmClearReason.valueOf(a.clearReason()),
                a.assigneeId() == null ? null : new AlarmSnapshot.UserRef(a.assigneeId(), a.assigneeName()), a.parentAlarmId(),
                a.childCount(), a.spaceEventId(), a.suppressedReason() == null ? null : SuppressedReason.valueOf(a.suppressedReason()));
    }

    static AlarmDtos.UserRef user(Long id, String name) {
        return id == null ? null : new AlarmDtos.UserRef(Long.toString(id), name);
    }

    static String str(Long value) {
        return value == null ? null : Long.toString(value);
    }
}
