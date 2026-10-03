package net.java21.data2flow.core.organization.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.organization.dto.OrganizationDtos.OrgSettingsResponse;
import net.java21.data2flow.core.organization.dto.OrganizationDtos.SecurityPolicyResponse;
import net.java21.data2flow.core.organization.dto.OrganizationDtos.UpdateOrgSettingsRequest;
import net.java21.data2flow.core.organization.service.OrganizationSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 조직 기본 설정(OPS-07.01)과 보안 정책(IAM-02.03·02.05·03.01·01.08) */
@RestController
public class OrganizationSettingsController {

    private final OrganizationSettingsService service;

    public OrganizationSettingsController(OrganizationSettingsService service) {
        this.service = service;
    }

    /** API-OPS-40 조직 설정 조회(OPS-07.01) — 로그인, 200 */
    @GetMapping("/core/org-settings")
    public ApiResponse<OrgSettingsResponse> detail() {
        return ApiResponse.success(service.settings());
    }

    /** API-OPS-40 조직 설정 저장(OPS-07.01) — ADMIN(OPS_MANAGE), 200 */
    @PutMapping("/core/org-settings")
    public ApiResponse<OrgSettingsResponse> replace(@Valid @RequestBody UpdateOrgSettingsRequest request) {
        return ApiResponse.success(service.updateSettings(request));
    }

    /** API-IAM-72 보안 정책 조회 — ADMIN, 200 */
    @GetMapping("/core/security-policy")
    public ApiResponse<SecurityPolicyResponse> policy() {
        return ApiResponse.success(service.policy());
    }

    /** API-IAM-72 보안 정책 저장(들어온 필드만) — ADMIN, 200 */
    @PutMapping("/core/security-policy")
    public ApiResponse<SecurityPolicyResponse> replacePolicy(@RequestBody JsonNode body) {
        return ApiResponse.success(service.updatePolicy(body));
    }
}
