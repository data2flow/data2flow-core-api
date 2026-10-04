package net.java21.data2flow.core.edge;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.config.WebConfig;
import net.java21.data2flow.core.edge.controller.EdgeController;
import net.java21.data2flow.core.edge.controller.InternalEdgeController;
import net.java21.data2flow.core.edge.domain.EdgeErrorCode;
import net.java21.data2flow.core.edge.dto.EdgeDtos.ConfigVersionResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.HeartbeatResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.RegistrationResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.RequestResponse;
import net.java21.data2flow.core.edge.dto.EdgeDtos.UpdateResponse;
import net.java21.data2flow.core.edge.service.EdgeInternalService;
import net.java21.data2flow.core.edge.service.EdgeService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 엣지 API 형식(API-DSC-62·64·65·67, 내부 API-DSC-78·79) — TC-DSC-211·212·216 */
@WebMvcTest({EdgeController.class, InternalEdgeController.class})
@Import({WebConfig.class, AccountGateInterceptor.class})
class EdgeGatewayControllerWebTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    EdgeService service;
    @MockitoBean
    EdgeInternalService internal;
    @MockitoBean
    UserRepository users;

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
        return b.header("X-USER-ID", "1").header("X-ORG-ID", "1").contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("[DSC-08.03][TC-DSC-211] 생성 201 + Location·토큰, 설정 판 201, 등록 토큰 오류 401 EDGE_TOKEN_INVALID, 없는 엣지 404 EDGE_NOT_FOUND")
    void createConfigRegister() throws Exception {
        given(service.create(any())).willReturn(new RegistrationResponse("3", "1층 엣지", "REGISTERING", "d2fe_x", Instant.parse("2026-10-05T00:00:00Z"),
                "docker run …", "https://hook/x.tar.gz"));
        mvc.perform(auth(post("/core/edges")).content("{\"name\":\"1층 엣지\",\"siteId\":\"1\"}")).andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/core/edges/3")).andExpect(jsonPath("$.response.registrationToken").value("d2fe_x"));
        given(service.createConfigVersion(anyLong(), any())).willReturn(new ConfigVersionResponse(1, null, null, "PENDING", null,
                Instant.parse("2026-10-04T00:00:00Z"), null, false, false));
        mvc.perform(auth(post("/core/edges/3/config-versions")).content("{\"targets\":[]}")).andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/core/edges/3/config-versions/1")).andExpect(jsonPath("$.response.result").value("PENDING"));
        given(internal.register(any())).willThrow(new BusinessException(EdgeErrorCode.EDGE_TOKEN_INVALID));
        mvc.perform(post("/internal/core/edges/register").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"bad\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.header.resultCode").value("EDGE_TOKEN_INVALID"));
        given(service.deploy(anyLong(), anyInt())).willThrow(new BusinessException(EdgeErrorCode.EDGE_NOT_FOUND));
        mvc.perform(auth(post("/core/edges/9/config-versions/1/deploy")).header("Accept-Language", "en")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultMessage").value("Edge gateway not found"));
    }

    @Test
    @DisplayName("[DSC-08.03][DSC-08.04][TC-DSC-212·216] 재시작 202 {requestId}, 폐기된 엣지 409 EDGE_STATE_CONFLICT, 업데이트 승인 201, 하트비트 200")
    void commandsUpdatesHeartbeat() throws Exception {
        given(service.command(eq(3L), eq("restart"), any())).willReturn(new RequestResponse("r-1"));
        mvc.perform(auth(post("/core/edges/3/restart"))).andExpect(status().isAccepted()).andExpect(jsonPath("$.response.requestId").value("r-1"));
        given(service.command(eq(4L), eq("restart"), any())).willThrow(new BusinessException(EdgeErrorCode.EDGE_STATE_CONFLICT));
        mvc.perform(auth(post("/core/edges/4/restart"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("EDGE_STATE_CONFLICT"));
        mvc.perform(auth(post("/core/edges/4/reboot"))).andExpect(status().isNotFound());
        given(service.approveUpdate(anyLong(), any())).willReturn(new UpdateResponse("7", "1.0.0", "1.1.0", "APPROVED",
                Instant.parse("2026-10-04T00:00:00Z"), null, null));
        mvc.perform(auth(post("/core/edges/3/updates")).content("{\"toVersion\":\"1.1.0\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.status").value("APPROVED"));
        given(internal.heartbeat(anyLong(), any())).willReturn(new HeartbeatResponse(2, null, null, List.of(), false));
        mvc.perform(post("/internal/core/edges/3/heartbeat").contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0.0\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.desiredConfigVersion").value(2))
                .andExpect(jsonPath("$.response.revoked").value(false));
    }
}
