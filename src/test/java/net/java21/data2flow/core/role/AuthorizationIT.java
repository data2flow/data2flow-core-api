package net.java21.data2flow.core.role;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.BuiltinRole;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.PermissionLookup;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** IAM-04.01·04.03·04.04 기본 역할·사용자 정의 역할·제어 권한 분리, 다른 서비스용 권한 판정(내부 API) */
class AuthorizationIT extends IntegrationTestSupport {

    @Autowired
    PermissionLookup lookup;

    private long org;
    private long admin;

    private void setUp() {
        org = fx.organization("authz");
        admin = fx.user(org, "boss.admin", "ADMIN");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BuiltinRole.class)
    @DisplayName("[IAM-04.01][AT-IAM-11.1] DB 판정(PermissionLookup)이 기본 역할 권한표와 같다 — TC-IAM-128")
    void builtinRolesMatchMatrix(BuiltinRole role) {
        setUp();
        long user = fx.user(org, "user." + role.name().toLowerCase(), role.name());
        AccessGrant grant = lookup.find(org, user);
        assertThat(grant.role()).isEqualTo(role.name());
        assertThat(grant.permissions()).isEqualTo(role.permissions());
        assertThat(grant.spaceScope().unrestricted()).isTrue();
        assertThat(lookup.find(org + 1000, user)).isEqualTo(AccessGrant.none());
    }

    @Test
    @DisplayName("[IAM-04.04][AT-IAM-11.3] 제어 권한은 따로: ANALYST는 DEVICE_CONTROL·FLOW_DEPLOY_CONTROL 없음, OPERATOR는 DEVICE_CONTROL만 — TC-IAM-139·140")
    void controlPermissionsAreSeparate() {
        setUp();
        long analyst = fx.user(org, "ana.user", "ANALYST");
        long operator = fx.user(org, "op.user", "OPERATOR");
        long integrator = fx.user(org, "int.user", "INTEGRATOR");
        assertThat(lookup.find(org, analyst).has(Permission.DEVICE_CONTROL)).isFalse();
        assertThat(lookup.find(org, operator).has(Permission.DEVICE_CONTROL)).isTrue();
        assertThat(lookup.find(org, operator).has(Permission.FLOW_DEPLOY_CONTROL)).isFalse();
        assertThat(lookup.find(org, integrator).has(Permission.FLOW_DEPLOY_CONTROL)).isTrue();
        jdbc.sql("UPDATE data2flow_core.app_users SET status = 'LOCKED' WHERE id = :id").param("id", operator).update();
        assertThat(lookup.find(org, operator)).isEqualTo(AccessGrant.none());
    }

