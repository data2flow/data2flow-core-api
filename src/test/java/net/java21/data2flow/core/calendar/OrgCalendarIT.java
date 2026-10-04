package net.java21.data2flow.core.calendar;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.event.SpaceModeChanged;
import net.java21.data2flow.core.alarm.AlarmItSupport;
import net.java21.data2flow.core.calendar.service.CalendarJobs;
import net.java21.data2flow.core.common.Pg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 조직 달력(DEV-12.01, API-DEV-100~102)·운영 모드(DEV-11.02, API-DEV-08, EVT-DEV-06)·예약 제어 휴일 제외(ACT-02.07 skipHolidays).
 * 공간: 캠퍼스 > 본관 > 실습실·강의실. T0 = 2026-10-03(토) 09:00 서울 — TC-DEV-289·295·296
 */
class OrgCalendarIT extends AlarmItSupport {

    @Autowired
    CalendarJobs calendarJobs;

    private MvcResult create(long user, String json) throws Exception {
        return mvc.perform(as(org, user, json(post("/core/calendar-events"), json))).andReturn();
    }

    private List<SpaceModeChanged> modeEvents() {
        return events(org, "space.mode.changed").stream()
                .map(e -> (SpaceModeChanged) ((DomainEvent<?>) codec.readEvent(e.getBytes(StandardCharsets.UTF_8))).payload()).toList();
    }

