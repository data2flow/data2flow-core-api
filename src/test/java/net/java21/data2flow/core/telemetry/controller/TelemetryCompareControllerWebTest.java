package net.java21.data2flow.core.telemetry.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.config.WebConfig;
import net.java21.data2flow.core.telemetry.domain.TelemetryErrorCode;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.CompareSpaceSeries;
import net.java21.data2flow.core.telemetry.dto.TelemetryDtos.CompareSpacesResponse;
import net.java21.data2flow.core.telemetry.service.TelemetryCachedQueries;
import net.java21.data2flow.core.telemetry.service.TelemetryCachedQueries.Cached;
import net.java21.data2flow.core.telemetry.service.TelemetryQueryService;
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
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 공간 비교 API 형식(DSH-api compare-spaces, DSH-02.04) — TC-DSH-018 */
@WebMvcTest(TelemetryController.class)
@Import({WebConfig.class, AccountGateInterceptor.class})
class TelemetryCompareControllerWebTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    TelemetryQueryService service;
    @MockitoBean
    TelemetryCachedQueries cached;
    @MockitoBean
    UserRepository users;

    @Test
    @DisplayName("[DSH-02.04][TC-DSH-018] 6곳 이하 200 + effectiveResolution·X-Cache, 7곳 400 WIDGET_QUERY_INVALID(4개 언어 문구)")
    void compareSpaces() throws Exception {
        given(cached.compareSpaces(any())).willReturn(new Cached<>(new CompareSpacesResponse("co2", "ppm", "avg", "1h", "REQUESTED",
                "Asia/Seoul", List.of(new CompareSpaceSeries("1", "301", 2, 0, List.of()))), false));
        mvc.perform(post("/core/telemetry/compare-spaces").header("X-USER-ID", "1").header("X-ORG-ID", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"spaceIds\":[\"1\"],\"metricKey\":\"co2\"}"))
                .andExpect(status().isOk()).andExpect(header().string("X-Cache", "MISS"))
                .andExpect(jsonPath("$.response.effectiveResolution").value("1h"));
        given(cached.compareSpaces(any())).willThrow(new BusinessException(TelemetryErrorCode.WIDGET_QUERY_INVALID));
        mvc.perform(post("/core/telemetry/compare-spaces").header("X-USER-ID", "1").header("X-ORG-ID", "1").header("Accept-Language", "ja")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"spaceIds\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("WIDGET_QUERY_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value("1つのチャートで比較できる空間は最大6か所です"));
    }
}
