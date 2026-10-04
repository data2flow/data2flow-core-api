package net.java21.data2flow.core.maintenance.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.common.InternalOrganizations;
import net.java21.data2flow.core.common.ItemsResponse;
import net.java21.data2flow.core.maintenance.dto.MaintenanceDtos.ActiveWindow;
import net.java21.data2flow.core.maintenance.dto.MaintenanceDtos.Created;
import net.java21.data2flow.core.maintenance.dto.MaintenanceDtos.Window;
import net.java21.data2flow.core.maintenance.service.MaintenanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/** 유지보수(OPS-05, API-OPS-20~24) */
@RestController
public class MaintenanceController {

    private final MaintenanceService service;
    private final InternalOrganizations organizations;

    public MaintenanceController(MaintenanceService service, InternalOrganizations organizations) {
        this.service = service;
        this.organizations = organizations;
    }

    @PostMapping("/core/maintenance-windows")
    @Idempotent
    public ResponseEntity<ApiResponse<Created>> create(@RequestBody JsonNode body) {
        Created c = service.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/maintenance-windows/" + c.id())).body(ApiResponse.success(c));
    }

    @PostMapping("/core/maintenance-windows/{maintenance-window-id}/end")
    public ResponseEntity<Void> end(@PathVariable("maintenance-window-id") long id) {
        service.end(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/core/maintenance-windows/{maintenance-window-id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable("maintenance-window-id") long id) {
        service.cancel(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/core/maintenance-windows")
    public ListApiResponse<Window> list(@RequestParam(required = false) String status, @RequestParam(required = false) String targetId,
                                        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.list(status, targetId, page, size);
    }

    /** API-OPS-24 내부(flow-engine·action·analytics): 지금 ACTIVE인 구간 */
    @GetMapping("/internal/core/maintenance-windows")
    public ItemsResponse<ActiveWindow> active(@RequestParam(required = false) Long organizationId) {
        return ItemsResponse.of(service.active(organizations.resolve(organizationId)));
    }
}
