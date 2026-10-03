package net.java21.data2flow.core.catalog.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.CloneModelRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.CreateModelRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.ModelResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.ModelSummaryResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.PackageRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.PackageResponse;
import net.java21.data2flow.core.catalog.service.DeviceModelService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/** 기기 모델(DEV-03.01·03.02·07.05, API-DEV-40~43·46·47). 조회 DEV_READ, 쓰기 DEV_ADMIN */
@RestController
public class DeviceModelController {

    private final DeviceModelService service;

    public DeviceModelController(DeviceModelService service) {
        this.service = service;
    }

    /** API-DEV-46 목록 — 200 */
    @GetMapping("/core/device-models")
    public ListApiResponse<ModelSummaryResponse> list(@RequestParam(required = false) String q,
                                                      @RequestParam(required = false) String code,
                                                      @RequestParam(required = false) String protocol,
                                                      @RequestParam(required = false) String kind,
                                                      @RequestParam(required = false) Boolean includeDeprecated,
                                                      @RequestParam(required = false) Boolean builtin,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return service.list(q, code, protocol, kind, includeDeprecated, builtin, page, size);
    }

    /** API-DEV-46 상세 — 200 */
    @GetMapping("/core/device-models/{model-id}")
    public ApiResponse<ModelResponse> detail(@PathVariable("model-id") long modelId) {
        return ApiResponse.success(service.get(modelId));
    }

    /** API-DEV-40 생성 — 201 + Location */
    @PostMapping("/core/device-models")
    @Idempotent
    public ResponseEntity<ApiResponse<ModelResponse>> create(@Valid @RequestBody CreateModelRequest request) {
        ModelResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/core/device-models/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-DEV-41 부분 수정(온 키만, baseVersion) — 200 */
    @PatchMapping("/core/device-models/{model-id}")
    public ApiResponse<ModelResponse> update(@PathVariable("model-id") long modelId, @RequestBody JsonNode body) {
        return ApiResponse.success(service.update(modelId, body));
    }

    /** API-DEV-42 패키지 저장 — 200 */
    @PutMapping("/core/device-models/{model-id}/package")
    public ApiResponse<PackageResponse> replacePackage(@PathVariable("model-id") long modelId,
                                                       @Valid @RequestBody PackageRequest request) {
        return ApiResponse.success(service.updatePackage(modelId, request));
    }

    /** API-DEV-43 사용 중지(DEPRECATED) — 200 */
    @PostMapping("/core/device-models/{model-id}/deprecate")
    @Idempotent
    public ApiResponse<ModelResponse> deprecate(@PathVariable("model-id") long modelId) {
        return ApiResponse.success(service.deprecate(modelId));
    }

    /** API-DEV-43 삭제 — 204 */
    @DeleteMapping("/core/device-models/{model-id}")
    public ResponseEntity<Void> delete(@PathVariable("model-id") long modelId) {
        service.delete(modelId);
        return ResponseEntity.noContent().build();
    }

    /** API-DEV-47 복제 — 201 + Location */
    @PostMapping("/core/device-models/{model-id}/clone")
    @Idempotent
    public ResponseEntity<ApiResponse<ModelResponse>> cloneModel(@PathVariable("model-id") long modelId,
                                                                 @Valid @RequestBody CloneModelRequest request) {
        ModelResponse created = service.cloneModel(modelId, request);
        return ResponseEntity.created(URI.create("/api/v1/core/device-models/" + created.id())).body(ApiResponse.success(created));
    }
}
