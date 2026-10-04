package net.java21.data2flow.core.board;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.hamcrest.Matchers;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DSH-04.01·04.07·04.08 사용자 정의 대시보드 저장·공개 범위·복제·기본·가져오기·내보내기·권한(API-DSH-06·07·12·13) */
class DashboardBoardIT extends IntegrationTestSupport {

    static final String LAYOUT = """
            {"widgets":[
              {"id":"co2","type":"stat","x":0,"y":0,"w":6,"h":4,"title":"CO2","options":{"decimals":0,"sparkline":true},
               "targets":[{"kind":"DEVICE_METRIC","deviceId":"%1$d","metricKey":"co2"}]},
              {"id":"temp","type":"line","x":6,"y":0,"w":18,"h":8,"targets":[{"kind":"DEVICE_METRIC","deviceId":"%1$d","metricKey":"temperature"},
                 {"kind":"SPACE_AGGREGATE","spaceId":"${space}","metricKey":"temperature"}]},
              {"id":"g","type":"gauge","x":0,"y":4,"w":6,"h":4,"options":{"min":0,"max":2000},
               "targets":[{"kind":"DEVICE_METRIC","deviceId":"%1$d","metricKey":"co2"}]},
              {"id":"tbl","type":"table","x":0,"y":8,"w":12,"h":6,"targets":[{"kind":"DEVICE_METRIC","deviceId":"%1$d","metricKey":"co2"}]},
              {"id":"alarms","type":"alarm-list","x":12,"y":8,"w":12,"h":6,"options":{"maxRows":5},"targets":[{"kind":"SPACE","spaceId":"%2$d"}]}]}""";

    private long org;
    private long admin;
    private long analyst;
    private long analyst2;
    private long viewer;
    private long room;
    private long device;

    @BeforeEach
    void setUp() {
        org = fx.organization("dash");
        admin = fx.user(org, "dash.admin", "ADMIN");
        analyst = fx.user(org, "dash.analyst", "ANALYST");
        analyst2 = fx.user(org, "dash.analyst2", "ANALYST");
        viewer = fx.user(org, "dash.viewer", "VIEWER");
        long site = data.site(org, "본관");
        room = data.space(org, site, "ROOM", "실습실");
        long source = data.source(org, "cs");
        data.metric(org, "co2", "ppm");
        data.metric(org, "temperature", "℃");
        device = data.device(org, source, "24e124136d151547", "ACTIVE", room, null);
    }

    String body(String name, String visibility) {
        return """
                {"name":"%s","description":"실습동 현황","visibility":"%s","layout":%s,
                 "variables":[{"name":"space","type":"SPACE","default":"%d"}],"timeRange":{"relative":"24h"},"resolution":"AUTO","refresh":"1m"}"""
                .formatted(name, visibility, LAYOUT.formatted(device, room), room);
    }

