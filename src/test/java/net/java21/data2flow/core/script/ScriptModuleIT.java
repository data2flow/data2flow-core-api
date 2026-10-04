package net.java21.data2flow.core.script;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 공유 모듈(SCR-04.01, BR-SCR-13·15, AT-SCR-08.1, API-SCR-18·19)과 실행 묶음 modules·moduleRefs(API-SCR-32) */
class ScriptModuleIT extends ScriptItSupport {

    static final String MODULE_CODE = "export function parseChannels(b) { return []; }";

    private long org;
    private long integrator;

    @BeforeEach
    void setUp() {
        org = fx.organization("mod");
        integrator = fx.user(org, "mod.integrator", "INTEGRATOR");
    }

    private String createModule(String name) throws Exception {
        return body(mvc.perform(as(org, integrator, json(post("/core/script-modules"),
                        "{\"name\":\"" + name + "\",\"description\":\"Milesight 채널 해석\",\"code\":" + str(MODULE_CODE) + "}")))
                .andExpect(status().isCreated()).andReturn()).get("response").get("id").asString();
    }

    @Test
    @DisplayName("[SCR-04.01][AT-SCR-08.1][BR-SCR-13] 생성(DRAFT) → 배포 v1(불변) → 코드 수정은 새 DRAFT v2, 이름 형식·중복 400, 실행 묶음에 배포된 버전만 — TC-SCR-068(core)")
    void releaseAndBundle() throws Exception {
        String id = createModule("milesight");
        mvc.perform(as(org, integrator, json(post("/core/script-modules"), "{\"name\":\"Bad Name\",\"code\":\"x\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("name"));
        mvc.perform(as(org, integrator, json(post("/core/script-modules"), "{\"name\":\"milesight\",\"code\":\"x\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        mvc.perform(as(org, integrator, post("/core/script-modules/" + id + "/release")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.versionNo").value(1));
        mvc.perform(as(org, integrator, post("/core/script-modules/" + id + "/release")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_VERSION_CONFLICT"));
        mvc.perform(as(org, integrator, json(put("/core/script-modules/" + id), "{\"code\":\"export const v = 2;\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.draftCode").value("export const v = 2;"))
                .andExpect(jsonPath("$.response.latestVersionNo").value(1))
                .andExpect(jsonPath("$.response.versions.length()").value(2))
                .andExpect(jsonPath("$.response.versions[1].status").value("DRAFT"));
        JsonNode bundle = body(mvc.perform(get("/internal/core/scripts/runtime-bundle?organizationId=" + org)).andReturn()).get("response");
        assertThat(bundle.get("modules")).hasSize(1);
        assertThat(bundle.get("modules").get(0).get("name").asString()).isEqualTo("milesight");
        assertThat(bundle.get("modules").get(0).get("versionNo").asInt()).isEqualTo(1);
        assertThat(bundle.get("modules").get(0).get("code").asString()).isEqualTo(MODULE_CODE);
        mvc.perform(as(org, integrator, get("/core/script-modules")))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].latestVersionNo").value(1));
        assertThat(outboxConfigCount(org)).isEqualTo(1);
    }

    @Test
    @DisplayName("[SCR-04.01][BR-SCR-15] 스크립트 DRAFT가 'module:milesight@1'을 가져오면 사용처·moduleRefs에 나오고, 쓰는 버전 삭제는 409 SCRIPT_MODULE_IN_USE, 안 쓰는 버전 삭제 204 — TC-SCR-069")
    void usageAndDelete() throws Exception {
        String id = createModule("milesight");
        mvc.perform(as(org, integrator, post("/core/script-modules/" + id + "/release"))).andExpect(status().isCreated());
        mvc.perform(as(org, integrator, json(put("/core/script-modules/" + id), "{\"code\":\"export const v = 2;\"}")));
        mvc.perform(as(org, integrator, post("/core/script-modules/" + id + "/release"))).andExpect(status().isCreated());
        JsonNode created = create(org, integrator, "{\"name\":\"디코더\",\"kind\":\"DECODE\"}");
        String scriptId = created.get("id").asString();
        String code = "import { parseChannels } from 'module:milesight@1';\nfunction decode(input, ctx) {\n  return null;\n}\n";
        mvc.perform(as(org, integrator, json(put("/core/scripts/" + scriptId + "/draft"), "{\"code\":" + str(code) + ",\"baseVersionNo\":1}")))
                .andExpect(status().isOk());
        mvc.perform(as(org, integrator, get("/core/script-modules/" + id + "/usage")))
                .andExpect(jsonPath("$.response.scripts.length()").value(1))
                .andExpect(jsonPath("$.response.scripts[0].scriptId").value(scriptId))
                .andExpect(jsonPath("$.response.scripts[0].versionNo").value(1));
        mvc.perform(as(org, integrator, get("/core/script-modules"))).andExpect(jsonPath("$.responses[0].usedBy").value(1));
        mvc.perform(as(org, integrator, delete("/core/script-modules/" + id + "/versions/1")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SCRIPT_MODULE_IN_USE"));
        mvc.perform(as(org, integrator, delete("/core/script-modules/" + id + "/versions/2"))).andExpect(status().isNoContent());
        mvc.perform(as(org, integrator, delete("/core/script-modules/" + id + "/versions/7"))).andExpect(status().isNotFound());
        // 배포하면 실행 묶음 moduleRefs에 버전 고정 참조가 실린다
        String draft = jdbc.sql("SELECT id::text FROM data2flow_core.script_versions WHERE script_id = :s AND status = 'DRAFT'")
                .param("s", Long.parseLong(scriptId)).query(String.class).single();
        mvc.perform(as(org, integrator, json(post("/core/scripts/" + scriptId + "/deploy"),
                "{\"versionId\":\"" + draft + "\",\"memo\":\"모듈 사용\",\"baseActiveVersionId\":null}"))).andExpect(status().isOk());
        JsonNode bundle = body(mvc.perform(get("/internal/core/scripts/runtime-bundle?organizationId=" + org)).andReturn()).get("response");
        assertThat(bundle.get("scripts").get(0).get("moduleRefs").get(0).asString()).isEqualTo("milesight@1");
        long other = fx.organization("mod2");
        long otherUser = fx.user(other, "mod2.integrator", "INTEGRATOR");
        mvc.perform(as(other, otherUser, get("/core/script-modules/" + id))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("SCRIPT_MODULE_NOT_FOUND"));
        long operator = fx.user(org, "mod.operator", "OPERATOR");
        mvc.perform(as(org, operator, get("/core/script-modules/" + id))).andExpect(status().isOk());
        mvc.perform(as(org, operator, post("/core/script-modules/" + id + "/release"))).andExpect(status().isForbidden());
    }
}
