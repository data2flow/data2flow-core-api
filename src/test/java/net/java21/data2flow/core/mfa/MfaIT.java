package net.java21.data2flow.core.mfa;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.mfa.service.Totp;
import net.java21.data2flow.core.support.Fixtures;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** IAM-02.05 2단계 인증(TOTP, BR-IAM-25·26·27) — core 쪽(등록·복구 코드·로그인 코드 확인 API-IAM-61b) */
class MfaIT extends IntegrationTestSupport {

    private long org;
    private long user;

    private void setUp() {
        org = fx.organization("mfa");
        user = fx.user(org, "kim.op", "OPERATOR");
    }

    private String setupSecret() throws Exception {
        String body = mvc.perform(as(org, user, post("/core/accounts/me/mfa/setup"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.otpauthUri").value(startsWith("otpauth://totp/data2flow:kim.op?secret=")))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.response.secret");
    }

    private List<String> enable() throws Exception {
        String secret = setupSecret();
        String body = mvc.perform(as(org, user, json(post("/core/accounts/me/mfa/confirm"),
                        "{\"code\":\"" + Totp.codeAt(secret, clock.instant()) + "\"}")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        this.secret = secret;
        return JsonPath.read(body, "$.response.recoveryCodes");
    }

    private String secret;

    private org.springframework.test.web.servlet.ResultActions verify(String code) throws Exception {
        return mvc.perform(json(post("/internal/core/users/" + user + "/mfa/verify"), "{\"code\":\"" + code + "\"}"));
    }

    @Test
    @DisplayName("[IAM-02.05][AT-IAM-14.1] QR 등록 후 올바른 코드 확인 → totp_enabled, 복구 코드 10개 1회, 감사 MFA_ENABLED, 비밀값은 암호문 — TC-IAM-098")
    void enableMfa() throws Exception {
        setUp();
        List<String> codes = enable();
        assertThat(codes).hasSize(10).allMatch(c -> c.matches("[A-Z2-9]{5}-[A-Z2-9]{5}"));
        assertThat(jdbc.sql("SELECT totp_enabled FROM data2flow_core.app_users WHERE id = :id").param("id", user).query(Boolean.class).single()).isTrue();
        byte[] stored = jdbc.sql("SELECT totp_secret_enc FROM data2flow_core.app_users WHERE id = :id").param("id", user).query(byte[].class).single();
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(secret);
        assertThat(auditCount(org, "MFA_ENABLED")).isEqualTo(1);
        verify(Totp.codeAt(secret, clock.instant())).andExpect(status().isOk()).andExpect(jsonPath("$.response.ok").value(true));
        mvc.perform(json(post("/internal/core/users/verify-credentials"), "{\"loginId\":\"kim.op\",\"password\":\"" + Fixtures.PASSWORD + "\"}"))
                .andExpect(jsonPath("$.response.mfaEnabled").value(true));
        mvc.perform(as(org, user, post("/core/accounts/me/mfa/setup"))).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("[IAM-02.05][AT-IAM-14.3] 30초 이전 코드(1단계 차이)는 성공, 2단계 차이는 401 MFA_CODE_INVALID — TC-IAM-102")
    void windowOfOneStep() throws Exception {
        setUp();
        enable();
        verify(Totp.codeAt(secret, clock.instant().minusSeconds(30))).andExpect(status().isOk());
        verify(Totp.codeAt(secret, clock.instant().minusSeconds(90))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("MFA_CODE_INVALID"));
    }

    @Test
    @DisplayName("[IAM-02.05][AT-IAM-14.4] 복구 코드로 통과 후 같은 코드 재사용 → 401 MFA_CODE_INVALID, 감사 MFA_RECOVERY_USED — TC-IAM-103")
    void recoveryCodeIsSingleUse() throws Exception {
        setUp();
        List<String> codes = enable();
        verify(codes.get(0).toLowerCase()).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.recoveryCodeUsed").value(true))
                .andExpect(jsonPath("$.response.remainingRecoveryCodes").value(9));
        verify(codes.get(0)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.header.resultCode").value("MFA_CODE_INVALID"));
        assertThat(auditCount(org, "MFA_RECOVERY_USED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[IAM-02.05][BR-IAM-27] TOTP 연속 5회 실패도 로그인 실패 횟수에 합산해 잠근다")
    void totpFailuresLock() throws Exception {
        setUp();
        enable();
        for (int i = 0; i < 5; i++) {
            verify("000000").andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.sql("SELECT status FROM data2flow_core.app_users WHERE id = :id").param("id", user).query(String.class).single())
                .isEqualTo("LOCKED");
        verify(Totp.codeAt(secret, clock.instant())).andExpect(status().isUnauthorized());
        assertThat(auditCount(org, "USER_LOCKED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[IAM-02.05] 등록 임시 비밀은 5분 뒤 무효, 잘못된 코드는 401")
    void setupExpires() throws Exception {
        setUp();
        String s = setupSecret();
        mvc.perform(as(org, user, json(post("/core/accounts/me/mfa/confirm"), "{\"code\":\"123\"}"))).andExpect(status().isUnauthorized());
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        mvc.perform(as(org, user, json(post("/core/accounts/me/mfa/confirm"), "{\"code\":\"" + Totp.codeAt(s, clock.instant()) + "\"}")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[IAM-02.05][BR-IAM-26] 복구 코드 재발급·끄기는 현재 비밀번호 확인, 관리자 초기화는 보안 경고 MFA_RESET")
    void manage() throws Exception {
        setUp();
        List<String> old = enable();
        mvc.perform(as(org, user, json(post("/core/accounts/me/mfa/recovery-codes"), "{\"currentPassword\":\"bad\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("PASSWORD_CURRENT_MISMATCH"));
        mvc.perform(as(org, user, json(post("/core/accounts/me/mfa/recovery-codes"), "{\"currentPassword\":\"" + Fixtures.PASSWORD + "\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.recoveryCodes.length()").value(10));
        verify(old.get(1)).andExpect(status().isUnauthorized());

        fx.policy(org, "mfa_required_roles", List.of("OPERATOR"));
        mvc.perform(as(org, user, json(delete("/core/accounts/me/mfa"), "{\"currentPassword\":\"" + Fixtures.PASSWORD + "\"}")))
                .andExpect(status().isConflict());
        fx.policy(org, "mfa_required_roles", List.of());
        mvc.perform(as(org, user, json(delete("/core/accounts/me/mfa"), "{\"currentPassword\":\"" + Fixtures.PASSWORD + "\"}")))
                .andExpect(status().isNoContent());
        mvc.perform(as(org, user, json(delete("/core/accounts/me/mfa"), "{\"currentPassword\":\"" + Fixtures.PASSWORD + "\"}")))
                .andExpect(status().isConflict());

        enable();
        long admin = fx.user(org, "boss.admin", "ADMIN");
        mvc.perform(as(org, admin, delete("/core/users/" + user + "/mfa"))).andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT totp_enabled FROM data2flow_core.app_users WHERE id = :id").param("id", user).query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE payload->>'kind' = 'MFA_RESET'").query(Long.class).single()).isEqualTo(1);
        verify("123456").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[IAM-02.05][AT-IAM-14.5] 정책상 필수 역할인데 미설정이면 설정 API 외 403 MFA_SETUP_REQUIRED")
    void setupRequiredGate() throws Exception {
        setUp();
        fx.policy(org, "mfa_required_roles", List.of("OPERATOR"));
        mvc.perform(as(org, user, get("/core/permissions"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("MFA_SETUP_REQUIRED"));
        mvc.perform(as(org, user, get("/core/accounts/me"))).andExpect(status().isOk());
        enable();
        mvc.perform(as(org, user, get("/core/permissions"))).andExpect(status().isOk());
    }
}
