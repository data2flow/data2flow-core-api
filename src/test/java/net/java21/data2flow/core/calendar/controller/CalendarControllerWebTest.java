package net.java21.data2flow.core.calendar.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.calendar.domain.CalendarErrorCode;
import net.java21.data2flow.core.calendar.dto.CalendarDtos.CalendarEventResponse;
import net.java21.data2flow.core.calendar.dto.CalendarDtos.ModeView;
import net.java21.data2flow.core.calendar.service.CalendarEventService;
import net.java21.data2flow.core.calendar.service.SpaceModeService;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.config.WebConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API-DEV-100~102·API-DEV-08 POST 응답 형식·오류 코드·문구 — TC-DEV-288·294 */
@WebMvcTest(CalendarController.class)
@Import({WebConfig.class, AccountGateInterceptor.class})
class CalendarControllerWebTest {

    static final CalendarEventResponse EVENT = new CalendarEventResponse("5", "개천절", "HOLIDAY", LocalDate.of(2026, 10, 3),
            LocalDate.of(2026, 10, 3), null, null, List.of(), "MANUAL", "HOLIDAY", null, false, false, 0, Instant.parse("2026-10-03T00:00:00Z"));

    @Autowired
    MockMvc mvc;
    @MockitoBean
    CalendarEventService events;
    @MockitoBean
    SpaceModeService modes;
    @MockitoBean
    UserRepository users;

    @Test
    @DisplayName("[DEV-12.01][API-DEV-100][TC-DEV-288·294] 등록 201 + Location, 위반은 400 CALENDAR_EVENT_INVALID(errors, 4개 언어)")
    void create() throws Exception {
        given(events.create(any())).willReturn(EVENT);
        mvc.perform(post("/core/calendar-events").header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"개천절\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/core/calendar-events/5"))
                .andExpect(jsonPath("$.response.startsOn").value("2026-10-03")).andExpect(jsonPath("$.response.scopeSpaceIds").isArray());
        given(events.create(any())).willThrow(new BusinessException(CalendarErrorCode.CALENDAR_EVENT_INVALID,
                List.of(new FieldErrorDetail("endsOn", "BEFORE_START", null))));
        mvc.perform(post("/core/calendar-events").header("X-USER-ID", "1").header("X-ORG-ID", "1").header(HttpHeaders.ACCEPT_LANGUAGE, "zh")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("CALENDAR_EVENT_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value("日程不正确(开始日期晚于结束日期或期间过长)"))
                .andExpect(jsonPath("$.errors[0].field").value("endsOn"));
    }

    @Test
    @DisplayName("[DEV-12.01][API-DEV-101·102][TC-DEV-294] 목록(page·size·totalCount), 수정 200, 삭제 204")
    void listUpdateDelete() throws Exception {
        given(events.list("2026-10-01", "2026-10-31", null, null, null)).willReturn(ListApiResponse.of(PageParams.of(1, 20), List.of(EVENT), 1));
        mvc.perform(get("/core/calendar-events").param("from", "2026-10-01").param("to", "2026-10-31").header("X-USER-ID", "1").header("X-ORG-ID", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].origin").value("MANUAL"));
        given(events.update(eq(5L), any())).willReturn(EVENT);
        given(events.get(5L)).willReturn(EVENT);
        mvc.perform(patch("/core/calendar-events/5").header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseVersion\":0}")).andExpect(status().isOk());
        mvc.perform(get("/core/calendar-events/5").header("X-USER-ID", "1").header("X-ORG-ID", "1")).andExpect(status().isOk());
        mvc.perform(delete("/core/calendar-events/5").header("X-USER-ID", "1").header("X-ORG-ID", "1")).andExpect(status().isNoContent());
        verify(events).delete(5L);
    }

    @Test
    @DisplayName("[DEV-11.02][API-DEV-08][TC-DEV-288] 수동 지정: 200 {mode, source, until, nextChangeAt}")
    void override() throws Exception {
        given(modes.override(anyLong(), any())).willReturn(new ModeView("OCCUPIED", "OVERRIDE", null, null));
        mvc.perform(post("/core/spaces/3/override-mode").header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"OCCUPIED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.source").value("OVERRIDE"));
    }
}
