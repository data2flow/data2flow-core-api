package net.java21.data2flow.core.role.domain;

import java.time.Instant;
import java.util.List;

/** 역할 값(domain-model §2.3·§2.10) */
public final class RoleModels {

    public static final String CUSTOM = "CUSTOM";

    private RoleModels() {
    }

    /** {@code user_roles} 한 행. spaceScope가 비어 있으면 전체 공간(IAM-04.02) */
    public record UserRole(long organizationId, long userId, String role, Long customRoleId, List<Long> spaceScope,
                           long grantedBy, Instant grantedAt) {
    }

    /** {@code custom_roles} 한 행(IAM-04.03) */
    public record CustomRole(long id, long organizationId, String name, String description, List<String> permissions,
                             String basedOn, int version, Instant updatedAt) {
    }

    /** 권한 판정 원천 한 줄: 사용자 상태 + 역할 + 사용자 정의 역할 권한 */
    public record GrantSource(String status, String role, List<Long> spaceScope, List<String> customPermissions) {
    }
}
