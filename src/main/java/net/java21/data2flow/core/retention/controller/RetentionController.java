package net.java21.data2flow.core.retention.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.retention.dto.RetentionDtos.ArchiveFileResponse;
import net.java21.data2flow.core.retention.dto.RetentionDtos.PoliciesResponse;
import net.java21.data2flow.core.retention.dto.RetentionDtos.PreviewRequest;
import net.java21.data2flow.core.retention.dto.RetentionDtos.PreviewResponse;
import net.java21.data2flow.core.retention.dto.RetentionDtos.SaveRequest;
import net.java21.data2flow.core.retention.dto.RetentionDtos.SaveResponse;
import net.java21.data2flow.core.retention.dto.RetentionDtos.StorageStatsResponse;
import net.java21.data2flow.core.retention.service.ArchiveFileService;
import net.java21.data2flow.core.retention.service.RetentionPolicyService;
import net.java21.data2flow.core.retention.service.StorageStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** 보관 정책·저장 현황·콜드 보관 파일(design/api/TSD-api.md §3·§4, API-TSD-33·40~43). 권한 TS_POLICY(ADMIN·INTEGRATOR) */
@RestController
public class RetentionController {

    private final RetentionPolicyService policies;
    private final StorageStatsService stats;
    private final ArchiveFileService archives;

    public RetentionController(RetentionPolicyService policies, StorageStatsService stats, ArchiveFileService archives) {
        this.policies = policies;
        this.stats = stats;
        this.archives = archives;
    }

    /** API-TSD-40 유효 정책 — 200 */
    @GetMapping("/core/retention-policies")
    public ApiResponse<PoliciesResponse> effective() {
        return ApiResponse.success(policies.effective());
    }

    /** API-TSD-41 변경 미리 보기(지워질 행 수·크기 + 확인 토큰) — 200 */
    @PostMapping("/core/retention-policies/preview")
    public ApiResponse<PreviewResponse> preview(@RequestBody(required = false) PreviewRequest request) {
        return ApiResponse.success(policies.preview(request));
    }

    /** API-TSD-42 저장 — 200. 줄이면 confirmToken 필수(409 RETENTION_CONFIRM_REQUIRED) */
    @PutMapping("/core/retention-policies")
    public ApiResponse<SaveResponse> save(@RequestBody(required = false) SaveRequest request) {
        return ApiResponse.success(policies.save(request));
    }

    /** API-TSD-43 저장 현황 — 200 */
    @GetMapping("/core/storage-stats")
    public ApiResponse<StorageStatsResponse> storageStats() {
        return ApiResponse.success(stats.stats());
    }

    /** API-TSD-33 콜드 보관 파일 목록 — 200 */
    @GetMapping("/core/archives")
    public ListApiResponse<ArchiveFileResponse> archives(@RequestParam(required = false) String dataClass,
                                                         @RequestParam(required = false) Instant from,
                                                         @RequestParam(required = false) Instant to,
                                                         @RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size) {
        return archives.list(dataClass, from, to, page, size);
    }

    /** 콜드 보관 파일 하나 — 200, 없으면 404 ARCHIVE_NOT_FOUND */
    @GetMapping("/core/archives/{archive-id}")
    public ApiResponse<ArchiveFileResponse> archive(@PathVariable("archive-id") long archiveId) {
        return ApiResponse.success(archives.get(archiveId));
    }
}
