package net.java21.data2flow.core.signup;

import net.java21.data2flow.core.common.MaintenanceJobs;
import net.java21.data2flow.core.support.Fixtures;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** IAM-01.08 관리자 승인형 가입 신청(ADR-032, BR-IAM-28·29)과 IAM-01.06 셀프 가입·조직 생성 없음 */
class SignupIT extends IntegrationTestSupport {

    private static final Pattern VERIFY = Pattern.compile("/signup/verify/([A-Za-z0-9_-]{20,})");

    @Autowired
    MaintenanceJobs jobs;

    private long org;
    private long admin;

    private void setUp(boolean enabled) {
        org = fx.organization("sign");
        admin = fx.user(org, "boss.admin", "ADMIN");
        fx.mail(org, smtpPort());
        fx.policy(org, "signup_request_enabled", enabled);
    }

    private ResultActions apply(String email, String loginId, String ip) throws Exception {
        return mvc.perform(json(post("/core/signup-requests").header("X-Forwarded-For", ip), """
                {"email":"%s","name":"신청자","loginId":"%s","password":"%s","message":"실습실 사용","privacyConsent":true}"""
                .formatted(email, loginId, Fixtures.PASSWORD)));
    }

    private String verifyToken() throws Exception {
        var messages = MAIL.getReceivedMessages();
        Matcher m = VERIFY.matcher((String) messages[messages.length - 1].getContent());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("[IAM-01.08][AT-IAM-16.1] 공개 가입 설정(API-IAM-74)은 토큰 없이 조직 정책의 허용 여부를 알려 준다 — TC-IAM-061")
    void publicSignupSettings() throws Exception {
        mvc.perform(get("/core/public/signup-settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.response.signupRequestEnabled").value(false));
        setUp(false);
        mvc.perform(get("/core/public/signup-settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.signupRequestEnabled").value(false));
        fx.policy(org, "signup_request_enabled", true);
        mvc.perform(get("/core/public/signup-settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.signupRequestEnabled").value(true));
    }

    @Test
    @DisplayName("[IAM-01.08][AT-IAM-16.1] 설정이 꺼져 있으면 404 SIGNUP_DISABLED — TC-IAM-061")
    void disabled() throws Exception {
        setUp(false);
        apply("a@school.ac.kr", "lab.kim", "10.0.0.1").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SIGNUP_DISABLED"));
    }

    @Test
    @DisplayName("[IAM-01.08][AT-IAM-16.2] 허용 도메인 밖 이메일은 400 SIGNUP_DOMAIN_NOT_ALLOWED — TC-IAM-063")
    void domainNotAllowed() throws Exception {
        setUp(true);
        fx.policy(org, "signup_allowed_domains", List.of("school.ac.kr"));
        apply("a@gmail.com", "lab.kim", "10.0.0.1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("SIGNUP_DOMAIN_NOT_ALLOWED"));
        apply("a@school.ac.kr", "lab.kim", "10.0.0.1").andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("[IAM-01.08][AT-IAM-16.3~16.5·20.3] 확인 전엔 목록에 없음 → 확인 후 승인 대기(로그인 403) → 역할·공간 지정 승인 → ACTIVE·메일·감사 — TC-IAM-064·068·070")
    void approveFlow() throws Exception {
        setUp(true);
        long orgsBefore = jdbc.sql("SELECT count(*) FROM data2flow_core.organizations").query(Long.class).single();
        apply("a@school.ac.kr", "lab.kim", "10.0.0.1").andExpect(status().isAccepted());
        mvc.perform(as(org, admin, get("/core/signup-requests"))).andExpect(jsonPath("$.totalCount").value(0));

        String token = verifyToken();
        mvc.perform(post("/core/signup-requests/" + token + "/verify")).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get("/core/signup-requests"))).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].loginId").value("lab.kim"))
                .andExpect(jsonPath("$.responses[0].requestIp").value("10.0.0.1"));
        String id = jdbc.sql("SELECT id::text FROM data2flow_core.signup_requests").query(String.class).single();

        mvc.perform(json(post("/internal/core/users/verify-credentials"), "{\"loginId\":\"lab.kim\",\"password\":\"" + Fixtures.PASSWORD + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("AUTH_PENDING_APPROVAL"))
                .andExpect(jsonPath("$.header.resultMessage").value("관리자 승인 대기 중입니다. 승인되면 메일로 알려 드립니다"));
        mvc.perform(json(post("/internal/core/users/verify-credentials"), "{\"loginId\":\"lab.kim\",\"password\":\"Wrong-Password-2!\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.header.resultCode").value("AUTH_INVALID_CREDENTIALS"));

        mvc.perform(as(org, admin, json(post("/core/signup-requests/" + id + "/approve"), "{\"role\":\"VIEWER\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
        mvc.perform(as(org, admin, json(post("/core/signup-requests/" + id + "/approve"), "{\"spaceScope\":[]}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(post("/core/signup-requests/" + id + "/approve"), "{\"role\":\"VIEWER\",\"spaceScope\":[]}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("APPROVED"));

        mvc.perform(json(post("/internal/core/users/verify-credentials"), "{\"loginId\":\"lab.kim\",\"password\":\"" + Fixtures.PASSWORD + "\"}"))
                .andExpect(status().isOk());
        assertThat((String) MAIL.getReceivedMessages()[MAIL.getReceivedMessages().length - 1].getContent()).contains("https://web.test/login");
        assertThat(auditCount(org, "SIGNUP_APPROVED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.organizations").query(Long.class).single()).isEqualTo(orgsBefore);
        mvc.perform(as(org, admin, json(post("/core/signup-requests/" + id + "/approve"), "{\"role\":\"VIEWER\",\"spaceScope\":[]}")))
                .andExpect(status().isConflict());
        mvc.perform(post("/core/signup-requests/" + token + "/verify")).andExpect(status().isGone())
                .andExpect(jsonPath("$.header.resultCode").value("SIGNUP_REQUEST_INVALID"));
    }

    @Test
    @DisplayName("[IAM-01.08][AT-IAM-16.6] 사유 없이 거절 400, 사유와 함께 거절 → 메일에 사유, 감사 SIGNUP_REJECTED, 같은 아이디로 다시 신청 가능 — TC-IAM-206")
    void reject() throws Exception {
        setUp(true);
        apply("a@school.ac.kr", "lab.kim", "10.0.0.1");
        mvc.perform(post("/core/signup-requests/" + verifyToken() + "/verify")).andExpect(status().isNoContent());
        String id = jdbc.sql("SELECT id::text FROM data2flow_core.signup_requests").query(String.class).single();

        mvc.perform(as(org, admin, json(post("/core/signup-requests/" + id + "/reject"), "{}"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(post("/core/signup-requests/" + id + "/reject"), "{\"reason\":\"소속 확인 불가\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.rejectReason").value("소속 확인 불가"));

        assertThat((String) MAIL.getReceivedMessages()[MAIL.getReceivedMessages().length - 1].getContent()).contains("소속 확인 불가");
        assertThat(auditCount(org, "SIGNUP_REJECTED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.app_users WHERE login_id = 'lab.kim'").query(Long.class).single()).isZero();
        apply("a@school.ac.kr", "lab.kim", "10.0.0.2").andExpect(status().isAccepted());
        mvc.perform(as(org, admin, get("/core/signup-requests").param("status", "REJECTED"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, admin, get("/core/signup-requests").param("status", "X"))).andExpect(status().isBadRequest());
        mvc.perform(get("/core/signup-requests")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[IAM-01.08][AT-IAM-16.7] 사용 중 아이디 409, 신청 중 이메일은 같은 202·새 신청 없음·안내 메일 1통 — TC-IAM-207")
    void duplicates() throws Exception {
        setUp(true);
        apply("x@school.ac.kr", "boss.admin", "10.0.0.1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("LOGIN_ID_DUPLICATED"));
        apply("a@school.ac.kr", "lab.kim", "10.0.0.1").andExpect(status().isAccepted());
        int mailsBefore = MAIL.getReceivedMessages().length;
        apply("a@school.ac.kr", "lab.other", "10.0.0.1").andExpect(status().isAccepted());
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.signup_requests").query(Long.class).single()).isEqualTo(1);
        assertThat(MAIL.getReceivedMessages()).hasSize(mailsBefore + 1);
        apply("boss_admin@school.ac.kr", "lab.third", "10.0.0.1").andExpect(status().isAccepted());
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.signup_requests").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("[IAM-01.08][AT-IAM-16.8] 같은 IP의 1시간 6번째 신청 → 429 SIGNUP_RATE_LIMITED + Retry-After — TC-IAM-208")
    void rateLimited() throws Exception {
        setUp(true);
        for (int i = 0; i < 5; i++) {
            apply("u" + i + "@school.ac.kr", "lab.user" + i, "10.9.9.9").andExpect(status().isAccepted());
        }
        apply("u9@school.ac.kr", "lab.user9", "10.9.9.9").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.header.resultCode").value("SIGNUP_RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        apply("u9@school.ac.kr", "lab.user9", "10.9.9.9").andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("[IAM-01.08][TC-IAM-071] 24시간 지난 확인 링크는 410, 만료 정리 뒤 EXPIRED")
    void verifyExpired() throws Exception {
        setUp(true);
        apply("a@school.ac.kr", "lab.kim", "10.0.0.1");
        String token = verifyToken();
        clock.advance(Duration.ofHours(24).plusMinutes(1));
        mvc.perform(post("/core/signup-requests/" + token + "/verify")).andExpect(status().isGone())
                .andExpect(jsonPath("$.header.resultMessage").value("확인 링크가 만료되었습니다. 다시 신청해 주세요"));
        jobs.runOnce();
        assertThat(jdbc.sql("SELECT status FROM data2flow_core.signup_requests").query(String.class).single()).isEqualTo("EXPIRED");
        mvc.perform(post("/core/signup-requests/short/verify")).andExpect(status().isGone());
    }

    @Test
    @DisplayName("[IAM-01.08][§3.4] 승인 대기 14일이 지나면 EXPIRED, 승인 대기 계정 행 삭제")
    void approvalExpired() throws Exception {
        setUp(true);
        apply("a@school.ac.kr", "lab.kim", "10.0.0.1");
        mvc.perform(post("/core/signup-requests/" + verifyToken() + "/verify")).andExpect(status().isNoContent());
        clock.advance(Duration.ofDays(14).plusMinutes(1));
        jobs.runOnce();
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.app_users WHERE login_id = 'lab.kim'").query(Long.class).single()).isZero();
    }
}
