package net.java21.data2flow.core.account.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.account.dto.AccountDtos.CreateUserResponse;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.account.service.UserAdminService;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.common.CoreErrorCode;
import net.java21.data2flow.core.config.WebConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 회원 관리 API 응답 형식(api-rules §3·§4, OPS-12.01) — TC-IAM-032·042·059 슬라이스 행 */
@WebMvcTest(UserController.class)
@Import({WebConfig.class, AccountGateInterceptor.class})
class AccountMembershipControllerWebTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    UserAdminService service;
    @MockitoBean
    UserRepository users;

    @Test
    @DisplayName("[IAM-01.03][TC-IAM-032] 중복 아이디 → 409, resultCode LOGIN_ID_DUPLICATED, 한국어 문구")
    void duplicated() throws Exception {
        given(service.create(any())).willThrow(new BusinessException(CoreErrorCode.LOGIN_ID_DUPLICATED));
        mvc.perform(post("/core/users").header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"kim.op\",\"email\":\"k@s.kr\",\"name\":\"김\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value("LOGIN_ID_DUPLICATED"))
                .andExpect(jsonPath("$.header.resultMessage").value("이미 사용 중인 아이디입니다"))
                .andExpect(header().exists("X-REQUEST-ID"));
    }

    @Test
    @DisplayName("[IAM-01.10][TC-IAM-042·059] 상태 충돌·자기 변경 → 409, Accept-Language en이면 문구만 영어")
    void conflicts() throws Exception {
        willThrow(new BusinessException(CoreErrorCode.USER_STATE_CONFLICT)).given(service).enable(anyLong());
        mvc.perform(post("/core/users/5/enable").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Accept-Language", "en"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("USER_STATE_CONFLICT"))
                .andExpect(jsonPath("$.header.resultMessage").value("This action is not allowed in the current state"));
        willThrow(new BusinessException(CoreErrorCode.SELF_MODIFICATION_FORBIDDEN)).given(service).unlock(anyLong());
        mvc.perform(post("/core/users/1/unlock").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Accept-Language", "ja"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultMessage").value("自分のロールや状態は変更できません"));
    }

    @Test
    @DisplayName("[OPS-12.02] 목록은 {header, page, size, totalPages, responses, totalCount}, 생성은 201 + Location")
    void listAndCreateShape() throws Exception {
        given(service.list(any(), any(), any(), any(), any(), any(), any()))
                .willReturn(ListApiResponse.of(PageParams.of(1, 20), List.of(), 0));
        mvc.perform(get("/core/users").header("X-USER-ID", "1").header("X-ORG-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.responses").isArray()).andExpect(jsonPath("$.totalCount").value(0));
        given(service.create(any())).willReturn(new CreateUserResponse("77", null));
        mvc.perform(post("/core/users").header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"kim.op\",\"email\":\"k@s.kr\",\"name\":\"김\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/core/users/77"))
                .andExpect(jsonPath("$.response.id").value("77"))
                .andExpect(jsonPath("$.response.temporaryPassword").doesNotExist());
        mvc.perform(post("/core/users").header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"\",\"email\":\"k@s.kr\",\"name\":\"김\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("loginId"));
        mvc.perform(post("/core/users/1/disable").header("X-USER-ID", "x").header("X-ORG-ID", "1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/core/users/1/disable")).andExpect(status().isUnauthorized());
    }
}
