package net.java21.data2flow.core.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEV-03.01·03.02 권한 매트릭스(IAM-04.05): 조회 DEV_READ(VIEWER+), 쓰기 DEV_ADMIN(INTEGRATOR+) — TC-DEV-092·098 */
class DeviceModelPermissionIT extends CatalogItSupport {

    static final List<String> WRITERS = List.of("ADMIN", "INTEGRATOR");
    static final List<String> READERS_ONLY = List.of("OPERATOR", "ANALYST", "VIEWER");

    @Test
    @DisplayName("[DEV-03.01][IAM-04.05] ADMIN·INTEGRATOR 생성 201, OPERATOR·ANALYST·VIEWER 403 PERMISSION_DENIED, 모든 역할 조회 200 — TC-DEV-092·098")
    void roleMatrix() throws Exception {
        long org = fx.organization("perm-model");
        seeder.seed(org);
        long em300 = modelId(org, "EM300-TH");
        int n = 0;
        for (String role : WRITERS) {
            long user = fx.user(org, "writer.u" + n, role);
            mvc.perform(as(org, user, json(post("/core/device-models"), """
                            {"code":"M-%d","vendor":"v","name":"모델","protocol":"MQTT","kind":"SENSOR","metrics":[{"key":"temperature"}]}"""
                            .formatted(n++)))).andExpect(status().isCreated());
            mvc.perform(as(org, user, json(post("/core/device-models/" + em300 + "/clone"), "{\"newCode\":\"C-" + n + "\"}")))
                    .andExpect(status().isCreated());
        }
        long custom = modelId(org, "M-0");
        for (String role : READERS_ONLY) {
            long user = fx.user(org, "reader.u" + n++, role);
            mvc.perform(as(org, user, get("/core/device-models"))).andExpect(status().isOk());
            mvc.perform(as(org, user, get("/core/device-models/" + em300))).andExpect(status().isOk());
            mvc.perform(as(org, user, json(post("/core/device-models"), """
                            {"code":"X-1","vendor":"v","name":"모델","protocol":"MQTT","kind":"SENSOR","metrics":[{"key":"temperature"}]}""")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
            mvc.perform(as(org, user, json(patch("/core/device-models/" + custom), "{\"name\":\"x\",\"baseVersion\":0}")))
                    .andExpect(status().isForbidden());
            mvc.perform(as(org, user, json(put("/core/device-models/" + custom + "/package"), "{}"))).andExpect(status().isForbidden());
            mvc.perform(as(org, user, post("/core/device-models/" + custom + "/deprecate"))).andExpect(status().isForbidden());
            mvc.perform(as(org, user, delete("/core/device-models/" + custom))).andExpect(status().isForbidden());
            mvc.perform(as(org, user, json(post("/core/device-models/" + em300 + "/clone"), "{\"newCode\":\"Z-1\"}")))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("[DEV-03.01][BR-IAM-01][BR-DEV-25] 다른 조직의 모델 ID는 404 MODEL_NOT_FOUND, 사용 기기 수는 권한 공간 안의 기기만 — TC-DEV-092")
    void organizationAndSpaceScope() throws Exception {
        long org = fx.organization("perm-a");
        long other = fx.organization("perm-b");
        seeder.seed(org);
        seeder.seed(other);
        long admin = fx.user(org, "a.admin", "ADMIN");
        long otherModel = modelId(other, "EM300-TH");
        mvc.perform(as(org, admin, get("/core/device-models/" + otherModel)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("MODEL_NOT_FOUND"));
        mvc.perform(as(org, admin, json(patch("/core/device-models/" + otherModel), "{\"name\":\"x\",\"baseVersion\":0}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(org, admin, delete("/core/device-models/" + otherModel))).andExpect(status().isNotFound());
        mvc.perform(as(org, admin, json(post("/core/device-models/" + otherModel + "/clone"), "{\"newCode\":\"STEAL\"}")))
                .andExpect(status().isNotFound());

        long site = data.site(org, "본관");
        long roomA = data.space(org, site, "ROOM", "실습실");
        long roomB = data.space(org, site, "ROOM", "사무실");
        long source = source(org, "lab");
        long em300 = modelId(org, "EM300-TH");
        data.device(org, source, "a1", "ACTIVE", roomA, em300);
        data.device(org, source, "a2", "ACTIVE", roomA, em300);
        data.device(org, source, "b1", "ACTIVE", roomB, em300);
        data.device(org, source, "gone", "DELETED", roomA, em300);
        long scoped = fx.user(org, "scoped.viewer", "VIEWER");
        data.spaceScope(org, scoped, List.of(roomA));
        mvc.perform(as(org, scoped, get("/core/device-models/" + em300))).andExpect(jsonPath("$.response.deviceCount").value(2));
        mvc.perform(as(org, scoped, get("/core/device-models?code=EM300-TH"))).andExpect(jsonPath("$.responses[0].deviceCount").value(2));
        mvc.perform(as(org, admin, get("/core/device-models/" + em300))).andExpect(jsonPath("$.response.deviceCount").value(3));
    }
}
