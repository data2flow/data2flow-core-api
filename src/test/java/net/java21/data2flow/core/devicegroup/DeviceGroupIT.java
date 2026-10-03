package net.java21.data2flow.core.devicegroup;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.device.DeviceTestData;
import net.java21.data2flow.core.devicegroup.service.DynamicGroupMembership;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-06.01 정적 그룹, DEV-06.02 동적 그룹(BR-DEV-12·13), EVT-DEV-07 */
class DeviceGroupIT extends IntegrationTestSupport {

    @Autowired
    DynamicGroupMembership membership;

    private DeviceTestData d;
    private long org;
    private long admin;
    private long floor3;
    private long room;
    private long other;
    private long co2;
    private long th;
    private long source;

    @BeforeEach
    void setUp() {
        d = new DeviceTestData(jdbc);
        org = fx.organization("grp");
        admin = fx.user(org, "grp.admin", "ADMIN");
        long site = data.site(org, "본관");
        floor3 = data.space(org, site, "FLOOR", "3층");
        room = data.space(org, floor3, "ROOM", "301호");
        other = data.space(org, site, "FLOOR", "1층");
        data.metric(org, "co2", "ppm");
        data.metric(org, "temperature", "℃");
        co2 = data.model(org, "EM500-CO2", List.of("co2"));
        th = data.model(org, "EM300-TH", List.of("temperature"));
        source = data.source(org, "cs");
    }

