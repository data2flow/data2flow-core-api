package net.java21.data2flow.core.sim.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.common.InternalOrganizations;
import net.java21.data2flow.core.sim.service.SimRelayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/** simulator가 부르는 core 내부 API(SIM-api §2 API-SIM-35·36). 토큰 없음, X-CALLER-SERVICE(ADR-021) */
@RestController
public class InternalSimController {

    private final SimRelayService service;
    private final InternalOrganizations organizations;

    public InternalSimController(SimRelayService service, InternalOrganizations organizations) {
        this.service = service;
        this.organizations = organizations;
    }

    /** API-SIM-35 가상 환경 맥락 */
    @GetMapping("/internal/core/sim/context")
    public ApiResponse<Map<String, Object>> context(@RequestParam(required = false) Long organizationId) {
        return ApiResponse.success(service.context(organizations.resolve(organizationId)));
    }

    /** API-SIM-36 보관 기한이 지난 실행의 가상 데이터 정리 요청 {organizationId, runIds[]} — 202 {jobId} */
    @PostMapping("/internal/core/sim/data/purge")
    public ResponseEntity<ApiResponse<Map<String, Object>>> purge(@RequestBody JsonNode body) {
        Long org = body != null && body.hasNonNull("organizationId") ? Long.valueOf(body.get("organizationId").asLong()) : null;
        return ResponseEntity.accepted().body(ApiResponse.success(service.purgeInternal(organizations.resolve(org), body)));
    }
}
