package net.java21.data2flow.core.edge.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.ConfigVersionResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.EdgeResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.RegistrationResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.RequestResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.UpdateResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.UpdatesResponse;
import net.java21.data2flow.core.edge.service.EdgeService;
import org.springframework.http.HttpStatus;
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
import java.util.List;

/** 엣지 게이트웨이 원격 관리(DSC-08.03·08.04, design/api/DSC-api.md §8 API-DSC-62·64·65·67) */
@RestController
public class EdgeController {

    private final EdgeService service;

    public EdgeController(EdgeService service) {
        this.service = service;
    }

    @GetMapping("/core/edges")
    public ListApiResponse<EdgeResponse> list(@RequestParam(required = false) String siteId, @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return service.list(siteId, page, size);
    }

    /** API-DSC-62 생성(201) — 등록 토큰은 이 응답에서만 */
    @PostMapping("/core/edges")
    @Idempotent
    public ResponseEntity<ApiResponse<RegistrationResponse>> create(@RequestBody JsonNode body) {
        RegistrationResponse r = service.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/edges/" + r.id())).body(ApiResponse.success(r));
    }

    @GetMapping("/core/edges/{edge-id:\\d+}")
    public ApiResponse<EdgeResponse> get(@PathVariable("edge-id") long edgeId) {
        return ApiResponse.success(service.get(edgeId));
    }

    @PatchMapping("/core/edges/{edge-id:\\d+}")
    public ApiResponse<EdgeResponse> patch(@PathVariable("edge-id") long edgeId, @RequestBody JsonNode body) {
        return ApiResponse.success(service.patch(edgeId, body));
    }

    @DeleteMapping("/core/edges/{edge-id:\\d+}")
    public ResponseEntity<Void> delete(@PathVariable("edge-id") long edgeId) {
        service.delete(edgeId);
        return ResponseEntity.noContent().build();
    }

    /** 등록 토큰 재발급(등록 전만, 201) */
    @PostMapping("/core/edges/{edge-id:\\d+}/registration-tokens")
    public ResponseEntity<ApiResponse<RegistrationResponse>> reissue(@PathVariable("edge-id") long edgeId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.reissueToken(edgeId)));
    }

    /** API-DSC-64 설정 판 */
    @PostMapping("/core/edges/{edge-id:\\d+}/config-versions")
    public ResponseEntity<ApiResponse<ConfigVersionResponse>> createConfig(@PathVariable("edge-id") long edgeId, @RequestBody JsonNode body) {
        ConfigVersionResponse r = service.createConfigVersion(edgeId, body);
        return ResponseEntity.created(URI.create("/api/v1/core/edges/" + edgeId + "/config-versions/" + r.version())).body(ApiResponse.success(r));
    }

    @GetMapping("/core/edges/{edge-id:\\d+}/config-versions")
    public ApiResponse<List<ConfigVersionResponse>> configs(@PathVariable("edge-id") long edgeId) {
        return ApiResponse.success(service.configVersions(edgeId));
    }

    @PostMapping("/core/edges/{edge-id:\\d+}/config-versions/{version:\\d+}/deploy")
    public ApiResponse<ConfigVersionResponse> deploy(@PathVariable("edge-id") long edgeId, @PathVariable("version") int version) {
        return ApiResponse.success(service.deploy(edgeId, version));
    }

    @PostMapping("/core/edges/{edge-id:\\d+}/config-versions/{version:\\d+}/rollback")
    public ApiResponse<ConfigVersionResponse> rollback(@PathVariable("edge-id") long edgeId, @PathVariable("version") int version) {
        return ApiResponse.success(service.rollback(edgeId, version));
    }

    /** API-DSC-65 업데이트 승인(201)·목록 */
    @PostMapping("/core/edges/{edge-id:\\d+}/updates")
    public ResponseEntity<ApiResponse<UpdateResponse>> approve(@PathVariable("edge-id") long edgeId, @RequestBody JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.approveUpdate(edgeId, body)));
    }

    @GetMapping("/core/edges/{edge-id:\\d+}/updates")
    public ApiResponse<UpdatesResponse> updates(@PathVariable("edge-id") long edgeId) {
        return ApiResponse.success(service.updates(edgeId));
    }

    /** API-DSC-67 재시작·로그 수집·폐기 */
    @PostMapping("/core/edges/{edge-id:\\d+}/{action:restart|collect-logs|revoke}")
    public ResponseEntity<ApiResponse<RequestResponse>> command(@PathVariable("edge-id") long edgeId, @PathVariable("action") String action,
                                                                @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(service.command(edgeId, action, body)));
    }
}
