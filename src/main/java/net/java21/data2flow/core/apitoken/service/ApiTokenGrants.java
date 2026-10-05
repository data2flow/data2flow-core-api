package net.java21.data2flow.core.apitoken.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.ApiScope;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.core.apitoken.domain.TokenUsability;
import net.java21.data2flow.core.apitoken.repository.ApiTokenRepository;
import net.java21.data2flow.core.apitoken.repository.ApiTokenRepository.TokenRow;
import net.java21.data2flow.core.role.service.SpaceDirectory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 장기 토큰 요청의 권한 원천(IAM-04.07, design/auth.md §8 "core는 TokenScope처럼 2차 방어"). gateway가 넣은 {@code X-ACCESS-TOKEN-ID}로
 * 토큰을 DB에서 다시 찾아 판정한다(헤더의 범위만 믿지 않는다).
 *
 * <ul>
 *   <li>토큰이 없거나 다른 조직·쓸 수 없는 상태(폐기·만료·유예 끝·소유자 비활성)면 {@link AccessGrant#none()} → 403</li>
 *   <li>사용자 토큰: 소유자가 요청 사용자와 같아야 하고, 사용자 역할 권한 그대로 두되 공간 범위를 토큰 {@code space_scope}로 좁힌다
 *       (발급자 범위의 부분집합). 범위 권한과의 교집합은 RoleChecker가 {@code X-TOKEN-SCOPE}로 한 번 더 한다</li>
 *   <li>서비스 계정 토큰: 사람이 아니므로 역할이 없다. 토큰 범위({@link ApiScope})가 여는 권한만, 공간은 토큰 {@code space_scope}(비면 전체).
 *       {@code X-USER-ID}로 온 값(계정 ID)은 사용자로 해석하지 않는다</li>
 * </ul>
 */
@Component
public class ApiTokenGrants {

    /** 서비스 계정 토큰 권한의 역할 표시(판정은 permissions로 한다) */
    public static final String SERVICE_ACCOUNT_ROLE = "SERVICE_ACCOUNT";

    private final ApiTokenRepository tokens;
    private final SpaceDirectory spaces;
    private final Clock clock;

    public ApiTokenGrants(ApiTokenRepository tokens, SpaceDirectory spaces, Clock clock) {
        this.tokens = tokens;
        this.spaces = spaces;
        this.clock = clock;
    }

    /**
     * 토큰 요청의 권한.
     *
     * @param userGrant 사용자 토큰일 때 소유자 역할 권한(원천 판정)
     */
    public AccessGrant grant(long organizationId, long userId, long tokenId, Supplier<AccessGrant> userGrant) {
        TokenRow t = tokens.find(organizationId, tokenId).orElse(null);
        if (t == null || !TokenUsability.usable(t, clock.instant())) {
            return AccessGrant.none();
        }
        SpaceScope tokenScope = t.spaceScope().isEmpty() ? SpaceScope.all()
                : SpaceScope.only(spaces.expandWithDescendants(organizationId, t.spaceScope()));
        if (t.serviceAccount()) {
            if (t.ownerId() != userId) {
                return AccessGrant.none();
            }
            Set<Permission> permissions = EnumSet.noneOf(Permission.class);
            for (String code : t.scopes()) {
                ApiScope.fromCode(code).ifPresent(s -> permissions.addAll(s.permissions()));
            }
            return new AccessGrant(SERVICE_ACCOUNT_ROLE, permissions, tokenScope);
        }
        if (t.ownerId() != userId) {
            return AccessGrant.none();
        }
        AccessGrant base = userGrant.get();
        return new AccessGrant(base.role(), base.permissions(), intersect(base.spaceScope(), tokenScope));
    }

    /**
     * 토큰 주체의 실효 권한(내부 access-grant용): {@link #grant}에 토큰 범위({@link ApiScope})가 여는 권한을 교집합으로 더 적용한다
     * (사용자 토큰 = 소유자 역할 권한 ∩ 범위 권한 ∩ 토큰 공간 범위, 서비스 계정 = 범위 권한 ∩ 토큰 공간 범위). 쓸 수 없는 토큰은 none
     */
    public AccessGrant effective(long organizationId, long userId, long tokenId, Supplier<AccessGrant> userGrant) {
        AccessGrant g = grant(organizationId, userId, tokenId, userGrant);
        if (g.permissions().isEmpty()) {
            return g;
        }
        Set<Permission> scoped = EnumSet.noneOf(Permission.class);
        for (String code : tokens.find(organizationId, tokenId).map(t -> t.scopes()).orElse(java.util.List.of())) {
            ApiScope.fromCode(code).ifPresent(s -> scoped.addAll(s.permissions()));
        }
        scoped.retainAll(g.permissions());
        return new AccessGrant(g.role(), scoped, g.spaceScope());
    }

    static SpaceScope intersect(SpaceScope a, SpaceScope b) {
        if (a.unrestricted()) {
            return b;
        }
        if (b.unrestricted()) {
            return a;
        }
        Set<Long> both = new HashSet<>(a.allowedSpaceIds());
        both.retainAll(b.allowedSpaceIds());
        return SpaceScope.only(both);
    }
}
