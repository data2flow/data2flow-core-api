package net.java21.data2flow.core.edge.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.HeartbeatResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.RegisteredResponse;
import net.java21.data2flow.core.edge.service.EdgeInternalService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** ingress 엣지 수신 측 내부 API(API-DSC-78 등록 토큰 확인, API-DSC-79 하트비트 — 새 ID, DSC-08.03·08.04) */
@RestController
public class InternalEdgeController {

    private final EdgeInternalService service;

    public InternalEdgeController(EdgeInternalService service) {
        this.service = service;
    }

    @PostMapping("/internal/core/edges/register")
    public ApiResponse<RegisteredResponse> register(@RequestBody JsonNode body) {
        return ApiResponse.success(service.register(body));
    }

    @PostMapping("/internal/core/edges/{edge-id}/heartbeat")
    public ApiResponse<HeartbeatResponse> heartbeat(@PathVariable("edge-id") long edgeId, @RequestBody JsonNode body) {
        return ApiResponse.success(service.heartbeat(edgeId, body));
    }
}
