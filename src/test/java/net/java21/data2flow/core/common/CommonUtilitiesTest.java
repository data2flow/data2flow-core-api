package net.java21.data2flow.core.common;

import net.java21.data2flow.core.config.CoreProperties;
import net.java21.data2flow.core.mail.service.MailLinks;
import net.java21.data2flow.core.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** 공통 도우미: inet 값 검사, 배열 리터럴, 토큰·해시, 호출 한도, 설정 기본값, 메일 링크 */
class CommonUtilitiesTest {

    @Test
    @DisplayName("[NFR-07] inet 열에는 IP 리터럴만 넣고 호스트 이름·잘못된 값은 버린다(DNS 조회 없음)")
    void inet() {
        assertThat(Pg.inetOrNull("10.0.0.1")).isEqualTo("10.0.0.1");
        assertThat(Pg.inetOrNull(" 192.168.1.255 ")).isEqualTo("192.168.1.255");
        assertThat(Pg.inetOrNull("256.1.1.1")).isNull();
        assertThat(Pg.inetOrNull("example.com")).isNull();
        assertThat(Pg.inetOrNull("::1")).isNotNull();
        assertThat(Pg.inetOrNull("fe80::zz")).isNull();
        assertThat(Pg.inetOrNull("")).isNull();
        assertThat(Pg.inetOrNull(null)).isNull();
    }

    @Test
    @DisplayName("PostgreSQL 배열 리터럴은 큰따옴표·역슬래시를 이스케이프한다")
    void arrays() {
        assertThat(Pg.bigintArray(List.of(1L, 2L))).isEqualTo("{1,2}");
        assertThat(Pg.bigintArray(List.of())).isEqualTo("{}");
        assertThat(Pg.textArray(List.of("A", "b\"c", "d\\e"))).isEqualTo("{\"A\",\"b\\\"c\",\"d\\\\e\"}");
        assertThat(Pg.textArray(null)).isEqualTo("{}");
    }

    @Test
    @DisplayName("[design/auth.md §3.3] 토큰은 32바이트 base64url, DB에는 SHA-256 hex 64자")
    void tokens() {
        String token = Tokens.newToken();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(Tokens.sha256Hex(token)).hasSize(64).isNotEqualTo(Tokens.sha256Hex(Tokens.newToken()));
        assertThat(Tokens.newPassword(16)).hasSize(16);
        assertThat(Tokens.newCode(10)).matches("[A-Z2-9]{10}");
    }

    @Test
    @DisplayName("[OPS-12.05] 슬라이딩 창 한도: 넘으면 다시 시도할 초를 돌려주고, 창이 지나면 다시 허용")
    void rateLimiter() {
        MutableClock clock = MutableClock.atUtc("2026-10-03T00:00:00Z");
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(2, Duration.ofMinutes(1), clock);
        assertThat(limiter.tryAcquire("ip")).isEmpty();
        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.tryAcquire("ip")).isEmpty();
        assertThat(limiter.tryAcquire("ip")).hasValue(50);
        assertThat(limiter.tryAcquire("other")).isEmpty();
        assertThat(limiter.tryAcquire(null)).isEmpty();
        clock.advance(Duration.ofSeconds(51));
        assertThat(limiter.tryAcquire("ip")).isEmpty();
    }

    @Test
    @DisplayName("[conventions §4] 설정 기본값: 초대 72시간, 재설정 30분, 회전 유예 30초, Flyway validate")
    void propertiesDefaults() {
        CoreProperties p = new CoreProperties("https://x/", null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(p.webBaseUrl()).isEqualTo("https://x");
        assertThat(p.authBaseUrl()).isEqualTo("http://data2flow-auth");
        assertThat(p.tokens().invitation()).isEqualTo(Duration.ofHours(72));
        assertThat(p.tokens().passwordReset()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.tokens().refreshGrace()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.outbox().relayEnabled()).isTrue();
        assertThat(p.jobs().enabled()).isTrue();
        assertThat(p.password().hibpEnabled()).isFalse();
        assertThat(p.bootstrap().enabled()).isFalse();
        assertThat(p.flywayMode()).isEqualTo("validate");
    }

    @Test
    @DisplayName("[ADR-037] 메일 링크는 ko만 접두사 없이, 시각은 받는 사람 시간대로")
    void mailLinks() {
        MailLinks links = new MailLinks(new CoreProperties("https://web", null, null, null, null, null, null, null, null, null, null, null, null));
        assertThat(links.link("/invitations/t", Locale.KOREAN)).isEqualTo("https://web/invitations/t");
        assertThat(links.link("/invitations/t", Locale.JAPANESE)).isEqualTo("https://web/ja/invitations/t");
        assertThat(MailLinks.format(Instant.parse("2026-10-03T00:00:00Z"), "Asia/Seoul")).isEqualTo("2026-10-03 09:00 (Asia/Seoul)");
        assertThat(MailLinks.format(Instant.parse("2026-10-03T00:00:00Z"), "bad/zone")).contains("Asia/Seoul");
        assertThat(MailLinks.locale(null)).isEqualTo(Locale.KOREAN);
    }

    @Test
    @DisplayName("[design/auth.md §5] 사용자 IP는 X-Forwarded-For 첫 값, User-Agent는 300자까지")
    void clientInfo() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        request.addHeader("User-Agent", "u".repeat(400));
        ClientInfo info = ClientInfo.from(request);
        assertThat(info.ip()).isEqualTo("203.0.113.9");
        assertThat(info.userAgent()).hasSize(300);
        assertThat(ClientInfo.from(new MockHttpServletRequest()).ip()).isEqualTo("127.0.0.1");
    }
}
