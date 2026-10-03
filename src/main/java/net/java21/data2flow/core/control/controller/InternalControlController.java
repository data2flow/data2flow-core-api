package net.java21.data2flow.core.control.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.common.InternalOrganizations;
import net.java21.data2flow.core.common.ItemsResponse;
import net.java21.data2flow.core.control.dto.ControlDtos.CapabilityResponse;
import net.java21.data2flow.core.control.dto.ControlDtos.ControlProfileResponse;
import net.java21.data2flow.core.control.dto.ControlDtos.InternalControlSettings;
import net.java21.data2flow.core.control.dto.ControlDtos.SandboxSpaces;
import net.java21.data2flow.core.control.service.CapabilityService;
import net.java21.data2flow.core.control.service.ControlInternalService;
import net.java21.data2flow.core.control.service.ControlSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** action이 부르는 제어 정의 내부 API(ACT-api §5.4 API-ACT-40·41, 조직 설정·사용자 정의 기능). 토큰 없음, X-CALLER-SERVICE(ADR-021) */
@RestController
public class InternalControlController {

    private final ControlInternalService service;
    private final CapabilityService capabilities;
    private final ControlSettingsService settings;
    private final InternalOrganizations organizations;

    public InternalControlController(ControlInternalService service, CapabilityService capabilities, ControlSettingsService settings,
                                     InternalOrganizations organizations) {
        this.service = service;
        this.capabilities = capabilities;
        this.settings = settings;
        this.organizations = organizations;
    }

    /** API-ACT-40 기기 제어 정보(기기 ID 전역 고유, 응답에 organizationId) */
    @GetMapping("/internal/core/devices/{device-id}/control-profile")
    public ApiResponse<ControlProfileResponse> profile(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(service.profile(deviceId));
    }

    /** API-ACT-41 조직의 사용자 정의 기능(표준 7종은 contracts) */
    @GetMapping("/internal/core/capabilities")
    public ItemsResponse<CapabilityResponse> capabilities(@RequestParam(required = false) Long organizationId) {
        return ItemsResponse.of(capabilities.internalCustom(organizations.resolve(organizationId)));
    }

    /** API-ACT-42 조직 제어 설정 */
    @GetMapping("/internal/core/control-settings")
    public ApiResponse<InternalControlSettings> settings(@RequestParam(required = false) Long organizationId) {
        return ApiResponse.success(settings.internal(organizations.resolve(organizationId)));
    }

    /** API-ACT-41 샌드박스 공간(하위 펼침). organizationId가 없으면 배포 조직 전체 */
    @GetMapping("/internal/core/sim/sandbox-spaces")
    public ApiResponse<SandboxSpaces> sandbox(@RequestParam(required = false) Long organizationId) {
        return ApiResponse.success(service.sandbox(organizationId == null ? organizations.deploymentOrganizations()
                : java.util.List.of(organizations.resolve(organizationId))));
    }
}
