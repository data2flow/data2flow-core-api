package net.java21.data2flow.core.device.controller;

import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.device.service.DeviceHistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** API-DEV-27 기기 변경 이력(DEV-02.07, M4) */
@RestController
public class DeviceHistoryController {

    private final DeviceHistoryService service;

    public DeviceHistoryController(DeviceHistoryService service) {
        this.service = service;
    }

    @GetMapping("/core/devices/{device-id}/history")
    public ListApiResponse<Map<String, Object>> history(@PathVariable("device-id") long deviceId, @RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size) {
        return service.list(deviceId, page, size);
    }
}
