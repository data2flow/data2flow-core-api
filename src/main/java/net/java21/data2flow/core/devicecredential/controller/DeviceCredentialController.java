package net.java21.data2flow.core.devicecredential.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.devicecredential.dto.DeviceCredentialDtos.CredentialResponse;
import net.java21.data2flow.core.devicecredential.dto.DeviceCredentialDtos.IssueCredentialRequest;
import net.java21.data2flow.core.devicecredential.dto.DeviceCredentialDtos.IssuedCredentialResponse;
import net.java21.data2flow.core.devicecredential.service.DeviceCredentialService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** 플랫폼 브로커 기기 자격(design/api/DSC-api.md API-DSC-20~22, DSC-03.02) */
@RestController
public class DeviceCredentialController {

    private final DeviceCredentialService service;

    public DeviceCredentialController(DeviceCredentialService service) {
        this.service = service;
    }

    /** API-DSC-21 — SRC_READ, 200 */
    @GetMapping("/core/devices/{device-id}/credentials")
    public ApiResponse<List<CredentialResponse>> list(@PathVariable("device-id") long deviceId) {
        return ApiResponse.success(service.list(deviceId));
    }

    /**
     * API-DSC-20 — SRC_ADMIN, 201. 응답의 비밀번호·서명 키는 한 번만 보여 주므로 멱등 저장소(응답 본문 보관)에 남기지 않으려고
     * {@code @Idempotent}를 붙이지 않는다(다시 보내면 새 자격이 발급되고 이전 것은 폐기된다).
     */
    @PostMapping("/core/devices/{device-id}/credentials")
    public ResponseEntity<ApiResponse<IssuedCredentialResponse>> issue(@PathVariable("device-id") long deviceId,
                                                                       @Valid @RequestBody(required = false) IssueCredentialRequest request) {
        IssuedCredentialResponse issued = service.issue(deviceId, request);
        return ResponseEntity.created(URI.create("/api/v1/core/devices/" + deviceId + "/credentials/" + issued.credentialId()))
                .body(ApiResponse.success(issued));
    }

    /** API-DSC-22 폐기 — SRC_ADMIN, 200 */
    @PostMapping("/core/devices/{device-id}/credentials/{credential-id}/revoke")
    public ApiResponse<CredentialResponse> revoke(@PathVariable("device-id") long deviceId,
                                                  @PathVariable("credential-id") long credentialId) {
        return ApiResponse.success(service.revoke(deviceId, credentialId));
    }
}
