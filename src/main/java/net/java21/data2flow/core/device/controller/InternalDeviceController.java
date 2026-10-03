package net.java21.data2flow.core.device.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.device.dto.DiscoveryDtos.AutoRegisterRequest;
import net.java21.data2flow.core.device.dto.DiscoveryDtos.AutoRegisterResponse;
import net.java21.data2flow.core.device.dto.DiscoveryDtos.DeviceLookupResponse;
import net.java21.data2flow.core.device.dto.InternalDeviceDtos.ChangedDevice;
import net.java21.data2flow.core.device.dto.InternalDeviceDtos.DeviceRuntimeResponse;
import net.java21.data2flow.core.device.service.DeviceDiscoveryService;
import net.java21.data2flow.core.device.service.InternalDeviceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 기기 내부 API(design/api/DEV-api.md §12: API-DEV-120·121·122·130). 호출자 pipeline·flow-engine, 토큰 없음(ADR-021) */
@RestController
public class InternalDeviceController {

    private final DeviceDiscoveryService discovery;
    private final InternalDeviceService internal;

    public InternalDeviceController(DeviceDiscoveryService discovery, InternalDeviceService internal) {
        this.discovery = discovery;
        this.internal = internal;
    }

    /** API-DEV-120 (소스, 외부 ID) 식별(ING-03.01) — 내부, 200 / 404 DEVICE_NOT_FOUND */
    @GetMapping("/internal/core/sources/{source-id}/devices/{external-id}")
    public ApiResponse<DeviceLookupResponse> lookup(@PathVariable("source-id") long sourceId,
                                                    @PathVariable("external-id") String externalId) {
        return ApiResponse.success(discovery.lookup(sourceId, externalId));
    }

    /** API-DEV-121 자동 등록(ING-03.02, ING-07.02, ADR-031) — 내부, 200 / 409 DEVICE_REJECTED / 429 DEVICE_AUTOREG_LIMIT */
    @PostMapping("/internal/core/devices/auto-register")
    public ApiResponse<AutoRegisterResponse> autoRegister(@Valid @RequestBody AutoRegisterRequest request) {
        return ApiResponse.success(discovery.autoRegister(request));
    }

    /** API-DEV-122 처리용 기기 정보 — 내부, 200 */
    @GetMapping("/internal/core/devices/{device-id}/runtime")
    public ApiResponse<DeviceRuntimeResponse> runtime(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(internal.runtime(deviceId));
    }

    /** API-DEV-130 변경분 페이지(pipeline 캐시 예열) — 내부, 200 */
    @GetMapping("/internal/core/devices")
    public ListApiResponse<ChangedDevice> changed(@RequestParam(required = false) List<String> status,
                                                  @RequestParam(required = false) String updatedAfter,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        return internal.changed(status, updatedAfter, page, size);
    }
}
