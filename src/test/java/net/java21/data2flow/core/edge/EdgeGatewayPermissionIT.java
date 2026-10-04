package net.java21.data2flow.core.edge;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.support.LoopItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 엣지 권한(SRC_ADMIN·SRC_READ, BR-IAM-01) — TC-DSC-214·218 */
class EdgeGatewayPermissionIT extends LoopItSupport {

    @Test
    @DisplayName("[DSC-08.03][DSC-08.04][TC-DSC-214·218] ADMIN·INTEGRATOR 쓰기, OPERATOR 조회만(쓰기 403), VIEWER 403, 다른 조직 404")
    void matrix() throws Exception {
        long site = data.site(org, "본관");
        String body = "{\"name\":\"e\",\"siteId\":\"" + site + "\"}";
        String id = JsonPath.read(mvc.perform(as(org, admin, json(post("/core/edges"), body))).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString(), "$.response.id");
        mvc.perform(as(org, integrator, json(post("/core/edges"), body.replace("\"e\"", "\"e2\"")))).andExpect(status().isCreated());
        mvc.perform(as(org, operator, json(post("/core/edges"), body.replace("\"e\"", "\"e3\"")))).andExpect(status().isForbidden());
        mvc.perform(as(org, operator, get("/core/edges/" + id))).andExpect(status().isOk());
        mvc.perform(as(org, operator, post("/core/edges/" + id + "/restart"))).andExpect(status().isForbidden());
        mvc.perform(as(org, operator, json(post("/core/edges/" + id + "/updates"), "{\"toVersion\":\"1.1.0\"}"))).andExpect(status().isForbidden());
        mvc.perform(as(org, viewer, get("/core/edges"))).andExpect(status().isForbidden());
        long other = fx.organization("edge-other");
        long otherAdmin = fx.user(other, "edge.other", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/edges/" + id))).andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, post("/core/edges/" + id + "/revoke"))).andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, get("/core/edges/" + id + "/updates"))).andExpect(status().isNotFound());
    }
}
