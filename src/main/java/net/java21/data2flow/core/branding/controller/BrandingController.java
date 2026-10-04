package net.java21.data2flow.core.branding.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.board.domain.BoardErrorCode;
import net.java21.data2flow.core.branding.dto.BrandingDtos.AssetFile;
import net.java21.data2flow.core.branding.dto.BrandingDtos.AssetResponse;
import net.java21.data2flow.core.branding.dto.BrandingDtos.BrandingResponse;
import net.java21.data2flow.core.branding.dto.BrandingDtos.PublicBranding;
import net.java21.data2flow.core.branding.service.BrandingService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

/** 브랜딩(design/api/DSH-api.md API-DSH-25, DSH-13.01). 공개 경로는 로그인 화면·공개 화면용 */
@RestController
public class BrandingController {

    private final BrandingService service;

    public BrandingController(BrandingService service) {
        this.service = service;
    }

    /** API-DSH-25 조회 — DASHBOARD_READ */
    @GetMapping("/core/branding")
    public ApiResponse<BrandingResponse> get() {
        return ApiResponse.success(service.get());
    }

    /** API-DSH-25 변경 — BRANDING_MANAGE, baseVersion */
    @PutMapping("/core/branding")
    public ApiResponse<BrandingResponse> update(@RequestBody JsonNode body) {
        return ApiResponse.success(service.update(body));
    }

    /** API-DSH-25 자산 올리기(multipart file + kind) — 201 */
    @PostMapping(value = "/core/branding/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AssetResponse>> upload(@RequestParam(value = "kind", required = false) String kind,
                                                             @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(BoardErrorCode.BRANDING_ASSET_INVALID, List.of(new FieldErrorDetail("file", "REQUIRED", null)));
        }
        if (file.getSize() > 1024 * 1024) {
            throw new BusinessException(BoardErrorCode.BRANDING_ASSET_INVALID, List.of(new FieldErrorDetail("file", "TOO_LARGE", "1MB")));
        }
        AssetResponse created = service.upload(kind, file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create(created.url())).body(ApiResponse.success(created));
    }

    /** 자산 내려받기(로그인, 같은 조직만) */
    @GetMapping("/core/branding/assets/{asset-id}")
    public ResponseEntity<byte[]> asset(@PathVariable("asset-id") long assetId) {
        return file(service.asset(assetId));
    }

    /** 공개 자산(이 배포 조직이 지금 쓰는 것만) */
    @GetMapping("/core/public/branding/assets/{asset-id}")
    public ResponseEntity<byte[]> publicAsset(@PathVariable("asset-id") long assetId) {
        return file(service.publicAsset(assetId));
    }

    /** 공개 브랜딩(로그인 화면) */
    @GetMapping("/core/public/branding")
    public ApiResponse<PublicBranding> publicBranding() {
        return ApiResponse.success(service.publicBranding());
    }

    private static ResponseEntity<byte[]> file(AssetFile f) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType())).eTag("\"" + f.sha256() + "\"")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .body(f.data());
    }
}
