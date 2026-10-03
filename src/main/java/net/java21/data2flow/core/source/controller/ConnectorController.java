package net.java21.data2flow.core.source.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.CatalogResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.ConnectorSchemaResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.PlatformBrokerResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.TemplateResponse;
import net.java21.data2flow.core.source.service.ConnectorCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 커넥터 카탈로그(DSC-09.01, API-DSC-55·56)와 플랫폼 브로커 안내(API-DSC-23) — SRC_READ */
@RestController
public class ConnectorController {

    private final ConnectorCatalogService service;

    public ConnectorController(ConnectorCatalogService service) {
        this.service = service;
    }

    /** API-DSC-55 커넥터 목록 + 템플릿 — SRC_READ, 200 */
    @GetMapping("/core/connectors")
    public ApiResponse<CatalogResponse> list(@RequestParam(required = false) String category, @RequestParam(required = false) String q) {
        return ApiResponse.success(service.list(category, q));
    }

    /** API-DSC-56 커넥터 설정 스키마 — SRC_READ, 200 / 404 CONNECTOR_NOT_FOUND */
    @GetMapping("/core/connectors/{connector-key}/schema")
    public ApiResponse<ConnectorSchemaResponse> schema(@PathVariable("connector-key") String key) {
        return ApiResponse.success(service.schema(key));
    }

    /** API-DSC-56 커넥터 템플릿 preset — SRC_READ, 200 / 404 CONNECTOR_NOT_FOUND */
    @GetMapping("/core/connector-templates/{template-key}")
    public ApiResponse<TemplateResponse> template(@PathVariable("template-key") String key) {
        return ApiResponse.success(service.template(key));
    }

    /** API-DSC-23 플랫폼 브로커 정보 — SRC_READ, 200 */
    @GetMapping("/core/platform-broker")
    public ApiResponse<PlatformBrokerResponse> platformBroker() {
        return ApiResponse.success(service.platformBroker());
    }
}
