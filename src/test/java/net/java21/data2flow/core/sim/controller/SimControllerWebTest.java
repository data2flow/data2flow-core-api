package net.java21.data2flow.core.sim.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.config.WebConfig;
import net.java21.data2flow.core.sim.domain.SimErrorCode;
import net.java21.data2flow.core.sim.service.SimDeviceService;
import net.java21.data2flow.core.sim.service.SimRelayService;
import net.java21.data2flow.core.sim.service.SimSpaceService;
import org.junit.jupiter.api.DisplayName;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 가상 환경 API 오류 코드별 문구(API-SIM-10·14·24) */
@WebMvcTest(SimController.class)
@Import({WebConfig.class, AccountGateInterceptor.class})
class SimControllerWebTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    SimRelayService relay;
    @MockitoBean
    SimDeviceService devices;
    @MockitoBean
    SimSpaceService spaces;
    @MockitoBean
    UserRepository users;

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "SIM_CONCURRENT_RUN_LIMIT,409,동시에 실행할 수 있는 시나리오는 5개입니다",
            "SIM_ACCELERATION_LIMIT,409,기기가 많아 이 가속으로 실행할 수 없습니다",
            "SIM_SPACE_BUSY,409,이 공간에서 이미 시나리오가 실행 중입니다",
            "SIM_NOT_FOUND,404,대상을 찾을 수 없습니다"})
    @DisplayName("[SIM-04.02][SIM-11.01][AT-SIM-08.4] 실행 시작 409 코드마다 자기 문구, 다른 조직 실행 404 — TC-SIM-116")
    void runErrors(String code, int status, String message) throws Exception {
        org.mockito.BDDMockito.willThrow(new BusinessException(SimErrorCode.valueOf(code))).given(relay).startRun(any());
        mvc.perform(post("/core/sim/runs").header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"3\"}"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.header.resultMessage").value(message));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"SIM_SANDBOX_VIOLATION,403", "PERMISSION_DENIED,403", "SIM_NOT_FOUND,404"})
    @DisplayName("[SIM-07.03][SIM-01.02] 샌드박스 지정(실제 기기 403 SIM_SANDBOX_VIOLATION·ADMIN만)과 가상 공간 만들기 오류 — TC-SIM-081·004")
    void sandboxAndSpaces(String code, int status) throws Exception {
        BusinessException ex = code.startsWith("SIM") ? new BusinessException(SimErrorCode.valueOf(code))
                : new BusinessException(CommonErrorCode.valueOf(code));
        org.mockito.BDDMockito.willThrow(ex).given(spaces).sandbox(anyLong(), any());
        mvc.perform(put("/core/sim/spaces/5/sandbox").header("X-USER-ID", "1").header("X-ORG-ID", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sandbox\":true}"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.header.resultCode").value(code));
        given(spaces.create(any())).willThrow(new BusinessException(SimErrorCode.SIM_PROPERTY_OUT_OF_RANGE));
        mvc.perform(post("/core/sim/spaces").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultMessage").value("The value is out of the allowed range"));
    }
}
