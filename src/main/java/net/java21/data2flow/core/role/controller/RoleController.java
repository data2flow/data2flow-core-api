package net.java21.data2flow.core.role.controller;

import jakarta.validation.Valid;
import net.java21.data2flow.contracts.idempotency.Idempotent;
import net.java21.data2flow.contracts.web.ApiResponse;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.core.role.dto.RoleDtos.CreateCustomRoleRequest;
import net.java21.data2flow.core.role.dto.RoleDtos.CustomRoleResponse;
import net.java21.data2flow.core.role.dto.RoleDtos.PermissionCatalogResponse;
import net.java21.data2flow.core.role.dto.RoleDtos.UpdateCustomRoleRequest;
import net.java21.data2flow.core.role.service.CustomRoleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** 권한 목록·사용자 정의 역할(IAM-04.01·04.03·04.04) */
@RestController
public class RoleController {

    private final CustomRoleService service;

    public RoleController(CustomRoleService service) {
        this.service = service;
    }

    /** API-IAM-73 권한 목록(IAM-04.01, 04.04) — 로그인, 200 */
    @GetMapping("/core/permissions")
    public ApiResponse<PermissionCatalogResponse> permissions() {
        return ApiResponse.success(service.catalog());
    }

    /** API-IAM-70 사용자 정의 역할 목록(IAM-04.03) — ADMIN, 200 */
    @GetMapping("/core/custom-roles")
    public ListApiResponse<CustomRoleResponse> list(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size) {
        return service.list(page, size);
    }

    /** API-IAM-70 사용자 정의 역할 생성(IAM-04.03) — ADMIN, 201 */
    @PostMapping("/core/custom-roles")
    @Idempotent
    public ResponseEntity<ApiResponse<CustomRoleResponse>> create(@Valid @RequestBody CreateCustomRoleRequest request) {
        CustomRoleResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/core/custom-roles/" + created.id())).body(ApiResponse.success(created));
    }

    /** API-IAM-70 사용자 정의 역할 수정(IAM-04.03) — ADMIN, 200 */
    @PutMapping("/core/custom-roles/{custom-role-id}")
    public ApiResponse<CustomRoleResponse> replace(@PathVariable("custom-role-id") long customRoleId,
                                                   @Valid @RequestBody UpdateCustomRoleRequest request) {
        return ApiResponse.success(service.update(customRoleId, request));
    }

    /** API-IAM-70 사용자 정의 역할 삭제(IAM-04.03, AT-IAM-17.3) — ADMIN, 204 */
    @DeleteMapping("/core/custom-roles/{custom-role-id}")
    public ResponseEntity<Void> delete(@PathVariable("custom-role-id") long customRoleId) {
        service.delete(customRoleId);
        return ResponseEntity.noContent().build();
    }
}
