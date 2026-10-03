package net.java21.data2flow.core.role.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.BuiltinRole;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.PermissionLookup;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.core.role.domain.RoleModels;
import net.java21.data2flow.core.role.domain.RoleModels.GrantSource;
import net.java21.data2flow.core.role.repository.RoleRepository;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * core-api의 권한 원천 판정(design/auth.md §7, contracts README §5.1). 요청마다 DB에서 읽고 캐시하지 않는다(BR-IAM-13).
 * 사용자가 없거나, ACTIVE가 아니거나, 역할이 없으면 {@link AccessGrant#none()}(기본 거부).
 * 사용자 정의 역할의 권한 중 모르는 이름과 ADMIN 전용 권한(IAM_MANAGE·AUDIT_READ)은 무시한다(BR-IAM-30).
 */
@Component
public class DbPermissionLookup implements PermissionLookup {

    private final RoleRepository roles;
    private final SpaceDirectory spaces;

    public DbPermissionLookup(RoleRepository roles, SpaceDirectory spaces) {
        this.roles = roles;
        this.spaces = spaces;
    }

    @Override
    public AccessGrant find(long organizationId, long userId) {
        return roles.findGrantSource(organizationId, userId)
                .filter(src -> "ACTIVE".equals(src.status()) && src.role() != null)
                .map(src -> toGrant(organizationId, src))
                .orElse(AccessGrant.none());
    }

    private AccessGrant toGrant(long organizationId, GrantSource src) {
        SpaceScope scope = src.spaceScope().isEmpty()
                ? SpaceScope.all()
                : SpaceScope.only(spaces.expandWithDescendants(organizationId, src.spaceScope()));
        if (RoleModels.CUSTOM.equals(src.role())) {
            return AccessGrant.custom(customPermissions(src.customPermissions()), scope);
        }
        return AccessGrant.of(BuiltinRole.valueOf(src.role()), scope);
    }

    static Set<Permission> customPermissions(List<String> names) {
        Set<Permission> result = EnumSet.noneOf(Permission.class);
        if (names == null) {
            return result;
        }
        for (String name : names) {
            for (Permission p : Permission.values()) {
                if (p.name().equals(name) && p.customRoleAllowed()) {
                    result.add(p);
                }
            }
        }
        return result;
    }
}
