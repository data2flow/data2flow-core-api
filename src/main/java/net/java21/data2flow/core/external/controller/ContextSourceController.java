package net.java21.data2flow.core.external.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.external.dto.ExternalDtos.ContextSourceView;
import net.java21.data2flow.core.external.dto.ExternalDtos.IcalUploadResponse;
import net.java21.data2flow.core.external.dto.ExternalDtos.RefreshResponse;
import net.java21.data2flow.core.external.dto.ExternalDtos.SiteContextResponse;
import net.java21.data2flow.core.external.dto.ExternalDtos.StationView;
import net.java21.data2flow.core.external.dto.ExternalDtos.UsageDay;
import net.java21.data2flow.core.external.dto.ExternalDtos.UsageRecorded;
import net.java21.data2flow.core.external.service.ContextSourceService;
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

import java.util.List;

/** 외부 맥락 소스(design/api/DSC-api.md §4 API-DSC-40~43, 사이트 카드 API-DSC-44·45 제안, 내부 API-DSC-78 제안, DSC-06) */
@RestController
public class ContextSourceController {

    private final ContextSourceService service;

    public ContextSourceController(ContextSourceService service) {
        this.service = service;
    }

    /** API-DSC-44(제안) 사이트 외부 맥락 카드 — SRC_READ */
    @GetMapping("/core/sites/{site-id}/context-sources")
    public ApiResponse<SiteContextResponse> site(@PathVariable("site-id") long siteId) {
        return ApiResponse.success(service.site(siteId));
    }

    /** API-DSC-45(제안) 카드 켜기·설정 — SRC_ADMIN */
    @PutMapping("/core/sites/{site-id}/context-sources/{type}")
    public ApiResponse<ContextSourceView> put(@PathVariable("site-id") long siteId, @PathVariable("type") String type,
                                              @RequestBody JsonNode body) {
        return ApiResponse.success(service.put(siteId, type, body));
    }

    /** API-DSC-40 — SRC_READ */
    @GetMapping("/core/sources/{source-id}/api-usage")
    public ApiResponse<List<UsageDay>> apiUsage(@PathVariable("source-id") long sourceId, @RequestParam(required = false) Integer days) {
        return ApiResponse.success(service.apiUsage(sourceId, days));
    }

    /** API-DSC-41 — SRC_ADMIN */
    @PostMapping("/core/sources/{source-id}/refresh-now")
    public ApiResponse<RefreshResponse> refreshNow(@PathVariable("source-id") long sourceId) {
        return ApiResponse.success(service.refreshNow(sourceId));
    }

    /** API-DSC-42 — SRC_ADMIN */
    @GetMapping("/core/external/airkorea-stations")
    public ApiResponse<List<StationView>> stations(@RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return ApiResponse.success(service.stations(lat, lng));
    }

    /** API-DSC-43 (multipart .ics ≤2MB) — SRC_ADMIN */
    @PostMapping("/core/sources/ical/upload")
    public ApiResponse<IcalUploadResponse> upload(@RequestPart(value = "file", required = false) MultipartFile file) {
        return ApiResponse.success(service.uploadIcal(file));
    }

    /** 내부 API-DSC-78(제안): ingress 공공 API 호출량 기록·한도 판정 */
    @PostMapping("/internal/core/sources/{source-id}/api-usage")
    public ApiResponse<UsageRecorded> record(@PathVariable("source-id") long sourceId, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(service.recordInternal(sourceId, body));
    }
}
