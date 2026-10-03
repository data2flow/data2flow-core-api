package net.java21.data2flow.core.space;

import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 평면도 업로드·교체·삭제와 마커(DEV-01.03, API-DEV-09·10) — TC-DEV-015·016·017 */
class FloorplanIT extends IntegrationTestSupport {

    private long org;
    private long integrator;
    private long floor;
    private long room;
    private long device;
    private long outside;

    private void setUp() {
        org = fx.organization("fp");
        integrator = fx.user(org, "fp.int", "INTEGRATOR");
        long site = data.site(org, "사이트");
        floor = data.space(org, site, "FLOOR", "2층");
        room = data.space(org, floor, "ROOM", "201호");
        long other = data.space(org, site, "FLOOR", "3층");
        long src = SpaceFixtures.source(jdbc, org, "src-f");
        device = data.device(org, src, "dev-1", "ACTIVE", room, null);
        outside = data.device(org, src, "dev-2", "ACTIVE", other, null);
    }

    private MockMultipartHttpServletRequestBuilder upload(byte[] data, String name, String type) {
        MockMultipartHttpServletRequestBuilder builder = multipart(HttpMethod.PUT, "/core/spaces/" + floor + "/floorplan")
                .file(new MockMultipartFile("file", name, type, data));
        builder.header("X-USER-ID", Long.toString(integrator)).header("X-ORG-ID", Long.toString(org));
        return builder;
    }

    @Test
    @DisplayName("[DEV-01.03][AT-DEV-13.1·13.2] 1600×900에 (800,450) → (0.5,0.5) 저장, 크기 다른 이미지로 교체해도 비율 유지 + 위치 확인 필요 — TC-DEV-015·017")
    void uploadAndMarkers() throws Exception {
        setUp();
        mvc.perform(as(org, integrator, get("/core/spaces/" + floor + "/floorplan")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("RESOURCE_NOT_FOUND"));
        mvc.perform((upload(Images.png(1600, 900), "plan.png", "image/png")).param("scaleMPerPx", "0.02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.widthPx").value(1600))
                .andExpect(jsonPath("$.response.heightPx").value(900))
                .andExpect(jsonPath("$.response.contentType").value("image/png"))
                .andExpect(jsonPath("$.response.imageUrl").value(containsString("/api/v1/core/spaces/" + floor + "/floorplan/image?v=")))
                .andExpect(jsonPath("$.response.markersNeedReview").value(false));
        mvc.perform(as(org, integrator, json(put("/core/spaces/" + floor + "/floorplan/markers"),
                        "{\"markers\":[{\"deviceId\":\"" + device + "\",\"x\":" + (800 / 1600.0) + ",\"y\":" + (450 / 900.0) + ",\"rotation\":90}]}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.markers[0].x").value(0.5))
                .andExpect(jsonPath("$.response.markers[0].y").value(0.5))
                .andExpect(jsonPath("$.response.markers[0].deviceName").value("기기 dev-1"));
        mvc.perform((upload(Images.jpeg(1200, 800), "plan.jpg", "image/jpeg")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.response.markersNeedReview").value(true))
                .andExpect(jsonPath("$.response.version").value(1))
                .andExpect(jsonPath("$.response.markers[0].x").value(0.5));
        mvc.perform(as(org, integrator, get("/core/spaces/" + floor + "/floorplan/image")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("ETag"));
        mvc.perform(as(org, integrator, get("/core/spaces/" + floor)))
                .andExpect(jsonPath("$.response.hasFloorplan").value(true));
        // 공간 밖 기기·좌표 범위·중복
        mvc.perform(as(org, integrator, json(put("/core/spaces/" + floor + "/floorplan/markers"),
                        "{\"markers\":[{\"deviceId\":\"" + outside + "\",\"x\":0.1,\"y\":0.1}]}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_NOT_FOUND"));
        mvc.perform(as(org, integrator, json(put("/core/spaces/" + floor + "/floorplan/markers"),
                        "{\"markers\":[{\"deviceId\":\"" + device + "\",\"x\":1.2,\"y\":0.1},{\"deviceId\":\"" + device + "\",\"x\":0.1,\"y\":0.1,\"rotation\":400},{}]}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(4));
        // 마커가 있으면 공간 삭제가 막힌다
        mvc.perform(as(org, integrator, delete("/core/spaces/" + floor)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.response.blockers.markers").value(1));
        mvc.perform(as(org, integrator, delete("/core/spaces/" + floor + "/floorplan"))).andExpect(status().isNoContent());
        mvc.perform(as(org, integrator, delete("/core/spaces/" + floor + "/floorplan"))).andExpect(status().isNotFound());
        mvc.perform(as(org, integrator, get("/core/spaces/" + floor + "/floorplan/image"))).andExpect(status().isNotFound());
        mvc.perform(as(org, integrator, json(put("/core/spaces/" + floor + "/floorplan/markers"), "{\"markers\":[]}")))
                .andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.floorplan_images").query(Long.class).single()).isZero();
        assertThat(auditCount(org, "SPACE_UPDATED")).isEqualTo(4);
    }

    @Test
    @DisplayName("[DEV-01.03] PNG·JPG·SVG만(내용으로 판정), 10MB·400×300, 스크립트·DTD가 든 SVG 거부 → 400 FLOORPLAN_IMAGE_INVALID — TC-DEV-016")
    void rejectsInvalidImages() throws Exception {
        setUp();
        String script = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800\" height=\"600\"><script>alert(1)</script></svg>";
        String handler = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800\" height=\"600\"><rect onclick=\"x()\"/></svg>";
        String doctype = "<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800\" height=\"600\">&x;</svg>";
        String link = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" viewBox=\"0 0 800 600\"><image xlink:href=\"https://evil.test/a.png\"/></svg>";
        for (byte[] bad : new byte[][]{Images.png(200, 100), "hello".getBytes(), Images.svg(script), Images.svg(handler),
                Images.svg(doctype), Images.svg(link), new byte[0], new byte[10 * 1024 * 1024 + 1]}) {
            mvc.perform((upload(bad, "x.png", "image/png")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.header.resultCode").value("FLOORPLAN_IMAGE_INVALID"))
                    .andExpect(jsonPath("$.header.resultMessage").value("이미지 형식이나 크기가 올바르지 않습니다"));
        }
        mvc.perform((upload(Images.png(800, 600), "x.png", "image/png")).param("scaleMPerPx", "-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("scaleMPerPx"));
        String ok = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1000 500\"><rect width=\"10\" height=\"10\" fill=\"#ccc\"/><use href=\"#a\"/></svg>";
        mvc.perform((upload(Images.svg(ok), "plan.svg", "image/svg+xml")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.widthPx").value(1000))
                .andExpect(jsonPath("$.response.heightPx").value(500));
        mvc.perform(as(org, integrator, get("/core/spaces/" + floor + "/floorplan/image")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("sandbox")))
                .andExpect(content().bytes(Images.svg(ok)));
    }
}
