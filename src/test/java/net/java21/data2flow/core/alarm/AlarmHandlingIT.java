package net.java21.data2flow.core.alarm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlarmHandlingIT extends AlarmItSupport {

    @Test
    @DisplayName("[RUL-02.02][TC-RUL-040][TC-RUL-041] OPERATOR 확인 → ACKNOWLEDGED·ackedBy·ackedAt, 감사 ALARM_ACKED, 다시 확인은 409 ALARM_STATE_CONFLICT")
    void ack() throws Exception {
        raise(sensor1, 1100, clock.instant());
        long id = alarmId(sensor1);
        mvc.perform(as(org, operator, post("/core/alarms/" + id + "/ack"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("ACKNOWLEDGED")).andExpect(jsonPath("$.response.ackedAt").exists());
        assertThat(auditCount(org, "ALARM_ACKED")).isEqualTo(1);
        assertThat(events(org, "alarm.acked")).hasSize(1);
        mvc.perform(as(org, operator, post("/core/alarms/" + id + "/ack"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value("ALARM_STATE_CONFLICT"))
                .andExpect(jsonPath("$.header.resultMessage").value("이미 처리된 알람입니다"));
        mvc.perform(as(org, operator, get("/core/alarms/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.alarm.ackedBy.userId").value(Long.toString(operator)))
                .andExpect(jsonPath("$.response.events[0].type").value("RAISED"))
                .andExpect(jsonPath("$.response.events[1].type").value("ACKED"))
                .andExpect(jsonPath("$.response.chart.metric").value("co2"));
    }

    @Test
    @DisplayName("[RUL-02.02][TC-RUL-042][TC-RUL-043][TC-RUL-050] VIEWER 확인 403 PERMISSION_DENIED, 다른 조직 알람·없는 알람 404 ALARM_NOT_FOUND")
    void permissions() throws Exception {
        raise(sensor1, 1100, clock.instant());
        long id = alarmId(sensor1);
        mvc.perform(as(org, viewer, post("/core/alarms/" + id + "/ack"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        mvc.perform(as(org, viewer, get("/core/alarms/" + id))).andExpect(status().isOk());
        long other = fx.organization("other");
        long otherAdmin = fx.user(other, "other.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/alarms/" + id))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("ALARM_NOT_FOUND"))
                .andExpect(jsonPath("$.header.resultMessage").value("알람을 찾을 수 없습니다"));
        mvc.perform(as(org, admin, get("/core/alarms/999999"))).andExpect(status().isNotFound());
        // 공간 범위가 강의실뿐인 운영자에게 실습실 알람은 404, 목록에도 없음
        data.spaceScope(org, operator, java.util.List.of(classroom));
        mvc.perform(as(org, operator, get("/core/alarms/" + id))).andExpect(status().isNotFound());
        mvc.perform(as(org, operator, get("/core/alarms"))).andExpect(jsonPath("$.totalCount").value(0));
    }

    @Test
    @DisplayName("[RUL-02.06][TC-RUL-057][TC-RUL-060] 알람 150건 일괄 확인 → 건별 결과, 이미 확인한 건 alreadyAcked, 201건은 400 ALARM_BULK_LIMIT_EXCEEDED")
    void bulkAck() throws Exception {
        StringJoiner ids = new StringJoiner(",");
        for (int i = 0; i < 150; i++) {
            raise(net.java21.data2flow.contracts.alarm.AlarmKeys.system("TEST", Integer.toString(i)),
                    net.java21.data2flow.contracts.alarm.AlarmSourceType.SYSTEM, null, net.java21.data2flow.contracts.alarm.AlarmSeverity.MINOR,
                    null, lab, i, clock.instant());
        }
        jdbc.sql("SELECT id FROM data2flow_core.alarms WHERE organization_id = :org ORDER BY id").param("org", org).query(Long.class).list()
                .forEach(id -> ids.add("\"" + id + "\""));
        String first = ids.toString().split(",")[0];
        mvc.perform(as(org, operator, post("/core/alarms/" + first.replace("\"", "") + "/ack"))).andExpect(status().isOk());
        MvcResult r = mvc.perform(as(org, operator, json(post("/core/alarms/bulk-ack"), "{\"alarmIds\":[" + ids + ",\"999999\"]}")))
                .andExpect(status().isOk()).andReturn();
        assertThat((Integer) read(r, "$.response.results.length()")).isEqualTo(151);
        assertThat((Boolean) read(r, "$.response.results[0].alreadyAcked")).isTrue();
        assertThat((String) read(r, "$.response.results[150].code")).isEqualTo("ALARM_NOT_FOUND");
        assertThat(count("SELECT count(*) FROM data2flow_core.alarms WHERE organization_id = :org AND status = 'ACKNOWLEDGED'")).isEqualTo(150);
        StringJoiner many = new StringJoiner(",");
        for (int i = 0; i < 201; i++) {
            many.add("\"" + (i + 1) + "\"");
        }
        mvc.perform(as(org, operator, json(post("/core/alarms/bulk-ack"), "{\"alarmIds\":[" + many + "]}"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("ALARM_BULK_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.header.resultMessage").value("한 번에 200건까지 처리할 수 있습니다"));
    }

    @Test
    @DisplayName("[RUL-02.04][TC-RUL-053] 담당자 지정·메모·조치 기록 → 타임라인에 작성자와 시각 순서로, 수동 해제는 MANUAL")
    void notesAssigneeAndClear() throws Exception {
        raise(sensor1, 1100, clock.instant());
        long id = alarmId(sensor1);
        mvc.perform(as(org, operator, json(put("/core/alarms/" + id + "/assignee"), "{\"userId\":\"" + operator + "\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.assignee.userId").value(Long.toString(operator)));
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(as(org, operator, json(post("/core/alarms/" + id + "/notes"), "{\"text\":\"현장 확인 중\"}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.type").value("NOTE"));
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(as(org, operator, json(post("/core/alarms/" + id + "/notes"), "{\"text\":\"창문 개방\",\"actionType\":\"ONSITE\"}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.type").value("ACTION"))
                .andExpect(jsonPath("$.response.actor.userId").value(Long.toString(operator)));
        mvc.perform(as(org, operator, json(post("/core/alarms/" + id + "/notes"), "{\"text\":\"" + "a".repeat(2001) + "\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, operator, json(post("/core/alarms/" + id + "/clear"), "{\"note\":\"환기 완료\"}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("CLEARED")).andExpect(jsonPath("$.response.clearReason").value("MANUAL"));
        mvc.perform(as(org, operator, json(post("/core/alarms/" + id + "/clear"), "{}"))).andExpect(status().isConflict());
        mvc.perform(as(org, operator, get("/core/alarms/" + id))).andExpect(jsonPath("$.response.events[1].type").value("ASSIGNED"))
                .andExpect(jsonPath("$.response.events[2].type").value("NOTE"))
                .andExpect(jsonPath("$.response.events[3].type").value("ACTION"))
                .andExpect(jsonPath("$.response.events[3].actor.name").exists())
                .andExpect(jsonPath("$.response.events[4].type").value("CLEARED"));
    }

    @Test
    @DisplayName("[RUL-02.06][TC-RUL-057] 목록 필터(상태·심각도·공간·기간·출처)와 상태·심각도별 수, 기본은 열린 알람")
    void listFilters() throws Exception {
        raise(sensor1, 1100, clock.instant());
        raise(sensor3, 1200, clock.instant().plusSeconds(10));
        clear(sensor3, 800, clock.instant().plusSeconds(20));
        mvc.perform(as(org, viewer, get("/core/alarms"))).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.counts.byStatus.CLEARED").value(1))
                .andExpect(jsonPath("$.responses[0].space.path").value("캠퍼스 / 본관 / 실습실"))
                .andExpect(jsonPath("$.responses[0].source.type").value("FLOW"));
        mvc.perform(as(org, viewer, get("/core/alarms").param("status", "ACTIVE,CLEARED").param("spaceId", Long.toString(classroom))))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].status").value("CLEARED"));
        mvc.perform(as(org, viewer, get("/core/alarms").param("severity", "CRITICAL"))).andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(as(org, viewer, get("/core/alarms").param("status", "BAD"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, viewer, get("/core/alarms").param("deviceId", Long.toString(sensor1)).param("sourceType", "FLOW")
                        .param("from", clock.instant().minusSeconds(60).toString())))
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("[RUL-06.01][TC-RUL-102] 통계: 발생 수, MTTA·MTTR, 확인 안 된 비율, 상위 공간·기기, 날짜별")
    void stats() throws Exception {
        raise(sensor1, 1100, clock.instant());
        long a = alarmId(sensor1);
        clock.advance(Duration.ofMinutes(12));
        mvc.perform(as(org, operator, post("/core/alarms/" + a + "/ack"))).andExpect(status().isOk());
        clear(sensor1, 800, clock.instant().plus(Duration.ofMinutes(8)));
        raise(sensor3, 1100, clock.instant());
        mvc.perform(as(org, analyst, get("/core/alarms/stats").param("from", clock.instant().minus(Duration.ofDays(1)).toString())
                        .param("to", clock.instant().plus(Duration.ofDays(1)).toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.raised").value(2))
                .andExpect(jsonPath("$.response.mttaSec").value(720)).andExpect(jsonPath("$.response.mttrSec").value(1200))
                .andExpect(jsonPath("$.response.unackedRatio").value(0.5))
                .andExpect(jsonPath("$.response.topSpaces.length()").value(2))
                .andExpect(jsonPath("$.response.daily[0].raised").value(2));
        mvc.perform(as(org, viewer, get("/core/alarms/stats"))).andExpect(status().isForbidden());
    }
}