    String create(long user, String name, String visibility) throws Exception {
        String res = mvc.perform(as(org, user, json(post("/core/dashboards"), body(name, visibility))))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, Matchers.startsWith("/api/v1/core/dashboards/")))
                .andExpect(jsonPath("$.response.version").value(1))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(res, "$.response.id");
    }

    @Test
    @DisplayName("[DSH-04.01][AT-DSH-04.1] 저장 후 다시 열면 배치·설정 그대로, 감사 DASHBOARD_CREATED·UPDATED·DELETED, 위젯 종류 목록 — TC-DSH-030 앞부분")
    void createAndReopen() throws Exception {
        String id = create(analyst, "실습동", "PRIVATE");
        mvc.perform(as(org, analyst, get("/core/dashboards/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.layout.widgets", hasSize(5)))
                .andExpect(jsonPath("$.response.layout.widgets[1].targets[1].spaceId").value("${space}"))
                .andExpect(jsonPath("$.response.layout.widgets[0].options.sparkline").value(true))
                .andExpect(jsonPath("$.response.variables[0].name").value("space"))
                .andExpect(jsonPath("$.response.refresh").value("1m"))
                .andExpect(jsonPath("$.response.editable").value(true))
                .andExpect(jsonPath("$.response.ownerUserId").value(Long.toString(analyst)));
        mvc.perform(as(org, analyst, json(put("/core/dashboards/" + id), "{\"name\":\"실습동 2\",\"baseVersion\":1}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.name").value("실습동 2"))
                .andExpect(jsonPath("$.response.layout.widgets", hasSize(5)))
                .andExpect(jsonPath("$.response.version").value(2));
        mvc.perform(as(org, analyst, get("/core/dashboards").param("tab", "mine")))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].widgetCount").value(5))
                .andExpect(jsonPath("$.responses[0].favorite").value(false));
        mvc.perform(as(org, analyst, get("/core/widget-types")))
                .andExpect(jsonPath("$.response[?(@.type == 'line')].targetRule.max").value(20))
                .andExpect(jsonPath("$.response[?(@.type == 'control')]", hasSize(0)));
        mvc.perform(as(org, analyst, delete("/core/dashboards/" + id))).andExpect(status().isNoContent());
        mvc.perform(as(org, analyst, get("/core/dashboards/" + id))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("DASHBOARD_NOT_FOUND"));
        assertThat(auditCount(org, "DASHBOARD_CREATED")).isEqualTo(1);
        assertThat(auditCount(org, "DASHBOARD_UPDATED")).isEqualTo(1);
        assertThat(auditCount(org, "DASHBOARD_DELETED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[DSH-04.01][AT-DSH-04.6][BR-DSH-07] A·B가 같은 판을 읽고 A 저장 → B 저장 409 DASHBOARD_VERSION_CONFLICT, 응답에 최신 version·수정자 — TC-DSH-030")
    void versionConflict() throws Exception {
        String id = create(admin, "공용", "ORG");
        mvc.perform(as(org, admin, json(put("/core/dashboards/" + id), "{\"name\":\"A 저장\",\"baseVersion\":1}"))).andExpect(status().isOk());
        mvc.perform(as(org, admin, json(put("/core/dashboards/" + id), "{\"name\":\"B 저장\",\"baseVersion\":1}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("DASHBOARD_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.response.version").value(2))
                .andExpect(jsonPath("$.response.updatedBy").value(Long.toString(admin)))
                .andExpect(jsonPath("$.response.updatedByName").exists());
        mvc.perform(as(org, admin, json(put("/core/dashboards/" + id), "{\"name\":\"B 저장\"}"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[DSH-04.07][BR-DSH-08] PRIVATE은 소유자만(남 404), ORG는 조직 전체 조회·편집은 소유자·관리자만, 복제는 내 PRIVATE 사본, 기본 대시보드 — TC-DSH-046")
    void visibility() throws Exception {
        String mine = create(analyst, "내 것", "PRIVATE");
        String shared = create(analyst, "공유", "ORG");
        mvc.perform(as(org, analyst2, get("/core/dashboards/" + mine))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("DASHBOARD_NOT_FOUND"));
        mvc.perform(as(org, analyst2, get("/core/dashboards/" + shared))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.editable").value(false));
        mvc.perform(as(org, analyst2, json(put("/core/dashboards/" + shared), "{\"name\":\"남의 수정\",\"baseVersion\":1}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, admin, json(put("/core/dashboards/" + shared), "{\"name\":\"관리자 수정\",\"baseVersion\":1}")))
                .andExpect(status().isOk());
        mvc.perform(as(org, analyst2, get("/core/dashboards").param("tab", "shared")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].name").value("관리자 수정"));
        mvc.perform(as(org, analyst2, get("/core/dashboards").param("tab", "mine"))).andExpect(jsonPath("$.totalCount").value(0));
        String copy = JsonPath.read(mvc.perform(as(org, analyst2, post("/core/dashboards/" + shared + "/duplicate")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.name").value("관리자 수정 (복사본)"))
                .andExpect(jsonPath("$.response.visibility").value("PRIVATE"))
                .andReturn().getResponse().getContentAsString(), "$.response.id");
        mvc.perform(as(org, analyst2, get("/core/dashboards/" + copy))).andExpect(jsonPath("$.response.editable").value(true))
                .andExpect(jsonPath("$.response.layout.widgets", hasSize(5)));
        mvc.perform(as(org, analyst, get("/core/dashboards/" + copy))).andExpect(status().isNotFound());
        mvc.perform(as(org, analyst2, post("/core/dashboards/" + mine + "/duplicate"))).andExpect(status().isNotFound());
        // 기본 대시보드(API-DSH-12): 보이는 것만, 지우면 비워진다
        mvc.perform(as(org, analyst2, json(put("/core/accounts/me/default-dashboard"), "{\"dashboardId\":\"" + mine + "\"}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, analyst2, json(put("/core/accounts/me/default-dashboard"), "{\"dashboardId\":\"" + shared + "\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.defaultDashboardId").value(shared));
        mvc.perform(as(org, analyst2, get("/core/accounts/me/preferences"))).andExpect(jsonPath("$.response.defaultDashboardId").value(shared));
        mvc.perform(as(org, analyst, delete("/core/dashboards/" + shared))).andExpect(status().isNoContent());
        mvc.perform(as(org, analyst2, get("/core/accounts/me/preferences")))
                .andExpect(jsonPath("$.response.defaultDashboardId").doesNotExist());
        mvc.perform(as(org, analyst2, json(put("/core/accounts/me/default-dashboard"), "{\"dashboardId\":null}")))
                .andExpect(status().isOk());
        mvc.perform(as(org, analyst2, json(put("/core/accounts/me/default-dashboard"), "{\"dashboardId\":\"x\"}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[DSH-04.07] 즐겨찾기 탭·표시, 검색어, 탭 값 검사")
    void favoritesAndKeyword() throws Exception {
        String a = create(analyst, "1층 현황", "PRIVATE");
        create(analyst, "2층 현황", "PRIVATE");
        mvc.perform(as(org, analyst, json(put("/core/accounts/me/preferences"),
                "{\"favorites\":[{\"type\":\"DASHBOARD\",\"id\":\"" + a + "\"}],\"baseVersion\":0}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.favorites[0].name").value("1층 현황"));
        mvc.perform(as(org, analyst, get("/core/dashboards").param("tab", "favorite")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].favorite").value(true));
        mvc.perform(as(org, analyst, get("/core/dashboards").param("keyword", "2층"))).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, analyst, get("/core/dashboards").param("tab", "all"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[DSH-04.01][TC-DSH-029] 저장 요청 검사: 겹침 400 DASHBOARD_LAYOUT_INVALID, 미지원 종류 400 WIDGET_TYPE_UNSUPPORTED, 이름·공개 범위·시간 범위")
    void validation() throws Exception {
        String overlap = "{\"name\":\"x\",\"layout\":{\"widgets\":[{\"id\":\"a\",\"type\":\"markdown\",\"x\":0,\"y\":0,\"w\":6,\"h\":2},"
                + "{\"id\":\"b\",\"type\":\"markdown\",\"x\":3,\"y\":1,\"w\":6,\"h\":2}]}}";
        mvc.perform(as(org, analyst, json(post("/core/dashboards"), overlap))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("DASHBOARD_LAYOUT_INVALID"))
                .andExpect(jsonPath("$.errors[0].code").value("OVERLAP"));
        mvc.perform(as(org, analyst, json(post("/core/dashboards"),
                        "{\"name\":\"x\",\"layout\":[{\"id\":\"c\",\"type\":\"control\",\"x\":0,\"y\":0,\"w\":6,\"h\":2}]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("WIDGET_TYPE_UNSUPPORTED"));
        mvc.perform(as(org, analyst, json(post("/core/dashboards"), "{\"name\":\"\",\"visibility\":\"PUBLIC\",\"refresh\":\"10s\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.containsInAnyOrder("name", "visibility", "refresh")));
        mvc.perform(as(org, analyst, json(post("/core/dashboards"), "{\"name\":\"x\",\"timeRange\":{\"relative\":\"9y\"}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("WIDGET_QUERY_INVALID"));
        mvc.perform(as(org, analyst, json(post("/core/dashboards"), "{\"name\":\"빈 대시보드\"}"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.visibility").value("PRIVATE"));
    }

    @Test
    @DisplayName("[DSH-04.08][TC-DSH-050] 내보내기(formatVersion·schemaVersion·대상 외부 ID) → 가져오기 같은 정의(새 ID), 다른 조직은 매핑 필요 목록")
    void exportImport() throws Exception {
        String id = create(analyst, "실습동", "ORG");
        String exported = mvc.perform(as(org, analyst, get("/core/dashboards/" + id + "/export")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.formatVersion").value(1))
                .andExpect(jsonPath("$.response.dashboard.schemaVersion").value(1))
                .andExpect(jsonPath("$.response.targets[0].ref").value("co2/0"))
                .andExpect(jsonPath("$.response.targets[0].deviceExternalId").value("24e124136d151547"))
                .andExpect(jsonPath("$.response.targets[?(@.ref == 'alarms/0')].spaceName").value("실습실"))
                .andReturn().getResponse().getContentAsString();
        String file = com.jayway.jsonpath.JsonPath.parse(exported).read("$.response", net.minidev.json.JSONObject.class).toJSONString();
        String imported = JsonPath.read(mvc.perform(as(org, analyst, json(post("/core/dashboards/import"), file)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.name").value("실습동"))
                .andExpect(jsonPath("$.response.unmapped", hasSize(0)))
                .andReturn().getResponse().getContentAsString(), "$.response.id");
        assertThat(imported).isNotEqualTo(id);
        mvc.perform(as(org, analyst, get("/core/dashboards/" + imported)))
                .andExpect(jsonPath("$.response.layout.widgets", hasSize(5)))
                .andExpect(jsonPath("$.response.layout.widgets[0].targets[0].deviceId").value(Long.toString(device)))
                .andExpect(jsonPath("$.response.visibility").value("PRIVATE"));
        // 다른 조직: 같은 외부 ID의 기기·같은 이름의 공간이 있으면 다시 매핑, 아니면 매핑 필요
        long other = fx.organization("dash2");
        long otherAdmin = fx.user(other, "dash2.admin", "ADMIN");
        long otherRoom = data.space(other, data.site(other, "별관"), "ROOM", "실습실");
        mvc.perform(as(other, otherAdmin, json(post("/core/dashboards/import"), file)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.unmapped[*].ref").value(Matchers.hasItems("co2/0", "co2", "temp/0", "g", "tbl")));
        String otherDash = JsonPath.read(mvc.perform(as(other, otherAdmin, get("/core/dashboards").param("tab", "mine")))
                .andReturn().getResponse().getContentAsString(), "$.responses[0].id");
        mvc.perform(as(other, otherAdmin, get("/core/dashboards/" + otherDash)))
                .andExpect(jsonPath("$.response.layout.widgets[?(@.id == 'alarms')].targets[0].spaceId").value(Long.toString(otherRoom)));
        mvc.perform(as(org, analyst, json(post("/core/dashboards/import"), "{\"formatVersion\":2,\"dashboard\":{}}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("DASHBOARD_LAYOUT_INVALID"));
    }

    @Test
    @DisplayName("[DSH-04.01][DSH-04.07] 권한: VIEWER 조회 가능·생성 403, ANALYST 생성 201, 다른 조직 대시보드 404 — TC-DSH-048")
    void permissionMatrix() throws Exception {
        String shared = create(analyst, "공유", "ORG");
        mvc.perform(as(org, viewer, get("/core/dashboards/" + shared))).andExpect(status().isOk());
        mvc.perform(as(org, viewer, json(post("/core/dashboards"), body("v", "PRIVATE")))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        mvc.perform(as(org, viewer, post("/core/dashboards/" + shared + "/duplicate"))).andExpect(status().isForbidden());
        long other = fx.organization("dash3");
        long otherAdmin = fx.user(other, "dash3.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/dashboards/" + shared))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("DASHBOARD_NOT_FOUND"));
        mvc.perform(as(other, otherAdmin, delete("/core/dashboards/" + shared))).andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, get("/core/dashboards").param("tab", "shared"))).andExpect(jsonPath("$.responses", hasSize(0)));
        assertThat(List.of(shared)).hasSize(1);
    }
}
