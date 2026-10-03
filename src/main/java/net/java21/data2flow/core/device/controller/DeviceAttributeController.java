package net.java21.data2flow.core.device.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.device.dto.AttributeDtos.AttributeHistoryResponse;
import net.java21.data2flow.core.device.dto.AttributeDtos.AttributeResponse;
import net.java21.data2flow.core.device.dto.AttributeDtos.AttributeValueRequest;
import net.java21.data2flow.core.device.dto.AttributeDtos.AttributesResponse;
import net.java21.data2flow.core.device.service.DeviceAttributeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 기기 속성(design/api/DEV-api.md §8: API-DEV-80~83, DEV-07.01·07.04) */
@RestController
public class DeviceAttributeController {

    private final DeviceAttributeService service;

    public DeviceAttributeController(DeviceAttributeService service) {
        this.service = service;
    }

    /** API-DEV-83 — DEV_READ, 200 */
    @GetMapping("/core/devices/{device-id}/attributes")
    public ApiResponse<AttributesResponse> list(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(service.list(deviceId));
    }

    /** API-DEV-82 변경 이력(최신순) — DEV_READ, 200 */
    @GetMapping("/core/devices/{device-id}/attributes/history")
    public ListApiResponse<AttributeHistoryResponse> history(@PathVariable("device-id") long deviceId,
                                                             @RequestParam(required = false) String key,
                                                             @RequestParam(required = false) Integer page,
                                                             @RequestParam(required = false) Integer size) {
        return service.history(deviceId, key, page, size);
    }

    /** API-DEV-80 — SERVER: DEV_ADMIN, SHARED: DEV_ADMIN + DEVICE_CONTROL, CLIENT: 403 ATTRIBUTE_READONLY, 200 */
    @PutMapping("/core/devices/{device-id}/attributes/{scope}/{key}")
    public ApiResponse<AttributeResponse> put(@PathVariable("device-id") long deviceId, @PathVariable("scope") String scope,
                                              @PathVariable("key") String key, @RequestBody AttributeValueRequest request) {
        return ApiResponse.success(service.put(deviceId, scope, key, request.value()));
    }

    /** API-DEV-81 — 권한은 API-DEV-80과 같음, 204 */
    @DeleteMapping("/core/devices/{device-id}/attributes/{scope}/{key}")
    public ResponseEntity<Void> delete(@PathVariable("device-id") long deviceId, @PathVariable("scope") String scope,
                                       @PathVariable("key") String key) {
        service.delete(deviceId, scope, key);
        return ResponseEntity.noContent().build();
    }
}
