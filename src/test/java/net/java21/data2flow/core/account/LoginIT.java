package net.java21.data2flow.core.account;

import net.java21.data2flow.core.account.service.BootstrapService;
import net.java21.data2flow.core.config.CoreProperties.Bootstrap;
import net.java21.data2flow.core.support.Fixtures;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** IAM-02.01·02.03·01.02 로그인 자격 확인(API-IAM-30)·잠금·최초 관리자·임시 비밀번호 관문(BR-IAM-06) */
class LoginIT extends IntegrationTestSupport {

    @Autowired
    BootstrapService bootstrap;

    private ResultActions verify(String loginId, String password) throws Exception {
        return mvc.perform(json(post("/internal/core/users/verify-credentials").header("X-CALLER-SERVICE", "data2flow-auth"),
                "{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\",\"ip\":\"10.0.0.7\",\"userAgent\":\"JUnit\"}"));
    }

    @Test
    @DisplayName("[IAM-02.01][AT-IAM-02.2] 아이디를 대문자로 입력해도 로그인 성공(대소문자 무시) — TC-IAM-076")
    void loginIdIsCaseInsensitive() throws Exception {
        long org = fx.organization("o1");
        long user = fx.user(org, "kim.op", "OPERATOR");

        verify("KIM.OP", Fixtures.PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.header.resultCode").value("SUCCESS"))
                .andExpect(jsonPath("$.response.userId").value(Long.toString(user)))
                .andExpect(jsonPath("$.response.orgId").value(Long.toString(org)))
                .andExpect(jsonPath("$.response.mustChangePassword").value(false))
                .andExpect(jsonPath("$.response.mfaEnabled").value(false));
        String ip = jdbc.sql("SELECT host(last_login_ip) FROM data2flow_core.app_users WHERE id = :id").param("id", user)
                .query(String.class).single();
        assertThat(ip).isEqualTo("10.0.0.7");
    }

    @Test
    @DisplayName("[IAM-02.03][AT-IAM-02.3] 없는 아이디·틀린 비밀번호·DISABLED는 모두 401 AUTH_INVALID_CREDENTIALS — TC-IAM-077")
    void failuresLookTheSame() throws Exception {
        long org = fx.organization("o1");
        fx.user(org, "kim.op", "OPERATOR");
        long disabled = fx.user(org, "off.user", "VIEWER");
        jdbc.sql("UPDATE data2flow_core.app_users SET status = 'DISABLED' WHERE id = :id").param("id", disabled).update();

        for (String[] c : new String[][]{{"nobody", Fixtures.PASSWORD}, {"kim.op", "Wrong-Password-1!"}, {"off.user", Fixtures.PASSWORD}}) {
            verify(c[0], c[1]).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.header.isSuccessful").value(false))
                    .andExpect(jsonPath("$.header.resultCode").value("AUTH_INVALID_CREDENTIALS"));
        }
        assertThat(auditCount(org, "USER_LOGIN_FAILED")).isEqualTo(3);
    }

    @Test
    @DisplayName("[IAM-02.03][AT-IAM-02.4] 5회 연속 실패 → LOCKED, 감사 USER_LOCKED, 6번째는 맞는 비밀번호도 실패 — TC-IAM-079")
    void fiveFailuresLock() throws Exception {
        long org = fx.organization("o1");
        long user = fx.user(org, "kim.op", "OPERATOR");

        for (int i = 0; i < 5; i++) {
            verify("kim.op", "Wrong-Password-1!").andExpect(status().isUnauthorized());
        }
        assertThat(userStatus(user)).isEqualTo("LOCKED");
        assertThat(auditCount(org, "USER_LOCKED")).isEqualTo(1);
        verify("kim.op", Fixtures.PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("AUTH_INVALID_CREDENTIALS"));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE routing_key = 'iam.security.alert'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("[IAM-02.03][AT-IAM-02.5] LOCKED 후 15분이 지나면 맞는 비밀번호로 성공하고 실패 횟수 0 — TC-IAM-081")
    void lockExpires() throws Exception {
        long org = fx.organization("o1");
        long user = fx.user(org, "kim.op", "OPERATOR");
        for (int i = 0; i < 5; i++) {
            verify("kim.op", "Wrong-Password-1!");
        }
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        verify("kim.op", Fixtures.PASSWORD).andExpect(status().isOk());
        assertThat(userStatus(user)).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT failed_login_count FROM data2flow_core.app_users WHERE id = :id").param("id", user)
                .query(Integer.class).single()).isZero();
        assertThat(auditCount(org, "USER_UNLOCKED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[IAM-02.03][BR-IAM-05] 성공하면 실패 횟수를 0으로 되돌린다(4회 실패 후 성공 → 다시 5회 가능)")
    void successResetsFailures() throws Exception {
        long org = fx.organization("o1");
        long user = fx.user(org, "kim.op", "OPERATOR");
        for (int i = 0; i < 4; i++) {
            verify("kim.op", "Wrong-Password-1!");
        }
        verify("kim.op", Fixtures.PASSWORD).andExpect(status().isOk());
        verify("kim.op", "Wrong-Password-1!");
        assertThat(userStatus(user)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("[IAM-01.02][AT-IAM-01.4] 부트스트랩 Job은 ADMIN을 만들고, 다시 실행하면 새로 만들지 않는다(멱등) — TC-IAM-013")
    void bootstrapIsIdempotent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("initial-password");
        Bootstrap config = new Bootstrap(true, false, "school", "학교", "first.admin", "admin@school.ac.kr", null, null, file.toString());

        assertThat(bootstrap.bootstrap(config)).isEqualTo(BootstrapService.Result.CREATED);
        assertThat(bootstrap.bootstrap(config)).isEqualTo(BootstrapService.Result.ALREADY_INITIALIZED);

        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.app_users").query(Long.class).single()).isEqualTo(1);
        String password = Files.readString(file).strip();
        assertThat(password).hasSize(20);
        verify("first.admin", password).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.mustChangePassword").value(true));
    }

    @Test
    @DisplayName("[IAM-01.02][AT-IAM-01.1~01.3] 임시 비밀번호 상태는 비밀번호 변경·내 정보만 허용(403 AUTH_PASSWORD_CHANGE_REQUIRED), 변경 후 이력 1건 — TC-IAM-007~012")
    void mustChangePasswordGate() throws Exception {
        long org = fx.organization("o1");
        long admin = fx.user(org, "first.admin", "ADMIN", "Temp-Initial-Pass-99", true);

        mvc.perform(as(org, admin, get("/core/users"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("AUTH_PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(as(org, admin, get("/core/accounts/me"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.mustChangePassword").value(true));

        mvc.perform(as(org, admin, json(put("/core/accounts/me/password"),
                "{\"currentPassword\":\"Temp-Initial-Pass-99\",\"newPassword\":\"Brand-New-Secure#2026\"}")))
                .andExpect(status().isNoContent());

        mvc.perform(as(org, admin, get("/core/users"))).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.password_history WHERE user_id = :id").param("id", admin)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(auditCount(org, "PASSWORD_CHANGED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[IAM-02.02][TC-IAM-089] 비밀번호 변경: 정책 위반은 사유와 함께 400, 최근 비밀번호 재사용 금지")
    void passwordChangePolicy() throws Exception {
        long org = fx.organization("o1");
        long user = fx.user(org, "kim.op", "OPERATOR");

        mvc.perform(as(org, user, json(put("/core/accounts/me/password"),
                        "{\"currentPassword\":\"" + Fixtures.PASSWORD + "\",\"newPassword\":\"short\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("PASSWORD_POLICY_VIOLATION"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_SHORT"));
        mvc.perform(as(org, user, json(put("/core/accounts/me/password"),
                        "{\"currentPassword\":\"" + Fixtures.PASSWORD + "\",\"newPassword\":\"" + Fixtures.PASSWORD + "\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("REUSED"));
        mvc.perform(as(org, user, json(put("/core/accounts/me/password"),
                        "{\"currentPassword\":\"wrong-current-1\",\"newPassword\":\"Brand-New-Secure#2026\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("PASSWORD_CURRENT_MISMATCH"));
    }

    @Test
    @DisplayName("[IAM-02.01][OPS-12.01] 요청 형식이 틀리면 400 INVALID_REQUEST, Accept-Language en이면 문구만 영어")
    void invalidRequestLocalized() throws Exception {
        mvc.perform(json(req(HttpMethod.POST, "/internal/core/users/verify-credentials").header("Accept-Language", "en"), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
        mvc.perform(json(post("/internal/core/users/verify-credentials").header("Accept-Language", "en"),
                        "{\"loginId\":\"x1234\",\"password\":\"y\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultMessage").value(org.hamcrest.Matchers.startsWith("Incorrect ID")));
    }

    private String userStatus(long userId) {
        return jdbc.sql("SELECT status FROM data2flow_core.app_users WHERE id = :id").param("id", userId).query(String.class).single();
    }
}
