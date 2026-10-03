package net.java21.data2flow.core.gateway.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.gateway.dto.GatewayDtos.GatewayResponse;
import net.java21.data2flow.core.gateway.dto.GatewayDtos.TouchRequest;
import net.java21.data2flow.core.gateway.service.GatewayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** LoRa 게이트웨이(design/api/DEV-api.md §6 API-DEV-60·62, 내부 API-DEV-125, DEV-05.01) */
@RestController
public class GatewayController {

    private final GatewayService service;

    public GatewayController(GatewayService service) {
        this.service = service;
    }

    /** API-DEV-60 — DEV_READ, 200 */
    @GetMapping("/core/gateways")
    public ListApiResponse<GatewayResponse> list(@RequestParam(required = false) String sourceId,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String spaceId,
                                                 @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.list(sourceId, status, spaceId, page, size);
    }

    /** 게이트웨이 상세 — DEV_READ, 200 */
    @GetMapping("/core/gateways/{gateway-id}")
    public ApiResponse<GatewayResponse> get(@PathVariable("gateway-id") long gatewayId) {
        return ApiResponse.success(service.get(gatewayId));
    }

    /** API-DEV-62 — DEV_ADMIN, 200 */
    @PatchMapping("/core/gateways/{gateway-id}")
    public ApiResponse<GatewayResponse> update(@PathVariable("gateway-id") long gatewayId, @RequestBody JsonNode body) {
        return ApiResponse.success(service.update(gatewayId, body));
    }

    /** API-DEV-125 업링크 게이트웨이 기록(pipeline, 1분 묶음) — 내부, 204 */
    @PostMapping("/internal/core/gateways/touch")
    public ResponseEntity<Void> touch(@Valid @RequestBody TouchRequest request) {
        service.touch(request);
        return ResponseEntity.noContent().build();
    }
}
