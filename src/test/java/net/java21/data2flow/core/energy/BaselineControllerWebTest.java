package net.java21.data2flow.core.energy;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.config.WebConfig;
import net.java21.data2flow.core.energy.controller.EnergyBaselineController;
import net.java21.data2flow.core.energy.domain.EnergyErrorCode;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.Accepted;
import net.java21.data2flow.core.energy.dto.EnergyBaselineDtos.VersionView;
import net.java21.data2flow.core.energy.service.EnergyBaselineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 기준선 API 형식(API-ENE-12) — TC-ENE-027: 학습 202 {jobId, versionId}, 적용 200, 409 문구(neededDays), 403 */
@WebMvcTest(EnergyBaselineController.class)
@Import({WebConfig.class, AccountGateInterceptor.class})
class BaselineControllerWebTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    EnergyBaselineService service;
    @MockitoBean
    UserRepository users;

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
        return b.header("X-USER-ID", "1").header("X-ORG-ID", "1");
    }

    @Test
    @DisplayName("[TC-ENE-027] 학습 요청 202, 기간 부족 409 ENE_BASELINE_INSUFFICIENT_DATA(neededDays, ja 문구), VIEWER 403")
    void create() throws Exception {
        String body = "{\"scopeSpaceId\":\"10\",\"method\":\"PERIOD_AVERAGE\",\"trainFrom\":\"2026-08-01\",\"trainTo\":\"2026-09-30\"}";
        given(service.create(any())).willReturn(new Accepted("901", "12"));
        mvc.perform(auth(post("/core/energy/baselines/versions")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.jobId").value("901")).andExpect(jsonPath("$.response.versionId").value("12"));
        willThrow(new BusinessException(EnergyErrorCode.ENE_BASELINE_INSUFFICIENT_DATA,
                List.of(new FieldErrorDetail("neededDays", "NeededDays", "5")), 5L)).given(service).create(any());
        mvc.perform(auth(post("/core/energy/baselines/versions")).contentType(MediaType.APPLICATION_JSON).content(body).header("Accept-Language", "ja"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("ENE_BASELINE_INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.header.resultMessage").value("学習期間が短すぎます。あと5日必要です(最低30日)"))
                .andExpect(jsonPath("$.errors[0].message").value("5"));
        willThrow(new BusinessException(CommonErrorCode.PERMISSION_DENIED)).given(service).create(any());
        mvc.perform(auth(post("/core/energy/baselines/versions")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[TC-ENE-027] 적용 200 ACTIVE, READY가 아니면 409 ENE_BASELINE_NOT_READY(en), 없으면 404")
    void activate() throws Exception {
        given(service.activate("12")).willReturn(new VersionView("12", "3", "PERIOD_AVERAGE", "2026-08-01", "2026-09-30", "ACTIVE",
                new BigDecimal("0.910"), null, null, null, null, Instant.parse("2026-10-29T00:00:00Z")));
        mvc.perform(auth(post("/core/energy/baselines/versions/12/activate"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("ACTIVE")).andExpect(jsonPath("$.response.r2").value(0.91));
        given(service.activate("13")).willThrow(new BusinessException(EnergyErrorCode.ENE_BASELINE_NOT_READY));
        mvc.perform(auth(post("/core/energy/baselines/versions/13/activate")).header("Accept-Language", "en")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultMessage").value("Only a trained (READY) version can be activated"));
        willThrow(new BusinessException(EnergyErrorCode.ENE_BASELINE_NOT_FOUND)).given(service).activate("x");
        mvc.perform(auth(post("/core/energy/baselines/versions/x/activate"))).andExpect(status().isNotFound());
    }
}
