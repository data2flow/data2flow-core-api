package net.java21.data2flow.core.role.service;

import net.java21.data2flow.contracts.authz.BuiltinRole;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;
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
 * 지정하는 관리자 자신이 공간 범위로 제한돼 있으면 자기 범위 안 공간만 줄 수 있고 전체 범위(빈 목록)는 줄 수 없다
 * (권한 상승 방지, TC-IAM-060 "범위 밖 공간 지정" → {@code SPACE_SCOPE_INVALID}).
 */
@Component
public class RoleAssignments {

    private final RoleRepository roles;
    private final SpaceDirectory spaces;
    private final RoleChecker roleChecker;

    public RoleAssignments(RoleRepository roles, SpaceDirectory spaces, RoleChecker roleChecker) {
        this.roles = roles;
        this.spaces = spaces;
        this.roleChecker = roleChecker;
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
        requireWithinAssignerScope(scope);
        return new Assignment(normalized, customId, scope);
    }

    /** 요청 사용자(관리자)가 공간 범위로 제한돼 있으면 그 안에서만 지정할 수 있다 */
    private void requireWithinAssignerScope(List<Long> scope) {
        if (CurrentUserHolder.find().isEmpty()) {
            return;
        }
        SpaceScope assigner = roleChecker.spaceScope();
        if (!assigner.unrestricted() && (scope.isEmpty() || !assigner.allowedSpaceIds().containsAll(scope))) {
            throw new BusinessException(CoreErrorCode.SPACE_SCOPE_INVALID);
        }
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
