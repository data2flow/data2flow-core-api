package net.java21.data2flow.core.source.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.LifecycleResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.SecretResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.SecretRotationResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.SourceDetailResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.SourceLimitsResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.SourceSummaryResponse;
import net.java21.data2flow.core.source.service.ConnectionTestService;
import net.java21.data2flow.core.source.service.DataSourceService;
import net.java21.data2flow.core.source.service.SourceQueryService;
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
import java.util.List;

/** 데이터 소스 등록·관리(DSC-01·07, API-DSC-01~07·12·57·58). 조회 SRC_READ(OPERATOR+), 관리 SRC_ADMIN(INTEGRATOR+) */
@RestController
public class DataSourceController {

    private final DataSourceService service;
    private final SourceQueryService queries;
    private final ConnectionTestService tests;

    private final net.java21.data2flow.core.source.service.SourceRotationService rotationService;

    public DataSourceController(DataSourceService service, SourceQueryService queries, ConnectionTestService tests,
                                net.java21.data2flow.core.source.service.SourceRotationService rotationService) {
        this.rotationService = rotationService;
        this.service = service;
        this.queries = queries;
        this.tests = tests;
    }

    /** API-DSC-01 소스 목록(DSC-01.01·02.01·02.03) — SRC_READ, 200. lifecycle 기본: ARCHIVED 제외 */
    @GetMapping("/core/sources")
    public ListApiResponse<SourceSummaryResponse> list(@RequestParam(required = false) String q,
                                                       @RequestParam(required = false) List<String> type,
                                                       @RequestParam(required = false) List<String> lifecycle,
                                                       @RequestParam(required = false) String state,
                                                       @RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer size) {
        return queries.list(q, type, lifecycle, state, page, size);
    }

