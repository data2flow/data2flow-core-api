package net.java21.data2flow.core.branding;

import com.jayway.jsonpath.JsonPath;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import net.java21.data2flow.core.mail.service.MailService;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DSH-13.01 브랜딩(API-DSH-25, BR-DSH-20) — TC-DSH-115·116 */
class BrandingApplyIT extends IntegrationTestSupport {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    @Autowired
    MailService mail;

    private long org;
    private long admin;
    private long operator;

    @BeforeEach
    void setUp() {
        org = fx.organization("brand");
        admin = fx.user(org, "brand.admin", "ADMIN");
        operator = fx.user(org, "brand.op", "OPERATOR");
    }

    private String upload(long user, String kind, byte[] data, String name) throws Exception {
        return mvc.perform(asM(org, user, multipart("/core/branding/assets").file(new MockMultipartFile("file", name, "application/octet-stream", data))
                        .param("kind", kind)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("[DSH-13.01][AT-DSH-14.1] 로고·주 색상 #0055AA 저장 → 조회·공개(로그인 화면)·공유 화면·발송 메일(발신 이름·서명)에 같은 값, 동시 수정 409 — TC-DSH-115")
    void applyEverywhere() throws Exception {
        String asset = JsonPath.read(upload(admin, "LOGO_LIGHT", PNG, "logo.png"), "$.response.assetId");
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"1\" height=\"1\"/></svg>";
        String dark = JsonPath.read(upload(admin, "logo_dark", svg.getBytes(StandardCharsets.UTF_8), "dark.svg"), "$.response.assetId");
        mvc.perform(as(org, admin, json(put("/core/branding"), """
                        {"logoLightAssetId":"%s","logoDarkAssetId":"%s","primaryColor":"#0055aa","loginMessage":"실습동 환경 관리",
                         "mailSenderName":"데이터 실습실","mailSignature":"아카데미 운영팀","publicTheme":"dark","appName":"실습실 환경","baseVersion":0}"""
                        .formatted(asset, dark))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.primaryColor").value("#0055AA"))
                .andExpect(jsonPath("$.response.logoLightUrl").value("/api/v1/core/public/branding/assets/" + asset))
                .andExpect(jsonPath("$.response.publicTheme").value("DARK"))
                .andExpect(jsonPath("$.response.contrastRatio").value(greaterThan(4.5)))
                .andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, operator, get("/core/branding"))).andExpect(jsonPath("$.response.mailSenderName").value("데이터 실습실"));
        // 로그인 화면(공개)
        mvc.perform(get("/core/public/branding"))
                .andExpect(jsonPath("$.response.primaryColor").value("#0055AA"))
                .andExpect(jsonPath("$.response.loginMessage").value("실습동 환경 관리"))
                .andExpect(jsonPath("$.response.mailSenderName").doesNotExist());
        mvc.perform(get("/core/public/branding/assets/" + asset))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(content().bytes(PNG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        // 메일: 발신 이름·서명
        fx.mail(org, smtpPort());
        assertThat(mail.send(org, "someone@test.example", "mail.invitation", Locale.KOREAN, "실습실", "https://web.test/x", "2026-10-04")).isTrue();
        List<MimeMessage> sent = mails();
        assertThat(sent).hasSize(1);
        assertThat(((InternetAddress) sent.getFirst().getFrom()[0]).getPersonal()).isEqualTo("데이터 실습실");
        assertThat(sent.getFirst().getContent().toString()).contains("아카데미 운영팀");
        // 동시 수정
        mvc.perform(as(org, admin, json(put("/core/branding"), "{\"loginMessage\":\"x\",\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));
        assertThat(auditCount(org, "BRANDING_UPDATED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[DSH-13.01][AT-DSH-14.2][AT-DSH-14.3] 대비 1.07:1(#FFFF00)은 확인 없이 400 CONTRAST_LOW, 확인하면 저장. 스크립트 SVG 400 BRANDING_ASSET_INVALID")
    void contrastAndUnsafe() throws Exception {
        mvc.perform(as(org, admin, json(put("/core/branding"), "{\"primaryColor\":\"#FFFF00\",\"baseVersion\":0}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("primaryColor"))
                .andExpect(jsonPath("$.errors[0].code").value("CONTRAST_LOW"))
                .andExpect(jsonPath("$.errors[0].message").value("1.07:1"));
        mvc.perform(as(org, admin, json(put("/core/branding"), "{\"primaryColor\":\"#FFFF00\",\"contrastWarningAcked\":true,\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.contrastRatio").value(1.07));
        mvc.perform(asM(org, admin, multipart("/core/branding/assets").file(new MockMultipartFile("file", "x.svg", "image/svg+xml",
                        "<svg onload=\"alert(1)\"></svg>".getBytes(StandardCharsets.UTF_8))).param("kind", "LOGO_LIGHT")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("BRANDING_ASSET_INVALID"));
        mvc.perform(asM(org, admin, multipart("/core/branding/assets").param("kind", "LOGO_LIGHT")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(put("/core/branding"), "{\"primaryColor\":\"blue\",\"publicTheme\":\"NEON\",\"logoLightAssetId\":\"99999\","
                        + "\"loginMessage\":\"" + "가".repeat(301) + "\",\"baseVersion\":1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("primaryColor", "publicTheme",
                        "logoLightAssetId", "loginMessage")));
    }

    @Test
    @DisplayName("[DSH-13.01][AT-DSH-14.4] 권한: ADMIN 200, OPERATOR 변경·올리기 403(조회는 가능), 다른 조직 자산 404, 쓰지 않는 자산은 공개 경로 404 — TC-DSH-116")
    void permissions() throws Exception {
        mvc.perform(as(org, operator, json(put("/core/branding"), "{\"loginMessage\":\"x\",\"baseVersion\":0}"))).andExpect(status().isForbidden());
        mvc.perform(asM(org, operator, multipart("/core/branding/assets").file(new MockMultipartFile("file", "l.png", "image/png", PNG))
                .param("kind", "LOGO_LIGHT"))).andExpect(status().isForbidden());
        mvc.perform(as(org, operator, get("/core/branding"))).andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(0));
        String asset = JsonPath.read(upload(admin, "FAVICON", PNG, "f.png"), "$.response.assetId");
        mvc.perform(as(org, operator, get("/core/branding/assets/" + asset))).andExpect(status().isOk());
        mvc.perform(get("/core/public/branding/assets/" + asset)).andExpect(status().isNotFound());
        long other = fx.organization("brand2");
        long otherAdmin = fx.user(other, "brand2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/branding/assets/" + asset))).andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, json(put("/core/branding"), "{\"faviconAssetId\":\"" + asset + "\",\"baseVersion\":0}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
    }

    private static MockMultipartHttpServletRequestBuilder asM(long orgId, long userId, MockMultipartHttpServletRequestBuilder b) {
        b.header("X-USER-ID", Long.toString(userId)).header("X-ORG-ID", Long.toString(orgId)).header("Accept-Language", "ko");
        return b;
    }
}