    @Test
    @DisplayName("[IAM-04.03][AT-IAM-17.1~17.3] 사용자 정의 역할: 지정한 권한만, 권한을 빼면 다음 요청부터 거부, 사용 중이면 삭제 409 — TC-IAM-131·134·136")
    void customRoles() throws Exception {
        setUp();
        String body = mvc.perform(as(org, admin, json(post("/core/custom-roles"),
                        "{\"name\":\"시설 야간 당직\",\"description\":\"야간\",\"permissions\":[\"ALARM_HANDLE\",\"dev_read\",\"DEVICE_CONTROL\"],\"basedOn\":\"OPERATOR\"}")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/core/custom-roles/")))
                .andExpect(jsonPath("$.response.permissions.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        String roleId = JsonPath.read(body, "$.response.id");
        long night = fx.user(org, "night.duty", null);
        fx.assignCustomRole(org, night, Long.parseLong(roleId), admin);

        AccessGrant grant = lookup.find(org, night);
        assertThat(grant.role()).isEqualTo("CUSTOM");
        assertThat(grant.permissions()).containsExactlyInAnyOrder(Permission.ALARM_HANDLE, Permission.DEV_READ, Permission.DEVICE_CONTROL);
        assertThat(grant.has(Permission.FLOW_WRITE)).isFalse();
        mvc.perform(as(org, night, get("/core/accounts/me"))).andExpect(jsonPath("$.response.role").value("CUSTOM"))
                .andExpect(jsonPath("$.response.customRole.name").value("시설 야간 당직"));

        mvc.perform(as(org, admin, json(put("/core/custom-roles/" + roleId),
                        "{\"name\":\"시설 야간 당직\",\"permissions\":[\"ALARM_HANDLE\",\"DEV_READ\"],\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(1))
                .andExpect(jsonPath("$.response.assignedUsers").value(1));
        assertThat(lookup.find(org, night).has(Permission.DEVICE_CONTROL)).isFalse();
        mvc.perform(as(org, admin, json(put("/core/custom-roles/" + roleId),
                        "{\"name\":\"x\",\"permissions\":[\"ALARM_HANDLE\"],\"baseVersion\":0}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("VERSION_CONFLICT"));

        mvc.perform(as(org, admin, delete("/core/custom-roles/" + roleId))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("CUSTOM_ROLE_IN_USE"));
        jdbc.sql("DELETE FROM data2flow_core.user_roles WHERE user_id = :id").param("id", night).update();
        mvc.perform(as(org, admin, delete("/core/custom-roles/" + roleId))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, delete("/core/custom-roles/" + roleId))).andExpect(status().isNotFound());
        assertThat(auditCount(org, "CUSTOM_ROLE_CREATED") + auditCount(org, "CUSTOM_ROLE_UPDATED") + auditCount(org, "CUSTOM_ROLE_DELETED"))
                .isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE routing_key = 'iam.role.changed'").query(Long.class).single())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("[IAM-04.03][BR-IAM-30] ADMIN 전용 권한은 400 CUSTOM_ROLE_PERMISSION_FORBIDDEN, 모르는 권한·예약 이름·중복 이름은 400 — TC-IAM-138")
    void customRoleValidation() throws Exception {
        setUp();
        mvc.perform(as(org, admin, json(post("/core/custom-roles"), "{\"name\":\"감사\",\"permissions\":[\"AUDIT_READ\"]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("CUSTOM_ROLE_PERMISSION_FORBIDDEN"))
                .andExpect(jsonPath("$.header.resultMessage").value("사용자 정의 역할에 넣을 수 없는 권한입니다"));
        mvc.perform(as(org, admin, json(post("/core/custom-roles"), "{\"name\":\"x\",\"permissions\":[\"FLY\"]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("permissions[0]"));
        mvc.perform(as(org, admin, json(post("/core/custom-roles"), "{\"name\":\"viewer\",\"permissions\":[\"DEV_READ\"]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("RESERVED"));
        mvc.perform(as(org, admin, json(post("/core/custom-roles"), "{\"name\":\"a\",\"permissions\":[\"DEV_READ\"],\"basedOn\":\"KING\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(post("/core/custom-roles"), "{\"name\":\"a\",\"permissions\":[\"DEV_READ\"]}"))).andExpect(status().isCreated());
        mvc.perform(as(org, admin, json(post("/core/custom-roles"), "{\"name\":\"a\",\"permissions\":[\"DEV_READ\"]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DUPLICATED"));
        mvc.perform(as(org, admin, get("/core/custom-roles"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1));
        mvc.perform(as(org, admin, json(put("/core/custom-roles/999999"), "{\"name\":\"a\",\"permissions\":[\"DEV_READ\"],\"baseVersion\":0}")))
                .andExpect(status().isNotFound());
        long viewer = fx.user(org, "view.user", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/custom-roles"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[IAM-04.01][API-IAM-73] 권한 목록과 기본 역할 권한표, 역할 지정에 CUSTOM 역할 사용")
    void catalogAndCustomAssignment() throws Exception {
        setUp();
        mvc.perform(as(org, admin, get("/core/permissions"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.permissions.length()").value(Permission.values().length))
                .andExpect(jsonPath("$.response.permissions[?(@.code == 'FLOW_DEPLOY_CONTROL')].area").value("FLW"))
                .andExpect(jsonPath("$.response.permissions[?(@.code == 'IAM_MANAGE')].customRoleAllowed").value(false))
                .andExpect(jsonPath("$.response.builtinRoles.VIEWER.length()").value(BuiltinRole.VIEWER.permissions().size()));

        String body = mvc.perform(as(org, admin, json(post("/core/custom-roles"), "{\"name\":\"당직\",\"permissions\":[\"ALARM_READ\"]}")))
                .andReturn().getResponse().getContentAsString();
        String roleId = JsonPath.read(body, "$.response.id");
        long user = fx.user(org, "kim.op", "OPERATOR");
        mvc.perform(as(org, admin, json(put("/core/users/" + user + "/role"),
                        "{\"role\":\"CUSTOM\",\"customRoleId\":\"" + roleId + "\",\"spaceScope\":[],\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.customRoleId").value(roleId));
        mvc.perform(as(org, admin, json(put("/core/users/" + user + "/role"), "{\"role\":\"CUSTOM\",\"baseVersion\":1}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("customRoleId"));
    }

    @Test
    @DisplayName("[IAM-04.05][api-rules §7] 다른 서비스용 내부 권한 판정: 활성 사용자는 권한·공간 범위, 다른 조직·없는 사용자는 active=false")
    void internalAccessGrant() throws Exception {
        setUp();
        long op = fx.user(org, "kim.op", "OPERATOR");
        mvc.perform(get("/internal/core/organizations/" + org + "/users/" + op + "/access-grant").header("X-CALLER-SERVICE", "data2flow-ai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.active").value(true))
                .andExpect(jsonPath("$.response.role").value("OPERATOR"))
                .andExpect(jsonPath("$.response.permissions[?(@ == 'DEVICE_CONTROL')]").exists())
                .andExpect(jsonPath("$.response.spaceScope.unrestricted").value(true));
        mvc.perform(get("/internal/core/organizations/" + (org + 99) + "/users/" + op + "/access-grant"))
                .andExpect(jsonPath("$.response.active").value(false))
                .andExpect(jsonPath("$.response.permissions.length()").value(0));
    }
}
