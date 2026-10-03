package net.java21.data2flow.core.script.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.config.WebConfig;
import net.java21.data2flow.core.script.domain.ScriptErrorCode;
import net.java21.data2flow.core.script.dto.ScriptDtos.CreateScriptResponse;
import net.java21.data2flow.core.script.service.ScriptBindingService;
import net.java21.data2flow.core.script.service.ScriptService;
import net.java21.data2flow.core.script.service.ScriptTestRunService;
import net.java21.data2flow.core.script.service.ScriptVersionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스크립트 API 요청 검증·오류 코드 매핑·4개 언어 문구(api-rules §3·§4) — TC-SCR-073 슬라이스, TC-SCR-045 경로(API-SCR-08) */
@WebMvcTest(ScriptController.class)
@Import({WebConfig.class, AccountGateInterceptor.class})
class ScriptControllerWebTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ScriptService scripts;
    @MockitoBean
    ScriptVersionService versions;
    @MockitoBean
    ScriptBindingService bindings;
    @MockitoBean
    ScriptTestRunService testRuns;
    @MockitoBean
    UserRepository users;

    @Test
    @DisplayName("[SCR-04.03][AT-SCR-03.5] 생성 201 + Location, 이름 길이·종류 누락 400(errors[]), 서비스 오류 코드 → HTTP 상태와 현지화 문구 — TC-SCR-073")
    void createAndErrors() throws Exception {
        given(scripts.create(any())).willReturn(new CreateScriptResponse("501", "온도 보정", "TRANSFORM", "ENABLED", "804", 0));
        mvc.perform(post("/core/scripts").header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"온도 보정\",\"kind\":\"TRANSFORM\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/core/scripts/501"))
                .andExpect(jsonPath("$.response.id").value("501"));
        mvc.perform(post("/core/scripts").header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors.length()").value(2));

        given(versions.saveDraft(anyLong(), any())).willThrow(new BusinessException(ScriptErrorCode.SCRIPT_CODE_TOO_LARGE));
        mvc.perform(put("/core/scripts/501/draft").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"x\",\"baseVersionNo\":4}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_CODE_TOO_LARGE"))
                .andExpect(jsonPath("$.header.resultMessage").value("The code is too large (maximum 64KB)"));
        given(versions.deploy(anyLong(), any())).willThrow(new BusinessException(ScriptErrorCode.SCRIPT_VERSION_CONFLICT));
        mvc.perform(post("/core/scripts/501/deploy").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Accept-Language", "ja")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"versionId\":\"805\",\"memo\":\"배포\",\"baseActiveVersionId\":\"804\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultMessage").value("他のユーザーが先に変更しました"));
        mvc.perform(post("/core/scripts/501/deploy").header("X-USER-ID", "1").header("X-ORG-ID", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"versionId\":\"805\",\"memo\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("memo"));
        given(scripts.detail(anyLong(), any())).willThrow(new BusinessException(ScriptErrorCode.SCRIPT_NOT_FOUND));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/core/scripts/9")
                        .header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Accept-Language", "zh"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultMessage").value("找不到脚本"));
    }

    @Test
    @DisplayName("[SCR-03.02] 테스트 실행은 API-SCR-08 POST /core/scripts/test-run(종류·코드 필수) — TC-SCR-045 경로 정정")
    void testRunPath() throws Exception {
        mvc.perform(post("/core/scripts/test-run").header("X-USER-ID", "1").header("X-ORG-ID", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("kind"));
    }
}
