package net.java21.data2flow.core.catalog;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.catalog.controller.InternalMetricController;
import net.java21.data2flow.core.catalog.controller.MetricController;
import net.java21.data2flow.core.catalog.domain.CatalogErrorCode;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.AliasResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.AliasToResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.MetricResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.MetricStatusResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.RemapJobResponse;
import net.java21.data2flow.core.catalog.service.MetricInternalService;
import net.java21.data2flow.core.catalog.service.MetricService;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 측정 항목 API 형식(API-DEV-50~56, 내부 API-DEV-123·124) — TC-DEV-122·123·129·130·135·136 */
@WebMvcTest({MetricController.class, InternalMetricController.class})
@Import({WebConfig.class, AccountGateInterceptor.class})
class MetricControllerWebTest {

    static final MetricResponse METRIC = new MetricResponse("5", "door", "문", null, "ENUM", null, null, null, 0, "LAST", true, null,
            "VERIFIED", false, List.of(), null, List.of(), 0, Instant.parse("2026-10-03T00:00:00Z"));

    @Autowired
    MockMvc mvc;
    @MockitoBean
    MetricService service;
    @MockitoBean
    MetricInternalService internal;
    @MockitoBean
    UserRepository users;

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b) {
        return b.header("X-USER-ID", "1").header("X-ORG-ID", "1");
    }

    @Test
    @DisplayName("[DEV-04.01][TC-DEV-122] 생성 201 + Location, 키 형식 400 METRIC_KEY_INVALID, PATCH 200, 표준 등록 409 METRIC_STATE_CONFLICT")
    void createPatchVerify() throws Exception {
        given(service.create(any())).willReturn(METRIC);
        mvc.perform(auth(post("/core/metrics")).contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"door\",\"displayName\":\"문\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/core/metrics/5"));
        given(service.create(any())).willThrow(new BusinessException(CatalogErrorCode.METRIC_KEY_INVALID));
        mvc.perform(auth(post("/core/metrics")).contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"1x\",\"displayName\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("METRIC_KEY_INVALID"));
        given(service.update(anyLong(), any())).willReturn(METRIC);
        mvc.perform(auth(patch("/core/metrics/5")).contentType(MediaType.APPLICATION_JSON).content("{\"unit\":\"%\",\"baseVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.key").value("door"));
        given(service.verify(anyLong(), any())).willThrow(new BusinessException(CatalogErrorCode.METRIC_STATE_CONFLICT));
        mvc.perform(auth(post("/core/metrics/5/verify")).header("Accept-Language", "en"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultMessage").value("This metric has already been processed"));
        given(service.get(5L)).willReturn(METRIC);
        mvc.perform(auth(get("/core/metrics/5"))).andExpect(status().isOk());
        given(service.list(any(), any(), any(), any(), any())).willReturn(ListApiResponse.of(PageParams.of(1, 20), List.of(METRIC), 1));
        mvc.perform(auth(get("/core/metrics?status=VERIFIED"))).andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("[DEV-04.02][TC-DEV-123] 별칭 연결 200 {alias, remapJobId}, 대상 오류 400 METRIC_ALIAS_INVALID, 무시·복원 200, 진행률 200")
    void aliasIgnoreRemap() throws Exception {
        AliasResponse alias = new AliasResponse("9", "illuminance", "illumination", "3", Instant.parse("2026-10-03T00:00:00Z"));
        given(service.aliasTo(anyLong(), any())).willReturn(new AliasToResponse(alias, "77"));
        mvc.perform(auth(post("/core/metrics/5/alias-to")).contentType(MediaType.APPLICATION_JSON).content("{\"targetKey\":\"illumination\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.remapJobId").value("77"));
        mvc.perform(auth(post("/core/metrics/5/alias-to")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        given(service.aliasTo(anyLong(), any())).willThrow(new BusinessException(CatalogErrorCode.METRIC_ALIAS_INVALID));
        mvc.perform(auth(post("/core/metrics/5/alias-to")).contentType(MediaType.APPLICATION_JSON).content("{\"targetKey\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("METRIC_ALIAS_INVALID"));
        given(service.ignore(5L)).willReturn(new MetricStatusResponse("5", "lux", "IGNORED", 1));
        mvc.perform(auth(post("/core/metrics/5/ignore"))).andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("IGNORED"));
        given(service.restore(5L)).willReturn(new MetricStatusResponse("5", "lux", "UNVERIFIED", 2));
        mvc.perform(auth(post("/core/metrics/5/restore"))).andExpect(status().isOk());
        given(service.remapJob(77L)).willReturn(new RemapJobResponse("77", "illuminance", "illumination", "RUNNING", 10, 1000, null,
                Instant.EPOCH, Instant.EPOCH));
        mvc.perform(auth(get("/core/metric-remap-jobs/77"))).andExpect(status().isOk()).andExpect(jsonPath("$.response.total").value(1000));
        given(service.createAlias(any())).willReturn(alias);
        mvc.perform(auth(post("/core/metric-aliases")).contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"a\",\"metricKey\":\"b\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/core/metric-aliases/9"));
        given(service.listAliases(any(), any())).willReturn(ListApiResponse.of(PageParams.of(1, 20), List.of(alias), 1));
        mvc.perform(auth(get("/core/metric-aliases"))).andExpect(status().isOk());
        mvc.perform(auth(delete("/core/metric-aliases/9"))).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("[DEV-04.03][ING-04.02] 내부 API: 바뀐 것이 없으면 204, 등록 요청 검증 400")
    void internalApis() throws Exception {
        given(internal.snapshot(any(), any())).willReturn(Optional.empty());
        mvc.perform(get("/internal/core/metrics?sinceVersion=3")).andExpect(status().isNoContent());
        mvc.perform(post("/internal/core/metrics/register-unverified").contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
