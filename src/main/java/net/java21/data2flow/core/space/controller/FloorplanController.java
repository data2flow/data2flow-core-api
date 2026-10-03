package net.java21.data2flow.core.space.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.space.domain.FloorplanImage;
import net.java21.data2flow.core.space.domain.SpaceErrorCode;
import net.java21.data2flow.core.space.dto.SpaceDtos.FloorplanResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.MarkersRequest;
import net.java21.data2flow.core.space.dto.SpaceDtos.MarkersResponse;
import net.java21.data2flow.core.space.repository.FloorplanRepository.Image;
import net.java21.data2flow.core.space.service.FloorplanService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;

/** 평면도(DEV-01.03): 조회·원본 DEV_READ, 업로드·삭제·마커 DEV_ADMIN */
@RestController
public class FloorplanController {

    private final FloorplanService service;

    public FloorplanController(FloorplanService service) {
        this.service = service;
    }

    /** 평면도 조회(API-DSH-03 기본 부분: 이미지 주소·크기·마커) — 200, 없으면 404 RESOURCE_NOT_FOUND */
    @GetMapping("/core/spaces/{space-id}/floorplan")
    public ApiResponse<FloorplanResponse> get(@PathVariable("space-id") long spaceId) {
        return ApiResponse.success(service.get(spaceId));
    }

    /** API-DEV-09 업로드·교체(multipart file, scaleMPerPx?) — 200 */
    @PutMapping(value = "/core/spaces/{space-id}/floorplan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FloorplanResponse> upload(@PathVariable("space-id") long spaceId,
                                                 @RequestPart(value = "file", required = false) MultipartFile file,
                                                 @RequestParam(required = false) BigDecimal scaleMPerPx) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > FloorplanImage.MAX_BYTES) {
            throw new BusinessException(SpaceErrorCode.FLOORPLAN_IMAGE_INVALID);
        }
        return ApiResponse.success(service.upload(spaceId, file.getBytes(), scaleMPerPx));
    }

    /** API-DEV-09 DELETE — 204 */
    @DeleteMapping("/core/spaces/{space-id}/floorplan")
    public ResponseEntity<Void> delete(@PathVariable("space-id") long spaceId) {
        service.delete(spaceId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 평면도 원본(응답의 imageUrl). SVG는 스크립트 없는 것만 저장하고, 내려줄 때도 CSP sandbox로 스크립트·외부 요청을 막는다.
     */
    @GetMapping("/core/spaces/{space-id}/floorplan/image")
    public ResponseEntity<byte[]> image(@PathVariable("space-id") long spaceId) {
        Image image = service.image(spaceId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .eTag("\"" + image.sha256() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .body(image.data());
    }

    /** API-DEV-10 마커 전체 교체 — 200 */
    @PutMapping("/core/spaces/{space-id}/floorplan/markers")
    public ApiResponse<MarkersResponse> markers(@PathVariable("space-id") long spaceId, @RequestBody MarkersRequest request) {
        return ApiResponse.success(service.replaceMarkers(spaceId, request));
    }
}
