package net.java21.data2flow.core.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IAM-04.05 권한 매트릭스(DSC): SRC_READ = ADMIN·INTEGRATOR·OPERATOR, SRC_ADMIN = ADMIN·INTEGRATOR, ANALYST·VIEWER는 DSC 전체 403,
 * 다른 조직 소스는 404 — TC-DSC-005·014·029·036·044·052·059·074·090·098·167·179·232
 */
class DataSourcePermissionIT extends SourceItSupport {

    @Test
    @DisplayName("[DSC-01.01][AT-DSC-01.6][AT-DSC-12.5] 조회는 OPERATOR 이상, 관리는 INTEGRATOR 이상, ANALYST·VIEWER 403, 다른 조직 404 — TC-DSC-005")
    void matrix() throws Exception {
        long analyst = fx.user(org, "dsc.analyst", "ANALYST");
        long viewer = fx.user(org, "dsc.viewer", "VIEWER");
        long id = createSource(mqttBody("perm", null));
        long other = fx.organization("perm-other");
        long otherAdmin = fx.user(other, "perm.other", "ADMIN");

        String[] reads = {"/core/sources", "/core/sources/" + id, "/core/sources/" + id + "/stats", "/core/sources/" + id + "/runtime",
                "/core/sources/" + id + "/usage", "/core/sources/" + id + "/ignore-list", "/core/connectors", "/core/connectors/mqtt/schema",
                "/core/connector-templates/chirpstack-v4", "/core/platform-broker", "/core/source-limits"};
        for (String path : reads) {
            for (long user : new long[]{admin, integrator, operator}) {
                mvc.perform(as(org, user, req(HttpMethod.GET, path))).andExpect(status().isOk());
            }
            for (long user : new long[]{analyst, viewer}) {
                mvc.perform(as(org, user, req(HttpMethod.GET, path))).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
            }
        }
        // 관리(SRC_ADMIN): OPERATOR 403
        mvc.perform(as(org, operator, json(req(HttpMethod.POST, "/core/sources"), mqttBody("op", null)))).andExpect(status().isForbidden());
        mvc.perform(as(org, operator, json(req(HttpMethod.PATCH, "/core/sources/" + id), "{\"name\":\"x\",\"baseVersion\":0}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, operator, json(req(HttpMethod.POST, "/core/sources/" + id + "/activate"), "{\"baseVersion\":0}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, operator, req(HttpMethod.DELETE, "/core/sources/" + id))).andExpect(status().isForbidden());
        mvc.perform(as(org, operator, json(req(HttpMethod.PUT, "/core/sources/" + id + "/secret"), "{\"kind\":\"HEADER\",\"value\":\"x\"}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, operator, json(req(HttpMethod.POST, "/core/sources/test"), mqttBody("t", null)))).andExpect(status().isForbidden());
        mvc.perform(as(org, operator, req(HttpMethod.GET, "/core/stream/sources/" + id + "/live"))).andExpect(status().isForbidden());
        mvc.perform(as(org, operator, req(HttpMethod.DELETE, "/core/sources/" + id + "/ignore-list/x"))).andExpect(status().isForbidden());
        // 한도 변경은 ADMIN(OPS_MANAGE)만
        mvc.perform(as(org, integrator, json(req(HttpMethod.PATCH, "/core/source-limits"), "{\"maxSources\":60,\"baseVersion\":0}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, admin, json(req(HttpMethod.PATCH, "/core/sources/" + id), "{\"name\":\"관리자 수정\",\"baseVersion\":0}")))
                .andExpect(status().isOk());

        // 다른 조직 자원 ID는 404(존재를 숨김)
        for (String path : new String[]{"/core/sources/" + id, "/core/sources/" + id + "/stats", "/core/sources/" + id + "/runtime",
                "/core/sources/" + id + "/usage", "/core/sources/" + id + "/ignore-list"}) {
            mvc.perform(as(other, otherAdmin, req(HttpMethod.GET, path))).andExpect(status().isNotFound());
        }
        mvc.perform(as(other, otherAdmin, json(req(HttpMethod.PATCH, "/core/sources/" + id), "{\"name\":\"x\",\"baseVersion\":1}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, json(req(HttpMethod.POST, "/core/sources/" + id + "/pause"), "{\"baseVersion\":1}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, req(HttpMethod.DELETE, "/core/sources/" + id))).andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, req(HttpMethod.GET, "/core/stream/sources/" + id + "/live"))).andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, json(req(HttpMethod.POST, "/core/sources/" + id + "/test"), "{}"))).andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, req(HttpMethod.GET, "/core/sources"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));
    }
}
