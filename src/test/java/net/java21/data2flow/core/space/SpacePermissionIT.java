package net.java21.data2flow.core.space;

import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공간 API 권한 매트릭스(IAM-04.05·04.06, BR-DEV-25): 역할 5종 × 조회·쓰기, 다른 조직 404, 권한 밖 공간 404, 목록·합계 범위 필터.
 * TC-DEV-005·013·018·024·030·265·278·284·306, TC-IAM-130·142·144
 */
class SpacePermissionIT extends IntegrationTestSupport {

    private static final List<String> ROLES = List.of("ADMIN", "INTEGRATOR", "OPERATOR", "ANALYST", "VIEWER");

    private long org;
    private long site1;
    private long buildingA;
    private long floorA;
    private long roomA;
    private long buildingB;
    private long roomB;
    private long site2;
    private long deviceA;
    private long deviceB;
    private final Map<String, Long> users = new LinkedHashMap<>();

    private void setUp() {
        org = fx.organization("perm");
        for (String role : ROLES) {
            users.put(role, fx.user(org, "u." + role.toLowerCase(), role));
        }
        data.metric(org, "temperature", "°C");
        site1 = data.site(org, "사이트1");
        buildingA = data.space(org, site1, "BUILDING", "A동");
        floorA = data.space(org, buildingA, "FLOOR", "A동 1층");
        roomA = data.space(org, floorA, "ROOM", "A101");
        buildingB = data.space(org, site1, "BUILDING", "B동");
        roomB = data.space(org, buildingB, "ROOM", "B101");
        site2 = data.site(org, "사이트2");
        long src = data.source(org, "src-p");
        deviceA = data.device(org, src, "dev-a", "ACTIVE", roomA, null);
        deviceB = data.device(org, src, "dev-b", "ACTIVE", roomB, null);
        data.device(org, src, "dev-c", "ACTIVE", site2, null);
        data.deviceState(org, deviceB, "OFFLINE", clock.instant(), null);
    }

