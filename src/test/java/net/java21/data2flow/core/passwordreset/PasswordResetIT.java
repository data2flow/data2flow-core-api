package net.java21.data2flow.core.passwordreset;

import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.support.Fixtures;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** IAM-02.04 비밀번호 재설정(30분·1회용, BR-IAM-11·12) */
class PasswordResetIT extends IntegrationTestSupport {

    private static final Pattern LINK = Pattern.compile("/password-reset/([A-Za-z0-9_-]{20,})");

    private long org;
    private long user;

    private void setUp() {
        org = fx.organization("reset");
        user = fx.user(org, "kim.op", "OPERATOR");
        fx.mail(org, smtpPort());
    }

    private void request(String value) throws Exception {
        mvc.perform(json(post("/core/password-resets").header("X-Forwarded-For", "10.2.2.2"), "{\"loginIdOrEmail\":\"" + value + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.response.status").value("ACCEPTED"));
    }

    private static String token() throws Exception {
        var messages = MAIL.getReceivedMessages();
        Matcher m = LINK.matcher((String) messages[messages.length - 1].getContent());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("[IAM-02.04][AT-IAM-07.1] 있는 이메일·없는 이메일 모두 202, 있을 때만 메일 — TC-IAM-091")
    void alwaysAccepted() throws Exception {
        setUp();
        request("nobody@school.ac.kr");
        assertThat(mails()).isEmpty();
        request("kim_op@school.ac.kr");
        assertThat(mails()).hasSize(1);
        assertThat(auditCount(org, "PASSWORD_RESET_REQUESTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[IAM-02.04][AT-IAM-07.2] 재설정 완료 → 다른 세션 모두 폐기, 감사 PASSWORD_RESET_COMPLETED, 같은 링크 재사용 410 — TC-IAM-094")
    void confirm() throws Exception {
        setUp();
        jdbc.sql("""
                INSERT INTO data2flow_core.refresh_tokens (jti, session_id, organization_id, user_id, token_hash, expires_at, absolute_expires_at)
                VALUES (gen_random_uuid(), gen_random_uuid(), :o, :u, :h, now() + interval '1 hour', now() + interval '2 hour')""")
                .param("o", org).param("u", user).param("h", Tokens.sha256Hex("x")).update();
        request("kim.op");
        String token = token();

        mvc.perform(json(post("/core/password-resets/" + token + "/confirm"), "{\"newPassword\":\"Brand-New-Secure#2026\"}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.refresh_tokens WHERE revoked_at IS NULL").query(Long.class).single()).isZero();
        assertThat(auditCount(org, "PASSWORD_RESET_COMPLETED")).isEqualTo(1);
        mvc.perform(json(post("/internal/core/users/verify-credentials"), "{\"loginId\":\"kim.op\",\"password\":\"Brand-New-Secure#2026\"}"))
                .andExpect(status().isOk());
        mvc.perform(json(post("/core/password-resets/" + token + "/confirm"), "{\"newPassword\":\"Other-New-Secure#2027\"}"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.header.resultCode").value("RESET_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("[IAM-02.04][AT-IAM-07.3] 31분 지난 링크 → 410 RESET_TOKEN_INVALID, 새 요청 시 이전 링크 무효 — TC-IAM-096")
    void expiredAndReplaced() throws Exception {
        setUp();
        request("kim.op");
        String first = token();
        clock.advance(Duration.ofMinutes(31));
        mvc.perform(json(post("/core/password-resets/" + first + "/confirm"), "{\"newPassword\":\"Brand-New-Secure#2026\"}"))
                .andExpect(status().isGone());
        request("kim.op");
        String second = token();
        request("kim.op");
        mvc.perform(json(post("/core/password-resets/" + second + "/confirm"), "{\"newPassword\":\"Brand-New-Secure#2026\"}"))
                .andExpect(status().isGone());
        mvc.perform(json(post("/core/password-resets/" + token() + "/confirm"), "{\"newPassword\":\"" + Fixtures.PASSWORD + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REUSED"));
        mvc.perform(json(post("/core/password-resets/x/confirm"), "{\"newPassword\":\"Brand-New-Secure#2026\"}")).andExpect(status().isGone());
    }

    @Test
    @DisplayName("[IAM-02.04][AT-IAM-07.4] 같은 계정 10분에 4번 요청 → 4번째도 202지만 메일은 3통 — TC-IAM-097")
    void throttled() throws Exception {
        setUp();
        for (int i = 0; i < 4; i++) {
            request("kim.op");
        }
        assertThat(mails()).hasSize(3);
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        request("kim.op");
        assertThat(mails()).hasSize(4);
    }

    @Test
    @DisplayName("[IAM-02.04][OPS-12.05] 같은 IP가 분당 20회를 넘으면 429 AUTH_RATE_LIMITED + Retry-After")
    void ipRateLimit() throws Exception {
        setUp();
        for (int i = 0; i < 20; i++) {
            mvc.perform(json(post("/core/password-resets").header("X-Forwarded-For", "10.7.7.7"), "{\"loginIdOrEmail\":\"n" + i + "\"}"))
                    .andExpect(status().isAccepted());
        }
        mvc.perform(json(post("/core/password-resets").header("X-Forwarded-For", "10.7.7.7"), "{\"loginIdOrEmail\":\"n\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.header.resultCode").value("AUTH_RATE_LIMITED"));
    }

    @Test
    @DisplayName("[IAM-02.04] 잠긴 계정도 재설정하면 ACTIVE로 풀린다")
    void resetUnlocks() throws Exception {
        setUp();
        jdbc.sql("UPDATE data2flow_core.app_users SET status = 'LOCKED', locked_until = now() + interval '1 day' WHERE id = :id")
                .param("id", user).update();
        request("kim.op");
        mvc.perform(json(post("/core/password-resets/" + token() + "/confirm"), "{\"newPassword\":\"Brand-New-Secure#2026\"}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT status FROM data2flow_core.app_users WHERE id = :id").param("id", user).query(String.class).single())
                .isEqualTo("ACTIVE");
    }
}
