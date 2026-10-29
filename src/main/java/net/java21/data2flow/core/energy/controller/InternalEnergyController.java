package net.java21.data2flow.core.energy.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.energy.dto.EnergyInternalDtos.ConfigBundle;
import net.java21.data2flow.core.energy.dto.EnergyInternalDtos.Organizations;
import net.java21.data2flow.core.energy.service.EnergyInternalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** analytics가 부르는 에너지 내부 API(API-ENE-70). 토큰 없음, 호출자 표시 X-CALLER-SERVICE(ADR-021) */
@RestController
public class InternalEnergyController {

    private final EnergyInternalService service;

    public InternalEnergyController(EnergyInternalService service) {
        this.service = service;
    }

    /** API-ENE-70 조직 설정 묶음 */
    @GetMapping("/internal/core/energy/config")
    public ApiResponse<ConfigBundle> config(@RequestParam long orgId) {
        return ApiResponse.success(service.bundle(orgId));
    }

    /** API-ENE-70 일 집계 대상 조직(켜진 원천이 있는 조직) */
    @GetMapping("/internal/core/energy/organizations")
    public ApiResponse<Organizations> organizations() {
        return ApiResponse.success(service.organizations());
    }
}
