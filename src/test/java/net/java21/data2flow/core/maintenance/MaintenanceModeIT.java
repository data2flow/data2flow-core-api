package net.java21.data2flow.core.maintenance;

import net.java21.data2flow.core.alarm.AlarmItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MaintenanceModeIT extends AlarmItSupport {

    String create(String body) throws Exception {
        MvcResult r = mvc.perform(as(org, operator, json(post("/core/maintenance-windows"), body))).andExpect(status().isCreated())
                .andExpect(header().exists("Location")).andReturn();
        return read(r, "$.response.id");
    }

    @Test
    @DisplayName("[OPS-05.01][TC-OPS-046][TC-OPS-049] 공간 유지보수 시작 → 열린 알람 SUPPRESSED·새 알람도 억제, EVT-OPS-02 시작(하위 공간 펼침), 내부 API-OPS-24")
    void startSuppresses() throws Exception {
        raise(sensor1, 1100, clock.instant());
        long open = alarmId(sensor1);
        String id = create("{\"targetType\":\"SPACE\",\"targetId\":\"" + building + "\",\"reason\":\"공조기 교체\"}");
        assertThat(alarmStatus(open)).isEqualTo("SUPPRESSED");
        raise(sensor3, 1100, clock.instant());
        assertThat(alarmStatus(alarmId(sensor3))).isEqualTo("SUPPRESSED");
        assertThat(events(org, "ops.maintenance.started")).hasSize(1).first().asString().contains("\"descendantSpaceIds\"");
        mvc.perform(get("/internal/core/maintenance-windows").param("organizationId", Long.toString(org)))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].windowId").value(id))
                .andExpect(jsonPath("$.responses[0].descendantSpaceIds.length()").value(2))
                .andExpect(jsonPath("$.responses[0].pauseAutomation").value(true));
        mvc.perform(as(org, viewer, get("/core/maintenance-windows"))).andExpect(jsonPath("$.responses[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.responses[0].targetName").value("본관"));
        assertThat(auditCount(org, "MAINTENANCE_CREATED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[OPS-05.02][TC-OPS-052][TC-OPS-053] 종료 → 조건이 남은 알람은 ACTIVE로 돌아오고 EVT-OPS-02 끝, 다시 종료는 409")
    void endReleases() throws Exception {
        String id = create("{\"targetType\":\"DEVICE\",\"targetId\":\"" + sensor1 + "\",\"reason\":\"센서 교정\",\"endsAt\":\""
                + clock.instant().plus(Duration.ofHours(2)) + "\"}");
        raise(sensor1, 1100, clock.instant());
        long alarm = alarmId(sensor1);
        assertThat(alarmStatus(alarm)).isEqualTo("SUPPRESSED");
        raise(sensor2, 1100, clock.instant());
        assertThat(alarmStatus(alarmId(sensor2))).isEqualTo("ACTIVE");
        mvc.perform(as(org, operator, post("/core/maintenance-windows/" + id + "/end"))).andExpect(status().isNoContent());
        assertThat(alarmStatus(alarm)).isEqualTo("ACTIVE");
        assertThat(events(org, "ops.maintenance.ended")).hasSize(1);
        mvc.perform(as(org, operator, post("/core/maintenance-windows/" + id + "/end"))).andExpect(status().isConflict());
        mvc.perform(as(org, viewer, post("/core/maintenance-windows/" + id + "/end"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[OPS-05.03][TC-OPS-055] 예약 → 시각이 되면 작업이 시작·종료, 취소는 SCHEDULED만, 겹치면 409, 기간 오류 400")
    void scheduledLifecycle() throws Exception {
        String later = create("{\"targetType\":\"SPACE\",\"targetId\":\"" + lab + "\",\"reason\":\"점검\",\"startsAt\":\""
                + clock.instant().plus(Duration.ofMinutes(10)) + "\",\"endsAt\":\"" + clock.instant().plus(Duration.ofMinutes(40)) + "\"}");
        mvc.perform(as(org, operator, json(post("/core/maintenance-windows"), "{\"targetType\":\"SPACE\",\"targetId\":\"" + lab
                        + "\",\"reason\":\"겹침\",\"startsAt\":\"" + clock.instant().plus(Duration.ofMinutes(20)) + "\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("MAINTENANCE_OVERLAP"))
                .andExpect(jsonPath("$.header.resultMessage").value("같은 대상에 겹치는 유지보수 일정이 있습니다"));
        mvc.perform(as(org, operator, json(post("/core/maintenance-windows"), "{\"targetType\":\"SPACE\",\"targetId\":\"" + classroom
                        + "\",\"reason\":\"x\",\"endsAt\":\"" + clock.instant().minusSeconds(1) + "\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("MAINTENANCE_RANGE_INVALID"));
        mvc.perform(as(org, operator, json(post("/core/maintenance-windows"), "{\"targetType\":\"ROOM\",\"targetId\":\"1\",\"reason\":\"x\"}")))
                .andExpect(status().isBadRequest());
        clock.advance(Duration.ofMinutes(11));
        assertThat(jobs.minute()).isTrue();
        mvc.perform(as(org, viewer, get("/core/maintenance-windows").param("status", "ACTIVE"))).andExpect(jsonPath("$.totalCount").value(1));
        clock.advance(Duration.ofMinutes(30));
        jobs.minute();
        mvc.perform(as(org, viewer, get("/core/maintenance-windows").param("status", "ENDED"))).andExpect(jsonPath("$.responses[0].id").value(later));
        String next = create("{\"targetType\":\"SPACE\",\"targetId\":\"" + lab + "\",\"reason\":\"다음\",\"startsAt\":\""
                + clock.instant().plus(Duration.ofDays(1)) + "\"}");
        mvc.perform(as(org, operator, post("/core/maintenance-windows/" + next + "/cancel"))).andExpect(status().isNoContent());
        mvc.perform(as(org, operator, post("/core/maintenance-windows/" + next + "/cancel"))).andExpect(status().isConflict());
        mvc.perform(as(org, operator, post("/core/maintenance-windows/999999/cancel"))).andExpect(status().isNotFound());
    }
}
