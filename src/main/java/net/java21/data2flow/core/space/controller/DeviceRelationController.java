package net.java21.data2flow.core.space.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.RelationsRequest;
import net.java21.data2flow.core.space.dto.SpaceDtos.RelationsResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.SpaceDeviceResponse;
import net.java21.data2flow.core.space.service.DeviceRelationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 공간–기기 관계(DEV-01.05): 저장 DEV_ADMIN, 조회 DEV_READ */
@RestController
public class DeviceRelationController {

    private final DeviceRelationService service;

    public DeviceRelationController(DeviceRelationService service) {
        this.service = service;
    }

    /** 기기의 유효 관계 조회(API-DEV-14 응답과 같은 모양) — 200 */
    @GetMapping("/core/devices/{device-id}/relations")
    public ApiResponse<RelationsResponse> relations(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(service.relations(deviceId));
    }

    /** API-DEV-14 추가 관계 전체 교체 — 200 */
    @PutMapping("/core/devices/{device-id}/relations")
    public ApiResponse<RelationsResponse> replace(@PathVariable("device-id") long deviceId, @RequestBody RelationsRequest request) {
        return ApiResponse.success(service.replaceRelations(deviceId, request));
    }

    /** API-DEV-18 공간과 관계가 있는 기기 — 200 목록 */
    @GetMapping("/core/spaces/{space-id}/devices")
    public ListApiResponse<SpaceDeviceResponse> spaceDevices(@PathVariable("space-id") long spaceId,
                                                             @RequestParam(required = false) String relation,
                                                             @RequestParam(required = false) String capability,
                                                             @RequestParam(required = false) Boolean includeDescendants,
                                                             @RequestParam(required = false) Integer page,
                                                             @RequestParam(required = false) Integer size) {
        return service.spaceDevices(spaceId, relation, capability, includeDescendants, page, size);
    }
}
