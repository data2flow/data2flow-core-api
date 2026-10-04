package net.java21.data2flow.core.output.controller;

import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.output.dto.OutputDtos.OutputConnectionResponse;
import net.java21.data2flow.core.output.dto.OutputDtos.ReplayResponse;
import net.java21.data2flow.core.output.dto.OutputDtos.StatPoint;
import net.java21.data2flow.core.output.service.OutputConnectionService;
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

/** 출력 연결(DSC-04.01, design/api/DSC-api.md §3 API-DSC-30~33) */
@RestController
public class OutputConnectionController {

    private final OutputConnectionService service;

    public OutputConnectionController(OutputConnectionService service) {
        this.service = service;
    }

    /** API-DSC-31 목록 — SRC_READ */
    @GetMapping("/core/output-connections")
    public ListApiResponse<OutputConnectionResponse> list(@RequestParam(required = false) String type, @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size) {
        return service.list(type, page, size);
    }

    /** API-DSC-30 생성 — SRC_ADMIN, 201 */
    @PostMapping("/core/output-connections")
    @Idempotent
    public ResponseEntity<ApiResponse<OutputConnectionResponse>> create(@RequestBody JsonNode body) {
        OutputConnectionResponse created = service.create(body);
        return ResponseEntity.created(URI.create("/api/v1/core/output-connections/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-DSC-32 테스트 발송 — SRC_ADMIN, 실패도 200(ok=false, failureKind) */
    @PostMapping("/core/output-connections/test")
    public ApiResponse<JsonNode> test(@RequestBody JsonNode body) {
        return ApiResponse.success(service.test(body));
    }

    @GetMapping("/core/output-connections/{output-connection-id:\\d+}")
    public ApiResponse<OutputConnectionResponse> get(@PathVariable("output-connection-id") long id) {
        return ApiResponse.success(service.get(id));
    }

    /** API-DSC-30 부분 수정 — SRC_ADMIN */
    @PatchMapping("/core/output-connections/{output-connection-id:\\d+}")
    public ApiResponse<OutputConnectionResponse> patch(@PathVariable("output-connection-id") long id, @RequestBody JsonNode body) {
        return ApiResponse.success(service.patch(id, body));
    }

    /** API-DSC-30 삭제 — SRC_ADMIN, 204 */
    @DeleteMapping("/core/output-connections/{output-connection-id:\\d+}")
    public ResponseEntity<Void> delete(@PathVariable("output-connection-id") long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** API-DSC-31 1분 발송 지표 — SRC_READ */
    @GetMapping("/core/output-connections/{output-connection-id:\\d+}/stats")
    public ApiResponse<List<StatPoint>> stats(@PathVariable("output-connection-id") long id, @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) {
        return ApiResponse.success(service.stats(id, from, to));
    }

    /** API-DSC-33 실패 보관 재전송 — SRC_ADMIN */
    @PostMapping("/core/output-connections/{output-connection-id:\\d+}/replay-failed")
    public ApiResponse<ReplayResponse> replayFailed(@PathVariable("output-connection-id") long id, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.success(service.replayFailed(id, body));
    }
}
