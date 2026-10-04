package net.java21.data2flow.core.bim;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DSH-12.04 층 전환 평면도·IFC 모델(API-DSH-24, BR-DSH-21) */
class BuildingModelIT extends IntegrationTestSupport {

    private long org;
    private long integrator;
    private long viewer;
    private long building;
    private long floor1;
    private long floor2;
    private long room301;

    @BeforeEach
    void setUp() {
        org = fx.organization("bim");
        integrator = fx.user(org, "bim.int", "INTEGRATOR");
        viewer = fx.user(org, "bim.viewer", "VIEWER");
        long site = data.site(org, "본관");
        building = data.space(org, site, "BUILDING", "실습동");
        floor2 = data.space(org, building, "FLOOR", "2층");
        floor1 = data.space(org, building, "FLOOR", "1층");
        jdbc.sql("UPDATE data2flow_core.spaces SET sort_order = 2 WHERE id = :id").param("id", floor2).update();
        jdbc.sql("UPDATE data2flow_core.spaces SET sort_order = 1 WHERE id = :id").param("id", floor1).update();
        room301 = data.space(org, floor1, "ROOM", "실습실");
        jdbc.sql("""
                        INSERT INTO data2flow_core.floorplans (organization_id, space_id, object_key, width_px, height_px, version)
                        VALUES (:org, :space, 'db:floorplan_images/1', 800, 600, 3)""").param("org", org).param("space", floor1).update();
    }

    private String upload(long user) throws Exception {
        return mvc.perform(asM(org, user, multipart("/core/buildings/" + building + "/models")
                        .file(new MockMultipartFile("file", "lab.ifc", "application/octet-stream", IfcFileTest.SAMPLE.getBytes(StandardCharsets.UTF_8)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.status").value("READY"))
                .andExpect(jsonPath("$.response.name").value("lab.ifc"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("[DSH-12.04][AT-DSH-13.1] 층 목록: 정렬 순서대로, 평면도 이미지 주소·크기, 범위 밖 층은 빠짐")
    void floors() throws Exception {
        mvc.perform(as(org, viewer, get("/core/buildings/" + building + "/floors")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response", hasSize(2)))
                .andExpect(jsonPath("$.response[0].name").value("1층"))
                .andExpect(jsonPath("$.response[0].hasFloorplan").value(true))
                .andExpect(jsonPath("$.response[0].imageUrl").value("/api/v1/core/spaces/" + floor1 + "/floorplan/image?v=3"))
                .andExpect(jsonPath("$.response[0].width").value(800))
                .andExpect(jsonPath("$.response[1].hasFloorplan").value(false));
        mvc.perform(as(org, viewer, get("/core/buildings/" + room301 + "/floors"))).andExpect(status().isBadRequest());
        data.spaceScope(org, viewer, List.of(floor1));
        mvc.perform(as(org, viewer, get("/core/buildings/" + building + "/floors"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
    }

    @Test
    @DisplayName("[DSH-12.04][AT-DSH-13.2] IFC 올리기 → READY(IFC4·요소 4), 공간 요소 2개 중 1개 연결 → 연결 안 된 요소 1(회색), 원본 내려받기, 삭제")
    void uploadAndMap() throws Exception {
        String id = JsonPath.read(upload(integrator), "$.response.id");
        mvc.perform(as(org, viewer, get("/core/buildings/" + building + "/models/" + id)))
                .andExpect(jsonPath("$.response.ifcSchema").value("IFC4"))
                .andExpect(jsonPath("$.response.elementCount").value(4))
                .andExpect(jsonPath("$.response.spaceElements", hasSize(2)))
                .andExpect(jsonPath("$.response.downloadUrl").value("/api/v1/core/buildings/" + building + "/models/" + id + "/file"));
        mvc.perform(as(org, integrator, json(put("/core/buildings/" + building + "/models/" + id + "/space-mapping"),
                        "{\"mappings\":[{\"ifcGlobalId\":\"2O2Fr$t4X7Zf8NOew3FLOH\",\"spaceId\":\"" + room301 + "\"}]}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.mapped").value(1))
                .andExpect(jsonPath("$.response.unmappedElements").value(1));
        mvc.perform(as(org, viewer, get("/core/buildings/" + building + "/models/" + id)))
                .andExpect(jsonPath("$.response.mappings[0].spaceId").value(Long.toString(room301)));
        mvc.perform(as(org, viewer, get("/core/buildings/" + building + "/models")))
                .andExpect(jsonPath("$.response", hasSize(1)));
        byte[] file = mvc.perform(as(org, viewer, get("/core/buildings/" + building + "/models/" + id + "/file")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(file, StandardCharsets.UTF_8)).startsWith("ISO-10303-21;");
        // 건물 밖 공간·없는 GlobalId는 400
        long elsewhere = data.space(org, data.site(org, "별관"), "ROOM", "다른 방");
        mvc.perform(as(org, integrator, json(put("/core/buildings/" + building + "/models/" + id + "/space-mapping"),
                        "{\"mappings\":[{\"ifcGlobalId\":\"2O2Fr$t4X7Zf8NOew3FLOH\",\"spaceId\":\"" + elsewhere + "\"},"
                                + "{\"ifcGlobalId\":\"0000000000000000000000\",\"spaceId\":\"" + room301 + "\"}]}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(contains("mappings[0].spaceId", "mappings[1].ifcGlobalId")));
        mvc.perform(as(org, viewer, json(put("/core/buildings/" + building + "/models/" + id + "/space-mapping"), "{\"mappings\":[]}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, integrator, delete("/core/buildings/" + building + "/models/" + id))).andExpect(status().isNoContent());
        mvc.perform(as(org, viewer, get("/core/buildings/" + building + "/models/" + id))).andExpect(status().isNotFound());
        assertThat(auditCount(org, "BUILDING_MODEL_UPLOADED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[DSH-12.04][AT-DSH-13.3][BR-DSH-21] IFC가 아니면 400 MODEL_FILE_INVALID, 스키마를 못 읽으면 FAILED, VIEWER 올리기 403, 다른 조직 건물 404")
    void invalid() throws Exception {
        mvc.perform(asM(org, integrator, multipart("/core/buildings/" + building + "/models")
                        .file(new MockMultipartFile("file", "x.zip", "application/zip", "PK\u0003\u0004".getBytes(StandardCharsets.ISO_8859_1)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("MODEL_FILE_INVALID"));
        mvc.perform(asM(org, integrator, multipart("/core/buildings/" + building + "/models")
                        .file(new MockMultipartFile("file", "x.ifc", "application/octet-stream",
                                IfcFileTest.SAMPLE.replace("'IFC4'", "'XYZ'").getBytes(StandardCharsets.UTF_8)))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.status").value("FAILED"));
        mvc.perform(asM(org, viewer, multipart("/core/buildings/" + building + "/models")
                        .file(new MockMultipartFile("file", "lab.ifc", "application/octet-stream", IfcFileTest.SAMPLE.getBytes(StandardCharsets.UTF_8)))))
                .andExpect(status().isForbidden());
        mvc.perform(asM(org, integrator, multipart("/core/buildings/" + building + "/models"))).andExpect(status().isBadRequest());
        long other = fx.organization("bim2");
        long otherAdmin = fx.user(other, "bim2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/buildings/" + building + "/models"))).andExpect(status().isNotFound());
    }

    private static MockMultipartHttpServletRequestBuilder asM(long orgId, long userId, MockMultipartHttpServletRequestBuilder b) {
        b.header("X-USER-ID", Long.toString(userId)).header("X-ORG-ID", Long.toString(orgId)).header("Accept-Language", "ko");
        return b;
    }
}
