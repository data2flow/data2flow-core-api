package net.java21.data2flow.core.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 역할·권한 API DTO(API-IAM-70·73, 내부 권한 판정) */
public final class RoleDtos {

    private RoleDtos() {
    }

    public record CreateCustomRoleRequest(@NotBlank @Size(max = 40) String name, @Size(max = 200) String description,
                                          @NotEmpty List<String> permissions, String basedOn) {
    }

    public record UpdateCustomRoleRequest(@NotBlank @Size(max = 40) String name, @Size(max = 200) String description,
                                          @NotEmpty List<String> permissions, @NotNull @PositiveOrZero Integer baseVersion) {
    }

    public record CustomRoleResponse(String id, String name, String description, String basedOn, List<String> permissions,
                                     long assignedUsers, int version, Instant updatedAt) {
    }

    /** API-IAM-73 권한 목록 */
    public record PermissionCatalogResponse(List<PermissionItem> permissions, Map<String, List<String>> builtinRoles) {
    }

    public record PermissionItem(String code, String area, String action, boolean customRoleAllowed) {
    }

    /**
     * 내부 권한 판정 응답(다른 서비스의 {@code PermissionLookup}이 쓴다). 사용자가 없거나 ACTIVE가 아니면 active=false·권한 없음.
     */
    public record AccessGrantResponse(String userId, String organizationId, boolean active, String role,
                                      List<String> permissions, SpaceScopeDto spaceScope) {
    }

    public record SpaceScopeDto(boolean unrestricted, List<String> allowedSpaceIds) {
    }
}
