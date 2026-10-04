package net.java21.data2flow.core.storage.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.storage.service.StorageMetricsService;
import net.java21.data2flow.core.storage.service.StorageMetricsService.StorageMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 저장 지표(design/api/OPS-api.md API-OPS-03, OPS-01.03) */
@RestController
public class StorageMetricsController {

    private final StorageMetricsService service;

    public StorageMetricsController(StorageMetricsService service) {
        this.service = service;
    }

    /** API-OPS-03 — OPS_MANAGE */
    @GetMapping("/core/ops/metrics/storage")
    public ApiResponse<StorageMetrics> storage() {
        return ApiResponse.success(service.metrics());
    }
}
