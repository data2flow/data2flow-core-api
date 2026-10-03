package net.java21.data2flow.core.extservice;

import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OPS-07.01 조직 기본 설정, OPS-07.02 외부 서비스 설정(비밀값 암호화·가림, NFR-03.02), API-IAM-72 보안 정책 — TC-OPS-078~080 */
class SystemSettingsIT extends IntegrationTestSupport {

    private long org;
    private long admin;

    private void setUp() {
        org = fx.organization("ops");
        admin = fx.user(org, "boss.admin", "ADMIN");
    }

    @Test
    @DisplayName("[OPS-07.01][AT-OPS-14.1] 시간대 Asia/Seoul → UTC 저장, 형식이 틀리면 400 SETTING_INVALID, 로그인 사용자는 조회 가능")
    void orgSettings() throws Exception {
        setUp();
        long viewer = fx.user(org, "view.user", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/org-settings"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.timezone").value("Asia/Seoul")).andExpect(jsonPath("$.response.version").value(0));
        mvc.perform(as(org, admin, json(put("/core/org-settings"),
                        "{\"displayName\":\"본관\",\"timezone\":\"UTC\",\"locale\":\"EN\",\"unitSystem\":\"imperial\",\"dateFormat\":\"YYYY/MM/DD HH:mm\",\"baseVersion\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.timezone").value("UTC")).andExpect(jsonPath("$.response.locale").value("en"))
                .andExpect(jsonPath("$.response.unitSystem").value("IMPERIAL")).andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, admin, json(put("/core/org-settings"), "{\"timezone\":\"Nowhere/City\",\"baseVersion\":1}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SETTING_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value("설정값이 올바르지 않습니다: timezone"));
        for (String body : new String[]{"{\"displayName\":\"\",\"baseVersion\":1}", "{\"locale\":\"fr\",\"baseVersion\":1}",
                "{\"unitSystem\":\"CUBIT\",\"baseVersion\":1}", "{\"dateFormat\":\"<script>\",\"baseVersion\":1}"}) {
            mvc.perform(as(org, admin, json(put("/core/org-settings"), body))).andExpect(status().isBadRequest());
        }
        mvc.perform(as(org, admin, json(put("/core/org-settings"), "{\"displayName\":\"x\",\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        mvc.perform(as(org, viewer, json(put("/core/org-settings"), "{\"displayName\":\"x\",\"baseVersion\":1}")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultMessage").value("이 작업을 할 권한이 없습니다"));
        assertThat(auditCount(org, "ORG_SETTING_CHANGED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[OPS-07.02][AT-OPS-14.3][NFR-03.02] 메일 비밀번호는 암호화 저장·응답엔 설정 여부만, 빈 값으로 저장하면 유지 — TC-OPS-078")
    void externalServiceSecrets() throws Exception {
        setUp();
        mvc.perform(as(org, admin, json(put("/core/external-services/mail"), """
                        {"provider":"smtp","settings":{"host":"127.0.0.1","port":%d,"from":"no-reply@school.ac.kr","username":"mailer"},
                         "secret":"smtp-p@ss-123","enabled":true}""".formatted(smtpPort()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.kind").value("MAIL"))
                .andExpect(jsonPath("$.response.secretConfigured").value(true))
                .andExpect(jsonPath("$.response.secret").doesNotExist())
                .andExpect(jsonPath("$.response.version").value(0));
        byte[] stored = jdbc.sql("SELECT secret_enc FROM data2flow_core.external_service_config WHERE organization_id = :o")
                .param("o", org).query(byte[].class).single();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("smtp-p@ss-123");

        mvc.perform(as(org, admin, json(put("/core/external-services/MAIL"), """
                        {"provider":"smtp","settings":{"host":"127.0.0.1","port":%d,"from":"no-reply@school.ac.kr"},"secret":"","enabled":true,"baseVersion":0}"""
                        .formatted(smtpPort()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.secretConfigured").value(true))
                .andExpect(jsonPath("$.response.version").value(1));
        byte[] after = jdbc.sql("SELECT secret_enc FROM data2flow_core.external_service_config WHERE organization_id = :o")
                .param("o", org).query(byte[].class).single();
        assertThat(after).isEqualTo(stored);
        mvc.perform(as(org, admin, get("/core/external-services"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].settings.host").value("127.0.0.1"));
        String audit = jdbc.sql("SELECT detail::text FROM data2flow_core.audit_logs WHERE organization_id = :o AND action = 'EXTERNAL_SERVICE_CHANGED' LIMIT 1")
                .param("o", org).query(String.class).single();
        assertThat(audit).doesNotContain("smtp-p@ss-123");
    }

    @Test
    @DisplayName("[OPS-07.02] 설정 검증: settings에 비밀값 키 금지, 메일 host·port·from 형식, 모르는 종류, 수정은 baseVersion 필요")
    void externalServiceValidation() throws Exception {
        setUp();
        String[] bad = {
                "{\"provider\":\"smtp\",\"settings\":{\"host\":\"h\",\"from\":\"a@b.c\",\"password\":\"x\"},\"enabled\":true}",
                "{\"provider\":\"smtp\",\"settings\":{\"from\":\"a@b.c\"},\"enabled\":true}",
                "{\"provider\":\"smtp\",\"settings\":{\"host\":\"h\",\"port\":70000,\"from\":\"a@b.c\"},\"enabled\":true}",
                "{\"provider\":\"smtp\",\"settings\":{\"host\":\"h\",\"port\":\"x\",\"from\":\"a@b.c\"},\"enabled\":true}",
                "{\"provider\":\"smtp\",\"settings\":{\"host\":\"h\",\"from\":\"nope\"},\"enabled\":true}"};
        for (String body : bad) {
            mvc.perform(as(org, admin, json(put("/core/external-services/MAIL"), body))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.resultCode").value("SETTING_INVALID"));
        }
        mvc.perform(as(org, admin, json(put("/core/external-services/TELEPORT"), "{\"provider\":\"x\",\"enabled\":true}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(put("/core/external-services/LLM"), "{\"provider\":\"anthropic\",\"settings\":{\"model\":\"m\"},\"secret\":\"k\",\"enabled\":false}")))
                .andExpect(status().isOk());
        mvc.perform(as(org, admin, json(put("/core/external-services/LLM"), "{\"provider\":\"anthropic\",\"enabled\":false}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(put("/core/external-services/WEATHER"), "{\"provider\":\"kma\",\"enabled\":true,\"baseVersion\":3}")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("[OPS-07.02][TC-OPS-079·080] 연결 테스트: SMTP 접속 성공 200, 실패 502 EXTERNAL_SERVICE_TEST_FAILED, 권한 없음 403")
    void connectionTest() throws Exception {
        setUp();
        fx.mail(org, smtpPort());
        mvc.perform(as(org, admin, post("/core/external-services/MAIL/test"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.ok").value(true));
        mvc.perform(as(org, admin, json(post("/core/external-services/MAIL/test"),
                        "{\"settings\":{\"host\":\"127.0.0.1\",\"port\":1,\"from\":\"a@b.c\"}}")))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.header.resultCode").value("EXTERNAL_SERVICE_TEST_FAILED"));
        mvc.perform(as(org, admin, post("/core/external-services/LLM/test"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(post("/core/external-services/LLM/test"), "{\"settings\":{\"model\":\"m\"}}")))
                .andExpect(status().isBadGateway());
        assertThat(jdbc.sql("SELECT last_test_result FROM data2flow_core.external_service_config WHERE kind = 'MAIL'")
                .query(String.class).single()).isEqualTo("OK");
        long op = fx.user(org, "kim.op", "OPERATOR");
        mvc.perform(as(org, op, post("/core/external-services/MAIL/test"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
    }

    @Test
    @DisplayName("[IAM-02.03·03.01·01.08][API-IAM-72] 보안 정책: 들어온 필드만 바꾸고 범위 밖은 400, 감사 SECURITY_POLICY_CHANGED")
    void securityPolicy() throws Exception {
        setUp();
        mvc.perform(as(org, admin, get("/core/security-policy"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.loginMaxFailures").value(5)).andExpect(jsonPath("$.response.lockoutMinutes").value(15));
        mvc.perform(as(org, admin, json(put("/core/security-policy"),
                        "{\"loginMaxFailures\":3,\"signupRequestEnabled\":true,\"signupAllowedDomains\":[\"School.ac.kr\"],\"mfaRequiredRoles\":[\"operator\"],\"baseVersion\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.loginMaxFailures").value(3))
                .andExpect(jsonPath("$.response.signupAllowedDomains[0]").value("school.ac.kr"))
                .andExpect(jsonPath("$.response.mfaRequiredRoles[0]").value("OPERATOR"))
                .andExpect(jsonPath("$.response.sessionIdleMinutes").value(30));
        mvc.perform(as(org, admin, json(put("/core/security-policy"),
                        "{\"loginMaxFailures\":99,\"auditRetentionDays\":30,\"signupRequestEnabled\":\"yes\",\"signupAllowedDomains\":[\"bad domain\"],\"mfaRequiredRoles\":[\"KING\"],\"baseVersion\":1}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(5));
        mvc.perform(as(org, admin, json(put("/core/security-policy"), "{\"lockoutMinutes\":30,\"baseVersion\":0}")))
                .andExpect(status().isConflict());
        assertThat(auditCount(org, "SECURITY_POLICY_CHANGED")).isEqualTo(1);
    }
}
