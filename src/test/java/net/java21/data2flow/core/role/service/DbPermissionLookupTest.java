package net.java21.data2flow.core.role.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.BuiltinRole;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;
import net.java21.data2flow.core.apitoken.service.ApiTokenGrants;
import net.java21.data2flow.core.role.domain.RoleModels.GrantSource;
import net.java21.data2flow.core.role.repository.RoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** IAM-05.01·IAM-04.07: core 원천 판정은 RoleChecker가 넘긴 토큰 ID로 판정하고, 웹 신원은 사용자 역할로 판정한다 */
class DbPermissionLookupTest {

    private final RoleRepository roles = mock(RoleRepository.class);
    private final SpaceDirectory spaces = mock(SpaceDirectory.class);
    private final ApiTokenGrants tokens = mock(ApiTokenGrants.class);
    private final DbPermissionLookup lookup = new DbPermissionLookup(roles, spaces, tokens);

    @AfterEach
    void clear() {
        CurrentUserHolder.clear();
    }

    @Test
    @DisplayName("[IAM-05.01][IAM-04.07] 같은 사용자: 웹 신원은 ADMIN·전체, 토큰 501은 토큰 공간 범위 — 요청 신원 보관소 없이도 토큰 ID로 판정")
    void tokenIdDecidesPrincipal() {
        // given
        when(roles.findGrantSource(1, 7)).thenReturn(Optional.of(new GrantSource("ACTIVE", "ADMIN", List.of(), null)));
        when(tokens.grant(eq(1L), eq(7L), eq(501L), any())).thenReturn(AccessGrant.of(BuiltinRole.ADMIN, SpaceScope.only(Set.of(20L))));
        // when
        AccessGrant web = lookup.find(1, 7, null);
        AccessGrant token = lookup.find(1, 7, 501L);
        // then
        assertThat(web.spaceScope().unrestricted()).isTrue();
        assertThat(token.spaceScope().includes(30L)).isFalse();
        verify(tokens).grant(eq(1L), eq(7L), eq(501L), any());

        // RoleChecker는 요청 신원의 토큰 ID를 넘긴다
        RoleChecker checker = new RoleChecker(lookup, null);
        CurrentUserHolder.set(new CurrentUser(7, 1, 501L, Set.of("read:devices")));
        assertThat(checker.spaceScope().includes(30L)).isFalse();
        assertThat(checker.has(Permission.DEVICE_CONTROL)).isFalse();
        CurrentUserHolder.set(new CurrentUser(7, 1));
        assertThat(checker.spaceScope().includes(30L)).isTrue();
    }

    @Test
    @DisplayName("[IAM-05.01] 쓸 수 없는 토큰이면 웹 권한으로 물러나지 않고 권한 없음")
    void unusableTokenIsNone() {
        when(tokens.grant(anyLong(), anyLong(), anyLong(), any())).thenReturn(AccessGrant.none());
        assertThat(lookup.find(1, 7, 999L).permissions()).isEmpty();
    }
}
