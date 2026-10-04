package net.java21.data2flow.core.bim.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.bim.dto.BimDtos.FloorItem;
import net.java21.data2flow.core.bim.dto.BimDtos.MappingRequest;
import net.java21.data2flow.core.bim.dto.BimDtos.MappingResult;
import net.java21.data2flow.core.bim.dto.BimDtos.ModelCreated;
import net.java21.data2flow.core.bim.dto.BimDtos.ModelDetail;
import net.java21.data2flow.core.bim.dto.BimDtos.ModelSummary;
import net.java21.data2flow.core.bim.service.BuildingModelService;
import net.java21.data2flow.core.board.domain.BoardErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/** 층 전환 평면도·IFC 건물 모델(design/api/DSH-api.md API-DSH-24, DSH-12.04) */
@RestController
public class BuildingModelController {

    private final BuildingModelService service;

    public BuildingModelController(BuildingModelService service) {
        this.service = service;
    }

    /** 건물의 층 목록(층 전환, AT-DSH-13.1) */
    @GetMapping("/core/buildings/{building-id}/floors")
    public ApiResponse<List<FloorItem>> floors(@PathVariable("building-id") long buildingId) {
        return ApiResponse.success(service.floors(buildingId));
    }

    /** API-DSH-24 IFC 올리기(multipart file, name?) — 201 */
    @PostMapping(value = "/core/buildings/{building-id}/models", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ModelCreated>> upload(@PathVariable("building-id") long buildingId,
                                                            @RequestParam(value = "name", required = false) String name,
                                                            @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(BoardErrorCode.MODEL_FILE_INVALID, List.of(new FieldErrorDetail("file", "REQUIRED", null)));
        }
        if (file.getSize() > net.java21.data2flow.core.bim.domain.IfcFile.MAX_BYTES) {
            throw new BusinessException(BoardErrorCode.MODEL_FILE_TOO_LARGE, List.of(new FieldErrorDetail("file", "TOO_LARGE", "200MB")));
        }
        ModelCreated created = service.upload(buildingId, name, file.getOriginalFilename(), file.getSize(), file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/core/buildings/" + buildingId + "/models/" + created.id())).body(ApiResponse.success(created));
    }

    @GetMapping("/core/buildings/{building-id}/models")
    public ApiResponse<List<ModelSummary>> list(@PathVariable("building-id") long buildingId) {
        return ApiResponse.success(service.list(buildingId));
    }

    /** API-DSH-24 상세(연결·공간 요소·내려받기 주소) */
    @GetMapping("/core/buildings/{building-id}/models/{model-id}")
    public ApiResponse<ModelDetail> get(@PathVariable("building-id") long buildingId, @PathVariable("model-id") long modelId) {
        return ApiResponse.success(service.get(buildingId, modelId));
    }

    /** IFC 원본(브라우저 뷰어) */
    @GetMapping("/core/buildings/{building-id}/models/{model-id}/file")
    public ResponseEntity<byte[]> file(@PathVariable("building-id") long buildingId, @PathVariable("model-id") long modelId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header("X-Content-Type-Options", "nosniff")
                .body(service.file(buildingId, modelId));
    }

    /** API-DSH-24 공간 연결 */
    @PutMapping("/core/buildings/{building-id}/models/{model-id}/space-mapping")
    public ApiResponse<MappingResult> map(@PathVariable("building-id") long buildingId, @PathVariable("model-id") long modelId,
                                          @RequestBody(required = false) MappingRequest request) {
        return ApiResponse.success(service.map(buildingId, modelId, request));
    }

    @DeleteMapping("/core/buildings/{building-id}/models/{model-id}")
    public ResponseEntity<Void> delete(@PathVariable("building-id") long buildingId, @PathVariable("model-id") long modelId) {
        service.delete(buildingId, modelId);
        return ResponseEntity.noContent().build();
    }
}
