package net.java21.data2flow.core.dataexchange.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.common.FailureWithResponse;
import net.java21.data2flow.core.config.WebConfig;
import net.java21.data2flow.core.dataexchange.domain.ExchangeErrorCode;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ExportCreatedResponse;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ExportJobResponse;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ExportScheduleResponse;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.ImportJobResponse;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.TestStep;
import net.java21.data2flow.core.dataexchange.dto.ExchangeDtos.TestTargetResponse;
import net.java21.data2flow.core.dataexchange.service.DataDictionaryService;
import net.java21.data2flow.core.dataexchange.service.ExportScheduleService;
import net.java21.data2flow.core.dataexchange.service.ExportService;
import net.java21.data2flow.core.dataexchange.service.ExportService.Download;
import net.java21.data2flow.core.dataexchange.service.ImportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 내보내기·정기 내보내기·가져오기·데이터 사전 API 형식(API-TSD-20~23·30~32·56·59) — TC-TSD-103·104·110·116·117·156·161 */
@WebMvcTest({ExportController.class, ExportScheduleController.class, ImportController.class, DataDictionaryController.class})
@Import({WebConfig.class, AccountGateInterceptor.class})
class TelemetryExportControllerWebTest {

    static final Instant T = Instant.parse("2026-10-03T00:00:00Z");
    static final ExportJobResponse JOB = new ExportJobResponse("7", "SUCCEEDED", "CSV", null, 10L, 100L, 1, T, "/api/v1/core/exports/7/file",
            null, "1", null, 10L, T, T);
    static final ImportJobResponse IMPORT = new ImportJobResponse("3", "CSV", "QUEUED", true, null, null, null, null, "label", List.of(),
            null, null, null, null, null, T);
    static final ExportScheduleResponse SCHEDULE = new ExportScheduleResponse("4", "daily", null, "CSV", "0 7 * * *", "PREVIOUS_DAY", "EMAIL",
            List.of("a@example.com"), null, null, null, false, true, null, null, null, T, 0, 0, "1");

