package net.java21.data2flow.core.role.service;

import net.java21.data2flow.contracts.authz.BuiltinRole;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.common.CoreErrorCode;
import net.java21.data2flow.core.role.domain.RoleModels;
import net.java21.data2flow.core.role.repository.RoleRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 역할 지정 값 검사(초대·직접 생성·역할 변경·가입 승인이 함께 쓴다). 역할은 기본 5개 또는 CUSTOM(+ 조직의 사용자 정의 역할),
 * 공간 범위는 조직에 있는 공간만(없으면 {@code SPACE_SCOPE_INVALID}, IAM-04.02).
 */
@Component
public class RoleAssignments {

    private final RoleRepository roles;
    private final SpaceDirectory spaces;

    public RoleAssignments(RoleRepository roles, SpaceDirectory spaces) {
        this.roles = roles;
        this.spaces = spaces;
    }

    /**
     * @param role         역할 이름(대소문자 무시)
     * @param customRoleId CUSTOM일 때 필수
     * @param spaceScope   공간 ID 문자열 목록(비면 전체)
     */
    public Assignment validate(long organizationId, String role, String customRoleId, List<String> spaceScope) {
        if (role == null || role.isBlank()) {
            throw invalid("role", "NotBlank");
        }
        String normalized = role.strip().toUpperCase(Locale.ROOT);
        Long customId = null;
        if (RoleModels.CUSTOM.equals(normalized)) {
            customId = parseId(customRoleId, "customRoleId");
            if (customId == null || roles.findCustomRole(organizationId, customId).isEmpty()) {
                throw invalid("customRoleId", "NOT_FOUND");
            }
        } else {
            try {
                BuiltinRole.valueOf(normalized);
            } catch (IllegalArgumentException ex) {
                throw invalid("role", "INVALID");
            }
        }
        List<Long> scope = new ArrayList<>();
        if (spaceScope != null) {
            Set<Long> unique = new LinkedHashSet<>();
            for (String raw : spaceScope) {
                Long id = parseId(raw, "spaceScope");
                if (id == null) {
                    throw new BusinessException(CoreErrorCode.SPACE_SCOPE_INVALID);
                }
                unique.add(id);
            }
            if (!unique.isEmpty() && !spaces.existingSpaceIds(organizationId, unique).containsAll(unique)) {
                throw new BusinessException(CoreErrorCode.SPACE_SCOPE_INVALID);
            }
            scope.addAll(unique);
        }
        return new Assignment(normalized, customId, scope);
    }

    private static Long parseId(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!raw.matches("\\d{1,18}")) {
            throw invalid(field, "Pattern");
        }
        return Long.parseLong(raw);
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }

    /** 검사한 역할 지정 */
    public record Assignment(String role, Long customRoleId, List<Long> spaceScope) {
    }
}
