package net.java21.data2flow.core.role.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.BuiltinRole;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.PermissionLookup;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.account.event.IamEventPublisher;
import net.java21.data2flow.core.audit.service.AuditCodes;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.CoreErrorCode;
import net.java21.data2flow.core.role.domain.RoleModels.CustomRole;
import net.java21.data2flow.core.role.dto.RoleDtos.AccessGrantResponse;
import net.java21.data2flow.core.role.dto.RoleDtos.CreateCustomRoleRequest;
import net.java21.data2flow.core.role.dto.RoleDtos.CustomRoleResponse;
import net.java21.data2flow.core.role.dto.RoleDtos.PermissionCatalogResponse;
import net.java21.data2flow.core.role.dto.RoleDtos.PermissionItem;
import net.java21.data2flow.core.role.dto.RoleDtos.SpaceScopeDto;
import net.java21.data2flow.core.role.dto.RoleDtos.UpdateCustomRoleRequest;
import net.java21.data2flow.core.role.repository.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 사용자 정의 역할(IAM-04.03, BR-IAM-30)과 권한 목록(IAM-04.01·04.04, API-IAM-73), 다른 서비스용 권한 판정.
 * 역할 권한을 바꾸면 그 역할을 쓰는 사용자의 다음 요청부터 반영된다(요청마다 DB 판정, AT-IAM-17.2).
 */
@Service
public class CustomRoleService {

    private final RoleRepository roles;
    private final RoleChecker roleChecker;
    private final PermissionLookup permissionLookup;
    private final IamEventPublisher events;
    private final Audits audits;
    private final Clock clock;

    public CustomRoleService(RoleRepository roles, RoleChecker roleChecker, PermissionLookup permissionLookup,
                             IamEventPublisher events, Audits audits, Clock clock) {
        this.roles = roles;
        this.roleChecker = roleChecker;
        this.permissionLookup = permissionLookup;
        this.events = events;
        this.audits = audits;
        this.clock = clock;
    }

    /** API-IAM-73 권한 목록과 기본 역할 권한표(로그인 사용자) */
    public PermissionCatalogResponse catalog() {
        roleChecker.currentUser();
        List<PermissionItem> items = Arrays.stream(Permission.values())
                .map(p -> new PermissionItem(p.name(), p.area(), action(p), p.customRoleAllowed())).toList();
        Map<String, List<String>> builtin = new LinkedHashMap<>();
        for (BuiltinRole role : BuiltinRole.values()) {
            builtin.put(role.name(), role.permissions().stream().map(Enum::name).sorted().toList());
        }
        return new PermissionCatalogResponse(items, builtin);
    }

    @Transactional(readOnly = true)
    public ListApiResponse<CustomRoleResponse> list(Integer page, Integer size) {
        roleChecker.requireAdmin();
        long orgId = roleChecker.currentUser().organizationId();
        PageParams params = PageParams.of(page, size);
        List<CustomRoleResponse> items = roles.listCustomRoles(orgId, params.size(), params.offset()).stream()
                .map(r -> toResponse(orgId, r)).toList();
        return ListApiResponse.of(params, items, roles.countCustomRoles(orgId));
    }

    @Transactional
    public CustomRoleResponse create(CreateCustomRoleRequest req) {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        String name = validateName(orgId, req.name(), null);
        List<String> permissions = validatePermissions(req.permissions());
        String basedOn = req.basedOn() == null || req.basedOn().isBlank() ? null : req.basedOn().toUpperCase(Locale.ROOT);
        if (basedOn != null && Arrays.stream(BuiltinRole.values()).noneMatch(r -> r.name().equals(basedOn))) {
            throw invalid("basedOn", "INVALID");
        }
        long id = roles.insertCustomRole(orgId, name, req.description(), permissions, basedOn, user.userId(), clock.instant());
        audits.record(audits.event(orgId, AuditCodes.CUSTOM_ROLE_CREATED).actor(user).target("CUSTOM_ROLE", Long.toString(id))
                .detail("name", name).detail("permissions", permissions));
        events.roleChanged(orgId, id, permissions, 0);
        return toResponse(orgId, roles.findCustomRole(orgId, id).orElseThrow());
    }

