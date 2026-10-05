package net.java21.data2flow.core.role.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.BuiltinRole;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.PermissionLookup;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;
import net.java21.data2flow.core.apitoken.service.ApiTokenGrants;
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
    private final ApiTokenGrants tokens;

    public DbPermissionLookup(RoleRepository roles, SpaceDirectory spaces, ApiTokenGrants tokens) {
        this.roles = roles;
        this.spaces = spaces;
        this.tokens = tokens;
    }

    /**
     * 요청 신원이 장기 토큰(API 키·MCP, {@code X-ACCESS-TOKEN-ID})이고 같은 조직·사용자를 묻는 것이면 토큰으로 판정한다(IAM-04.07).
     * 그 밖(웹 요청, 요청 밖 작업, 다른 사용자 판정)은 사용자 역할로 판정한다.
     */
    @Override
    public AccessGrant find(long organizationId, long userId) {
        CurrentUser current = CurrentUserHolder.find().orElse(null);
        if (current != null && current.viaAccessToken() && current.organizationId() == organizationId && current.userId() == userId) {
            return tokens.grant(organizationId, userId, current.accessTokenId(), () -> userGrant(organizationId, userId));
        }
        return userGrant(organizationId, userId);
    }

    /**
     * 신원을 명시한 판정(RoleChecker가 요청 신원의 토큰 ID를 넘긴다, IAM-05.01). 토큰 ID가 있으면 그 토큰으로, 없으면 사용자 역할로 판정한다.
     * 요청 밖 작업(메시지 소비자 등)에서도 요청 신원 보관소에 기대지 않는다.
     */
    @Override
    public AccessGrant find(long organizationId, long userId, Long accessTokenId) {
        if (accessTokenId != null) {
            return tokens.grant(organizationId, userId, accessTokenId, () -> userGrant(organizationId, userId));
        }
        return userGrant(organizationId, userId);
    }

    /** 장기 토큰 주체의 실효 권한(범위 교집합까지, 내부 access-grant API) */
    public AccessGrant findForToken(long organizationId, long userId, long tokenId) {
        return tokens.effective(organizationId, userId, tokenId, () -> userGrant(organizationId, userId));
    }

    private AccessGrant userGrant(long organizationId, long userId) {
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
