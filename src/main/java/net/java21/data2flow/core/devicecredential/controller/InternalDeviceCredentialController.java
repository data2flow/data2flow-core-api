package net.java21.data2flow.core.devicecredential.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.devicecredential.dto.DeviceCredentialDtos.SigningKeysResponse;
import net.java21.data2flow.core.devicecredential.service.DeviceCredentialService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 API(클러스터 안, 토큰 없음·X-CALLER-SERVICE 표시, ADR-021): API-DSC-72 ingress가 플랫폼 브로커 기기 payload 서명을 검증할 키
 * (DSC-03.02·03.03·03.05, ADR-042). 응답에 원문 서명 키가 있으므로 로그에 남기지 않는다.
 */
@RestController
public class InternalDeviceCredentialController {

    private final DeviceCredentialService service;

    public InternalDeviceCredentialController(DeviceCredentialService service) {
        this.service = service;
    }

    @GetMapping("/internal/core/device-credentials/signing-keys")
    public ApiResponse<SigningKeysResponse> signingKeys() {
        return ApiResponse.success(service.signingKeys());
    }
}
