package net.java21.data2flow.core.role.controller;

import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.core.role.dto.RoleDtos.AccessGrantResponse;
import net.java21.data2flow.core.role.service.CustomRoleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 다른 서비스의 권한 판정(api-rules §7 "다른 서비스의 권한 판정이 필요하면 core 내부 API로 묻는다", contracts README §5.1).
 * 호출 쪽은 {@code CachingPermissionLookup}(10초 이내)으로 감싸서 쓴다(BR-IAM-13).
 */
@RestController
public class InternalAccessGrantController {

    private final CustomRoleService service;

    public InternalAccessGrantController(CustomRoleService service) {
        this.service = service;
    }

    /** 내부 권한 판정(IAM-04.01·04.05) — 내부(모든 서비스), 200. 없는 사용자·비활성·다른 조직은 active=false */
    @GetMapping("/internal/core/organizations/{organization-id}/users/{user-id}/access-grant")
    public ApiResponse<AccessGrantResponse> accessGrant(@PathVariable("organization-id") long organizationId,
                                                        @PathVariable("user-id") long userId) {
        return ApiResponse.success(service.accessGrant(organizationId, userId));
    }
}
