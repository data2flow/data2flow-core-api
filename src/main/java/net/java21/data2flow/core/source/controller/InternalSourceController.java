package net.java21.data2flow.core.source.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.IngestContextResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.RuntimeConfigResponse;
import net.java21.data2flow.core.source.service.SourceRuntimeConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 내부 API(클러스터 안, 토큰 없음·X-CALLER-SERVICE 표시, ADR-021): ingress·pipeline이 읽는 소스 설정 */
@RestController
public class InternalSourceController {

    private final SourceRuntimeConfigService service;

    public InternalSourceController(SourceRuntimeConfigService service) {
        this.service = service;
    }

    /**
     * API-DSC-50 ingress 소스 실행 설정(복호화한 비밀값 포함, 로그 금지). {@code sinceVersion}과 같으면 204. 배포 조직(ADR-030)으로 좁힌다
     */
    @GetMapping("/internal/core/sources/runtime-config")
    public ResponseEntity<ApiResponse<RuntimeConfigResponse>> runtimeConfig(@RequestParam(required = false) String lifecycle,
                                                                            @RequestParam(required = false) Long sinceVersion) {
        return service.runtimeConfig(lifecycle, sinceVersion)
                .map(r -> ResponseEntity.ok(ApiResponse.success(r)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** API-ING-21 pipeline 수집 맥락(디코더·미등록 정책·기본값·측정 항목과 별칭·스크립트) */
    @GetMapping("/internal/core/ingest-context")
    public ApiResponse<IngestContextResponse> ingestContext(@RequestParam long sourceId) {
        return ApiResponse.success(service.ingestContext(sourceId));
    }
}
