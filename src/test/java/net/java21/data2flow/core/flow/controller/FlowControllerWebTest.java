package net.java21.data2flow.core.flow.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.config.WebConfig;
import net.java21.data2flow.core.flow.domain.FlowErrorCode;
import net.java21.data2flow.core.flow.dto.FlowDtos.ApprovalPending;
import net.java21.data2flow.core.flow.service.FlowApplyService;
import net.java21.data2flow.core.flow.service.FlowRuntimeService;
import net.java21.data2flow.core.flow.service.FlowService;
import net.java21.data2flow.core.flow.service.FlowSupport;
import net.java21.data2flow.core.flow.service.FlowTemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 플로우 API 오류 코드·승인 대기 응답(API-FLW-03·07·20·24) */
@WebMvcTest({FlowController.class, FlowCatalogController.class})
@Import({WebConfig.class, AccountGateInterceptor.class})
class FlowControllerWebTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    FlowService flows;
    @MockitoBean
    FlowApplyService apply;
    @MockitoBean
    FlowRuntimeService runtime;
    @MockitoBean
    FlowSupport support;
    @MockitoBean
    FlowTemplateService templates;
    @MockitoBean
    net.java21.data2flow.contracts.authz.RoleChecker roleChecker;
    @MockitoBean
    UserRepository users;

    @Test
    @DisplayName("[FLW-01.01][FLW-05.06] 이름 중복 409 FLOW_NAME_DUPLICATED, 없거나 범위 밖 404 FLOW_NOT_FOUND — TC-FLW-009·119")
    void errors() throws Exception {
        given(flows.create(any())).willThrow(new BusinessException(FlowErrorCode.FLOW_NAME_DUPLICATED));
        mvc.perform(post("/core/flows").header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"definition\":{}}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultMessage").value("같은 이름의 플로우가 있습니다"));
        given(flows.detail(anyString(), any())).willThrow(new BusinessException(FlowErrorCode.FLOW_NOT_FOUND));
        mvc.perform(get("/core/flows/abc").header("X-USER-ID", "1").header("X-ORG-ID", "1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultMessage").value("플로우를 찾을 수 없습니다"));
    }

    @Test
    @DisplayName("[FLW-05.06] 승인 대기 → 202 FLOW_APPROVAL_REQUIRED(isSuccessful=true, \"승인 요청을 보냈습니다\"), 권한 없음 403 — TC-FLW-117·120")
    void approvalPending() throws Exception {
        given(apply.apply(anyString(), any())).willReturn(new FlowApplyService.Outcome(null, new ApprovalPending("9", 3)));
        mvc.perform(post("/core/flows/f/apply").header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3,\"baseVersion\":2}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.header.resultCode").value("FLOW_APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.header.resultMessage").value("승인 요청을 보냈습니다"))
                .andExpect(jsonPath("$.response.approvalId").value("9"));
        given(apply.rollback(anyString(), any())).willThrow(new BusinessException(CommonErrorCode.PERMISSION_DENIED));
        mvc.perform(post("/core/flows/f/rollback").header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toVersion\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[FLW-01.05][AT-FLW-01.3] 템플릿 생성: 권한 없음 403, 다른 조직 공간 404, 모르는 템플릿 404 — TC-FLW-111")
    void templates() throws Exception {
        given(templates.instantiate(anyString(), any())).willThrow(new BusinessException(CommonErrorCode.PERMISSION_DENIED));
        mvc.perform(post("/core/flow-templates/hot-then-cool/instantiate").header("X-USER-ID", "1").header("X-ORG-ID", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\",\"params\":{}}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        org.mockito.BDDMockito.willThrow(new BusinessException(FlowErrorCode.FLOW_TEMPLATE_NOT_FOUND)).given(templates).instantiate(anyString(), any());
        mvc.perform(post("/core/flow-templates/x/instantiate").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultMessage").value("Flow template not found"));
    }
}
