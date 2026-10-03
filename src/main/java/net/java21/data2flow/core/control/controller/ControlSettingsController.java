package net.java21.data2flow.core.control.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.control.dto.ControlDtos.ControlSettingsResponse;
import net.java21.data2flow.core.control.service.ControlSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 조직 제어 설정(API-ACT-17, ACT-06.04 절대 한계). CONTROL_SETTINGS */
@RestController
public class ControlSettingsController {

    private final ControlSettingsService service;

    public ControlSettingsController(ControlSettingsService service) {
        this.service = service;
    }

    @GetMapping("/core/settings/control")
    public ApiResponse<ControlSettingsResponse> get() {
        return ApiResponse.success(service.get());
    }

    @PutMapping("/core/settings/control")
    public ApiResponse<ControlSettingsResponse> put(@RequestBody JsonNode body) {
        return ApiResponse.success(service.put(body));
    }
}