    @Test
    @DisplayName("[DEV-12.01][TC-DEV-295][TC-DEV-293] 일정 등록(201·Location, 유형별 모드 영향 기본값)·조회(기간·공간 필터)·수정(baseVersion)·삭제, 규칙 위반은 CALENDAR_EVENT_INVALID")
    void crud() throws Exception {
        MvcResult r = create(operator, """
                {"title":"겨울방학","type":"VACATION","startsOn":"2026-12-21","endsOn":"2027-02-28","scopeSpaceIds":["%d"]}""".formatted(building));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        String id = JsonPath.read(body(r), "$.response.id");
        assertThat(r.getResponse().getHeader("Location")).isEqualTo("/api/v1/core/calendar-events/" + id);
        assertThat((String) JsonPath.read(body(r), "$.response.affectsMode")).isEqualTo("UNOCCUPIED");
        assertThat((String) JsonPath.read(body(r), "$.response.origin")).isEqualTo("MANUAL");
        MvcResult timed = create(operator, """
                {"title":"정전 점검","type":"CLOSURE","startsOn":"2026-12-22","startTime":"13:00","endTime":"15:00","affectsMode":"NONE"}""");
        assertThat((String) JsonPath.read(body(timed), "$.response.endsOn")).isEqualTo("2026-12-22");
        assertThat((String) JsonPath.read(body(timed), "$.response.startTime")).isEqualTo("13:00");
        assertThat((String) JsonPath.read(body(timed), "$.response.affectsMode")).isEqualTo("NONE");
        // 공간 필터: 실습실(본관 하위)은 본관 일정·조직 전체 일정 모두, 다른 사이트는 조직 전체만
        long otherSite = data.site(org, "제2캠퍼스");
        mvc.perform(as(org, viewer, get("/core/calendar-events").param("from", "2026-12-01").param("to", "2026-12-31").param("spaceId",
                        Long.toString(lab)))).andExpect(jsonPath("$.totalCount").value(2));
        mvc.perform(as(org, viewer, get("/core/calendar-events").param("from", "2026-12-01").param("to", "2026-12-31").param("spaceId",
                        Long.toString(otherSite)))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, viewer, get("/core/calendar-events").param("from", "2027-03-01").param("to", "2027-03-31")))
                .andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(as(org, viewer, get("/core/calendar-events").param("from", "2026-01-01"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, viewer, get("/core/calendar-events").param("from", "2026-01-01").param("to", "2027-01-02")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("to"));
        mvc.perform(as(org, viewer, get("/core/calendar-events").param("from", "x").param("to", "2026-01-02"))).andExpect(status().isBadRequest());
        // 수정
        mvc.perform(as(org, operator, json(patch("/core/calendar-events/" + id), "{\"title\":\"겨울 방학\",\"affectsMode\":\"HOLIDAY\",\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(1))
                .andExpect(jsonPath("$.response.affectsMode").value("HOLIDAY")).andExpect(jsonPath("$.response.startsOn").value("2026-12-21"));
        mvc.perform(as(org, operator, json(patch("/core/calendar-events/" + id), "{\"title\":\"x\",\"baseVersion\":0}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, operator, json(patch("/core/calendar-events/" + id), "{\"type\":\"EXAM\",\"baseVersion\":1}")))
                .andExpect(jsonPath("$.response.affectsMode").value("NONE"));
        mvc.perform(as(org, operator, json(patch("/core/calendar-events/" + id), "{\"color\":\"red\",\"baseVersion\":2}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_FIELD"));
        mvc.perform(as(org, operator, json(patch("/core/calendar-events/" + id), "{\"title\":\"x\"}"))).andExpect(status().isBadRequest());
        // 규칙 위반
        MvcResult reversed = create(operator, "{\"title\":\"x\",\"type\":\"EVENT\",\"startsOn\":\"2026-10-10\",\"endsOn\":\"2026-10-09\"}");
        assertThat(reversed.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(body(reversed), "$.header.resultCode")).isEqualTo("CALENDAR_EVENT_INVALID");
        assertThat((String) JsonPath.read(body(reversed), "$.errors[0].code")).isEqualTo("BEFORE_START");
        assertThat(create(operator, "{\"title\":\"x\",\"type\":\"EVENT\",\"startsOn\":\"2026-01-01\",\"endsOn\":\"2027-01-02\"}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(create(operator, "{\"title\":\"x\",\"type\":\"EVENT\",\"startsOn\":\"2026-01-01\",\"startTime\":\"09:00\"}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(create(operator, "{\"title\":\"x\",\"type\":\"EVENT\",\"startsOn\":\"2026-01-01\",\"startTime\":\"10:00\",\"endTime\":\"09:00\"}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(create(operator, "{\"title\":\"\",\"type\":\"PARTY\",\"startsOn\":\"2026-01-01\",\"affectsMode\":\"X\"}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(create(operator, "{\"title\":\"x\",\"type\":\"EVENT\",\"startsOn\":\"2026-01-01\",\"startTime\":\"9시\",\"endTime\":\"10:00\"}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(create(operator, "{\"title\":\"x\",\"type\":\"EVENT\",\"startsOn\":\"2026-01-01\",\"scopeSpaceIds\":\"1\"}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(create(operator, "{\"title\":\"x\",\"type\":\"EVENT\",\"startsOn\":\"2026-01-01\",\"scopeSpaceIds\":[\"a\"]}")
                .getResponse().getStatus()).isEqualTo(400);
        MvcResult noSpace = create(operator, "{\"title\":\"x\",\"type\":\"EVENT\",\"startsOn\":\"2026-01-01\",\"scopeSpaceIds\":[\"999999\"]}");
        assertThat(noSpace.getResponse().getStatus()).isEqualTo(404);
        assertThat((String) JsonPath.read(body(noSpace), "$.header.resultCode")).isEqualTo("SPACE_NOT_FOUND");
        assertThat(mvc.perform(as(org, operator, json(post("/core/calendar-events"), "[]"))).andReturn().getResponse().getStatus()).isEqualTo(400);
        mvc.perform(as(org, operator, delete("/core/calendar-events/" + id))).andExpect(status().isNoContent());
        mvc.perform(as(org, viewer, get("/core/calendar-events/" + id))).andExpect(status().isNotFound());
        assertThat(auditCount(org, "CALENDAR_EVENT_CREATED")).isEqualTo(2);
        assertThat(auditCount(org, "CALENDAR_EVENT_UPDATED")).isEqualTo(2);
        assertThat(auditCount(org, "CALENDAR_EVENT_DELETED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[DEV-12.01][TC-DEV-296] 권한 DEV_PLACE: OPERATOR 201, ANALYST 403, VIEWER 조회만, 다른 조직 404, 공간 범위 제한 사용자는 조직 전체 일정 403·범위 밖 일정 404")
    void permissions() throws Exception {
        assertThat(create(analyst, "{\"title\":\"x\",\"type\":\"EVENT\",\"startsOn\":\"2026-10-10\"}").getResponse().getStatus()).isEqualTo(403);
        assertThat(create(viewer, "{\"title\":\"x\",\"type\":\"EVENT\",\"startsOn\":\"2026-10-10\"}").getResponse().getStatus()).isEqualTo(403);
        String labEvent = JsonPath.read(body(create(admin, "{\"title\":\"실습실 공사\",\"type\":\"CLOSURE\",\"startsOn\":\"2026-10-10\","
                + "\"scopeSpaceIds\":[\"" + lab + "\"]}")), "$.response.id");
        long other = fx.organization("cal-other");
        long otherAdmin = fx.user(other, "cal.other.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/calendar-events/" + labEvent))).andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, json(patch("/core/calendar-events/" + labEvent), "{\"title\":\"x\",\"baseVersion\":0}")))
                .andExpect(status().isNotFound());
        data.spaceScope(org, operator, List.of(classroom));
        assertThat(create(operator, "{\"title\":\"x\",\"type\":\"EVENT\",\"startsOn\":\"2026-10-10\"}").getResponse().getStatus()).isEqualTo(403);
        assertThat(create(operator, "{\"title\":\"x\",\"type\":\"EVENT\",\"startsOn\":\"2026-10-10\",\"scopeSpaceIds\":[\"" + lab + "\"]}")
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(create(operator, "{\"title\":\"강의실\",\"type\":\"EVENT\",\"startsOn\":\"2026-10-10\",\"scopeSpaceIds\":[\"" + classroom + "\"]}")
                .getResponse().getStatus()).isEqualTo(201);
        mvc.perform(as(org, operator, get("/core/calendar-events/" + labEvent))).andExpect(status().isNotFound());
        mvc.perform(as(org, operator, get("/core/calendar-events").param("from", "2026-10-10").param("to", "2026-10-10")))
                .andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, operator, get("/core/calendar-events").param("from", "2026-10-10").param("to", "2026-10-10")
                .param("spaceId", Long.toString(lab)))).andExpect(status().isNotFound());
        mvc.perform(as(org, admin, get("/core/calendar-events").param("from", "2026-10-10").param("to", "2026-10-10").param("size", "1")))
                .andExpect(jsonPath("$.totalCount").value(2)).andExpect(jsonPath("$.responses.length()").value(1));
    }

    @Test
    @DisplayName("[DEV-11.02][AT-DEV-17.1][TC-DEV-289][BR-DEV-23] 모드: 시간표 → 달력(HOLIDAY) → 유지보수 → 수동 지정 우선, 1분 계산이 바뀐 공간만 space.mode.changed(처음은 from=null)")
    void modes() throws Exception {
        jdbc.sql("UPDATE data2flow_core.spaces SET schedule_inherit = false WHERE id = :id").param("id", site).update();
        for (int d = 1; d <= 7; d++) {
            jdbc.sql("INSERT INTO data2flow_core.space_schedules (organization_id, space_id, day_of_week, start_time, end_time)"
                            + " VALUES (:org, :site, :d, '09:00', '18:00')")
                    .param("org", org).param("site", site).param("d", d).update();
        }
        mvc.perform(as(org, viewer, get("/core/spaces/" + lab + "/mode")))
                .andExpect(jsonPath("$.response.mode").value("OCCUPIED")).andExpect(jsonPath("$.response.source").value("SCHEDULE"));
        assertThat(calendarJobs.modes()).isTrue();
        assertThat(modeEvents()).hasSize(4).allSatisfy(e -> {
            assertThat(e.from()).isNull();
            assertThat(e.to()).isEqualTo("OCCUPIED");
            assertThat(e.source()).isEqualTo("SCHEDULE");
        });
        calendarJobs.modes();
        assertThat(modeEvents()).hasSize(4);
        // 오늘(개천절)을 본관 휴일로 → 본관·실습실·강의실 HOLIDAY, 캠퍼스는 그대로
        create(operator, "{\"title\":\"개천절\",\"type\":\"HOLIDAY\",\"startsOn\":\"2026-10-03\",\"scopeSpaceIds\":[\"" + building + "\"]}");
        mvc.perform(as(org, viewer, get("/core/spaces/" + lab + "/mode")))
                .andExpect(jsonPath("$.response.mode").value("HOLIDAY")).andExpect(jsonPath("$.response.source").value("CALENDAR"))
                .andExpect(jsonPath("$.response.until").value("2026-10-03T15:00:00Z"));
        mvc.perform(get("/internal/core/spaces/" + lab + "/mode")).andExpect(jsonPath("$.response.mode").value("HOLIDAY"));
        mvc.perform(as(org, viewer, get("/core/spaces/" + lab))).andExpect(jsonPath("$.response.mode.mode").value("HOLIDAY"));
        calendarJobs.modes();
        List<SpaceModeChanged> events = modeEvents();
        assertThat(events).hasSize(7);
        assertThat(events.subList(4, 7)).allSatisfy(e -> {
            assertThat(e.from()).isEqualTo("OCCUPIED");
            assertThat(e.to()).isEqualTo("HOLIDAY");
            assertThat(e.source()).isEqualTo("CALENDAR");
        });
        // 본관 유지보수 → MAINTENANCE
        jdbc.sql("""
                        INSERT INTO data2flow_core.maintenance_windows (organization_id, target_type, target_id, starts_at, ends_at, reason, status, created_by)
                        VALUES (:org, 'SPACE', :b, :start, :end, '점검', 'ACTIVE', 0)""")
                .param("org", org).param("b", building).param("start", Pg.ts(clock.instant())).param("end", Pg.ts(clock.instant().plusSeconds(7200)))
                .update();
        mvc.perform(as(org, viewer, get("/core/spaces/" + lab + "/mode")))
                .andExpect(jsonPath("$.response.mode").value("MAINTENANCE")).andExpect(jsonPath("$.response.source").value("MAINTENANCE"));
        // 수동 지정이 가장 앞섬
        mvc.perform(as(org, operator, json(post("/core/spaces/" + lab + "/override-mode"),
                        "{\"mode\":\"OCCUPIED\",\"until\":\"" + clock.instant().plusSeconds(3600) + "\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.mode").value("OCCUPIED"))
                .andExpect(jsonPath("$.response.source").value("OVERRIDE"));
        calendarJobs.modes();
        assertThat(modeEvents()).filteredOn(e -> e.spaceId() == lab).last().satisfies(e -> {
            assertThat(e.to()).isEqualTo("OCCUPIED");
            assertThat(e.source()).isEqualTo("MANUAL");
        });
        assertThat(modeEvents()).filteredOn(e -> e.spaceId() == classroom).last().extracting(SpaceModeChanged::to).isEqualTo("MAINTENANCE");
        mvc.perform(as(org, operator, json(post("/core/spaces/" + lab + "/override-mode"), "{\"mode\":null}")))
                .andExpect(jsonPath("$.response.mode").value("MAINTENANCE"));
        mvc.perform(as(org, operator, json(post("/core/spaces/" + lab + "/override-mode"), "{\"mode\":\"SLEEP\"}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, operator, json(post("/core/spaces/" + lab + "/override-mode"), "{\"mode\":\"OCCUPIED\",\"until\":\"x\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, operator, json(post("/core/spaces/" + lab + "/override-mode"),
                "{\"mode\":\"OCCUPIED\",\"until\":\"2020-01-01T00:00:00Z\"}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, viewer, json(post("/core/spaces/" + lab + "/override-mode"), "{\"mode\":\"OCCUPIED\"}"))).andExpect(status().isForbidden());
        assertThat(auditCount(org, "SPACE_MODE_OVERRIDDEN")).isEqualTo(2);
        // 하루 뒤: 휴일·유지보수 끝(유지보수 상태는 OPS-05 작업이 바꾼다)
        jdbc.sql("UPDATE data2flow_core.maintenance_windows SET status = 'ENDED' WHERE organization_id = :org").param("org", org).update();
        clock.advance(Duration.ofDays(1));
        mvc.perform(as(org, viewer, get("/core/spaces/" + lab + "/mode"))).andExpect(jsonPath("$.response.mode").value("OCCUPIED"));
    }

    @Test
    @DisplayName("[ACT-02.07][DEV-12.01] 예약 제어 skipHolidays: 휴일이면 보내지 않고 lastRun SKIPPED·HOLIDAY, 다음 날(평일)은 SENT·WORKDAY")
    void schedulesSkipHolidays() throws Exception {
        create(operator, "{\"title\":\"개천절\",\"type\":\"HOLIDAY\",\"startsOn\":\"2026-10-03\",\"scopeSpaceIds\":[\"" + site + "\"]}");
        mvc.perform(as(org, integrator, json(post("/core/control-schedules"), """
                        {"name":"아침 환기","target":{"deviceId":"%d","capability":"switch","command":"setState","args":{"state":"ON"}},
                         "kind":"RECURRING","cron":"5 9 * * *","skipHolidays":true}""".formatted(sensor1))))
                .andExpect(status().isCreated());
        clock.advance(Duration.ofMinutes(6));
        jobs.minute();
        assertThat(commandRequests()).isEmpty();
        mvc.perform(as(org, integrator, get("/core/control-schedules")))
                .andExpect(jsonPath("$.responses[0].lastRun.status").value("SKIPPED"))
                .andExpect(jsonPath("$.responses[0].lastRun.holidayCheck").value("HOLIDAY"))
                .andExpect(jsonPath("$.responses[0].nextRunAt").value("2026-10-04T00:05:00Z"));
        clock.advance(Duration.ofDays(1));
        jobs.minute();
        assertThat(commandRequests()).hasSize(1);
        mvc.perform(as(org, integrator, get("/core/control-schedules")))
                .andExpect(jsonPath("$.responses[0].lastRun.status").value("SENT"))
                .andExpect(jsonPath("$.responses[0].lastRun.holidayCheck").value("WORKDAY"));
    }

    List<String> commandRequests() {
        return jdbc.sql("SELECT payload::text FROM data2flow_core.outboxes WHERE organization_id = :org AND kind = 'COMMAND' ORDER BY id")
                .param("org", org).query(String.class).list();
    }
}