    /** API-DSC-02 소스 생성(DSC-01.01~01.07) — SRC_ADMIN, 201 */
    @PostMapping("/core/sources")
    @Idempotent
    public ResponseEntity<ApiResponse<SourceDetailResponse>> create(@RequestBody JsonNode body) {
        SourceDetailResponse created = service.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/sources/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-DSC-03 소스 상세(비밀값은 지문만) — SRC_READ, 200 */
    @GetMapping("/core/sources/{source-id}")
    public ApiResponse<SourceDetailResponse> get(@PathVariable("source-id") long sourceId) {
        return ApiResponse.success(queries.get(sourceId));
    }

    /** API-DSC-04 소스 수정(온 키만, baseVersion) — SRC_ADMIN, 200 */
    @PatchMapping("/core/sources/{source-id}")
    public ApiResponse<SourceDetailResponse> patch(@PathVariable("source-id") long sourceId, @RequestBody JsonNode body) {
        return ApiResponse.success(service.patch(sourceId, body));
    }

    /** API-DSC-07 소스 삭제(DRAFT·ARCHIVED, 기기 0대) — SRC_ADMIN, 204 */
    @DeleteMapping("/core/sources/{source-id}")
    public ResponseEntity<Void> delete(@PathVariable("source-id") long sourceId) {
        service.delete(sourceId);
        return ResponseEntity.noContent().build();
    }

    /** API-DSC-05 비밀값 교체({kind, value}) — SRC_ADMIN, 200 */
    @PutMapping("/core/sources/{source-id}/secret")
    public ApiResponse<SecretRotationResponse> replaceSecret(@PathVariable("source-id") long sourceId, @RequestBody JsonNode body) {
        return ApiResponse.success(service.replaceSecret(sourceId, body));
    }

    /** API-DSC-58 비밀값 한 종류 교체({value}) — SRC_ADMIN, 200 */
    /** API-DSC-05b 무중단 교체 진행 상태(DSC-07.02) — SRC_READ */
    @GetMapping("/core/sources/{source-id}/secret-rotations/{rotation-id}")
    public ApiResponse<net.java21.data2flow.core.source.service.SourceRotationService.RotationStatus> rotation(
            @PathVariable("source-id") long sourceId, @PathVariable("rotation-id") String rotationId) {
        return ApiResponse.success(rotationService.status(sourceId, rotationId));
    }

    @PutMapping("/core/sources/{source-id}/secrets/{kind}")
    public ApiResponse<SecretResponse> replaceSecretKind(@PathVariable("source-id") long sourceId, @PathVariable("kind") String kind,
                                                         @RequestBody JsonNode body) {
        return ApiResponse.success(service.replaceSecretKind(sourceId, kind, body));
    }

    /** API-DSC-06 활성화(DRAFT→ACTIVE) — SRC_ADMIN, 200 */
    @PostMapping("/core/sources/{source-id}/activate")
    @Idempotent
    public ApiResponse<LifecycleResponse> activate(@PathVariable("source-id") long sourceId, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(service.transition(sourceId, "activate", body));
    }

    /** API-DSC-06 일시정지(ACTIVE→PAUSED, 영속 세션 유지) — SRC_ADMIN, 200 */
    @PostMapping("/core/sources/{source-id}/pause")
    @Idempotent
    public ApiResponse<LifecycleResponse> pause(@PathVariable("source-id") long sourceId, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(service.transition(sourceId, "pause", body));
    }

    /** API-DSC-06 재개(PAUSED→ACTIVE) — SRC_ADMIN, 200 */
    @PostMapping("/core/sources/{source-id}/resume")
    @Idempotent
    public ApiResponse<LifecycleResponse> resume(@PathVariable("source-id") long sourceId, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(service.transition(sourceId, "resume", body));
    }

    /** API-DSC-06 보관(ACTIVE·PAUSED→ARCHIVED, confirm 필요) — SRC_ADMIN, 200 */
    @PostMapping("/core/sources/{source-id}/archive")
    @Idempotent
    public ApiResponse<LifecycleResponse> archive(@PathVariable("source-id") long sourceId, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(service.transition(sourceId, "archive", body));
    }

    /** API-DSC-12 복제(비밀값 제외, DRAFT) — SRC_ADMIN, 201 */
    @PostMapping("/core/sources/{source-id}/clone")
    @Idempotent
    public ResponseEntity<ApiResponse<SourceDetailResponse>> cloneSource(@PathVariable("source-id") long sourceId,
                                                                         @RequestBody JsonNode body) {
        SourceDetailResponse created = service.cloneSource(sourceId, body);
        return ResponseEntity.created(URI.create("/api/v1/core/sources/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-DSC-57 저장 전 연결 테스트(ingress API-DSC-51 중계, 결과 저장 안 함) — SRC_ADMIN, 200 */
    @PostMapping("/core/sources/test")
    public ApiResponse<JsonNode> test(@RequestBody JsonNode body, @RequestParam(required = false) Integer timeoutSec) {
        return ApiResponse.success(tests.test(body, timeoutSec));
    }

    /** API-DSC-57 저장된 소스 연결 테스트(본문 값이 우선) — SRC_ADMIN, 200 */
    @PostMapping("/core/sources/{source-id}/test")
    public ApiResponse<JsonNode> testExisting(@PathVariable("source-id") long sourceId, @RequestBody(required = false) JsonNode body,
                                              @RequestParam(required = false) Integer timeoutSec) {
        return ApiResponse.success(tests.testExisting(sourceId, body, timeoutSec));
    }

    /** 조직 소스 한도 조회(DSC-07.03) — SRC_READ, 200 */
    @GetMapping("/core/source-limits")
    public ApiResponse<SourceLimitsResponse> limits() {
        return ApiResponse.success(service.limits());
    }

    /** 조직 소스 한도 변경(DSC-07.03, AT-DSC-21.4) — ADMIN(OPS_MANAGE), 200 */
    @PatchMapping("/core/source-limits")
    public ApiResponse<SourceLimitsResponse> updateLimits(@RequestBody JsonNode body) {
        return ApiResponse.success(service.updateLimits(body));
    }
}
