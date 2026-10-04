package net.java21.data2flow.core.output;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.config.WebConfig;
import net.java21.data2flow.core.output.controller.InternalOutputController;
import net.java21.data2flow.core.output.controller.OutputConnectionController;
import net.java21.data2flow.core.output.domain.OutputErrorCode;
import net.java21.data2flow.core.output.dto.OutputDtos.OutputConnectionResponse;
import net.java21.data2flow.core.output.dto.OutputDtos.ReplayResponse;
import net.java21.data2flow.core.output.dto.OutputDtos.RuntimeResponse;
import net.java21.data2flow.core.output.service.OutputConnectionService;
import net.java21.data2flow.core.output.service.OutputInternalService;
import net.java21.data2flow.core.source.domain.SourceErrorCode;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 출력 연결 API 형식(API-DSC-30·32·33, 내부 API-DSC-73) — TC-DSC-127 */
@WebMvcTest({OutputConnectionController.class, InternalOutputController.class})
@Import({WebConfig.class, AccountGateInterceptor.class})
class OutputConnectionControllerWebTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final OutputConnectionResponse CONN = new OutputConnectionResponse("9", "파트너 MQTT", "MQTT_PUBLISH",
            JSON.readTree("{\"url\":\"mqtts://b.example:8883\",\"topicTemplate\":\"d2f/{deviceName}\"}"),
            JSON.readTree("{\"metrics\":[\"co2\"]}"), "CANONICAL", null, true, List.of("PASSWORD"), true, 1,
            Instant.parse("2026-10-04T00:00:00Z"), Instant.parse("2026-10-04T00:00:00Z"));

    @Autowired
    MockMvc mvc;
    @MockitoBean
    OutputConnectionService service;
    @MockitoBean
    OutputInternalService internal;
    @MockitoBean
    UserRepository users;

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
        return b.header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("[DSC-04.01][TC-DSC-127] 생성 201 + Location, 설정 오류 400 SOURCE_CONFIG_INVALID(errors), 없는 연결 404 OUTPUT_NOT_FOUND, 삭제 204")
    void createAndErrors() throws Exception {
        given(service.create(any())).willReturn(CONN);
        mvc.perform(auth(post("/core/output-connections")).content("{\"name\":\"파트너 MQTT\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/core/output-connections/9"))
                .andExpect(jsonPath("$.header.resultCode").value("SUCCESS")).andExpect(jsonPath("$.response.secretConfigured").value(true))
                .andExpect(jsonPath("$.response.secret").doesNotExist());
        given(service.create(any())).willThrow(new BusinessException(SourceErrorCode.SOURCE_CONFIG_INVALID,
                List.of(new FieldErrorDetail("target.url", "FORBIDDEN_HOST", null)), "target.url"));
        mvc.perform(auth(post("/core/output-connections")).content("{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("SOURCE_CONFIG_INVALID"))
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN_HOST"));
        given(service.get(anyLong())).willThrow(new BusinessException(OutputErrorCode.OUTPUT_NOT_FOUND));
        mvc.perform(auth(get("/core/output-connections/77")).header("Accept-Language", "en")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("OUTPUT_NOT_FOUND"))
                .andExpect(jsonPath("$.header.resultMessage").value("Output connection not found"));
        mvc.perform(auth(delete("/core/output-connections/9"))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("[DSC-04.01][TC-DSC-127] 테스트 실패는 200 + ok=false·failureKind, 재전송은 {queued}, 내부 실행 설정은 같은 버전이면 204")
    void testReplayRuntime() throws Exception {
        given(service.test(any())).willReturn(JSON.readTree("{\"ok\":false,\"failureKind\":\"AUTH\",\"rendered\":\"{}\",\"response\":{\"status\":null}}"));
        mvc.perform(auth(post("/core/output-connections/test")).content("{\"sampleDeviceId\":\"1\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true)).andExpect(jsonPath("$.response.ok").value(false))
                .andExpect(jsonPath("$.response.failureKind").value("AUTH"));
        given(service.replayFailed(anyLong(), any())).willReturn(new ReplayResponse(3));
        mvc.perform(auth(post("/core/output-connections/9/replay-failed")).content("{\"from\":\"2026-10-04T00:00:00Z\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.queued").value(3));
        given(internal.runtime(5L)).willReturn(Optional.empty());
        mvc.perform(get("/internal/core/output-connections/runtime").param("sinceVersion", "5")).andExpect(status().isNoContent());
        given(internal.runtime(null)).willReturn(Optional.of(new RuntimeResponse(5, List.of())));
        mvc.perform(get("/internal/core/output-connections/runtime")).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(5));
    }
}
