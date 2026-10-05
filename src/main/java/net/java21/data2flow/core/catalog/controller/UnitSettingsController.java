package net.java21.data2flow.core.catalog.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.catalog.service.UnitSettingsService;
import net.java21.data2flow.core.catalog.service.UnitSettingsService.UnitSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 조직 기본 단위(design/api/DEV-api.md API-DEV-57, DEV-04.04). 사용자별 단위는 API-DSH-12 temperatureUnit */
@RestController
public class UnitSettingsController {

    private final UnitSettingsService service;

    public UnitSettingsController(UnitSettingsService service) {
        this.service = service;
    }

    @GetMapping("/core/settings/units")
    public ApiResponse<UnitSettings> get() {
        return ApiResponse.success(service.get());
    }

    /** API-DEV-57 {temperatureUnit: C|F, baseVersion} — ADMIN(OPS_MANAGE) */
    @PutMapping("/core/settings/units")
    public ApiResponse<UnitSettings> put(@RequestBody JsonNode body) {
        return ApiResponse.success(service.put(body));
    }
}