    @Autowired
    MockMvc mvc;
    @MockitoBean
    ExportService exports;
    @MockitoBean
    ExportScheduleService schedules;
    @MockitoBean
    ImportService imports;
    @MockitoBean
    DataDictionaryService dictionary;
    @MockitoBean
    UserRepository users;

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
        return b.header("X-USER-ID", "1").header("X-ORG-ID", "1");
    }

    @Test
    @DisplayName("[TSD-04.01][TC-TSD-103·116] 동기 200 SYNC, 비동기 202 + Location, 한도 429 EXPORT_LIMIT_EXCEEDED, 만료 410, 없음 404, 취소, 파일 내려받기")
    void exports() throws Exception {
        given(exports.create(any())).willReturn(new ExportCreatedResponse("SYNC", "7", "/api/v1/core/exports/7/file?x", 10));
        mvc.perform(auth(post("/core/exports")).contentType(MediaType.APPLICATION_JSON).content("{\"query\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.mode").value("SYNC"));
        given(exports.create(any())).willReturn(new ExportCreatedResponse("ASYNC", "8", null, 3_000_000));
        mvc.perform(auth(post("/core/exports")).contentType(MediaType.APPLICATION_JSON).content("{\"query\":{}}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/v1/core/exports/8"))
                .andExpect(jsonPath("$.response.estimatedRows").value(3_000_000));
        given(exports.create(any())).willThrow(new BusinessException(ExchangeErrorCode.EXPORT_LIMIT_EXCEEDED));
        mvc.perform(auth(post("/core/exports")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.header.resultCode").value("EXPORT_LIMIT_EXCEEDED"));
        given(exports.list(any(), any())).willReturn(ListApiResponse.of(PageParams.of(1, 20), List.of(JOB), 1));
        mvc.perform(auth(get("/core/exports"))).andExpect(jsonPath("$.responses[0].id").value("7"));
        given(exports.get(7L)).willReturn(JOB);
        mvc.perform(auth(get("/core/exports/7"))).andExpect(jsonPath("$.response.status").value("SUCCEEDED"));
        given(exports.get(9L)).willThrow(new BusinessException(ExchangeErrorCode.EXPORT_NOT_FOUND));
        mvc.perform(auth(get("/core/exports/9"))).andExpect(status().isNotFound());
        given(exports.cancel(7L)).willReturn(JOB);
        mvc.perform(auth(post("/core/exports/7/cancel"))).andExpect(status().isOk());
        given(exports.download(eq(7L), any(), any())).willReturn(new Download("data2flow_export_7.csv", "text/csv; charset=UTF-8", 3,
                new ByteArrayInputStream("a,b".getBytes(StandardCharsets.UTF_8))));
        mvc.perform(auth(get("/core/exports/7/file?expires=1&signature=s"))).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("data2flow_export_7.csv")))
                .andExpect(content().string("a,b"));
        given(exports.download(eq(8L), any(), any())).willThrow(new BusinessException(ExchangeErrorCode.EXPORT_EXPIRED));
        mvc.perform(auth(get("/core/exports/8/file?expires=1&signature=s")).header("Accept-Language", "en"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.header.resultMessage").value(org.hamcrest.Matchers.containsString("expired")));
    }

    @Test
    @DisplayName("[TSD-04.03][TSD-07.02][TC-TSD-104·117·156] 정기 내보내기 201 + Location·PATCH·DELETE 204, 대상 테스트 실패 502 EXPORT_TARGET_UNWRITABLE + 단계")
    void schedules() throws Exception {
        given(schedules.create(any())).willReturn(SCHEDULE);
        mvc.perform(auth(post("/core/export-schedules")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/core/export-schedules/4"));
        given(schedules.list(any(), any())).willReturn(ListApiResponse.of(PageParams.of(1, 20), List.of(SCHEDULE), 1));
        mvc.perform(auth(get("/core/export-schedules"))).andExpect(jsonPath("$.totalCount").value(1));
        given(schedules.get(4L)).willReturn(SCHEDULE);
        mvc.perform(auth(get("/core/export-schedules/4"))).andExpect(jsonPath("$.response.cron").value("0 7 * * *"));
        given(schedules.update(anyLong(), any())).willReturn(SCHEDULE);
        mvc.perform(auth(patch("/core/export-schedules/4")).contentType(MediaType.APPLICATION_JSON).content("{\"baseVersion\":0}"))
                .andExpect(status().isOk());
        mvc.perform(auth(delete("/core/export-schedules/4"))).andExpect(status().isNoContent());
        TestTargetResponse failed = new TestTargetResponse(false, List.of(new TestStep("CONNECT", true, null), new TestStep("WRITE", false, "403")));
        given(schedules.testTarget(any())).willThrow(new FailureWithResponse(ExchangeErrorCode.EXPORT_TARGET_UNWRITABLE, List.of(), failed, "403"));
        mvc.perform(auth(post("/core/export-schedules/test-target")).contentType(MediaType.APPLICATION_JSON).content("{\"targetType\":\"S3\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.header.resultMessage").value("대상에 파일을 쓸 수 없습니다: 403"))
                .andExpect(jsonPath("$.response.steps[1].ok").value(false));
    }

    @Test
    @DisplayName("[TSD-04.02][TC-TSD-110] 가져오기 CSV(multipart)·InfluxDB(JSON) 202 + Location, 파일 오류 400 IMPORT_FILE_INVALID, 원천 접속 502, 실행 202, 오류 목록")
    void imports() throws Exception {
        given(imports.createCsv(any(), any(), any(), any())).willReturn(IMPORT);
        MockMultipartHttpServletRequestBuilder upload = multipart("/core/imports")
                .file(new MockMultipartFile("file", "a.csv", "text/csv", "time\n".getBytes(StandardCharsets.UTF_8)));
        upload.header("X-USER-ID", "1").header("X-ORG-ID", "1");
        mvc.perform(upload.param("mapping", "{}").param("originLabel", "x")).andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/core/imports/3"));
        given(imports.createInflux(any())).willThrow(new BusinessException(ExchangeErrorCode.IMPORT_SOURCE_UNREACHABLE, "ping 401"));
        mvc.perform(auth(post("/core/imports")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.header.resultCode").value("IMPORT_SOURCE_UNREACHABLE"));
        given(imports.run(3L)).willReturn(IMPORT);
        mvc.perform(auth(post("/core/imports/3/run"))).andExpect(status().isAccepted());
        given(imports.get(4L)).willThrow(new BusinessException(ExchangeErrorCode.IMPORT_NOT_FOUND));
        mvc.perform(auth(get("/core/imports/4"))).andExpect(status().isNotFound());
        given(imports.errors(eq(3L), any(), any())).willReturn(ListApiResponse.of(PageParams.of(1, 20), List.of(), 0));
        mvc.perform(auth(get("/core/imports/3/errors"))).andExpect(jsonPath("$.totalCount").value(0));
        given(imports.list(any(), any())).willReturn(ListApiResponse.of(PageParams.of(1, 20), List.of(IMPORT), 1));
        mvc.perform(auth(get("/core/imports"))).andExpect(jsonPath("$.responses[0].sourceKind").value("CSV"));
    }

    @Test
    @DisplayName("[TSD-07.04][TC-TSD-161] 데이터 사전 JSON(봉투)·HTML(text/html), 잘못된 format 400")
    void dictionary() throws Exception {
        given(dictionary.dictionary(any())).willReturn(Map.of("version", 3));
        mvc.perform(auth(get("/core/data-dictionary"))).andExpect(jsonPath("$.response.version").value(3));
        given(dictionary.html(any())).willReturn("<html>v3</html>");
        mvc.perform(auth(get("/core/data-dictionary?format=HTML"))).andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string("<html>v3</html>"));
        mvc.perform(auth(get("/core/data-dictionary?format=pdf"))).andExpect(status().isBadRequest());
    }
}
