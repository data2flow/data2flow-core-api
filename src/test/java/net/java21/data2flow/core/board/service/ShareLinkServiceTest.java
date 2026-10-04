package net.java21.data2flow.core.board.service;

import net.java21.data2flow.contracts.audit.AuditRecorder;
import net.java21.data2flow.contracts.authz.PermissionLookup;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.board.dto.BoardDtos.ShareLinkCreated;
import net.java21.data2flow.core.board.repository.DashboardBoardRepository;
import net.java21.data2flow.core.board.repository.DashboardBoardRepository.DashboardRow;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.config.CoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DSH-06.03 공유 링크 토큰·만료(BR-DSH-12) — TC-DSH-066 */
class ShareLinkServiceTest {

    static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    final RoleChecker roleChecker = mock(RoleChecker.class);
    final DashboardBoardService boards = mock(DashboardBoardService.class);
    final DashboardBoardRepository repository = mock(DashboardBoardRepository.class);
    final CoreProperties properties = mock(CoreProperties.class);
    final ShareLinkService service = new ShareLinkService(roleChecker, boards, repository, mock(WidgetDataService.class),
            mock(PermissionLookup.class), null, null, new Audits(mock(AuditRecorder.class), Clock.fixed(NOW, ZoneOffset.UTC)), properties,
            JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));

    ShareLinkServiceTest() {
        when(roleChecker.currentUser()).thenReturn(new CurrentUser(3L, 1L, null, null));
        when(boards.visible(9L)).thenReturn(new DashboardRow(9, 1, "운영", null, "ORG", 3, "u", "{\"widgets\":[]}", "[]",
                "{\"relative\":\"24h\"}", "AUTO", "LIVE", null, 1, 3L, "u", NOW, NOW, 0));
        when(properties.webBaseUrl()).thenReturn("https://web.test");
        when(repository.insertShareLink(anyLong(), anyLong(), any(), any(), anyLong(), any())).thenReturn(77L);
    }

    @Test
    @DisplayName("[DSH-06.03][TC-DSH-066][AT-DSH-07.1] 토큰은 32바이트 난수, DB에는 SHA-256 해시만, 원문 URL은 생성 응답에만, 만료 = 지금 + N일")
    void createStoresHashOnly() {
        ShareLinkCreated created = service.create(9L, 7);
        String token = created.url().substring("https://web.test/share/".length());
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repository).insertShareLink(eq(1L), eq(9L), hash.capture(), eq(NOW.plusSeconds(7 * 86400)), eq(3L), eq(NOW));
        assertThat(hash.getValue()).hasSize(64).isEqualTo(Tokens.sha256Hex(token)).doesNotContain(token);
        assertThat(created.id()).isEqualTo("77");
        assertThat(created.expiresAt()).isEqualTo(NOW.plusSeconds(7 * 86400));
        assertThat(service.create(9L, 1).expiresAt()).isEqualTo(NOW.plusSeconds(86400));
        assertThat(service.create(9L, 90).expiresAt()).isEqualTo(NOW.plusSeconds(90L * 86400));
    }

    @Test
    @DisplayName("[DSH-06.03][TC-DSH-066] 만료 0일·91일·없음 → 400, 저장하지 않음")
    void expiryRange() {
        for (Integer days : new Integer[]{0, 91, null}) {
            assertThatThrownBy(() -> service.create(9L, days)).isInstanceOfSatisfying(BusinessException.class,
                    ex -> assertThat(ex.getErrors().getFirst().field()).isEqualTo("expiresInDays"));
        }
        verify(repository, never()).insertShareLink(anyLong(), anyLong(), any(), any(), anyLong(), any());
    }
}
