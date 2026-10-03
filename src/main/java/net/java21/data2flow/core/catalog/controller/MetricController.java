package net.java21.data2flow.core.catalog.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.AliasResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.AliasToRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.AliasToResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.CreateAliasRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.CreateMetricRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.MetricResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.MetricStatusResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.RemapJobResponse;
import net.java21.data2flow.core.catalog.service.MetricService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/** 측정 항목·미검증 항목·별칭(DEV-04.01~04.03, API-DEV-50~56). 조회 DEV_READ, 쓰기 DEV_ADMIN */
@RestController
public class MetricController {

    private final MetricService service;

    public MetricController(MetricService service) {
        this.service = service;
    }

    /** API-DEV-50 목록(status·q·key) — 200 */
    @GetMapping("/core/metrics")
    public ListApiResponse<MetricResponse> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(required = false) String key,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        return service.list(status, q, key, page, size);
    }

    /** 측정 항목 하나 — 200 */
    @GetMapping("/core/metrics/{metric-id}")
    public ApiResponse<MetricResponse> detail(@PathVariable("metric-id") long metricId) {
        return ApiResponse.success(service.get(metricId));
    }

    /** API-DEV-51 생성 — 201 + Location */
    @PostMapping("/core/metrics")
    @Idempotent
    public ResponseEntity<ApiResponse<MetricResponse>> create(@Valid @RequestBody CreateMetricRequest request) {
        MetricResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/core/metrics/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-DEV-51 부분 수정(온 키만, baseVersion) — 200 */
    @PatchMapping("/core/metrics/{metric-id}")
    public ApiResponse<MetricResponse> update(@PathVariable("metric-id") long metricId, @RequestBody JsonNode body) {
        return ApiResponse.success(service.update(metricId, body));
    }

    /** API-DEV-52 표준으로 등록 — 200 */
    @PostMapping("/core/metrics/{metric-id}/verify")
    @Idempotent
    public ApiResponse<MetricResponse> verify(@PathVariable("metric-id") long metricId,
                                              @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(service.verify(metricId, body));
    }

    /** API-DEV-53 별칭으로 연결 — 200 {alias, remapJobId} */
    @PostMapping("/core/metrics/{metric-id}/alias-to")
    @Idempotent
    public ApiResponse<AliasToResponse> aliasTo(@PathVariable("metric-id") long metricId, @Valid @RequestBody AliasToRequest request) {
        return ApiResponse.success(service.aliasTo(metricId, request));
    }

    /** API-DEV-54 무시 — 200 */
    @PostMapping("/core/metrics/{metric-id}/ignore")
    @Idempotent
    public ApiResponse<MetricStatusResponse> ignore(@PathVariable("metric-id") long metricId) {
        return ApiResponse.success(service.ignore(metricId));
    }

    /** API-DEV-54 복원 — 200 */
    @PostMapping("/core/metrics/{metric-id}/restore")
    @Idempotent
    public ApiResponse<MetricStatusResponse> restore(@PathVariable("metric-id") long metricId) {
        return ApiResponse.success(service.restore(metricId));
    }

    /** API-DEV-55 별칭 목록 — 200 */
    @GetMapping("/core/metric-aliases")
    public ListApiResponse<AliasResponse> aliases(@RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        return service.listAliases(page, size);
    }

    /** API-DEV-55 별칭 추가 — 201 + Location */
    @PostMapping("/core/metric-aliases")
    @Idempotent
    public ResponseEntity<ApiResponse<AliasResponse>> createAlias(@Valid @RequestBody CreateAliasRequest request) {
        AliasResponse created = service.createAlias(request);
        return ResponseEntity.created(URI.create("/api/v1/core/metric-aliases/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-DEV-55 별칭 삭제 — 204 */
    @DeleteMapping("/core/metric-aliases/{metric-alias-id}")
    public ResponseEntity<Void> deleteAlias(@PathVariable("metric-alias-id") long aliasId) {
        service.deleteAlias(aliasId);
        return ResponseEntity.noContent().build();
    }

    /** API-DEV-56 재매핑 진행률 — 200 */
    @GetMapping("/core/metric-remap-jobs/{job-id}")
    public ApiResponse<RemapJobResponse> remapJob(@PathVariable("job-id") long jobId) {
        return ApiResponse.success(service.remapJob(jobId));
    }
}
