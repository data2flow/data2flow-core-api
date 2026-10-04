package net.java21.data2flow.core.control.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.common.ItemsResponse;
import net.java21.data2flow.core.control.dto.SafetyDtos;
import net.java21.data2flow.core.control.service.ActionClient;
import net.java21.data2flow.core.control.service.SafetyService;
import net.java21.data2flow.core.control.service.SceneService;
import net.java21.data2flow.core.control.service.ScheduleService;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/** 일괄 제어·장면·예약·인터락·비상 정지·가동 현황(API-ACT-05·10~21·35, M4) */
@RestController
public class AutomationControlController {

    private final SceneService scenes;
    private final ScheduleService schedules;
    private final SafetyService safety;
    private final ActionClient action;
    private final RoleChecker roleChecker;

    public AutomationControlController(SceneService scenes, ScheduleService schedules, SafetyService safety, ActionClient action,
                                       RoleChecker roleChecker) {
        this.scenes = scenes;
        this.schedules = schedules;
        this.safety = safety;
        this.action = action;
        this.roleChecker = roleChecker;
    }

    // ------------------------------------------------------------------ 일괄(API-ACT-05)

    @PostMapping("/core/commands/bulk")
    public ResponseEntity<ApiResponse<JsonNode>> bulk(@RequestBody JsonNode body,
                                                      @RequestHeader(value = DataflowHeaders.IDEMPOTENCY_KEY, required = false) String key) {
        return relay(scenes.bulk(body, key));
    }

    @GetMapping("/core/command-bulk-jobs/{bulk-job-id}")
    public ApiResponse<JsonNode> bulkJob(@PathVariable("bulk-job-id") String jobId) {
        return ApiResponse.success(scenes.bulkJob(jobId));
    }

    // ------------------------------------------------------------------ 장면(API-ACT-10~12)

    @GetMapping("/core/scenes")
    public ItemsResponse<SafetyDtos.Scene> scenes() {
        return ItemsResponse.of(scenes.list());
    }

    @PostMapping("/core/scenes")
    @Idempotent
    public ResponseEntity<ApiResponse<SafetyDtos.Scene>> createScene(@RequestBody JsonNode body) {
        SafetyDtos.Scene s = scenes.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/scenes/" + s.sceneId())).body(ApiResponse.success(s));
    }

    @GetMapping("/core/scenes/{scene-id}")
    public ApiResponse<SafetyDtos.Scene> scene(@PathVariable("scene-id") long id) {
        return ApiResponse.success(scenes.get(id));
    }

    @PutMapping("/core/scenes/{scene-id}")
    public ApiResponse<SafetyDtos.Scene> updateScene(@PathVariable("scene-id") long id, @RequestBody JsonNode body) {
        return ApiResponse.success(scenes.update(id, body));
    }

