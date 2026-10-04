package net.java21.data2flow.core.alarm.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.alarm.dto.AlarmDtos;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.AlarmDetail;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.AlarmStats;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.AssigneeResult;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.BulkResult;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.HandleResult;
import net.java21.data2flow.core.alarm.dto.AlarmDtos.NoteResult;
import net.java21.data2flow.core.alarm.service.AlarmHandlingService;
import net.java21.data2flow.core.alarm.service.AlarmQueryService;
import net.java21.data2flow.core.common.CountedListResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;

/** 알람(RUL-02·06, API-RUL-10~13·20). 실시간 스트림(API-RUL-14)은 {@code LiveStreamController} */
@RestController
public class AlarmController {

    private final AlarmQueryService query;
    private final AlarmHandlingService handling;

    public AlarmController(AlarmQueryService query, AlarmHandlingService handling) {
        this.query = query;
        this.handling = handling;
    }

    @GetMapping("/core/alarms")
    public CountedListResponse<AlarmDtos.Alarm> list(@RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String severity,
                                                     @RequestParam(required = false) String spaceId,
                                                     @RequestParam(required = false) String ruleId,
                                                     @RequestParam(required = false) String sourceType,
                                                     @RequestParam(required = false) String deviceId,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size) {
        return query.list(new AlarmQueryService.Query(status, severity, spaceId, ruleId, sourceType, deviceId, from, to, page, size));
    }

    /** API-RUL-20(ID 자리와 겹치지 않게 {@code /alarms/stats}는 숫자 ID 경로보다 먼저 맞는다) */
    @GetMapping("/core/alarms/stats")
    public ApiResponse<AlarmStats> stats(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                         @RequestParam(required = false) String spaceId) {
        return ApiResponse.success(query.stats(from, to, spaceId));
    }

    @GetMapping("/core/alarms/{alarm-id:\\d+}")
    public ApiResponse<AlarmDetail> detail(@PathVariable("alarm-id") long alarmId) {
        return ApiResponse.success(query.detail(alarmId));
    }

    @PostMapping("/core/alarms/{alarm-id:\\d+}/ack")
    public ApiResponse<HandleResult> ack(@PathVariable("alarm-id") long alarmId) {
        return ApiResponse.success(handling.ack(alarmId));
    }

    @PostMapping("/core/alarms/{alarm-id:\\d+}/clear")
    public ApiResponse<HandleResult> clear(@PathVariable("alarm-id") long alarmId, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(handling.clear(alarmId, body));
    }

    @PostMapping("/core/alarms/bulk-ack")
    public ApiResponse<BulkResult> bulkAck(@RequestBody JsonNode body) {
        return ApiResponse.success(handling.bulkAck(body));
    }

    @PostMapping("/core/alarms/{alarm-id:\\d+}/notes")
    public ResponseEntity<ApiResponse<NoteResult>> note(@PathVariable("alarm-id") long alarmId, @RequestBody JsonNode body) {
        NoteResult note = handling.note(alarmId, body);
        return ResponseEntity.created(URI.create("/api/v1/core/alarms/" + alarmId)).body(ApiResponse.success(note));
    }

    @PutMapping("/core/alarms/{alarm-id:\\d+}/assignee")
    public ApiResponse<AssigneeResult> assign(@PathVariable("alarm-id") long alarmId, @RequestBody JsonNode body) {
        return ApiResponse.success(handling.assign(alarmId, body));
    }
}
