package net.java21.data2flow.core.catalog.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.InternalMetricsResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.RegisterUnverifiedRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.RegisterUnverifiedResponse;
import net.java21.data2flow.core.catalog.service.MetricInternalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** pipeline·analytics가 부르는 측정 항목 내부 API(API-DEV-123·124). 토큰 없음, 호출자 표시 X-CALLER-SERVICE(ADR-021) */
@RestController
public class InternalMetricController {

    private final MetricInternalService service;

    public InternalMetricController(MetricInternalService service) {
        this.service = service;
    }

    /** API-DEV-123 측정 항목·별칭 전체 — 200, sinceVersion과 같으면 204 */
    @GetMapping("/internal/core/metrics")
    public ResponseEntity<ApiResponse<InternalMetricsResponse>> metrics(@RequestParam(required = false) Long organizationId,
                                                                        @RequestParam(required = false) Long sinceVersion) {
        return service.snapshot(organizationId, sinceVersion)
                .map(r -> ResponseEntity.ok(ApiResponse.success(r)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** API-DEV-124 처음 보는 측정 키 등록(멱등) — 200 */
    @PostMapping("/internal/core/metrics/register-unverified")
    public ApiResponse<RegisterUnverifiedResponse> registerUnverified(@Valid @RequestBody RegisterUnverifiedRequest request) {
        return ApiResponse.success(service.registerUnverified(request));
    }
}
