package net.java21.data2flow.core.space.controller;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.account.repository.UserRepository;
import net.java21.data2flow.core.common.AccountGateInterceptor;
import net.java21.data2flow.core.config.WebConfig;
import net.java21.data2flow.core.space.domain.SpaceErrorCode;
import net.java21.data2flow.core.space.dto.SpaceDtos.Blockers;
import net.java21.data2flow.core.space.dto.SpaceDtos.ModeResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.SiteSummaryResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.SpaceResponse;
import net.java21.data2flow.core.space.repository.FloorplanRepository.Image;
import net.java21.data2flow.core.space.service.DeviceRelationService;
import net.java21.data2flow.core.space.service.FloorplanService;
import net.java21.data2flow.core.space.service.SemanticService;
import net.java21.data2flow.core.space.service.SiteService;
import net.java21.data2flow.core.space.service.SpaceNotEmptyException;
import net.java21.data2flow.core.space.service.SpaceService;
import net.java21.data2flow.core.space.service.SpaceSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 공간 API 응답 형식·오류 코드·문구(api-rules §3·§4) — TC-DEV-002·003·010·011·016·022·028·262·263·276·281·282·304 */
@WebMvcTest({SpaceController.class, SpaceSettingsController.class, FloorplanController.class, DeviceRelationController.class,
        SemanticController.class, SiteController.class, InternalSpaceController.class})
