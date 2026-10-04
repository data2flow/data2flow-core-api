package net.java21.data2flow.core.alarm;

import net.java21.data2flow.contracts.alarm.AlarmKeys;
import net.java21.data2flow.contracts.alarm.AlarmSeverity;
import net.java21.data2flow.contracts.alarm.AlarmSourceType;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.AlarmSignal;
import net.java21.data2flow.core.support.LoopItSupport;
import org.junit.jupiter.api.BeforeEach;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * M4 알람 IT 공통: 캠퍼스 > 본관 > 실습실(CO2 센서 2대) + 강의실(1대), 소스·측정 항목·모델. 알람 신호(EVT-RUL-01)를 소비자에 직접 넣는 도우미.
 */
public abstract class AlarmItSupport extends LoopItSupport {

    protected long site;
    protected long building;
    protected long lab;
    protected long classroom;
    protected long source;
    protected long co2Model;
    protected long sensor1;
    protected long sensor2;
    protected long sensor3;
    protected final String flowId = UUID.randomUUID().toString();
    @org.springframework.beans.factory.annotation.Autowired
    protected net.java21.data2flow.core.alarm.service.AutomationJobs jobs;

    @BeforeEach
    void alarmFixtures() {
        site = data.site(org, "캠퍼스");
        building = data.space(org, site, "BUILDING", "본관");
        lab = data.space(org, building, "ROOM", "실습실");
        classroom = data.space(org, building, "ROOM", "강의실");
        source = data.source(org, "lns");
        data.metric(org, "co2", "ppm");
        data.metric(org, "temperature", "°C");
        co2Model = data.model(org, "AM319", List.of("co2", "temperature"));
        sensor1 = data.device(org, source, "co2-1", "ACTIVE", lab, co2Model);
        sensor2 = data.device(org, source, "co2-2", "ACTIVE", lab, co2Model);
        sensor3 = data.device(org, source, "co2-3", "ACTIVE", classroom, co2Model);
    }

    /** 플로우 알람 노드의 발생 신호(flow:{flowId}:{node}:{device}) */
    protected boolean raise(long deviceId, double value, Instant at) {
        return raise(AlarmKeys.flow(flowId, "n-alarm", Long.toString(deviceId)), AlarmSourceType.FLOW, null, AlarmSeverity.MAJOR, deviceId, null,
                value, at);
    }

    protected boolean raise(String key, AlarmSourceType type, Long ruleId, AlarmSeverity severity, Long deviceId, Long spaceId, double value,
                            Instant at) {
        AlarmSignal s = AlarmSignal.raise(key, type, ruleId, flowId, 1, "n-alarm", severity, "CO2 높음 " + value, deviceId, spaceId, "co2",
                value, new AlarmSignal.Threshold(1000.0, 900.0), at, UUID.randomUUID().toString());
        return deliver(EventType.ALARM_SIGNAL, org, s, at);
    }

    protected boolean clear(long deviceId, double value, Instant at) {
        AlarmSignal s = AlarmSignal.clear(AlarmKeys.flow(flowId, "n-alarm", Long.toString(deviceId)), AlarmSourceType.FLOW, null, flowId, 1,
                "n-alarm", deviceId, null, "co2", value, at, UUID.randomUUID().toString());
        return deliver(EventType.ALARM_SIGNAL, org, s, at);
    }

    protected long alarmId(long deviceId) {
        return jdbc.sql("SELECT id FROM data2flow_core.alarms WHERE organization_id = :org AND device_id = :d ORDER BY id DESC LIMIT 1")
                .param("org", org).param("d", deviceId).query(Long.class).single();
    }

    protected String alarmStatus(long alarmId) {
        return jdbc.sql("SELECT status FROM data2flow_core.alarms WHERE id = :id").param("id", alarmId).query(String.class).single();
    }

    protected long count(String sql) {
        return jdbc.sql(sql).param("org", org).query(Long.class).single();
    }

    /** 아웃박스의 알림 요청(data2flow.actions notify) */
    protected List<String> notifyRequests() {
        return jdbc.sql("SELECT payload::text FROM data2flow_core.outboxes WHERE organization_id = :org AND kind = 'NOTIFY' ORDER BY id")
                .param("org", org).query(String.class).list();
    }
}
