package net.java21.data2flow.core.output.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.output.dto.OutputDtos.DeviceContextsResponse;
import net.java21.data2flow.core.output.dto.OutputDtos.RuntimeResponse;
import net.java21.data2flow.core.output.service.OutputInternalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * action 출력 실행용 내부 API(출력 계약 API-DSC-73·74·75, 클러스터 내부 전용, ADR-021). API-DSC-73 응답에는 복호화한 비밀값이 있으므로
 * 로그에 남기지 않는다.
 */
@RestController
public class InternalOutputController {

    private final OutputInternalService service;

    public InternalOutputController(OutputInternalService service) {
        this.service = service;
    }

    /** API-DSC-73 실행 설정. sinceVersion과 같으면 204 */
    @GetMapping("/internal/core/output-connections/runtime")
    public ResponseEntity<ApiResponse<RuntimeResponse>> runtime(@RequestParam(required = false) Long sinceVersion) {
        return service.runtime(sinceVersion).map(r -> ResponseEntity.ok(ApiResponse.success(r)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** API-DSC-74 기기 맥락 */
    @GetMapping("/internal/core/output-connections/device-contexts")
    public ApiResponse<DeviceContextsResponse> deviceContexts(@RequestParam(required = false) String deviceIds) {
        return ApiResponse.success(service.deviceContexts(deviceIds));
    }

    /** API-DSC-75 1분 발송 지표(204) */
    @PostMapping("/internal/core/output-connections/stats")
    public ResponseEntity<Void> stats(@RequestBody JsonNode body) {
        service.addStats(body);
        return ResponseEntity.noContent().build();
    }
}
