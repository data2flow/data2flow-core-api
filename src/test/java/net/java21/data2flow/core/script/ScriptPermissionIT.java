package net.java21.data2flow.core.script;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCR-02.05 스크립트 작성·배포 권한과 감사 로그(IAM-04.05 권한 매트릭스) — TC-SCR-037·038.
 * 역할 × API: VIEWER·ANALYST 접근 불가, OPERATOR 조회만, INTEGRATOR 작성·배포, ADMIN 강제 배포. 다른 조직 스크립트 ID → 404.
 */
class ScriptPermissionIT extends ScriptItSupport {

    private long org;
    private long admin;
    private long integrator;
    private String scriptId;
    private String draftId;

    @BeforeEach
    void setUp() throws Exception {
        org = fx.organization("perm");
        admin = fx.user(org, "boss.admin", "ADMIN");
        integrator = fx.user(org, "lee.integrator", "INTEGRATOR");
        for (String role : List.of("OPERATOR", "ANALYST", "VIEWER")) {
            fx.user(org, role.toLowerCase() + ".user", role);
        }
        JsonNode created = create(org, integrator, "{\"name\":\"권한 시험\",\"kind\":\"TRANSFORM\"}");
        scriptId = created.get("id").asString();
        draftId = created.get("draftVersionId").asString();
    }

    private long user(String role) {
        return switch (role) {
            case "ADMIN" -> admin;
            case "INTEGRATOR" -> integrator;
            default -> jdbc.sql("SELECT id FROM data2flow_core.app_users WHERE organization_id = :o AND login_id = :l")
                    .param("o", org).param("l", role.toLowerCase() + ".user").query(Long.class).single();
        };
    }

