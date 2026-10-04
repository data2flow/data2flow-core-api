package net.java21.data2flow.core.asset.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.asset.dto.AssetDtos.AssetInfoRequest;
import net.java21.data2flow.core.asset.dto.AssetDtos.AssetInfoResponse;
import net.java21.data2flow.core.asset.service.AssetService;
import net.java21.data2flow.core.filestore.repository.FileBlobRepository.Blob;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** 자산 정보(design/api/DEV-api.md §9 API-DEV-96, DEV-08.01) */
@RestController
public class AssetController {

    private final AssetService service;

    public AssetController(AssetService service) {
        this.service = service;
    }

    @GetMapping("/core/devices/{device-id}/asset-info")
    public ApiResponse<AssetInfoResponse> get(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(service.get(deviceId));
    }

    /** API-DEV-96 — DEV_ADMIN */
    @PutMapping("/core/devices/{device-id}/asset-info")
    public ApiResponse<AssetInfoResponse> put(@PathVariable("device-id") long deviceId, @Valid @RequestBody AssetInfoRequest request) {
        return ApiResponse.success(service.put(deviceId, request));
    }

    /** 사진 추가(multipart file, 이미지 ≤20MB) — DEV_ADMIN, 201 */
    @PostMapping(value = "/core/devices/{device-id}/asset-info/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AssetInfoResponse>> addPhoto(@PathVariable("device-id") long deviceId,
                                                                   @RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.addPhoto(deviceId, file.getOriginalFilename(),
                file.getBytes())));
    }

    @GetMapping("/core/devices/{device-id}/asset-info/photos/{photo-id}")
    public ResponseEntity<byte[]> photo(@PathVariable("device-id") long deviceId, @PathVariable("photo-id") long photoId) {
        Blob blob = service.photo(deviceId, photoId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(blob.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600").body(blob.data());
    }

    @DeleteMapping("/core/devices/{device-id}/asset-info/photos/{photo-id}")
    public ResponseEntity<Void> deletePhoto(@PathVariable("device-id") long deviceId, @PathVariable("photo-id") long photoId) {
        service.deletePhoto(deviceId, photoId);
        return ResponseEntity.noContent().build();
    }
}
