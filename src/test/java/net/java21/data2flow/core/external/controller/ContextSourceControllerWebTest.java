package net.java21.data2flow.core.external.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.config.WebConfig;
import net.java21.data2flow.core.external.domain.ExternalErrorCode;
import net.java21.data2flow.core.external.dto.ExternalDtos.IcalUploadResponse;
import net.java21.data2flow.core.external.dto.ExternalDtos.RefreshResponse;
import net.java21.data2flow.core.external.dto.ExternalDtos.StationView;
import net.java21.data2flow.core.external.dto.ExternalDtos.UsageDay;
import net.java21.data2flow.core.external.service.ContextSourceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API-DSC-40~43 응답 형식·오류 코드·문구 — TC-DSC-134·135·140·141·146·147·152·153·158·159 */
@WebMvcTest(ContextSourceController.class)
@Import({WebConfig.class, AccountGateInterceptor.class})
class ContextSourceControllerWebTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ContextSourceService service;
    @MockitoBean
    UserRepository users;

    @Test
    @DisplayName("[DSC-06.05][API-DSC-40][TC-DSC-134] 호출량: 200 + 하루 항목(day·calls·failures·quota·warning·exhausted·cost)")
    void usage() throws Exception {
        given(service.apiUsage(7L, 2)).willReturn(List.of(new UsageDay(LocalDate.of(2026, 10, 3), 800, 3, 1000, true, false, null)));
        mvc.perform(get("/core/sources/7/api-usage").param("days", "2").header("X-USER-ID", "1").header("X-ORG-ID", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.header.resultCode").value("SUCCESS"))
                .andExpect(jsonPath("$.response[0].day").value("2026-10-03")).andExpect(jsonPath("$.response[0].warning").value(true));
    }

    @Test
    @DisplayName("[DSC-06.05][API-DSC-41][TC-DSC-134] 지금 갱신: 한도 도달은 429 EXTERNAL_API_QUOTA_EXCEEDED + Retry-After, 4개 언어 문구")
    void quotaExceeded() throws Exception {
        given(service.refreshNow(7L)).willThrow(new BusinessException(ExternalErrorCode.EXTERNAL_API_QUOTA_EXCEEDED).withHeader("Retry-After", "3600"));
        mvc.perform(post("/core/sources/7/refresh-now").header("X-USER-ID", "1").header("X-ORG-ID", "1").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "3600"))
                .andExpect(jsonPath("$.header.resultCode").value("EXTERNAL_API_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.header.resultMessage").value("The daily call limit has been reached. Collection resumes tomorrow"));
        given(service.refreshNow(8L)).willReturn(new RefreshResponse("j", "8", "SUCCEEDED", 2, 0, 1, null));
        mvc.perform(post("/core/sources/8/refresh-now").header("X-USER-ID", "1").header("X-ORG-ID", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.jobId").value("j")).andExpect(jsonPath("$.response.removed").value(1));
        mvc.perform(post("/core/sources/x/refresh-now").header("X-USER-ID", "1").header("X-ORG-ID", "1")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[DSC-06.02][API-DSC-42][TC-DSC-140] 측정소: 200 목록, 범위 오류 400 INVALID_REQUEST(errors), 신원 없으면 401")
    void stations() throws Exception {
        given(service.stations(35.15, 126.85)).willReturn(List.of(new StationView("치평동", "광주", 35.15, 126.85, 0.2, List.of("PM10"))));
        mvc.perform(get("/core/external/airkorea-stations").param("lat", "35.15").param("lng", "126.85").header("X-USER-ID", "1").header("X-ORG-ID", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response[0].stationName").value("치평동"))
                .andExpect(jsonPath("$.response[0].distanceKm").value(0.2));
        given(service.stations(any(), any())).willThrow(new BusinessException(CommonErrorCode.INVALID_REQUEST,
                List.of(new FieldErrorDetail("lat", "Range", "-90~90"))));
        mvc.perform(get("/core/external/airkorea-stations").param("lat", "99").param("lng", "1").header("X-USER-ID", "1").header("X-ORG-ID", "1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("lat"));
        mvc.perform(get("/core/external/airkorea-stations")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[DSC-06.04][API-DSC-43][TC-DSC-135·141] iCal 업로드: 200 {fileObjectKey, eventCount, categories}, 형식 오류 400 SOURCE_CONFIG_INVALID, 좌표 없음 400")
    void upload() throws Exception {
        given(service.uploadIcal(any())).willReturn(new IcalUploadResponse("db:ical_files/3", 4, List.of("시험", "휴일")));
        mvc.perform(multipart("/core/sources/ical/upload").file(new MockMultipartFile("file", "a.ics", "text/calendar", "x".getBytes()))
                        .header("X-USER-ID", "1").header("X-ORG-ID", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.fileObjectKey").value("db:ical_files/3"))
                .andExpect(jsonPath("$.response.categories[1]").value("휴일"));
        given(service.uploadIcal(any())).willThrow(new BusinessException(ExternalErrorCode.SOURCE_CONFIG_INVALID,
                List.of(new FieldErrorDetail("file", "ICAL_INVALID", null)), "file"));
        mvc.perform(multipart("/core/sources/ical/upload").file(new MockMultipartFile("file", "a.ics", "text/calendar", "x".getBytes()))
                        .header("X-USER-ID", "1").header("X-ORG-ID", "1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_CONFIG_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value("연결 설정이 올바르지 않습니다: file"));
        given(service.site(anyLong())).willThrow(new BusinessException(ExternalErrorCode.SITE_LOCATION_REQUIRED));
        mvc.perform(get("/core/sites/1/context-sources").header("X-USER-ID", "1").header("X-ORG-ID", "1").header(HttpHeaders.ACCEPT_LANGUAGE, "ja"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SITE_LOCATION_REQUIRED"))
                .andExpect(jsonPath("$.header.resultMessage").value("先にサイトの位置(緯度・経度)を入力してください"));
    }
}