    @ParameterizedTest(name = "[{index}] {0}: 조회 {1}, 작성 {2}")
    @CsvSource({"ADMIN,200,201", "INTEGRATOR,200,201", "OPERATOR,200,403", "ANALYST,403,403", "VIEWER,403,403"})
    @DisplayName("[SCR-02.05][AT-SCR-01.4] 역할별 조회(API-SCR-01·04·06·15)·작성(API-SCR-02·03·07·09·17) 허용·거부 — TC-SCR-037")
    void roleMatrix(String role, int read, int write) throws Exception {
        long u = user(role);
        mvc.perform(as(org, u, get("/core/scripts"))).andExpect(status().is(read));
        mvc.perform(as(org, u, get("/core/scripts/" + scriptId))).andExpect(status().is(read));
        mvc.perform(as(org, u, get("/core/scripts/" + scriptId + "/versions/" + draftId))).andExpect(status().is(read));
        mvc.perform(as(org, u, get("/core/scripts/templates"))).andExpect(status().is(read));
        mvc.perform(as(org, u, json(post("/core/scripts"), "{\"name\":\"새 스크립트 " + role + "\",\"kind\":\"TRANSFORM\"}")))
                .andExpect(status().is(write));
        int ok = write == 201 ? 200 : 403;
        mvc.perform(as(org, u, json(put("/core/scripts/" + scriptId + "/draft"),
                        "{\"code\":" + str(TRANSFORM_OK) + ",\"baseVersionNo\":1}"))).andExpect(status().is(ok));
        mvc.perform(as(org, u, json(post("/core/scripts/check"), "{\"kind\":\"TRANSFORM\",\"code\":" + str(TRANSFORM_OK) + "}")))
                .andExpect(status().is(ok));
        mvc.perform(as(org, u, json(post("/core/scripts/test-run"), "{\"kind\":\"TRANSFORM\",\"code\":" + str(TRANSFORM_OK)
                + ",\"input\":{\"metrics\":[]}}"))).andExpect(status().is(ok));
        mvc.perform(as(org, u, json(put("/core/scripts/" + scriptId + "/bindings"), "{\"bindings\":[]}"))).andExpect(status().is(ok));
        mvc.perform(as(org, u, post("/core/scripts/" + scriptId + "/disable"))).andExpect(status().is(ok));
        if (write == 403) {
            mvc.perform(as(org, u, json(post("/core/scripts/" + scriptId + "/deploy"),
                            "{\"versionId\":\"" + draftId + "\",\"memo\":\"배포\",\"baseActiveVersionId\":null}")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        }
    }

    @Test
    @DisplayName("[SCR-02.05][BR-SCR-10] 강제 배포는 ADMIN만(INTEGRATOR 403), 사유 필수, 장기 토큰 요청은 배포 403, 다른 조직 스크립트 404 — TC-SCR-037")
    void forceDeployAndTokens() throws Exception {
        String deploy = "{\"versionId\":\"" + draftId + "\",\"memo\":\"급한 수정\",\"force\":true,\"forceReason\":\"현장 요청\",\"baseActiveVersionId\":null}";
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/deploy"), deploy)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        mvc.perform(as(org, admin, json(post("/core/scripts/" + scriptId + "/deploy"),
                        "{\"versionId\":\"" + draftId + "\",\"memo\":\"급한 수정\",\"force\":true,\"baseActiveVersionId\":null}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("forceReason"));
        mvc.perform(as(org, admin, json(post("/core/scripts/" + scriptId + "/deploy"), deploy)
                        .header("X-ACCESS-TOKEN-ID", "77").header("X-TOKEN-SCOPE", "read:devices")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, admin, json(post("/core/scripts/" + scriptId + "/deploy"), deploy))).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT forced FROM data2flow_core.script_versions WHERE id = :id").param("id", Long.parseLong(draftId))
                .query(Boolean.class).single()).isTrue();
        assertThat(auditCount(org, "SCRIPT_FORCE_DEPLOYED")).isEqualTo(1);
        mvc.perform(as(org, admin, get("/core/scripts/" + scriptId + "/versions/" + draftId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.forced").value(true)).andExpect(jsonPath("$.response.forceReason").value("현장 요청"));

        long other = fx.organization("other");
        long otherAdmin = fx.user(other, "other.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/scripts/" + scriptId))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SCRIPT_NOT_FOUND"));
        mvc.perform(as(other, otherAdmin, json(put("/core/scripts/" + scriptId + "/draft"), "{\"code\":\"x\",\"baseVersionNo\":1}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, post("/core/scripts/" + scriptId + "/enable"))).andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, get("/core/scripts"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0));
    }

    @Test
    @DisplayName("[SCR-02.05][AT-SCR-03.2][BR-SCR-16] 생성·저장·배포·롤백·연결·활성 전환·삭제마다 감사 1건(사용자·버전·메모) — TC-SCR-038")
    void auditTrail() throws Exception {
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + scriptId + "/draft"),
                "{\"code\":" + str(TRANSFORM_OK) + ",\"baseVersionNo\":1}"))).andExpect(status().isOk());
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/deploy"),
                "{\"versionId\":\"" + draftId + "\",\"memo\":\"v1 배포\",\"baseActiveVersionId\":null}"))).andExpect(status().isOk());
        String v2 = body(mvc.perform(as(org, integrator, json(put("/core/scripts/" + scriptId + "/draft"),
                "{\"code\":" + str(TRANSFORM_OK + "// v2\n") + ",\"baseVersionNo\":1}"))).andReturn()).get("response").get("versionId").asString();
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/deploy"),
                "{\"versionId\":\"" + v2 + "\",\"memo\":\"v2 배포\",\"baseActiveVersionId\":\"" + draftId + "\"}"))).andExpect(status().isOk());
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/deploy"),
                "{\"versionId\":\"" + draftId + "\",\"memo\":\"v1로 되돌림\",\"baseActiveVersionId\":\"" + v2 + "\"}"))).andExpect(status().isOk());
        mvc.perform(as(org, integrator, post("/core/scripts/" + scriptId + "/disable"))).andExpect(status().isOk());
        mvc.perform(as(org, integrator, post("/core/scripts/" + scriptId + "/enable"))).andExpect(status().isOk());
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + scriptId + "/bindings"), "{\"bindings\":[]}"))).andExpect(status().isOk());
        mvc.perform(as(org, integrator, delete("/core/scripts/" + scriptId)))
                .andExpect(status().isNoContent());
        for (String action : List.of("SCRIPT_CREATED", "SCRIPT_DEPLOYED", "SCRIPT_ROLLED_BACK", "SCRIPT_DISABLED", "SCRIPT_ENABLED",
                "SCRIPT_BINDING_CHANGED", "SCRIPT_DELETED")) {
            assertThat(auditCount(org, action)).as(action).isEqualTo(action.equals("SCRIPT_DEPLOYED") ? 2 : 1);
        }
        assertThat(auditCount(org, "SCRIPT_VERSION_SAVED")).isEqualTo(2);
        String rollback = jdbc.sql("""
                        SELECT actor_id || '|' || target_id || '|' || detail::text FROM data2flow_core.audit_logs
                         WHERE organization_id = :o AND action = 'SCRIPT_ROLLED_BACK'""")
                .param("o", org).query(String.class).single();
        assertThat(rollback).startsWith(integrator + "|" + scriptId + "|").contains("v1로 되돌림").contains("\"fromVersionNo\": 2")
                .contains("\"toVersionNo\": 1");
    }
}