    @DeleteMapping("/core/scenes/{scene-id}")
    public ResponseEntity<Void> deleteScene(@PathVariable("scene-id") long id) {
        scenes.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/core/scenes/{scene-id}/run")
    public ResponseEntity<ApiResponse<JsonNode>> runScene(@PathVariable("scene-id") long id,
                                                          @RequestHeader(value = DataflowHeaders.IDEMPOTENCY_KEY, required = false) String key) {
        return relay(scenes.run(id, key));
    }

    @PostMapping("/core/scenes/{scene-id}/preview")
    public ApiResponse<JsonNode> preview(@PathVariable("scene-id") long id) {
        return ApiResponse.success(scenes.preview(id));
    }

    @GetMapping("/core/scene-runs/{scene-run-id}")
    public ApiResponse<JsonNode> sceneRun(@PathVariable("scene-run-id") String runId) {
        return ApiResponse.success(scenes.sceneRun(runId));
    }

    // ------------------------------------------------------------------ 예약(API-ACT-15)

    @GetMapping("/core/control-schedules")
    public ItemsResponse<SafetyDtos.Schedule> schedules() {
        return ItemsResponse.of(schedules.list());
    }

    @PostMapping("/core/control-schedules")
    @Idempotent
    public ResponseEntity<ApiResponse<SafetyDtos.Schedule>> createSchedule(@RequestBody JsonNode body) {
        SafetyDtos.Schedule s = schedules.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/control-schedules/" + s.id())).body(ApiResponse.success(s));
    }

    @PutMapping("/core/control-schedules/{control-schedule-id}")
    public ApiResponse<SafetyDtos.Schedule> updateSchedule(@PathVariable("control-schedule-id") long id, @RequestBody JsonNode body) {
        return ApiResponse.success(schedules.update(id, body));
    }

    @DeleteMapping("/core/control-schedules/{control-schedule-id}")
    public ResponseEntity<Void> deleteSchedule(@PathVariable("control-schedule-id") long id) {
        schedules.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/core/control-schedules/{control-schedule-id}/enable")
    public ApiResponse<SafetyDtos.Schedule> enable(@PathVariable("control-schedule-id") long id) {
        return ApiResponse.success(schedules.enable(id, true));
    }

    @PostMapping("/core/control-schedules/{control-schedule-id}/disable")
    public ApiResponse<SafetyDtos.Schedule> disable(@PathVariable("control-schedule-id") long id) {
        return ApiResponse.success(schedules.enable(id, false));
    }

    // ------------------------------------------------------------------ 인터락(API-ACT-16)

    @GetMapping("/core/interlocks")
    public ItemsResponse<SafetyDtos.Interlock> interlocks() {
        return ItemsResponse.of(safety.interlocks());
    }

    @PostMapping("/core/interlocks")
    @Idempotent
    public ResponseEntity<ApiResponse<SafetyDtos.Interlock>> createInterlock(@RequestBody JsonNode body) {
        SafetyDtos.Interlock i = safety.createInterlock(body);
        return ResponseEntity.created(URI.create("/api/v1/core/interlocks/" + i.interlockId())).body(ApiResponse.success(i));
    }

    @GetMapping("/core/interlocks/{interlock-id}")
    public ApiResponse<SafetyDtos.Interlock> interlock(@PathVariable("interlock-id") long id) {
        return ApiResponse.success(safety.interlock(id));
    }

    @PutMapping("/core/interlocks/{interlock-id}")
    public ApiResponse<SafetyDtos.Interlock> updateInterlock(@PathVariable("interlock-id") long id, @RequestBody JsonNode body) {
        return ApiResponse.success(safety.updateInterlock(id, body));
    }

    @DeleteMapping("/core/interlocks/{interlock-id}")
    public ResponseEntity<Void> deleteInterlock(@PathVariable("interlock-id") long id) {
        safety.deleteInterlock(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/core/interlocks/{interlock-id}/blocks")
    public ApiResponse<JsonNode> blocks(@PathVariable("interlock-id") long id, @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        safety.interlock(id);
        return ApiResponse.success(action.interlockBlocks(id, from, to));
    }

    // ------------------------------------------------------------------ 비상 정지(API-ACT-20·21)

    @PostMapping("/core/emergency-stops")
    public ResponseEntity<ApiResponse<SafetyDtos.EmergencyStop>> stop(@RequestBody JsonNode body) {
        SafetyDtos.EmergencyStop s = safety.start(body);
        return ResponseEntity.created(URI.create("/api/v1/core/emergency-stops/" + s.id())).body(ApiResponse.success(s));
    }

    @PostMapping("/core/emergency-stops/{emergency-stop-id}/release")
    public ApiResponse<SafetyDtos.EmergencyStop> release(@PathVariable("emergency-stop-id") long id, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(safety.release(id, body));
    }

    @GetMapping("/core/emergency-stops")
    public ItemsResponse<SafetyDtos.EmergencyStop> stops(@RequestParam(required = false) Boolean active) {
        return ItemsResponse.of(safety.stops(active));
    }

    // ------------------------------------------------------------------ 가동·효과(API-ACT-35)

    @GetMapping("/core/devices/{device-id}/runtime")
    public ApiResponse<JsonNode> runtime(@PathVariable("device-id") long deviceId, @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to) {
        roleChecker.require(Permission.DEV_READ);
        return ApiResponse.success(action.runtime(deviceId, from, to));
    }

    private static ResponseEntity<ApiResponse<JsonNode>> relay(InternalHttp.Result r) {
        return ResponseEntity.status(r.status()).body(ApiResponse.success(r.response()));
    }
}
