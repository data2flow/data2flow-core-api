package net.java21.data2flow.core.retention.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.retention.dto.RetentionDtos.ArchiveFileRequest;
import net.java21.data2flow.core.retention.dto.RetentionDtos.IdResponse;
import net.java21.data2flow.core.retention.dto.RetentionDtos.InternalPoliciesResponse;
import net.java21.data2flow.core.retention.service.ArchiveFileService;
import net.java21.data2flow.core.retention.service.RetentionPolicyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** 보관 내부 API(TSD-api §5): 호출자 pipeline, 내부망 신뢰(ADR-021), 사용자 신원 없음 */
@RestController
public class InternalRetentionController {

    private final RetentionPolicyService policies;
    private final ArchiveFileService archives;

    public InternalRetentionController(RetentionPolicyService policies, ArchiveFileService archives) {
        this.policies = policies;
        this.archives = archives;
    }

    /** API-TSD-60 배포 조직 전체의 유효 보관 정책(pipeline 5분 주기·API-TSD-53 통지 때) — 200 */
    @GetMapping("/internal/core/retention-policies")
    public ApiResponse<InternalPoliciesResponse> all() {
        return ApiResponse.success(policies.internalAll());
    }

    /** API-TSD-61 콜드 보관 파일 등록 — 201 {id}(같은 객체 키 재등록은 같은 ID) */
    @PostMapping("/internal/core/archive-files")
    public ResponseEntity<ApiResponse<IdResponse>> register(@RequestBody ArchiveFileRequest request) {
        IdResponse created = archives.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).location(URI.create("/api/v1/core/archives/" + created.id()))
                .body(ApiResponse.success(created));
    }
}