    private int code(long user, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        request.header("X-USER-ID", Long.toString(user)).header("X-ORG-ID", Long.toString(org));
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("[IAM-04.05][AT-DEV-01.6] 역할 5종: 조회는 VIEWER+, 공간 바꾸기는 INTEGRATOR+(OPERATOR·ANALYST·VIEWER 403 + ACCESS_DENIED) — TC-DEV-005·013·018·024·030·284, TC-IAM-130·142")
    void roleMatrix() throws Exception {
        setUp();
        Map<String, Supplier<AbstractMockHttpServletRequestBuilder<?>>> reads = new LinkedHashMap<>();
        reads.put("tree", () -> get("/core/spaces"));
        reads.put("detail", () -> get("/core/spaces/" + roomA));
        reads.put("targets", () -> get("/core/spaces/" + roomA + "/targets"));
        reads.put("schedule", () -> get("/core/spaces/" + roomA + "/schedule"));
        reads.put("mode", () -> get("/core/spaces/" + roomA + "/mode"));
        reads.put("spaceDevices", () -> get("/core/spaces/" + roomA + "/devices"));
        reads.put("relations", () -> get("/core/devices/" + deviceA + "/relations"));
        reads.put("semantic", () -> get("/core/devices/" + deviceA + "/semantic"));
        reads.put("sites", () -> get("/core/sites/summary"));
        Map<String, Supplier<AbstractMockHttpServletRequestBuilder<?>>> writes = new LinkedHashMap<>();
        writes.put("create", () -> json(post("/core/spaces"), "{\"parentId\":\"" + roomA + "\",\"type\":\"ZONE\",\"name\":\"구역" + System.nanoTime() + "\"}"));
        writes.put("patch", () -> json(patch("/core/spaces/" + roomA), "{\"capacity\":10,\"baseVersion\":" + version(roomA) + "}"));
        writes.put("targets", () -> json(put("/core/spaces/" + roomA + "/targets"), "{\"inherit\":true}"));
        writes.put("schedule", () -> json(put("/core/spaces/" + roomA + "/schedule"), "{\"inherit\":true}"));
        writes.put("floorplan", () -> multipart(HttpMethod.PUT, "/core/spaces/" + roomA + "/floorplan")
                .file(new MockMultipartFile("file", "plan.png", "image/png", Images.png(800, 600))));
        writes.put("relations", () -> json(put("/core/devices/" + deviceA + "/relations"), "{\"items\":[]}"));
        writes.put("semantic", () -> json(put("/core/devices/" + deviceA + "/semantic"), "{\"equipment\":[]}"));
        writes.put("move", () -> json(post("/core/spaces/" + floorA + "/move"), "{\"newParentId\":\"" + buildingA + "\"}"));
        for (String role : ROLES) {
            long user = users.get(role);
            for (Map.Entry<String, Supplier<AbstractMockHttpServletRequestBuilder<?>>> e : reads.entrySet()) {
                assertThat(code(user, e.getValue().get())).as(role + " " + e.getKey()).isEqualTo(200);
            }
            boolean allowed = role.equals("ADMIN") || role.equals("INTEGRATOR");
            for (Map.Entry<String, Supplier<AbstractMockHttpServletRequestBuilder<?>>> e : writes.entrySet()) {
                int status = code(user, e.getValue().get());
                if (allowed) {
                    assertThat(status).as(role + " " + e.getKey()).isBetween(200, 204);
                } else {
                    assertThat(status).as(role + " " + e.getKey()).isEqualTo(403);
                }
            }
            long empty = data.space(org, site2, "ROOM", "빈 방 " + role);
            assertThat(code(user, delete("/core/spaces/" + empty))).as(role + " delete").isEqualTo(allowed ? 204 : 403);
        }
        mvc.perform(as(org, users.get("OPERATOR"), json(post("/core/spaces"), "{\"parentId\":\"" + roomA + "\",\"type\":\"ZONE\",\"name\":\"z\"}")))
                .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        assertThat(auditCount(org, "ACCESS_DENIED")).isEqualTo(3 * 9L + 1);
    }

    private int version(long spaceId) {
        return jdbc.sql("SELECT version FROM data2flow_core.spaces WHERE id = :id").param("id", spaceId).query(Integer.class).single();
    }

    @Test
    @DisplayName("[IAM-04.05][AT-IAM-11.2] 다른 조직 사용자가 공간·기기 ID로 조회·수정하면 404(존재 은닉), 내부 API는 조직을 공간에서 정한다 — TC-IAM-142")
    void otherOrganization() throws Exception {
        setUp();
        long otherOrg = fx.organization("perm-other");
        long stranger = fx.user(otherOrg, "stranger", "ADMIN");
        for (MockHttpServletRequestBuilder request : List.of(get("/core/spaces/" + roomA), get("/core/spaces/" + roomA + "/targets"),
                json(patch("/core/spaces/" + roomA), "{\"baseVersion\":0}"), delete("/core/spaces/" + roomA),
                json(put("/core/spaces/" + roomA + "/schedule"), "{\"inherit\":true}"), get("/core/spaces/" + roomA + "/floorplan/image"))) {
            mvc.perform(as(otherOrg, stranger, request))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        }
        mvc.perform(as(otherOrg, stranger, get("/core/devices/" + deviceA + "/semantic")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_NOT_FOUND"));
        mvc.perform(as(otherOrg, stranger, get("/core/spaces"))).andExpect(jsonPath("$.response.length()").value(0));
        mvc.perform(as(otherOrg, stranger, get("/core/sites/summary"))).andExpect(jsonPath("$.response.length()").value(0));
    }

    @Test
    @DisplayName("[IAM-04.02][AT-DEV-01.7] 공간 권한이 'A동'인 사용자: 트리에 A동과 하위만(조상은 회색), B동 ID 직접 조회·수정 404, 새 사이트 403 — TC-DEV-005, TC-IAM-129·130")
    void scopedUser() throws Exception {
        setUp();
        long scoped = fx.user(org, "scoped.int", "INTEGRATOR");
        data.spaceScope(org, scoped, List.of(buildingA));
        mvc.perform(as(org, scoped, get("/core/spaces")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].id").value(Long.toString(site1)))
                .andExpect(jsonPath("$.response[0].accessible").value(false))
                .andExpect(jsonPath("$.response[0].code").doesNotExist())
                .andExpect(jsonPath("$.response[0].children.length()").value(1))
                .andExpect(jsonPath("$.response[0].children[0].id").value(Long.toString(buildingA)))
                .andExpect(jsonPath("$.response[0].children[0].accessible").value(true))
                .andExpect(jsonPath("$.response[0].children[0].children[0].children[0].id").value(Long.toString(roomA)));
        mvc.perform(as(org, scoped, get("/core/spaces").param("include", "counts")))
                .andExpect(jsonPath("$.response[0].counts").doesNotExist())
                .andExpect(jsonPath("$.response[0].children[0].counts.devices").value(1));
        for (MockHttpServletRequestBuilder request : List.of(get("/core/spaces/" + buildingB), get("/core/spaces/" + roomB + "/mode"),
                json(patch("/core/spaces/" + roomB), "{\"baseVersion\":0}"), get("/core/spaces/" + site1),
                get("/core/spaces").param("rootId", Long.toString(site1)))) {
            mvc.perform(as(org, scoped, request))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        }
        mvc.perform(as(org, scoped, json(post("/core/spaces/" + floorA + "/move"), "{\"newParentId\":\"" + buildingB + "\"}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, scoped, json(post("/core/spaces"), "{\"type\":\"SITE\",\"name\":\"새 사이트\",\"timezone\":\"Asia/Seoul\"}")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        mvc.perform(as(org, scoped, json(post("/core/spaces"), "{\"parentId\":\"" + floorA + "\",\"type\":\"ROOM\",\"name\":\"A102\"}")))
                .andExpect(status().isCreated());
        // 기기: B동 기기는 404, 관계에 범위 밖 공간 지정은 404
        mvc.perform(as(org, scoped, get("/core/devices/" + deviceB + "/relations")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("DEVICE_NOT_FOUND"));
        mvc.perform(as(org, scoped, json(put("/core/devices/" + deviceA + "/relations"),
                        "{\"items\":[{\"spaceId\":\"" + roomB + "\",\"relation\":\"MEASURES\"}]}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        // 공간 미배치 기기는 범위 있는 사용자에게 보이지 않는다
        long loose = data.device(org, data.source(org, "src-q"), "loose", "PENDING", null, null);
        mvc.perform(as(org, scoped, get("/core/devices/" + loose + "/semantic"))).andExpect(status().isNotFound());
        mvc.perform(as(org, users.get("VIEWER"), get("/core/devices/" + loose + "/semantic"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("[DEV-10.03][AT-DEV-22.1] 사이트 단위 권한(사이트1)이면 사이트 요약·공간 기기 목록·개수에 사이트2가 섞이지 않는다 — TC-DEV-265·275·278, TC-IAM-143·144")
    void siteScopedLists() throws Exception {
        setUp();
        long viewer = fx.user(org, "site1.viewer", "VIEWER");
        data.spaceScope(org, viewer, List.of(site1));
        mvc.perform(as(org, viewer, get("/core/sites/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].siteId").value(Long.toString(site1)))
                .andExpect(jsonPath("$.response[0].devices").value(2))
                .andExpect(jsonPath("$.response[0].offline").value(1))
                .andExpect(jsonPath("$.response[0].openAlarms").value(0));
        mvc.perform(as(org, users.get("VIEWER"), get("/core/sites/summary")))
                .andExpect(jsonPath("$.response.length()").value(2));
        mvc.perform(as(org, viewer, get("/core/spaces/" + site2 + "/devices"))).andExpect(status().isNotFound());
        mvc.perform(as(org, viewer, get("/core/spaces/" + site1 + "/devices").param("includeDescendants", "true")))
                .andExpect(jsonPath("$.totalCount").value(2));
        // 건물 A만 볼 수 있는 사용자: 사이트1이 보이지만 합계는 A동 기기만, B동 기기는 관계로 A동에 걸려 있어도 목록에 없다
        long scoped = fx.user(org, "a.viewer", "VIEWER");
        data.spaceScope(org, scoped, List.of(buildingA));
        jdbc.sql("INSERT INTO data2flow_core.device_space_relations (organization_id, device_id, space_id, relation) VALUES (:org, :d, :s, 'MEASURES')")
                .param("org", org).param("d", deviceB).param("s", roomA).update();
        mvc.perform(as(org, scoped, get("/core/sites/summary")))
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].devices").value(1))
                .andExpect(jsonPath("$.response[0].offline").value(0));
        mvc.perform(as(org, scoped, get("/core/spaces/" + roomA + "/devices")))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].id").value(Long.toString(deviceA)));
        mvc.perform(as(org, users.get("VIEWER"), get("/core/spaces/" + roomA + "/devices").param("page", "1").param("size", "1")))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.responses.length()").value(1));
    }
}