    @Transactional
    public CustomRoleResponse update(long customRoleId, UpdateCustomRoleRequest req) {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        CustomRole before = roles.findCustomRole(orgId, customRoleId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        VersionCheck.require(req.baseVersion(), before.version());
        String name = validateName(orgId, req.name(), customRoleId);
        List<String> permissions = validatePermissions(req.permissions());
        VersionCheck.requireUpdated(roles.updateCustomRole(orgId, customRoleId, req.baseVersion(), name, req.description(),
                permissions, user.userId(), clock.instant()));
        audits.record(audits.event(orgId, AuditCodes.CUSTOM_ROLE_UPDATED).actor(user)
                .target("CUSTOM_ROLE", Long.toString(customRoleId))
                .detail("before", Map.of("name", before.name(), "permissions", before.permissions()))
                .detail("after", Map.of("name", name, "permissions", permissions)));
        events.roleChanged(orgId, customRoleId, permissions, before.version() + 1);
        return toResponse(orgId, roles.findCustomRole(orgId, customRoleId).orElseThrow());
    }

    /** 사용 중이면 409 CUSTOM_ROLE_IN_USE(AT-IAM-17.3) */
    @Transactional
    public void delete(long customRoleId) {
        roleChecker.requireAdmin();
        roleChecker.requireInteractive();
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        CustomRole role = roles.findCustomRole(orgId, customRoleId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (roles.countUsersWithCustomRole(orgId, customRoleId) > 0) {
            throw new BusinessException(CoreErrorCode.CUSTOM_ROLE_IN_USE);
        }
        roles.deleteCustomRole(orgId, customRoleId);
        audits.record(audits.event(orgId, AuditCodes.CUSTOM_ROLE_DELETED).actor(user)
                .target("CUSTOM_ROLE", Long.toString(customRoleId)).detail("name", role.name()));
        events.roleChanged(orgId, customRoleId, List.of(), role.version() + 1);
    }

    /** 다른 서비스의 PermissionLookup이 묻는 내부 판정(조직이 다르거나 비활성이면 권한 없음) */
    @Transactional(readOnly = true)
    public AccessGrantResponse accessGrant(long organizationId, long userId) {
        AccessGrant grant = permissionLookup.find(organizationId, userId);
        boolean active = !grant.permissions().isEmpty() || !"NONE".equals(grant.role());
        return new AccessGrantResponse(Long.toString(userId), Long.toString(organizationId), active, active ? grant.role() : null,
                grant.permissions().stream().map(Enum::name).sorted().toList(),
                new SpaceScopeDto(grant.spaceScope().unrestricted(),
                        grant.spaceScope().allowedSpaceIds().stream().sorted().map(String::valueOf).toList()));
    }

    private CustomRoleResponse toResponse(long orgId, CustomRole r) {
        return new CustomRoleResponse(Long.toString(r.id()), r.name(), r.description(), r.basedOn(), r.permissions(),
                roles.countUsersWithCustomRole(orgId, r.id()), r.version(), r.updatedAt());
    }

    private String validateName(long orgId, String raw, Long exceptId) {
        String name = raw.strip();
        if (Arrays.stream(BuiltinRole.values()).anyMatch(r -> r.name().equalsIgnoreCase(name)) || "CUSTOM".equalsIgnoreCase(name)) {
            throw invalid("name", "RESERVED");
        }
        if (roles.existsCustomRoleName(orgId, name, exceptId)) {
            throw invalid("name", "DUPLICATED");
        }
        return name;
    }

    /** BR-IAM-30: Permission 목록 안에서만, IAM_MANAGE·AUDIT_READ 금지 */
    static List<String> validatePermissions(List<String> raw) {
        Set<String> result = new LinkedHashSet<>();
        List<FieldErrorDetail> unknown = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String name = raw.get(i) == null ? "" : raw.get(i).strip().toUpperCase(Locale.ROOT);
            Permission permission;
            try {
                permission = Permission.valueOf(name);
            } catch (IllegalArgumentException ex) {
                unknown.add(new FieldErrorDetail("permissions[" + i + "]", "INVALID", null));
                continue;
            }
            if (!permission.customRoleAllowed()) {
                throw new BusinessException(CoreErrorCode.CUSTOM_ROLE_PERMISSION_FORBIDDEN);
            }
            result.add(permission.name());
        }
        if (!unknown.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, unknown);
        }
        return List.copyOf(result);
    }

    static String action(Permission p) {
        int idx = p.name().indexOf('_');
        return idx < 0 ? p.name() : p.name().substring(idx + 1);
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
