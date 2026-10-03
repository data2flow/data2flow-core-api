package net.java21.data2flow.core.account;

import jakarta.mail.internet.MimeMessage;
import net.java21.data2flow.core.support.Fixtures;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원·초대(IAM-01.03·01.04·01.05·01.07·01.10), 조직 격리(IAM-01.01·04.05), 개인정보 익명화(NFR-12.01).
 * test-plan TC-IAM-014~073의 core-api 통합 행.
 */
class AccountMembershipIT extends IntegrationTestSupport {

    private static final Pattern TOKEN = Pattern.compile("/invitations/([A-Za-z0-9_-]{20,})");

    private long org;
    private long admin;

    private void setUp() {
        org = fx.organization("acct");
        admin = fx.user(org, "boss.admin", "ADMIN");
        fx.mail(org, smtpPort());
    }

    private String invite(String email, String role) throws Exception {
        mvc.perform(as(org, admin, json(post("/core/invitations"),
                        "{\"emails\":[\"" + email + "\"],\"role\":\"" + role + "\",\"spaceScope\":[]}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.results[0].status").value("CREATED"));
        return lastToken();
    }

    private static String lastToken() throws Exception {
        MimeMessage[] messages = MAIL.getReceivedMessages();
        String body = (String) messages[messages.length - 1].getContent();
        Matcher m = TOKEN.matcher(body);
        assertThat(m.find()).as("메일 본문에 초대 링크").isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("[IAM-01.03][AT-IAM-05.1] OPERATOR로 초대 → 목록에 INVITED·PENDING, 메일 1통, 감사 USER_INVITED — TC-IAM-014")
    void inviteCreatesInvitedUser() throws Exception {
        setUp();
        invite("lee@school.ac.kr", "OPERATOR");

        assertThat(mails()).hasSize(1);
        assertThat(mails().get(0).getAllRecipients()[0].toString()).isEqualTo("lee@school.ac.kr");
        assertThat((String) mails().get(0).getContent()).contains("https://web.test/invitations/");
        mvc.perform(as(org, admin, get("/core/users").param("status", "INVITED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.responses[0].email").value("lee@school.ac.kr"));
        mvc.perform(as(org, admin, get("/core/invitations")))
                .andExpect(jsonPath("$.responses[0].status").value("PENDING"))
                .andExpect(jsonPath("$.responses[0].role").value("OPERATOR"));
        assertThat(auditCount(org, "USER_INVITED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[IAM-01.03][AT-IAM-05.2] OPERATOR가 초대 API 호출 → 403 PERMISSION_DENIED, 감사 ACCESS_DENIED — TC-IAM-017")
    void operatorCannotInvite() throws Exception {
        setUp();
        long op = fx.user(org, "kim.op", "OPERATOR");
        mvc.perform(as(org, op, json(post("/core/invitations"), "{\"emails\":[\"x@school.ac.kr\"],\"role\":\"VIEWER\"}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        assertThat(auditCount(org, "ACCESS_DENIED")).isEqualTo(1);
        String detail = jdbc.sql("SELECT detail->>'permission' FROM data2flow_core.audit_logs WHERE organization_id = :o AND action = 'ACCESS_DENIED'")
                .param("o", org).query(String.class).single();
        assertThat(detail).isEqualTo("IAM_MANAGE");
    }

    @Test
    @DisplayName("[IAM-01.03][AT-IAM-05.3·05.4] 재발송하면 이전 링크는 410, 새 링크는 정상. 5회 재발송 후 6번째는 429 — TC-IAM-019·021")
    void resend() throws Exception {
        setUp();
        String oldToken = invite("lee@school.ac.kr", "OPERATOR");
        String id = jdbc.sql("SELECT id::text FROM data2flow_core.invitations").query(String.class).single();

        mvc.perform(as(org, admin, post("/core/invitations/" + id + "/resend"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.sentCount").value(2));
        String newToken = lastToken();
        mvc.perform(get("/core/invitations/" + oldToken)).andExpect(status().isGone())
                .andExpect(jsonPath("$.header.resultCode").value("INVITATION_INVALID"));
        mvc.perform(get("/core/invitations/" + newToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.email").value("lee@school.ac.kr"))
                .andExpect(jsonPath("$.response.role").value("OPERATOR"))
                .andExpect(jsonPath("$.response.invitedBy").value("이름 boss.admin"));
        for (int i = 3; i <= 6; i++) {
            mvc.perform(as(org, admin, post("/core/invitations/" + id + "/resend"))).andExpect(status().isOk());
        }
        mvc.perform(as(org, admin, post("/core/invitations/" + id + "/resend"))).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.header.resultCode").value("INVITATION_RESEND_LIMIT"));
    }

    @Test
    @DisplayName("[IAM-01.03][AT-IAM-06.1~06.4] 수락: 예약어 아이디 400, 아이디 포함 비밀번호 400, 정상 수락 → ACTIVE·OPERATOR, 재사용 410 — TC-IAM-024·027·028")
    void accept() throws Exception {
        setUp();
        String token = invite("lee@school.ac.kr", "OPERATOR");

        mvc.perform(json(post("/core/invitations/" + token + "/accept"),
                        "{\"loginId\":\"Admin\",\"password\":\"" + Fixtures.PASSWORD + "\",\"privacyConsent\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("LOGIN_ID_INVALID"))
                .andExpect(jsonPath("$.errors[0].code").value("RESERVED"));
        mvc.perform(json(post("/core/invitations/" + token + "/accept"),
                        "{\"loginId\":\"lee.op\",\"password\":\"Xlee.op-2026-long\",\"privacyConsent\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("PASSWORD_POLICY_VIOLATION"))
                .andExpect(jsonPath("$.errors[0].code").value("CONTAINS_LOGIN_ID"));
        mvc.perform(json(post("/core/invitations/" + token + "/accept"),
                        "{\"loginId\":\"lee.op\",\"password\":\"" + Fixtures.PASSWORD + "\",\"privacyConsent\":false}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("privacyConsent"));
        mvc.perform(get("/core/invitations/" + token + "/login-id-availability").param("loginId", "boss.admin"))
                .andExpect(jsonPath("$.response.available").value(false)).andExpect(jsonPath("$.response.reason").value("DUPLICATED"));
        mvc.perform(get("/core/invitations/" + token + "/login-id-availability").param("loginId", "lee.op"))
                .andExpect(jsonPath("$.response.available").value(true));

        mvc.perform(json(post("/core/invitations/" + token + "/accept"),
                        "{\"loginId\":\"Lee.Op\",\"password\":\"" + Fixtures.PASSWORD + "\",\"privacyConsent\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.loginId").value("lee.op"));

        long userId = jdbc.sql("SELECT id FROM data2flow_core.app_users WHERE login_id = 'lee.op'").query(Long.class).single();
        mvc.perform(as(org, userId, get("/core/accounts/me"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.role").value("OPERATOR"))
                .andExpect(jsonPath("$.response.permissions").isArray());
        mvc.perform(json(post("/core/invitations/" + token + "/accept"),
                        "{\"loginId\":\"lee.op2\",\"password\":\"" + Fixtures.PASSWORD + "\",\"privacyConsent\":true}"))
                .andExpect(status().isGone());
        mvc.perform(as(org, admin, get("/core/invitations").param("status", "ACCEPTED"))).andExpect(jsonPath("$.totalCount").value(1));
        assertThat(auditCount(org, "INVITATION_ACCEPTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[IAM-01.03][AT-IAM-06.2] 72시간 1분 지난 초대는 410, 정리 작업 뒤 EXPIRED로 보이고 같은 이메일을 다시 초대할 수 있다")
    void expiredInvitation() throws Exception {
        setUp();
        String token = invite("lee@school.ac.kr", "VIEWER");
        clock.advance(Duration.ofHours(72).plusMinutes(1));
        mvc.perform(get("/core/invitations/" + token)).andExpect(status().isGone());
        mvc.perform(as(org, admin, get("/core/invitations").param("status", "EXPIRED"))).andExpect(jsonPath("$.totalCount").value(1));
        invite("lee@school.ac.kr", "VIEWER");
    }

    @Test
    @DisplayName("[IAM-01.03][BR-IAM-10] 이미 ACTIVE인 이메일은 EMAIL_DUPLICATED, PENDING 초대가 있으면 INVITATION_PENDING_EXISTS(항목별) — TC-IAM-033·034")
    void inviteDuplicates() throws Exception {
        setUp();
        invite("lee@school.ac.kr", "VIEWER");
        mvc.perform(as(org, admin, json(post("/core/invitations"),
                        "{\"emails\":[\"boss_admin@school.ac.kr\",\"lee@school.ac.kr\",\"bad-email\",\"new@school.ac.kr\"],\"role\":\"VIEWER\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.results[0].resultCode").value("EMAIL_DUPLICATED"))
                .andExpect(jsonPath("$.response.results[1].resultCode").value("INVITATION_PENDING_EXISTS"))
                .andExpect(jsonPath("$.response.results[2].resultCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.response.results[3].status").value("CREATED"));
        String id = jdbc.sql("SELECT id::text FROM data2flow_core.invitations WHERE email = 'new@school.ac.kr'").query(String.class).single();
        mvc.perform(as(org, admin, post("/core/invitations/" + id + "/cancel"))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, post("/core/invitations/" + id + "/cancel"))).andExpect(status().isGone());
        mvc.perform(as(org, admin, get("/core/invitations").param("status", "CANCELED"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, admin, get("/core/invitations").param("status", "WRONG"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(post("/core/invitations"), "{\"emails\":[\"z@school.ac.kr\"],\"role\":\"GOD\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("role"));
        mvc.perform(as(org, admin, json(post("/core/invitations"), "{\"emails\":[\"z@school.ac.kr\"],\"role\":\"VIEWER\",\"spaceScope\":[\"7\"]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SPACE_SCOPE_INVALID"));
    }

    @Test
    @DisplayName("[OPS-12.03][AT-OPS-25.3] 같은 Idempotency-Key로 초대를 두 번 보내면 같은 응답, 실행은 1번")
    void idempotentInvite() throws Exception {
        setUp();
        String body = "{\"emails\":[\"idem@school.ac.kr\"],\"role\":\"VIEWER\"}";
        MvcResult first = mvc.perform(as(org, admin, json(post("/core/invitations"), body)).header("Idempotency-Key", "inv-001"))
                .andExpect(status().isOk()).andReturn();
        MvcResult second = mvc.perform(as(org, admin, json(post("/core/invitations"), body)).header("Idempotency-Key", "inv-001"))
                .andExpect(status().isOk()).andReturn();
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.invitations").query(Long.class).single()).isEqualTo(1);
        mvc.perform(as(org, admin, json(post("/core/invitations"), "{\"emails\":[\"other@school.ac.kr\"],\"role\":\"VIEWER\"}"))
                        .header("Idempotency-Key", "inv-001"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @DisplayName("[IAM-01.03][AT-IAM-05.5] 직접 생성: 임시 비밀번호(자동 생성 1회 응답)로 로그인하면 변경 강제, 중복 아이디 409 — TC-IAM-023·032")
    void directCreate() throws Exception {
        setUp();
        MvcResult result = mvc.perform(as(org, admin, json(post("/core/users"),
                        "{\"loginId\":\"park.an\",\"email\":\"park@school.ac.kr\",\"name\":\"박분석\",\"role\":\"ANALYST\",\"spaceScope\":[]}")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/core/users/")))
                .andReturn();
        String temp = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.response.temporaryPassword");
        mvc.perform(json(post("/internal/core/users/verify-credentials"), "{\"loginId\":\"park.an\",\"password\":\"" + temp + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.mustChangePassword").value(true));

        mvc.perform(as(org, admin, json(post("/core/users"),
                        "{\"loginId\":\"park.an\",\"email\":\"p2@school.ac.kr\",\"name\":\"x\",\"role\":\"ANALYST\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("LOGIN_ID_DUPLICATED"))
                .andExpect(jsonPath("$.header.resultMessage").value("이미 사용 중인 아이디입니다"));
        mvc.perform(as(org, admin, json(post("/core/users"),
                        "{\"loginId\":\"park.two\",\"email\":\"PARK@school.ac.kr\",\"name\":\"x\",\"role\":\"ANALYST\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("EMAIL_DUPLICATED"));
        mvc.perform(as(org, admin, json(post("/core/users"),
                        "{\"loginId\":\"park.three\",\"email\":\"p3@school.ac.kr\",\"name\":\"x\",\"role\":\"ANALYST\",\"temporaryPassword\":\"short\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("PASSWORD_POLICY_VIOLATION"));
    }

    @Test
    @DisplayName("[IAM-01.07][AT-IAM-09.1·09.2] 역할 변경은 다음 요청부터 반영(재로그인 불필요), 마지막 ADMIN 변경은 409, 자기 변경 409, baseVersion 불일치 409 — TC-IAM-052·055·059·049")
    void changeRole() throws Exception {
        setUp();
        long op = fx.user(org, "kim.op", "OPERATOR");
        mvc.perform(as(org, op, get("/core/accounts/me"))).andExpect(jsonPath("$.response.permissions[?(@ == 'DEVICE_CONTROL')]").exists());

        mvc.perform(as(org, admin, json(put("/core/users/" + op + "/role"), "{\"role\":\"VIEWER\",\"spaceScope\":[],\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.role").value("VIEWER"))
                .andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, op, get("/core/accounts/me"))).andExpect(jsonPath("$.response.role").value("VIEWER"))
                .andExpect(jsonPath("$.response.permissions[?(@ == 'DEVICE_CONTROL')]").doesNotExist());
        mvc.perform(as(org, admin, json(put("/core/users/" + op + "/role"), "{\"role\":\"ANALYST\",\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        long admin2 = fx.user(org, "second.admin", "ADMIN");
        mvc.perform(as(org, admin2, json(put("/core/users/" + admin + "/role"), "{\"role\":\"OPERATOR\",\"baseVersion\":0}")))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE routing_key = 'iam.user.permission.changed'")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(auditCount(org, "ROLE_CHANGED")).isEqualTo(2);
    }

    @Test
    @DisplayName("[IAM-01.07][AT-IAM-09.2][BR-IAM-07·08] ADMIN이 1명이면 자기 역할 변경·비활성화·삭제는 409 LAST_ADMIN_REQUIRED, 2명이면 자기 변경은 SELF_MODIFICATION_FORBIDDEN — TC-IAM-055·059")
    void lastAdmin() throws Exception {
        setUp();
        mvc.perform(as(org, admin, json(put("/core/users/" + admin + "/role"), "{\"role\":\"OPERATOR\",\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("LAST_ADMIN_REQUIRED"))
                .andExpect(jsonPath("$.header.resultMessage").value("관리자가 최소 1명 있어야 합니다"));
        mvc.perform(as(org, admin, json(post("/core/users/" + admin + "/disable"), "{}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("LAST_ADMIN_REQUIRED"));
        mvc.perform(as(org, admin, json(delete("/core/users/" + admin), "{\"confirmLoginId\":\"boss.admin\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("LAST_ADMIN_REQUIRED"));

        long other = fx.user(org, "other.admin", "ADMIN");
        mvc.perform(as(org, admin, json(put("/core/users/" + admin + "/role"), "{\"role\":\"OPERATOR\",\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SELF_MODIFICATION_FORBIDDEN"));
        mvc.perform(as(org, admin, json(post("/core/users/" + admin + "/disable"), "{}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SELF_MODIFICATION_FORBIDDEN"));
        mvc.perform(as(org, other, json(post("/core/users/" + admin + "/disable"), "{\"reason\":\"퇴사\"}"))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get("/core/users"))).andExpect(status().isForbidden());
        mvc.perform(as(org, other, post("/core/users/" + admin + "/enable"))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get("/core/users"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("[IAM-01.04][AT-IAM-10.1] 비활성화 → 세션·장기 토큰 즉시 폐기, 감사 USER_DISABLED, 상태 이벤트, 이후 로그인 401 — TC-IAM-035")
    void disable() throws Exception {
        setUp();
        long c = fx.user(org, "user.c", "OPERATOR");
        jdbc.sql("""
                INSERT INTO data2flow_core.refresh_tokens (jti, session_id, organization_id, user_id, token_hash, expires_at, absolute_expires_at)
                VALUES (gen_random_uuid(), gen_random_uuid(), :o, :u, repeat('a', 64), now() + interval '1 hour', now() + interval '2 hour')""")
                .param("o", org).param("u", c).update();
        jdbc.sql("""
                INSERT INTO data2flow_core.api_tokens (organization_id, owner_type, owner_id, kind, name, token_prefix, token_hash, scopes,
                    expires_at, created_by) VALUES (:o, 'USER', :u, 'MCP', 'mcp', 'data2flow_ab', repeat('b', 64), '{read:telemetry}',
                    now() + interval '30 day', :u)""").param("o", org).param("u", c).update();

        mvc.perform(as(org, admin, json(post("/core/users/" + c + "/disable"), "{\"reason\":\"휴직\"}"))).andExpect(status().isNoContent());

        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.refresh_tokens WHERE revoked_at IS NULL").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM data2flow_core.api_tokens").query(String.class).single()).isEqualTo("REVOKED");
        assertThat(auditCount(org, "USER_DISABLED")).isEqualTo(1);
        mvc.perform(json(post("/internal/core/users/verify-credentials"), "{\"loginId\":\"user.c\",\"password\":\"" + Fixtures.PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(as(org, admin, json(post("/core/users/" + c + "/disable"), "{}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("USER_STATE_CONFLICT"))
                .andExpect(jsonPath("$.header.resultMessage").value("현재 상태에서는 할 수 없는 작업입니다"));
        mvc.perform(as(org, admin, post("/core/users/" + c + "/enable"))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, post("/core/users/" + c + "/enable"))).andExpect(status().isConflict());
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE routing_key = 'iam.user.state.changed'")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("[IAM-01.10][AT-IAM-10.2·10.3] 삭제 → deleted-{id}로 익명화, 감사의 actor_name 스냅샷은 남는다. 잠금 해제는 즉시 로그인 가능 — TC-IAM-038·073")
    void deleteAnonymizesAndUnlock() throws Exception {
        setUp();
        long c = fx.user(org, "user.c", "OPERATOR");
        mvc.perform(as(org, c, json(patch("/core/accounts/me"), "{\"phone\":\"+821012345678\",\"baseVersion\":0}")))
                .andExpect(status().isOk());

        mvc.perform(as(org, admin, json(delete("/core/users/" + c), "{\"confirmLoginId\":\"wrong\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("confirmLoginId"));
        mvc.perform(as(org, admin, json(delete("/core/users/" + c), "{\"confirmLoginId\":\"user.c\"}"))).andExpect(status().isNoContent());

        mvc.perform(as(org, admin, get("/core/users/" + c))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.loginId").value("deleted-" + c))
                .andExpect(jsonPath("$.response.email").value("deleted-" + c + "@deleted.invalid"))
                .andExpect(jsonPath("$.response.phone").doesNotExist())
                .andExpect(jsonPath("$.response.status").value("DELETED"));
        String snapshot = jdbc.sql("SELECT actor_name FROM data2flow_core.audit_logs WHERE organization_id = :o AND action = 'USER_UPDATED'")
                .param("o", org).query(String.class).single();
        assertThat(snapshot).isEqualTo("이름 user.c");
        assertThat(auditCount(org, "USER_DELETED")).isEqualTo(1);
        mvc.perform(as(org, admin, json(delete("/core/users/" + c), "{\"confirmLoginId\":\"x\"}"))).andExpect(status().isConflict());

        long d = fx.user(org, "user.d", "VIEWER");
        jdbc.sql("UPDATE data2flow_core.app_users SET status = 'LOCKED', locked_until = now() + interval '1 day' WHERE id = :id").param("id", d).update();
        mvc.perform(as(org, admin, post("/core/users/" + d + "/unlock"))).andExpect(status().isNoContent());
        mvc.perform(json(post("/internal/core/users/verify-credentials"), "{\"loginId\":\"user.d\",\"password\":\"" + Fixtures.PASSWORD + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(org, admin, post("/core/users/" + d + "/unlock"))).andExpect(status().isConflict());
        assertThat(auditCount(org, "USER_UNLOCKED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[IAM-01.01][AT-IAM-11.2] 조직 A 사용자가 조직 B 자원 ID로 조회·수정 → 404(존재 은닉) — TC-IAM-003")
    void organizationIsolation() throws Exception {
        setUp();
        long orgB = fx.organization("bbb");
        long userB = fx.user(orgB, "user.b", "OPERATOR");
        mvc.perform(as(org, admin, get("/core/users/" + userB))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("USER_NOT_FOUND"));
        mvc.perform(as(org, admin, json(post("/core/users/" + userB + "/disable"), "{}"))).andExpect(status().isNotFound());
        mvc.perform(as(org, admin, json(put("/core/users/" + userB + "/role"), "{\"role\":\"VIEWER\",\"baseVersion\":0}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, admin, get("/core/users"))).andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("[IAM-01.07][NFR-12.02] 회원 목록·상세 조회는 개인정보 접속 기록으로 남고, 목록은 페이지·정렬·검색을 따른다(OPS-12.02)")
    void listAndAccessLog() throws Exception {
        setUp();
        for (int i = 0; i < 3; i++) {
            fx.user(org, "member" + i, "VIEWER");
        }
        mvc.perform(as(org, admin, get("/core/users").param("size", "1000").param("page", "0").param("sort", "loginId,asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100)).andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.responses[0].loginId").value("boss.admin"))
                .andExpect(jsonPath("$.responses[1].spaceScopeSummary").value("ALL"));
        mvc.perform(as(org, admin, get("/core/users").param("keyword", "member").param("role", "viewer").param("size", "2")))
                .andExpect(jsonPath("$.totalCount").value(3)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.responses.length()").value(2));
        mvc.perform(as(org, admin, get("/core/users").param("sort", "password,asc"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("sort"));
        mvc.perform(as(org, admin, get("/core/users/" + admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.role").value("ADMIN")).andExpect(jsonPath("$.response.activeSessionCount").value(0));
        assertThat(auditCount(org, "PERSONAL_INFO_ACCESSED")).isEqualTo(3);
    }

    @Test
    @DisplayName("[IAM-01.05][AT-IAM-08.1] 시간대를 UTC로, 이름·언어 수정. 목록에 없는 시간대는 400, 낙관적 잠금 409 — TC-IAM-049")
    void profile() throws Exception {
        setUp();
        mvc.perform(as(org, admin, json(patch("/core/accounts/me"), "{\"timezone\":\"UTC\",\"locale\":\"en\",\"name\":\"관리자\",\"baseVersion\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.timezone").value("UTC")).andExpect(jsonPath("$.response.locale").value("en"))
                .andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, admin, json(patch("/core/accounts/me"), "{\"timezone\":\"Mars/Base\",\"baseVersion\":1}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("timezone"));
        mvc.perform(as(org, admin, json(patch("/core/accounts/me"), "{\"name\":\"x\",\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        mvc.perform(as(org, admin, json(patch("/core/accounts/me"), "{\"name\":\"\",\"baseVersion\":1}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(patch("/core/accounts/me"), "{\"phone\":\"010\",\"baseVersion\":1}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(patch("/core/accounts/me"), "{\"locale\":\"fr\",\"baseVersion\":1}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(patch("/core/accounts/me"), "{\"notificationPref\":{\"telegram\":true},\"phone\":null,\"baseVersion\":1}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.notificationPref.telegram").value(true));
        mvc.perform(as(org, admin, json(patch("/core/accounts/me"), "{\"notificationPref\":3,\"baseVersion\":2}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(patch("/core/accounts/me"), "{\"name\":\"x\"}"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("baseVersion"));
    }

    @Test
    @DisplayName("[IAM-01.06][AT-IAM-20.2] 조직 생성 경로는 없다 → 404 RESOURCE_NOT_FOUND — TC-IAM-031")
    void noOrganizationCreation() throws Exception {
        setUp();
        mvc.perform(as(org, admin, json(post("/core/organizations"), "{\"name\":\"new\"}"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("[IAM-07.09][AT-IAM-21.4] 신원 헤더가 없거나 숫자가 아니면 401 AUTH_TOKEN_INVALID")
    void identityRequired() throws Exception {
        mvc.perform(get("/core/users")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("AUTH_TOKEN_INVALID"));
        mvc.perform(get("/core/users").header("X-USER-ID", "abc").header("X-ORG-ID", "1")).andExpect(status().isUnauthorized());
    }
}
