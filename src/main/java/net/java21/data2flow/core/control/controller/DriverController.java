package net.java21.data2flow.core.control.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.control.dto.ControlDtos.DriverResponse;
import net.java21.data2flow.core.control.dto.ControlDtos.DriverSummary;
import net.java21.data2flow.core.control.dto.ControlDtos.ModelDriverResponse;
import net.java21.data2flow.core.control.service.DriverService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/** 드라이버(ACT-03, API-ACT-30~32)와 모델 연결(DEV-03.03, API-ACT-31). DRIVER_MANAGE */
@RestController
public class DriverController {

    private final DriverService service;

    public DriverController(DriverService service) {
        this.service = service;
    }

    @GetMapping("/core/drivers")
    public ListApiResponse<DriverSummary> list(@RequestParam(required = false) String type, @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return service.list(type, page, size);
    }

    @PostMapping("/core/drivers")
    @Idempotent
    public ResponseEntity<ApiResponse<DriverResponse>> create(@RequestBody JsonNode body) {
        DriverResponse created = service.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/drivers/" + created.driverId())).body(ApiResponse.success(created));
    }

    @GetMapping("/core/drivers/{driver-id}")
    public ApiResponse<DriverResponse> get(@PathVariable("driver-id") long driverId) {
        return ApiResponse.success(service.get(driverId));
    }

    @PutMapping("/core/drivers/{driver-id}")
    public ApiResponse<DriverResponse> update(@PathVariable("driver-id") long driverId, @RequestBody JsonNode body) {
        return ApiResponse.success(service.update(driverId, body));
    }

    @DeleteMapping("/core/drivers/{driver-id}")
    public ResponseEntity<Void> delete(@PathVariable("driver-id") long driverId) {
        service.delete(driverId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/core/drivers/{driver-id}/healthcheck")
    public ApiResponse<JsonNode> healthcheck(@PathVariable("driver-id") long driverId) {
        return ApiResponse.success(service.healthcheck(driverId));
    }

    @GetMapping("/core/drivers/{driver-id}/metrics")
    public ApiResponse<JsonNode> metrics(@PathVariable("driver-id") long driverId, @RequestParam(required = false) String window) {
        return ApiResponse.success(service.metrics(driverId, window));
    }

    /** DEV-03.03 모델에 드라이버 연결 {driverId|null, encoderScriptRef?} — 200 */
    @PutMapping("/core/device-models/{model-id}/driver")
    public ApiResponse<ModelDriverResponse> linkModel(@PathVariable("model-id") long modelId, @RequestBody JsonNode body) {
        return ApiResponse.success(service.linkModel(modelId, body));
    }
}