@Import({WebConfig.class, AccountGateInterceptor.class})
class SpaceControllerWebTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    SpaceService spaces;
    @MockitoBean
    SpaceSettingsService settings;
    @MockitoBean
    FloorplanService floorplans;
    @MockitoBean
    DeviceRelationService relations;
    @MockitoBean
    SemanticService semantic;
    @MockitoBean
    SiteService sites;
    @MockitoBean
    UserRepository users;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b) {
        return b.header("X-USER-ID", "1").header("X-ORG-ID", "1");
    }

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder b, String json) {
        return as(b).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test
    @DisplayName("[DEV-01.01][TC-DEV-002] 생성 201 + Location, ID는 문자열 / 깊이 초과 400 SPACE_DEPTH_EXCEEDED(en 문구)")
    void createAndDepth() throws Exception {
        given(spaces.create(any())).willReturn(new SpaceResponse("7", "3", "ROOM", "실습실", null, "/1/3/7", 3, 0, null, null, null,
                null, null, null, null, null, null, "ACTIVE", 0, Instant.parse("2026-10-03T00:00:00Z")));
        mvc.perform(body(post("/core/spaces"), "{\"parentId\":\"3\",\"type\":\"ROOM\",\"name\":\"실습실\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/core/spaces/7"))
                .andExpect(jsonPath("$.response.id").value("7"))
                .andExpect(jsonPath("$.response.timezone").isEmpty());
        given(spaces.create(any())).willThrow(new BusinessException(SpaceErrorCode.SPACE_DEPTH_EXCEEDED));
        mvc.perform(body(post("/core/spaces"), "{}").header("Accept-Language", "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value("SPACE_DEPTH_EXCEEDED"))
                .andExpect(jsonPath("$.header.resultMessage").value("Spaces can be nested up to 6 levels"));
    }

    @Test
    @DisplayName("[DEV-01.01][TC-DEV-003] 삭제 204, 비지 않으면 409 SPACE_NOT_EMPTY + response.blockers, 이동 순환 400")
    void deleteAndMove() throws Exception {
        mvc.perform(as(delete("/core/spaces/5"))).andExpect(status().isNoContent());
        willThrow(new SpaceNotEmptyException(new Blockers(1, 3, 0, 0))).given(spaces).delete(9L);
        mvc.perform(as(delete("/core/spaces/9")).header("Accept-Language", "ja"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_EMPTY"))
                .andExpect(jsonPath("$.header.resultMessage").value("下位空間や機器があるため削除できません"))
                .andExpect(jsonPath("$.response.blockers.devices").value(3))
                .andExpect(jsonPath("$.response.blockers.children").value(1));
        given(spaces.move(anyLong(), any())).willThrow(new BusinessException(SpaceErrorCode.SPACE_MOVE_CYCLE));
        mvc.perform(body(post("/core/spaces/2/move"), "{\"newParentId\":\"4\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SPACE_MOVE_CYCLE"));
        given(spaces.update(anyLong(), any())).willThrow(new BusinessException(SpaceErrorCode.SPACE_NAME_DUPLICATE));
        mvc.perform(body(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/core/spaces/2"), "{\"name\":\"x\",\"baseVersion\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NAME_DUPLICATE"));
        mvc.perform(as(get("/core/spaces/abc"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[DEV-11.01][TC-DEV-010·011·281·282] 시간표 겹침 400 SCHEDULE_OVERLAP(zh), 모드 조회 형식, 목표 측정 항목 없음 404")
    void settings() throws Exception {
        given(settings.replaceSchedule(anyLong(), any())).willThrow(new BusinessException(SpaceErrorCode.SCHEDULE_OVERLAP));
        mvc.perform(body(put("/core/spaces/1/schedule"), "{\"inherit\":false,\"slots\":[]}").header("Accept-Language", "zh"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultMessage").value("同一天的时间段重叠"));
        given(settings.mode(1L)).willReturn(new ModeResponse("OCCUPIED", "SCHEDULE", null, Instant.parse("2026-10-05T09:00:00Z")));
        mvc.perform(as(get("/core/spaces/1/mode")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.mode").value("OCCUPIED"))
                .andExpect(jsonPath("$.response.until").isEmpty())
                .andExpect(jsonPath("$.response.nextChangeAt").value("2026-10-05T09:00:00Z"));
        given(settings.replaceTargets(anyLong(), any())).willThrow(new BusinessException(SpaceErrorCode.METRIC_NOT_FOUND));
        mvc.perform(body(put("/core/spaces/1/targets"), "{\"items\":[]}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultMessage").value("측정 항목을 찾을 수 없습니다"));
        given(settings.internalMode(3L)).willReturn(new ModeResponse("UNOCCUPIED", "SCHEDULE", null, null));
        mvc.perform(get("/internal/core/spaces/3/mode")).andExpect(jsonPath("$.response.mode").value("UNOCCUPIED"));
    }

    @Test
    @DisplayName("[DEV-01.03][TC-DEV-016] 파일 없는 업로드는 서비스 전에 400 FLOORPLAN_IMAGE_INVALID, 원본은 nosniff·CSP sandbox, 마커 공간 밖 기기 404")
    void floorplan() throws Exception {
        var upload = multipart(HttpMethod.PUT, "/core/spaces/1/floorplan");
        upload.header("X-USER-ID", "1").header("X-ORG-ID", "1");
        mvc.perform(upload)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("FLOORPLAN_IMAGE_INVALID"));
        verify(floorplans, never()).upload(anyLong(), any(), any());
        given(floorplans.image(1L)).willReturn(new Image("image/svg+xml", "abc", "<svg/>".getBytes()));
        mvc.perform(as(get("/core/spaces/1/floorplan/image")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/svg+xml"))
                .andExpect(header().string("ETag", "\"abc\""))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox"));
        given(floorplans.replaceMarkers(anyLong(), any())).willThrow(new BusinessException(SpaceErrorCode.DEVICE_NOT_FOUND));
        mvc.perform(body(put("/core/spaces/1/floorplan/markers"), "{\"markers\":[]}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_NOT_FOUND"));
    }

    @Test
    @DisplayName("[DEV-13.01][AT-DEV-23.3] SEMANTIC_TAG_UNKNOWN 문구에 후보, errors[] 필드 — TC-DEV-304 / 관계 SPACE_NOT_FOUND — TC-DEV-028 / 사이트 요약 배열 — TC-DEV-276")
    void semanticRelationsSites() throws Exception {
        given(semantic.replace(anyLong(), any())).willThrow(new BusinessException(SpaceErrorCode.SEMANTIC_TAG_UNKNOWN,
                List.of(new FieldErrorDetail("equipment[0].points[0].quantity", "SEMANTIC_TAG_UNKNOWN", "Temperature")), "Temperature"));
        mvc.perform(body(put("/core/devices/4/semantic"), "{\"equipment\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultMessage").value("알 수 없는 태그입니다. 혹시 Temperature?"))
                .andExpect(jsonPath("$.errors[0].field").value("equipment[0].points[0].quantity"));
        given(relations.replaceRelations(anyLong(), any())).willThrow(new BusinessException(SpaceErrorCode.SPACE_NOT_FOUND));
        mvc.perform(body(put("/core/devices/4/relations"), "{\"items\":[]}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultMessage").value("공간을 찾을 수 없습니다"));
        given(relations.spaceDevices(anyLong(), any(), any(), any(), any(), any()))
                .willReturn(ListApiResponse.of(PageParams.of(1, 20), List.of(), 0));
        mvc.perform(as(get("/core/spaces/1/devices").param("relation", "CONTROLS")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0)).andExpect(jsonPath("$.page").value(1));
        given(sites.summary()).willReturn(List.of(new SiteSummaryResponse("1", "본교", null, null, 3, 1, 0, null)));
        mvc.perform(as(get("/core/sites/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[0].siteId").value("1"))
                .andExpect(jsonPath("$.response[0].comfortScore").isEmpty());
        given(relations.internalSpaceDevices(anyLong(), any(), any(), any())).willReturn(List.of());
        mvc.perform(get("/internal/core/spaces/1/devices")).andExpect(status().isOk()).andExpect(jsonPath("$.response").isArray());
    }
}