    private String create(String body) throws Exception {
        String res = mvc.perform(as(org, admin, json(post("/core/device-groups"), body)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/core/device-groups/")))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.response.id");
    }

    @Test
    @DisplayName("[DEV-06.02][AT-DEV-08.1] 동적 그룹 '3층 CO2'(3층 하위 + EM500-CO2): 3층에 EM500-CO2 기기를 승인하면 즉시 소속 +1, EVT-DEV-07 — TC-DEV-080·168·175")
    void dynamicGroupFollowsApproval() throws Exception {
        data.device(org, source, "old", "ACTIVE", room, co2);
        data.device(org, source, "th", "ACTIVE", room, th);
        String id = create("{\"name\":\"3층 CO2\",\"type\":\"DYNAMIC\",\"criteria\":{\"models\":[\"EM500-CO2\"],\"spaceIds\":[\"" + floor3
                + "\"],\"includeDescendants\":true}}");
        mvc.perform(as(org, admin, get("/core/device-groups/" + id))).andExpect(jsonPath("$.response.memberCount").value(1))
                .andExpect(jsonPath("$.response.criteria.models[0]").value("EM500-CO2"))
                .andExpect(jsonPath("$.response.usage.rules").value(0));
        long pending = data.device(org, source, "new", "PENDING", null, null);
        mvc.perform(as(org, admin, json(post("/core/devices/approve"),
                "{\"items\":[{\"deviceId\":\"" + pending + "\",\"baseVersion\":0}],\"modelId\":\"" + co2 + "\",\"spaceId\":\"" + room + "\"}")))
                .andExpect(jsonPath("$.response.results[0].ok").value(true));
        mvc.perform(as(org, admin, get("/core/device-groups/" + id))).andExpect(jsonPath("$.response.memberCount").value(2));
        mvc.perform(as(org, admin, get("/core/device-groups/" + id + "/members"))).andExpect(jsonPath("$.totalCount").value(2));
        assertThat(d.outboxPayloads(org, "group.membership.changed")).hasSize(2).last().asString().contains("\"added\": [" + pending + "]");
        // 공간을 옮기면 빠진다
        mvc.perform(as(org, admin, json(patch("/core/devices/" + pending), "{\"spaceId\":\"" + other + "\",\"baseVersion\":1}")))
                .andExpect(status().isOk());
        mvc.perform(as(org, admin, get("/core/device-groups/" + id))).andExpect(jsonPath("$.response.memberCount").value(1));
        mvc.perform(as(org, admin, get("/core/devices/" + pending))).andExpect(jsonPath("$.response.groups", hasSize(0)));
    }

    @Test
    @DisplayName("[DEV-06.02] 동적 조건: 태그 any/all(대소문자 무시)·상태·속성(maxTemp > 28)·조건 수정 시 재계산·1시간 전체 재계산 — TC-DEV-168·171")
    void dynamicCriteria() throws Exception {
        long a = data.device(org, source, "a", "ACTIVE", room, th);
        long b = data.device(org, source, "b", "INACTIVE", room, th);
        d.tag(org, a, "Pilot");
        d.tag(org, a, "x");
        d.tag(org, b, "pilot");
        String tagAll = create("{\"name\":\"t\",\"type\":\"DYNAMIC\",\"criteria\":{\"tags\":{\"match\":\"all\",\"values\":[\"PILOT\",\"x\"]}}}");
        String tagAny = create("{\"name\":\"t2\",\"type\":\"DYNAMIC\",\"criteria\":{\"tags\":{\"any\":[\"pilot\"]},\"status\":[\"ACTIVE\"]}}");
        mvc.perform(as(org, admin, get("/core/device-groups/" + tagAll))).andExpect(jsonPath("$.response.memberCount").value(1));
        mvc.perform(as(org, admin, get("/core/device-groups/" + tagAny))).andExpect(jsonPath("$.response.memberCount").value(1));
        String attr = create("{\"name\":\"hot\",\"type\":\"DYNAMIC\",\"criteria\":{\"attributes\":[{\"key\":\"maxTemp\",\"op\":\"GT\",\"value\":28}]}}");
        mvc.perform(as(org, admin, get("/core/device-groups/" + attr))).andExpect(jsonPath("$.response.memberCount").value(0));
        mvc.perform(as(org, admin, json(put("/core/devices/" + b + "/attributes/SERVER/maxTemp"), "{\"value\":30}"))).andExpect(status().isOk());
        mvc.perform(as(org, admin, get("/core/device-groups/" + attr))).andExpect(jsonPath("$.response.memberCount").value(1));
        mvc.perform(as(org, admin, json(patch("/core/device-groups/" + attr),
                        "{\"criteria\":{\"attributes\":[{\"key\":\"maxTemp\",\"op\":\"LT\",\"value\":28}]},\"description\":\"설명\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.memberCount").value(0))
                .andExpect(jsonPath("$.response.description").value("설명"));
        // DB를 직접 바꾼 변경(이벤트 없음)은 1시간 재계산이 바로잡는다
        jdbc.sql("UPDATE data2flow_core.devices SET status = 'ACTIVE' WHERE id = :id").param("id", b).update();
        assertThat(membership.recomputeAll()).isTrue();
        mvc.perform(as(org, admin, get("/core/device-groups/" + tagAny))).andExpect(jsonPath("$.response.memberCount").value(2));
        mvc.perform(as(org, admin, get("/core/device-groups").param("type", "DYNAMIC").param("q", "t"))).andExpect(jsonPath("$.totalCount").value(3));
        mvc.perform(as(org, admin, get("/core/device-groups").param("type", "WEIRD"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(post("/core/device-groups"), "{\"name\":\"bad\",\"type\":\"DYNAMIC\",\"criteria\":{\"color\":1}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("[DEV-06.01][BR-DEV-12] 정적 그룹: 한 기기 여러 그룹, 추가·제거, 1,000대 넘으면 400 GROUP_SIZE_EXCEEDED, 이름 중복 409, 동적 그룹에 직접 추가 400 — TC-DEV-161~164")
    void staticGroups() throws Exception {
        long a = data.device(org, source, "s1", "ACTIVE", room, th);
        long b = data.device(org, source, "s2", "ACTIVE", room, th);
        String g1 = create("{\"name\":\"정적1\",\"type\":\"STATIC\",\"deviceIds\":[\"" + a + "\"]}");
        String g2 = create("{\"name\":\"정적2\",\"type\":\"STATIC\",\"deviceIds\":[\"" + a + "\",\"" + b + "\"]}");
        mvc.perform(as(org, admin, get("/core/devices/" + a))).andExpect(jsonPath("$.response.groups", hasSize(2)));
        mvc.perform(as(org, admin, json(post("/core/device-groups/" + g1 + "/members/add"), "{\"deviceIds\":[\"" + a + "\",\"" + b + "\"]}")))
                .andExpect(jsonPath("$.response.memberCount").value(2)).andExpect(jsonPath("$.response.added").value(1));
        mvc.perform(as(org, admin, json(post("/core/device-groups/" + g2 + "/members/remove"), "{\"deviceIds\":[\"" + a + "\"]}")))
                .andExpect(jsonPath("$.response.memberCount").value(1)).andExpect(jsonPath("$.response.removed").value(1));
        mvc.perform(as(org, admin, json(post("/core/device-groups"), "{\"name\":\"정적1\",\"type\":\"STATIC\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("GROUP_NAME_DUPLICATE"));
        mvc.perform(as(org, admin, json(post("/core/device-groups/" + g1 + "/members/add"), "{\"deviceIds\":[\"999999\"]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DEVICE_NOT_FOUND"));
        String dyn = create("{\"name\":\"동적\",\"type\":\"DYNAMIC\",\"criteria\":{\"statuses\":[\"ACTIVE\"]}}");
        mvc.perform(as(org, admin, json(post("/core/device-groups/" + dyn + "/members/add"), "{\"deviceIds\":[\"" + a + "\"]}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(patch("/core/device-groups/" + g1), "{\"criteria\":{\"statuses\":[\"ACTIVE\"]}}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(post("/core/device-groups"), "{\"name\":\"x\",\"type\":\"STATIC\",\"criteria\":{\"statuses\":[\"ACTIVE\"]}}")))
                .andExpect(status().isBadRequest());

        // AT-DEV-08.3: 1,000대인 정적 그룹에 1대 더하면 400
        jdbc.sql("""
                        INSERT INTO data2flow_core.devices (organization_id, source_id, external_id, name, status, space_id, model_id)
                        SELECT :org, :src, 'bulk-' || g, 'b' || g, 'ACTIVE', :space, :model FROM generate_series(1, 1000) g""")
                .param("org", org).param("src", source).param("space", room).param("model", th).update();
        List<Long> bulk = jdbc.sql("SELECT id FROM data2flow_core.devices WHERE external_id LIKE 'bulk-%' ORDER BY id").query(Long.class).list();
        String big = create("{\"name\":\"천대\",\"type\":\"STATIC\",\"deviceIds\":" + ids(bulk) + "}");
        mvc.perform(as(org, admin, get("/core/device-groups/" + big))).andExpect(jsonPath("$.response.memberCount").value(1000));
        mvc.perform(as(org, admin, json(post("/core/device-groups/" + big + "/members/add"), "{\"deviceIds\":[\"" + a + "\"]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("GROUP_SIZE_EXCEEDED"));
        // 동적 조건이 1,000대를 넘으면 저장 거부, 미리 보기도 400
        mvc.perform(as(org, admin, json(post("/core/device-groups"), "{\"name\":\"모두\",\"type\":\"DYNAMIC\",\"criteria\":{\"statuses\":[\"ACTIVE\"]}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("GROUP_SIZE_EXCEEDED"));
        mvc.perform(as(org, admin, json(post("/core/device-groups/preview"), "{\"criteria\":{\"statuses\":[\"ACTIVE\"]}}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[DEV-06.01] 미리 보기(개수·표본 ≤20, 공간 범위), 삭제 204·이후 404, 기기 삭제하면 그룹에서 빠짐, 쓰기는 DEV_ADMIN만 — TC-DEV-163·165·170")
    void previewDeleteAndPermissions() throws Exception {
        long a = data.device(org, source, "p1", "ACTIVE", room, co2);
        data.device(org, source, "p2", "ACTIVE", other, co2);
        long viewer = fx.user(org, "grp.viewer", "VIEWER");
        data.spaceScope(org, viewer, List.of(floor3));
        mvc.perform(as(org, viewer, json(post("/core/device-groups/preview"), "{\"criteria\":{\"modelIds\":[\"" + co2 + "\"]}}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.count").value(1))
                .andExpect(jsonPath("$.response.sample[0].id").value(Long.toString(a)));
        mvc.perform(as(org, viewer, json(post("/core/device-groups"), "{\"name\":\"v\",\"type\":\"STATIC\"}"))).andExpect(status().isForbidden());
        long op = fx.user(org, "grp.op", "OPERATOR");
        mvc.perform(as(org, op, json(post("/core/device-groups"), "{\"name\":\"v\",\"type\":\"STATIC\"}"))).andExpect(status().isForbidden());
        String g = create("{\"name\":\"삭제 대상\",\"type\":\"STATIC\",\"deviceIds\":[\"" + a + "\"]}");
        mvc.perform(as(org, admin, delete("/core/devices/" + a).param("baseVersion", "0"))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get("/core/device-groups/" + g))).andExpect(jsonPath("$.response.memberCount").value(0));
        mvc.perform(as(org, op, delete("/core/device-groups/" + g))).andExpect(status().isForbidden());
        mvc.perform(as(org, admin, delete("/core/device-groups/" + g))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get("/core/device-groups/" + g))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("GROUP_NOT_FOUND"));
        long otherOrg = fx.organization("grp2");
        long otherAdmin = fx.user(otherOrg, "grp2.admin", "ADMIN");
        String mine = create("{\"name\":\"내 그룹\",\"type\":\"STATIC\"}");
        mvc.perform(as(otherOrg, otherAdmin, get("/core/device-groups/" + mine))).andExpect(status().isNotFound());
        mvc.perform(as(org, admin, json(patch("/core/device-groups/" + mine), "{\"name\":\"새 이름\",\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, admin, json(patch("/core/device-groups/" + mine), "{\"name\":\"x\",\"baseVersion\":0}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, admin, json(patch("/core/device-groups/" + mine), "{\"name\":\"\"}"))).andExpect(status().isBadRequest());
        assertThat(auditCount(org, "DEVICE_GROUP_DELETED")).isEqualTo(1);
    }

    private static String ids(List<Long> ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(ids.get(i)).append('"');
        }
        return sb.append(']').toString();
    }
}
