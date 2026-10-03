package net.java21.data2flow.core.flow.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.common.InternalOrganizations;
import net.java21.data2flow.core.flow.dto.FlowDtos.RuntimeFlows;
import net.java21.data2flow.core.flow.dto.FlowDtos.RuntimeFlow;
import org.springframework.http.ResponseEntity;
import net.java21.data2flow.core.flow.service.FlowRuntimeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** flow-engine이 부르는 플로우 정의 내부 API(FLW-api §8 API-FLW-80·81, ADR-044). 토큰 없음, X-CALLER-SERVICE(ADR-021) */
@RestController
public class InternalFlowController {

    private final FlowRuntimeService service;
    private final InternalOrganizations organizations;

    public InternalFlowController(FlowRuntimeService service, InternalOrganizations organizations) {
        this.service = service;
        this.organizations = organizations;
    }

    /** API-FLW-80 배포 조직의 실행 대상 전체. sinceVersion이 같으면 204 */
    @GetMapping("/internal/core/flows/runtime")
    public ResponseEntity<ApiResponse<RuntimeFlows>> runtime(@RequestParam(required = false) Long sinceVersion,
                                                             @RequestParam(required = false) Long organizationId) {
        return service.runtimeFlows(organizations.resolve(organizationId), sinceVersion)
                .map(r -> ResponseEntity.ok(ApiResponse.success(r)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** API-FLW-81 플로우 하나(실행 대상이 아니면 404) */
    @GetMapping("/internal/core/flows/{flow-id}/runtime")
    public ApiResponse<RuntimeFlow> flow(@PathVariable("flow-id") String flowId) {
        return ApiResponse.success(service.runtimeFlow(flowId));
    }
}
