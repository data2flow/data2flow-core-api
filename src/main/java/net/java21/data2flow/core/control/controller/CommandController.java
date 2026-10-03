package net.java21.data2flow.core.control.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.contracts.web.ApiHeader;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.CursorListApiResponse;
import net.java21.data2flow.core.common.InternalHttp.Result;
import net.java21.data2flow.core.control.service.CommandService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

/** 수동 제어·명령 조회(ACT-02.01·04.03, API-ACT-01~04·06). 명령은 Idempotency-Key 필수(24시간) */
@RestController
public class CommandController {

    private final CommandService service;

    public CommandController(CommandService service) {
        this.service = service;
    }

    /** API-ACT-01 — DEVICE_CONTROL. action 응답 상태(202 접수 / 200 wait 결과)를 그대로 */
    @PostMapping("/core/devices/{device-id}/commands")
    @Idempotent(required = true)
    public ResponseEntity<ApiResponse<JsonNode>> send(@PathVariable("device-id") long deviceId, @RequestBody JsonNode body,
                                                      @RequestHeader(DataflowHeaders.IDEMPOTENCY_KEY) String idempotencyKey) {
        Result result = service.send(deviceId, body, idempotencyKey);
        return ResponseEntity.status(result.status() == 200 ? 200 : 202)
                .body(new ApiResponse<>(ApiHeader.success(), result.response()));
    }

    @GetMapping("/core/devices/{device-id}/commands")
    public JsonNode deviceHistory(@PathVariable("device-id") long deviceId,
                                                                    @RequestParam(required = false) Instant from,
                                                                    @RequestParam(required = false) Instant to,
                                                                    @RequestParam(required = false) String sourceType,
                                                                    @RequestParam(required = false) String status,
                                                                    @RequestParam(required = false) String capability,
                                                                    @RequestParam(required = false) String cursor,
                                                                    @RequestParam(required = false) Integer size) {
        return service.deviceHistory(deviceId, from, to, sourceType, status, capability, cursor, size);
    }

    @GetMapping("/core/commands")
    public CursorListApiResponse<Map<String, Object>> history(@RequestParam(required = false) Long spaceId,
                                                              @RequestParam(required = false) Instant from,
                                                              @RequestParam(required = false) Instant to,
                                                              @RequestParam(required = false) String sourceType,
                                                              @RequestParam(required = false) String status,
                                                              @RequestParam(required = false) String capability,
                                                              @RequestParam(required = false) String cursor,
                                                              @RequestParam(required = false) Integer size) {
        return service.history(spaceId, from, to, sourceType, status, capability, cursor, size);
    }

    @GetMapping("/core/commands/{command-id}")
    public ApiResponse<JsonNode> get(@PathVariable("command-id") String commandId) {
        return ApiResponse.success(service.get(commandId));
    }

    @PostMapping("/core/commands/{command-id}/cancel")
    public ApiResponse<JsonNode> cancel(@PathVariable("command-id") String commandId) {
        return ApiResponse.success(service.cancel(commandId));
    }

    @GetMapping("/core/devices/{device-id}/control")
    public ApiResponse<JsonNode> control(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(service.control(deviceId));
    }

    @GetMapping("/core/devices/{device-id}/shadow")
    public ApiResponse<JsonNode> shadow(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(service.shadow(deviceId));
    }

    @DeleteMapping("/core/devices/{device-id}/manual-override")
    public ResponseEntity<Void> releaseManualOverride(@PathVariable("device-id") long deviceId,
                                                      @RequestParam(required = false) String capability) {
        service.releaseManualOverride(deviceId, capability);
        return ResponseEntity.noContent().build();
    }
}
