package net.java21.data2flow.core.space.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.SemanticDocument;
import net.java21.data2flow.core.space.service.SemanticService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 시맨틱 계층(DEV-13.01, API-DEV-131): 조회 DEV_READ, 수정·모델 다시 적용 DEV_ADMIN */
@RestController
public class SemanticController {

    private final SemanticService service;

    public SemanticController(SemanticService service) {
        this.service = service;
    }

    @GetMapping("/core/devices/{device-id}/semantic")
    public ApiResponse<SemanticDocument> get(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(service.get(deviceId));
    }

    @PutMapping("/core/devices/{device-id}/semantic")
    public ApiResponse<SemanticDocument> replace(@PathVariable("device-id") long deviceId, @RequestBody SemanticDocument request) {
        return ApiResponse.success(service.replace(deviceId, request));
    }

    @PostMapping("/core/devices/{device-id}/semantic/reapply-model")
    @Idempotent
    public ApiResponse<SemanticDocument> reapply(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(service.reapplyModel(deviceId));
    }
}
