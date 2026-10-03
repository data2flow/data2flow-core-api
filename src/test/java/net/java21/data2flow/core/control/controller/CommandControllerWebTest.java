package net.java21.data2flow.core.control.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.config.WebConfig;
import net.java21.data2flow.core.control.domain.ControlErrorCode;
import net.java21.data2flow.core.control.service.CapabilityService;
import net.java21.data2flow.core.control.service.CommandService;
import net.java21.data2flow.core.control.service.DriverService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 제어 API 오류 코드 → HTTP 상태·현지화 문구(api-rules §4, ACT domain-model §5) */
@WebMvcTest({CommandController.class, CapabilityController.class, DriverController.class})
@Import({WebConfig.class, AccountGateInterceptor.class})
class CommandControllerWebTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    CommandService commands;
    @MockitoBean
    CapabilityService capabilities;
    @MockitoBean
    DriverService drivers;
    @MockitoBean
    UserRepository users;

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "COMMAND_ARGS_INVALID,400,명령 값이 올바르지 않습니다",
            "COMMAND_ARG_OUT_OF_RANGE,400,허용 범위 밖의 값입니다",
            "CAPABILITY_NOT_SUPPORTED,400,이 기기가 지원하지 않는 기능입니다",
            "DEVICE_NOT_CONTROLLABLE,409,이 기기는 제어할 수 없습니다",
            "COMMAND_ABSOLUTE_LIMIT,400,조직 제한을 넘었습니다",
            "COMMAND_BLOCKED,409,명령이 차단되었습니다"})
    @DisplayName("[ACT-01.01][ACT-01.03][ACT-06.04] 명령 오류 → HTTP·resultCode·문구, isSuccessful=false — TC-ACT-001·005·006·007·032·114·118·064")
    void commandErrors(String code, int status, String message) throws Exception {
        given(commands.send(anyLong(), any(), anyString())).willThrow(new BusinessException(ControlErrorCode.valueOf(code)));
        mvc.perform(post("/core/devices/17/commands").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"capability\":\"Thermostat\",\"command\":\"set\",\"args\":{}}"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value(code)).andExpect(jsonPath("$.header.resultMessage").value(message));
    }

    @Test
    @DisplayName("[ACT-04.03] 명령 없음 404 COMMAND_NOT_FOUND, 취소 불가 409 COMMAND_NOT_CANCELLABLE(영어 문구), 성공 202 — TC-ACT-088·089")
    void commandLookups() throws Exception {
        given(commands.get("x")).willThrow(new BusinessException(ControlErrorCode.COMMAND_NOT_FOUND));
        mvc.perform(get("/core/commands/x").header("X-USER-ID", "1").header("X-ORG-ID", "1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultMessage").value("명령을 찾을 수 없습니다"));
        given(commands.cancel("y")).willThrow(new BusinessException(ControlErrorCode.COMMAND_NOT_CANCELLABLE));
        mvc.perform(post("/core/commands/y/cancel").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Accept-Language", "en"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultMessage").value("The command cannot be cancelled in its current state"));
        given(commands.send(anyLong(), any(), anyString())).willReturn(new InternalHttp.Result(202,
                JsonMapper.builder().build().readTree("{\"id\":\"c\",\"status\":\"QUEUED\"}")));
        mvc.perform(post("/core/devices/17/commands").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Idempotency-Key", "k2")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.status").value("QUEUED"));
        given(commands.control(anyLong())).willThrow(new BusinessException(CommonErrorCode.PERMISSION_DENIED));
        mvc.perform(get("/core/devices/17/control").header("X-USER-ID", "1").header("X-ORG-ID", "1"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
    }

    @Test
    @DisplayName("[ACT-01.01][ACT-03.01] 표준 이름 409 CAPABILITY_NAME_RESERVED(일본어), 드라이버 검증·연결 실패 502·기능 불일치 400 — TC-ACT-011·013·067·079·080")
    void capabilityAndDriverErrors() throws Exception {
        given(capabilities.create(any())).willThrow(new BusinessException(ControlErrorCode.CAPABILITY_NAME_RESERVED));
        mvc.perform(post("/core/capabilities").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Accept-Language", "ja")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Switch\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultMessage").value("標準機能名は使用できません"));
        given(drivers.create(any())).willThrow(new BusinessException(ControlErrorCode.DRIVER_CONTRACT_FAILED));
        mvc.perform(post("/core/drivers").header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.header.resultMessage").value("드라이버 검증에 실패했습니다"));
        given(drivers.healthcheck(anyLong())).willThrow(new BusinessException(ControlErrorCode.DRIVER_HEALTHCHECK_FAILED, "timeout"));
        mvc.perform(post("/core/drivers/3/healthcheck").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Accept-Language", "zh"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.header.resultMessage").value("无法连接驱动程序: timeout"));
        given(drivers.linkModel(anyLong(), any())).willThrow(new BusinessException(ControlErrorCode.DRIVER_CAPABILITY_MISMATCH, "Dimmer"));
        mvc.perform(put("/core/device-models/3/driver").header("X-USER-ID", "1").header("X-ORG-ID", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"driverId\":\"1\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultMessage").value("이 드라이버는 Dimmer를 지원하지 않습니다"));
    }
}
