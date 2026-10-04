package net.java21.data2flow.core.calendar.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.calendar.dto.CalendarDtos.CalendarEventResponse;
import net.java21.data2flow.core.calendar.dto.CalendarDtos.ModeView;
import net.java21.data2flow.core.calendar.service.CalendarEventService;
import net.java21.data2flow.core.calendar.service.SpaceModeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/** 조직 달력(design/api/DEV-api.md §10 API-DEV-100~102)과 운영 모드 수동 지정(API-DEV-08 POST), DEV-11.02·12.01 */
@RestController
public class CalendarController {

    private final CalendarEventService events;
    private final SpaceModeService modes;

    public CalendarController(CalendarEventService events, SpaceModeService modes) {
        this.events = events;
        this.modes = modes;
    }

    /** API-DEV-101 — DEV_READ */
    @GetMapping("/core/calendar-events")
    public ListApiResponse<CalendarEventResponse> list(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                       @RequestParam(required = false) String spaceId,
                                                       @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return events.list(from, to, spaceId, page, size);
    }

    @GetMapping("/core/calendar-events/{calendar-event-id}")
    public ApiResponse<CalendarEventResponse> get(@PathVariable("calendar-event-id") long id) {
        return ApiResponse.success(events.get(id));
    }

    /** API-DEV-100 — DEV_PLACE, 201 */
    @PostMapping("/core/calendar-events")
    public ResponseEntity<ApiResponse<CalendarEventResponse>> create(@RequestBody JsonNode body) {
        CalendarEventResponse created = events.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/calendar-events/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-DEV-102 PATCH — DEV_PLACE */
    @PatchMapping("/core/calendar-events/{calendar-event-id}")
    public ApiResponse<CalendarEventResponse> update(@PathVariable("calendar-event-id") long id, @RequestBody JsonNode body) {
        return ApiResponse.success(events.update(id, body));
    }

    /** API-DEV-102 DELETE — DEV_PLACE, 204 */
    @DeleteMapping("/core/calendar-events/{calendar-event-id}")
    public ResponseEntity<Void> delete(@PathVariable("calendar-event-id") long id) {
        events.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** API-DEV-08 POST 수동 지정(mode=null이면 해제) — DEV_PLACE */
    @PostMapping("/core/spaces/{space-id}/override-mode")
    public ApiResponse<ModeView> override(@PathVariable("space-id") long spaceId, @RequestBody JsonNode body) {
        return ApiResponse.success(modes.override(spaceId, body));
    }
}
