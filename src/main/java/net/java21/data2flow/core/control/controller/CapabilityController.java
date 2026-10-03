package net.java21.data2flow.core.control.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.control.dto.ControlDtos.CapabilityResponse;
import net.java21.data2flow.core.control.dto.ControlDtos.CapabilitySummary;
import net.java21.data2flow.core.control.service.CapabilityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/** 기능 카탈로그(ACT-01.01·01.02, API-ACT-25). 조회 DEV_READ, 추가·수정 CAPABILITY_MANAGE */
@RestController
public class CapabilityController {

    private final CapabilityService service;

    public CapabilityController(CapabilityService service) {
        this.service = service;
    }

    @GetMapping("/core/capabilities")
    public ListApiResponse<CapabilitySummary> list(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.list(page, size);
    }

    @GetMapping("/core/capabilities/{capability-name}")
    public ApiResponse<CapabilityResponse> get(@PathVariable("capability-name") String name) {
        return ApiResponse.success(service.get(name));
    }

    @PostMapping("/core/capabilities")
    public ResponseEntity<ApiResponse<CapabilityResponse>> create(@RequestBody JsonNode body) {
        CapabilityResponse created = service.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/capabilities/" + created.name())).body(ApiResponse.success(created));
    }

    @PutMapping("/core/capabilities/{capability-name}")
    public ApiResponse<CapabilityResponse> update(@PathVariable("capability-name") String name, @RequestBody JsonNode body) {
        return ApiResponse.success(service.update(name, body));
    }
}
