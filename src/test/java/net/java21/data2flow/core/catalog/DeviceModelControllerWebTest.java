package net.java21.data2flow.core.catalog;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.catalog.controller.DeviceModelController;
import net.java21.data2flow.core.catalog.domain.CatalogErrorCode;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.ModelResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.PackageDto;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.PackageResponse;
import net.java21.data2flow.core.catalog.service.DeviceModelService;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.config.WebConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 기기 모델 API 형식(API-DEV-40·42·43·46·47) — TC-DEV-089·090·095·096 */
@WebMvcTest(DeviceModelController.class)
@Import({WebConfig.class, AccountGateInterceptor.class})
class DeviceModelControllerWebTest {

    static final ModelResponse MODEL = new ModelResponse("11", "ESP32-TH", "자체", "ESP32", "MQTT", "SENSOR", 600, 3.0, null, null,
            false, "ACTIVE", List.of(), List.of(), new PackageDto(null, null, null, null, List.of(), null), null, 0, 0,
            Instant.parse("2026-10-03T00:00:00Z"));

    @Autowired
    MockMvc mvc;
    @MockitoBean
    DeviceModelService service;
    @MockitoBean
    UserRepository users;

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
        return b.header("X-USER-ID", "1").header("X-ORG-ID", "1");
    }

    @Test
    @DisplayName("[DEV-03.01][TC-DEV-089] 생성 201 + Location, 필수 누락 400 INVALID_REQUEST, 중복 409 MODEL_CODE_DUPLICATE")
    void create() throws Exception {
        given(service.create(any())).willReturn(MODEL);
        String body = "{\"code\":\"ESP32-TH\",\"vendor\":\"자체\",\"name\":\"ESP32\",\"protocol\":\"MQTT\",\"kind\":\"SENSOR\"}";
        mvc.perform(auth(post("/core/device-models")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/core/device-models/11"))
                .andExpect(jsonPath("$.header.resultCode").value("SUCCESS")).andExpect(jsonPath("$.response.package").exists());
        mvc.perform(auth(post("/core/device-models")).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"X\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
        given(service.create(any())).willThrow(new BusinessException(CatalogErrorCode.MODEL_CODE_DUPLICATE));
        mvc.perform(auth(post("/core/device-models")).contentType(MediaType.APPLICATION_JSON).content(body).header("Accept-Language", "en"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultMessage").value("A model with the same code already exists"));
    }

    @Test
    @DisplayName("[DEV-03.01][TC-DEV-089] 패키지 200, 기본 모델 403 MODEL_BUILTIN_READONLY(ja), 스크립트 없음 404, 삭제 204·사용 중 409(zh)")
    void packageAndDelete() throws Exception {
        given(service.updatePackage(anyLong(), any())).willReturn(new PackageResponse("11", null, null, "d", null, List.of(), null, 1));
        mvc.perform(auth(put("/core/device-models/11/package")).contentType(MediaType.APPLICATION_JSON).content("{\"driverKey\":\"d\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.driverKey").value("d"));
        given(service.updatePackage(anyLong(), any())).willThrow(new BusinessException(CatalogErrorCode.MODEL_BUILTIN_READONLY));
        mvc.perform(auth(put("/core/device-models/1/package")).contentType(MediaType.APPLICATION_JSON).content("{}").header("Accept-Language", "ja"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultMessage").value("標準モデルは変更できません。複製して使用してください"));
        willThrow(new BusinessException(CatalogErrorCode.SCRIPT_NOT_FOUND)).given(service).updatePackage(anyLong(), any());
        mvc.perform(auth(put("/core/device-models/1/package")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_NOT_FOUND"));
        mvc.perform(auth(delete("/core/device-models/11"))).andExpect(status().isNoContent());
        willThrow(new BusinessException(CatalogErrorCode.MODEL_IN_USE)).given(service).delete(12L);
        mvc.perform(auth(delete("/core/device-models/12")).header("Accept-Language", "zh"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultMessage").value("有设备正在使用此型号"));
    }

    @Test
    @DisplayName("[DEV-03.01][TC-DEV-090] 목록 형식, 상세·사용 중지 200, 복제 201 + Location, 복제 코드 누락 400")
    void listCloneDeprecate() throws Exception {
        given(service.list(any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(ListApiResponse.of(PageParams.of(1, 20), List.of(), 0));
        mvc.perform(auth(get("/core/device-models?protocol=MQTT&includeDeprecated=true"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.responses").isArray());
        given(service.get(11L)).willReturn(MODEL);
        mvc.perform(auth(get("/core/device-models/11"))).andExpect(status().isOk()).andExpect(jsonPath("$.response.id").value("11"));
        given(service.deprecate(11L)).willReturn(MODEL);
        mvc.perform(auth(post("/core/device-models/11/deprecate"))).andExpect(status().isOk());
        given(service.cloneModel(anyLong(), any())).willReturn(MODEL);
        mvc.perform(auth(post("/core/device-models/1/clone")).contentType(MediaType.APPLICATION_JSON).content("{\"newCode\":\"ESP32-TH\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/core/device-models/11"));
        mvc.perform(auth(post("/core/device-models/1/clone")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
