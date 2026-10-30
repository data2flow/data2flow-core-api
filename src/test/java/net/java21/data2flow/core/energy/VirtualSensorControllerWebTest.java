package net.java21.data2flow.core.energy;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.config.WebConfig;
import net.java21.data2flow.core.energy.controller.VirtualSensorController;
import net.java21.data2flow.core.energy.domain.EnergyErrorCode;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.AchDay;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.AchSeries;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.DecayWindow;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.Input;
import net.java21.data2flow.core.energy.dto.VirtualSensorDtos.SensorView;
import net.java21.data2flow.core.energy.service.VirtualSensorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 가상 센서 API 형식(API-ENE-20·21) — TC-ENE-038: 변경 200·403, 조회 200·404, 409 문구 */
@WebMvcTest(VirtualSensorController.class)
@Import({WebConfig.class, AccountGateInterceptor.class})
class VirtualSensorControllerWebTest {

    private static final Instant AT = Instant.parse("2026-10-29T17:00:00Z");

    @Autowired
    MockMvc mvc;
    @MockitoBean
    VirtualSensorService service;
    @MockitoBean
    UserRepository users;

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
        return b.header("X-USER-ID", "1").header("X-ORG-ID", "1");
    }

    private static SensorView view(boolean enabled) {
        return new SensorView("ACH", enabled, JsonMapper.builder().build().readTree("{\"outdoorCo2Ppm\":420}"), List.of("ach", "ventilation_m3h"),
                List.of(new Input("co2", true, List.of("3001"))), AT, enabled ? "OK" : "DISABLED", null, 150.0);
    }

    @Test
    @DisplayName("[TC-ENE-038] 변경 200(ADMIN), 권한 없으면 403 PERMISSION_DENIED, 부피 없음 409(ko 문구), 입력 없음 409 errors[].reason")
    void update() throws Exception {
        String body = "{\"enabled\":true,\"params\":{\"outdoorCo2Ppm\":420}}";
        given(service.update(eq("31"), eq("ach"), any())).willReturn(view(true));
        mvc.perform(auth(put("/core/energy/spaces/31/virtual-sensors/ach")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.kind").value("ACH")).andExpect(jsonPath("$.response.status").value("OK"))
                .andExpect(jsonPath("$.response.inputs[0].deviceIds[0]").value("3001"))
                .andExpect(jsonPath("$.response.lastComputedAt").value("2026-10-29T17:00:00Z"));
        willThrow(new BusinessException(CommonErrorCode.PERMISSION_DENIED)).given(service).update(eq("31"), eq("ach"), any());
        mvc.perform(auth(put("/core/energy/spaces/31/virtual-sensors/ach")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        willThrow(new BusinessException(EnergyErrorCode.ENE_SPACE_VOLUME_REQUIRED)).given(service).update(eq("32"), eq("ach"), any());
        mvc.perform(auth(put("/core/energy/spaces/32/virtual-sensors/ach")).contentType(MediaType.APPLICATION_JSON).content(body).header("Accept-Language", "ko"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("ENE_SPACE_VOLUME_REQUIRED"))
                .andExpect(jsonPath("$.header.resultMessage").value("공간 면적과 층고를 입력하세요"));
        willThrow(new BusinessException(EnergyErrorCode.ENE_VIRTUAL_SENSOR_INPUT_MISSING, List.of(new FieldErrorDetail("inputs", "co2", null))))
                .given(service).update(eq("33"), eq("ach"), any());
        mvc.perform(auth(put("/core/energy/spaces/33/virtual-sensors/ach")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors[0].field").value("inputs"))
                .andExpect(jsonPath("$.errors[0].code").value("co2"));
    }

    @Test
    @DisplayName("[TC-ENE-038] 조회 200(VIEWER), 범위 밖 공간 404 SPACE_NOT_FOUND, ACH 일별 환기량")
    void read() throws Exception {
        given(service.list("31")).willReturn(List.of(view(false)));
        mvc.perform(auth(get("/core/energy/spaces/31/virtual-sensors"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response[0].status").value("DISABLED")).andExpect(jsonPath("$.response[0].volumeM3").value(150.0));
        willThrow(new BusinessException(EnergyErrorCode.SPACE_NOT_FOUND)).given(service).list("99");
        mvc.perform(auth(get("/core/energy/spaces/99/virtual-sensors"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        given(service.ach("31", "2026-10-01", "2026-10-31")).willReturn(new AchSeries("31", 150.0, 420, List.of(
                new AchDay("2026-10-04", 2.0, 1.9, 2.1, 300.0, new DecayWindow(AT, AT.plusSeconds(7200)), 0.99))));
        mvc.perform(auth(get("/core/energy/spaces/31/ach")).param("from", "2026-10-01").param("to", "2026-10-31")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.items[0].ventilationM3h").value(300.0))
                .andExpect(jsonPath("$.response.items[0].decayWindow.from").value("2026-10-29T17:00:00Z"));
    }
}
