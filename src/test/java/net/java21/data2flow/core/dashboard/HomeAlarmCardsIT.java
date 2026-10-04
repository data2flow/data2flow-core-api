package net.java21.data2flow.core.dashboard;

import net.java21.data2flow.contracts.capability.ExpectedEffect;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.CommandNoEffect;
import net.java21.data2flow.core.alarm.AlarmItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HomeAlarmCardsIT extends AlarmItSupport {

    @Test
    @DisplayName("[DSH-01.01][TC-DSH-002][TC-DSH-008] 홈 알람 카드는 열린 알람 심각도별 수, 타임라인은 발생·해제 최신순, 공간 범위 사용자는 자기 공간 것만")
    void homeSummary() throws Exception {
        raise(sensor1, 1100, clock.instant());
        raise(sensor3, 1200, clock.instant().plusSeconds(10));
        clear(sensor3, 800, clock.instant().plusSeconds(20));
        raise(sensor2, 1300, clock.instant().plusSeconds(30));
        mvc.perform(as(org, admin, get("/core/home/summary"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.alarms.major").value(2)).andExpect(jsonPath("$.response.alarms.critical").value(0))
                .andExpect(jsonPath("$.response.timeline.length()").value(4))
                .andExpect(jsonPath("$.response.timeline[0].title").value("CO2 높음 1300.0"))
                .andExpect(jsonPath("$.response.timeline[1].type").value("ALARM_CLEARED"));
        data.spaceScope(org, viewer, List.of(classroom));
        mvc.perform(as(org, viewer, get("/core/home/summary"))).andExpect(jsonPath("$.response.alarms.major").value(0))
                .andExpect(jsonPath("$.response.timeline.length()").value(2));
    }

    @Test
    @DisplayName("[DSH-02.01][AT-DSH-02.1] 공간 요약 openAlarms는 이 공간과 하위의 열린 알람")
    void spaceOverview() throws Exception {
        raise(sensor1, 1100, clock.instant());
        raise(sensor3, 1100, clock.instant());
        mvc.perform(as(org, viewer, get("/core/spaces/" + building + "/overview"))).andExpect(jsonPath("$.response.openAlarms.length()").value(2));
        mvc.perform(as(org, viewer, get("/core/spaces/" + lab + "/overview"))).andExpect(jsonPath("$.response.openAlarms.length()").value(1))
                .andExpect(jsonPath("$.response.openAlarms[0].device.id").value(Long.toString(sensor1)));
    }

    @Test
    @DisplayName("[DEV-02.07][AT-DEV-02.7] 기기 이력(API-DEV-27): 감사 기록 중 그 기기 대상만 최신순, 다른 조직은 404")
    void deviceHistory() throws Exception {
        UUID command = UUID.randomUUID();
        deliver(EventType.COMMAND_NO_EFFECT, org, new CommandNoEffect(command, sensor1, lab, "thermostat",
                new CommandNoEffect.Expected("temperature", ExpectedEffect.Direction.DOWN, 30), CommandNoEffect.Observed.between(26.0, 26.1),
                clock.instant()), clock.instant());
        mvc.perform(as(org, viewer, get("/core/devices/" + sensor1 + "/history"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].action").value("COMMAND_NO_EFFECT"))
                .andExpect(jsonPath("$.responses[0].changes.commandId").value(command.toString()));
        mvc.perform(as(org, viewer, get("/core/devices/" + sensor2 + "/history"))).andExpect(jsonPath("$.totalCount").value(0));
        long other = fx.organization("other");
        mvc.perform(as(other, fx.user(other, "o.admin", "ADMIN"), get("/core/devices/" + sensor1 + "/history"))).andExpect(status().isNotFound());
    }
}
