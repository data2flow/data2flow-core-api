package net.java21.data2flow.core.apitoken.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.apitoken.domain.ApiTokenErrorCode;
import net.java21.data2flow.core.apitoken.dto.ApiTokenDtos.IssueResponse;
import net.java21.data2flow.core.apitoken.service.ApiTokenService;
import net.java21.data2flow.core.apitoken.service.ServiceAccountService;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.config.WebConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 장기 토큰·서비스 계정 API 오류 코드·문구(4개 언어)·응답 모양(API-IAM-40~45) */
@WebMvcTest(ApiTokenController.class)
@Import({WebConfig.class, AccountGateInterceptor.class})
class ApiTokenControllerWebTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ApiTokenService tokens;
    @MockitoBean
    ServiceAccountService accounts;
    @MockitoBean
    UserRepository users;

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "API_TOKEN_SCOPE_EXCEEDED,ko,400,내 권한을 넘는 범위는 지정할 수 없습니다",
            "API_TOKEN_EXPIRY_INVALID,ko,400,만료일은 오늘부터 1년 이내여야 합니다",
            "API_TOKEN_LIMIT_EXCEEDED,ko,409,API 키 한도를 넘었습니다",
            "API_TOKEN_EXPIRY_INVALID,en,400,The expiry date must be within one year from today",
            "API_TOKEN_SCOPE_EXCEEDED,ja,400,自分の権限を超える範囲は指定できません",
            "API_TOKEN_LIMIT_EXCEEDED,zh,409,已超过API密钥上限"})
    @DisplayName("[IAM-05.02][IAM-04.07] 발급 오류 코드별 상태·문구(4개 언어) — TC-IAM-149·154·155")
    void issueErrors(String code, String lang, int status, String message) throws Exception {
        given(tokens.issue(any())).willThrow(new BusinessException(ApiTokenErrorCode.valueOf(code)));
        mvc.perform(post("/core/api-tokens").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Accept-Language", lang)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"MCP\"}"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.header.resultCode").value(code))
                .andExpect(jsonPath("$.header.isSuccessful").value(false)).andExpect(jsonPath("$.header.resultMessage").value(message));
    }

    @Test
    @DisplayName("[IAM-05.01] 발급 201 + Location + Cache-Control no-store, 원문은 이 응답에만 — TC-IAM-163")
    void issueCreated() throws Exception {
        given(tokens.issue(any())).willReturn(new IssueResponse("7", "data2flow_secret", "data2flow_se", "ACTIVE"));
        mvc.perform(post("/core/api-tokens").header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/core/api-tokens/7"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.response.token").value("data2flow_secret"));
    }

    @Test
    @DisplayName("[IAM-05.03][IAM-05.01] 없는 토큰 404 API_TOKEN_NOT_FOUND, 없는 서비스 계정 404 SERVICE_ACCOUNT_NOT_FOUND — TC-IAM-156·162")
    void notFound() throws Exception {
        willThrow(new BusinessException(ApiTokenErrorCode.API_TOKEN_NOT_FOUND)).given(tokens).revoke(anyLong());
        mvc.perform(delete("/core/api-tokens/9").header("X-USER-ID", "1").header("X-ORG-ID", "1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultMessage").value("토큰을 찾을 수 없습니다"));
        given(accounts.disable(anyLong())).willThrow(new BusinessException(ApiTokenErrorCode.SERVICE_ACCOUNT_NOT_FOUND));
        mvc.perform(post("/core/service-accounts/9/disable").header("X-USER-ID", "1").header("X-ORG-ID", "1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SERVICE_ACCOUNT_NOT_FOUND"));
    }
}
