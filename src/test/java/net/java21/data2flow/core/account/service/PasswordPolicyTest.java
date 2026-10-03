package net.java21.data2flow.core.account.service;

import com.sun.net.httpserver.HttpServer;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.account.service.PasswordPolicy.Violation;
import net.java21.data2flow.core.config.CoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** IAM-02.02 비밀번호 정책(BR-IAM-03) — TC-IAM-089, AT-IAM-06.4 */
class PasswordPolicyTest {

    private final PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final BreachedPasswordChecker breached = new BreachedPasswordChecker(properties(false, null));
    private final PasswordPolicy policy = new PasswordPolicy(breached, encoder, messages());

    private static ResourceBundleMessageSource messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    private static CoreProperties properties(boolean hibp, String url) {
        return new CoreProperties(null, null, null, null, null, new CoreProperties.Password(hibp, url), null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("[IAM-02.02][AT-IAM-01.1] 10자 미만·128자 초과는 거부, 정책을 지키면 통과")
    void length() {
        assertThat(policy.violations("Short-1!", null, null, List.of())).containsExactly(Violation.TOO_SHORT);
        assertThat(policy.violations("x".repeat(129), null, null, List.of())).containsExactly(Violation.TOO_LONG);
        assertThat(policy.violations("Vivid-Orbit-73!Lamp", "kim.op", "kim@school.ac.kr", List.of())).isEmpty();
        assertThat(policy.violations(null, null, null, List.of())).contains(Violation.TOO_SHORT);
    }

    @Test
    @DisplayName("[IAM-02.02][AT-IAM-06.4] 아이디·이메일 로컬 부분을 포함하면 거부(대소문자 무시)")
    void containsIdentity() {
        assertThat(policy.violations("My-KIM.OP-Secret-9", "kim.op", null, List.of())).contains(Violation.CONTAINS_LOGIN_ID);
        assertThat(policy.violations("Lee-Gildong-Secret9", null, "lee-gildong@school.ac.kr", List.of()))
                .contains(Violation.CONTAINS_EMAIL);
    }

    @Test
    @DisplayName("[IAM-02.02][AT-IAM-01.2] 유출 목록(로컬 사전)에 있으면 거부")
    void breachedDictionary() {
        assertThat(policy.violations("Password1234", null, null, List.of())).contains(Violation.BREACHED);
        assertThat(policy.violations("qwertyuiop", null, null, List.of())).contains(Violation.BREACHED);
        assertThat(breached.dictionarySize()).isGreaterThan(500);
    }

    @Test
    @DisplayName("[IAM-02.02][AT-IAM-01.3] 최근 3개 비밀번호와 같으면 거부")
    void reuse() {
        List<String> recent = List.of(encoder.encode("Old-Secret-Value-1"), encoder.encode("Old-Secret-Value-2"));
        assertThat(policy.violations("Old-Secret-Value-2", null, null, recent)).containsExactly(Violation.REUSED);
        assertThat(policy.violations("Fresh-Secret-Value-3", null, null, recent)).isEmpty();
    }

    @Test
    @DisplayName("[IAM-02.02] 위반은 400 PASSWORD_POLICY_VIOLATION, 사유는 요청 언어로(ko·en)")
    void exceptionWithLocalizedReason() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        try {
            assertThatThrownBy(() -> policy.check("newPassword", "short", null, null, List.of()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getErrorCode().code()).isEqualTo("PASSWORD_POLICY_VIOLATION");
                        assertThat(be.getErrors().get(0).field()).isEqualTo("newPassword");
                        assertThat(be.getErrors().get(0).message()).isEqualTo("must be at least 10 characters");
                    });
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    @DisplayName("[IAM-02.02][BR-IAM-03] k-익명성 조회: SHA-1 앞 5자만 보내고 응답의 나머지 해시와 비교, 실패하면 로컬 결과만")
    void hibpRange() throws Exception {
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest("Uncommon-Leaked-77".getBytes(StandardCharsets.UTF_8)))
                .toUpperCase(Locale.ROOT);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/range/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            byte[] body = (sha1.substring(5) + ":12\r\n0000000000000000000000000000000000A:0\r\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            BreachedPasswordChecker online = new BreachedPasswordChecker(properties(true, "http://127.0.0.1:" + server.getAddress().getPort()));
            assertThat(online.isBreached("Uncommon-Leaked-77")).isTrue();
            assertThat(online.isBreached("Another-Unique-88")).isFalse();
            assertThat(paths.get(0)).isEqualTo("/range/" + sha1.substring(0, 5));
        } finally {
            server.stop(0);
        }
        BreachedPasswordChecker down = new BreachedPasswordChecker(properties(true, "http://127.0.0.1:1"));
        assertThat(down.isBreached("Uncommon-Leaked-77")).isFalse();
    }
}
