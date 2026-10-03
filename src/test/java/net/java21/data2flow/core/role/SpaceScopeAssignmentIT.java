package net.java21.data2flow.core.role;

import net.java21.data2flow.contracts.authz.PermissionLookup;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공간 단위 권한 지정(IAM-04.02, API-IAM-23)과 회원 관리의 공간 필터·표시(IAM-01.07, API-IAM-31·32). 공간 트리(DEV-01.01)가 생긴 M2부터
 * 지정한 공간이 실제로 있는지, 지정하는 관리자 범위 안인지 확인한다. TC-IAM-052·057·060·129·130
 */
class SpaceScopeAssignmentIT extends IntegrationTestSupport {

    @Autowired
    PermissionLookup lookup;

    private long org;
    private long admin;
    private long site;
    private long buildingA;
    private long floorA;
    private long buildingB;

    private void setUp() {
        org = fx.organization("scope");
        admin = fx.user(org, "boss.admin", "ADMIN");
        site = data.site(org, "본교");
        buildingA = data.space(org, site, "BUILDING", "본관");
        floorA = data.space(org, buildingA, "FLOOR", "본관 2층");
        buildingB = data.space(org, site, "BUILDING", "별관");
    }

    @Test
    @DisplayName("[IAM-04.02][AT-IAM-09.3·09.4] 공간 권한을 '본관 2층'으로 바꾸면 다음 요청부터 하위만 보이고 다른 공간 ID는 404, 재로그인 불필요 — TC-IAM-052·057·129")
    void changeScopeAppliesNextRequest() throws Exception {
        setUp();
        long op = fx.user(org, "op.b", "OPERATOR");
        long room = data.space(org, floorA, "ROOM", "201호");
        mvc.perform(as(org, op, get("/core/spaces/" + buildingB))).andExpect(status().isOk());
        mvc.perform(as(org, admin, json(put("/core/users/" + op + "/role"),
                        "{\"role\":\"OPERATOR\",\"spaceScope\":[\"" + floorA + "\"],\"baseVersion\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.spaceScope[0]").value(Long.toString(floorA)));
        assertThat(lookup.find(org, op).spaceScope().allowedSpaceIds()).containsExactlyInAnyOrder(floorA, room);
        mvc.perform(as(org, op, get("/core/spaces/" + buildingB)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SPACE_NOT_FOUND"));
        mvc.perform(as(org, op, get("/core/spaces/" + room))).andExpect(status().isOk());
        assertThat(auditCount(org, "SPACE_SCOPE_CHANGED")).isEqualTo(1);
        // 보관한 공간은 지정할 수 없다
        jdbc.sql("UPDATE data2flow_core.spaces SET status = 'ARCHIVED' WHERE id = :id").param("id", buildingB).update();
        mvc.perform(as(org, admin, json(put("/core/users/" + op + "/role"),
                        "{\"role\":\"OPERATOR\",\"spaceScope\":[\"" + buildingB + "\"],\"baseVersion\":1}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("SPACE_SCOPE_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value("지정할 수 없는 공간이 포함되어 있습니다"));
    }

    @Test
    @DisplayName("[IAM-04.02] 없는 공간·다른 조직 공간·범위 밖 공간 지정 → 400 SPACE_SCOPE_INVALID, 범위가 있는 관리자는 자기 범위 안에서만 지정 — TC-IAM-060·130")
    void invalidScopes() throws Exception {
        setUp();
        long viewer = fx.user(org, "viewer.v", "VIEWER");
        long otherOrg = fx.organization("scope-other");
        long foreign = data.site(otherOrg, "남의 학교");
        for (String scope : List.of("999999", Long.toString(foreign))) {
            mvc.perform(as(org, admin, json(put("/core/users/" + viewer + "/role"),
                            "{\"role\":\"VIEWER\",\"spaceScope\":[\"" + scope + "\"],\"baseVersion\":0}")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SPACE_SCOPE_INVALID"));
        }
        long scopedAdmin = fx.user(org, "campus.admin", "ADMIN");
        data.spaceScope(org, scopedAdmin, List.of(buildingA));
        for (String body : List.of("[\"" + buildingB + "\"]", "[]")) {
            mvc.perform(as(org, scopedAdmin, json(put("/core/users/" + viewer + "/role"),
                            "{\"role\":\"VIEWER\",\"spaceScope\":" + body + ",\"baseVersion\":0}")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SPACE_SCOPE_INVALID"));
        }
        mvc.perform(as(org, scopedAdmin, json(post("/core/invitations"),
                        "{\"emails\":[\"new@school.ac.kr\"],\"role\":\"VIEWER\",\"spaceScope\":[]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SPACE_SCOPE_INVALID"));
        mvc.perform(as(org, scopedAdmin, json(put("/core/users/" + viewer + "/role"),
                        "{\"role\":\"VIEWER\",\"spaceScope\":[\"" + floorA + "\"],\"baseVersion\":0}")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[IAM-01.07] 회원 목록 spaceId 필터는 그 공간을 볼 수 있는 회원(전체 범위·조상 범위)만, 범위 요약과 상세에 공간 이름 — TC-IAM-052")
    void memberListBySpace() throws Exception {
        setUp();
        long all = fx.user(org, "all.viewer", "VIEWER");
        long a = fx.user(org, "a.viewer", "VIEWER");
        data.spaceScope(org, a, List.of(buildingA));
        long ab = fx.user(org, "ab.viewer", "VIEWER");
        data.spaceScope(org, ab, List.of(floorA, buildingB));
        long b = fx.user(org, "b.viewer", "VIEWER");
        data.spaceScope(org, b, List.of(buildingB));
        mvc.perform(as(org, admin, get("/core/users").param("spaceId", Long.toString(floorA)).param("sort", "loginId,asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(4))
                .andExpect(jsonPath("$.responses[*].loginId").value(containsInAnyOrder("boss.admin", "all.viewer", "a.viewer", "ab.viewer")));
        mvc.perform(as(org, admin, get("/core/users").param("keyword", "viewer").param("sort", "loginId,asc")))
                .andExpect(jsonPath("$.responses[0].loginId").value("a.viewer"))
                .andExpect(jsonPath("$.responses[0].spaceScopeSummary").value("본관"))
                .andExpect(jsonPath("$.responses[1].spaceScopeSummary").value("본관 2층 +1"))
                .andExpect(jsonPath("$.responses[2].spaceScopeSummary").value("ALL"));
        mvc.perform(as(org, admin, get("/core/users/" + ab)))
                .andExpect(jsonPath("$.response.spaceScope[0].id").value(Long.toString(floorA)))
                .andExpect(jsonPath("$.response.spaceScope[0].name").value("본관 2층"))
                .andExpect(jsonPath("$.response.spaceScope[1].name").value("별관"));
        assertThat(all).isPositive();
        assertThat(b).isPositive();
    }
}
