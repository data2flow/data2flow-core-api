package net.java21.data2flow.core.output;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.support.LoopItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 출력 연결 권한(SRC_ADMIN·SRC_READ, BR-IAM-01) — TC-DSC-129 */
class OutputConnectionPermissionIT extends LoopItSupport {

    static final String BODY = """
            {"name":"훅","type":"WEBHOOK","target":{"url":"https://hooks.example.com/d2f"},"secret":{"HMAC_KEY":"k-1"}}""";

    @Test
    @DisplayName("[DSC-04.01][TC-DSC-129] ADMIN·INTEGRATOR 쓰기 허용, OPERATOR 조회만(쓰기 403 PERMISSION_DENIED), VIEWER·ANALYST 403, 다른 조직 자원 404")
    void matrix() throws Exception {
        String id = JsonPath.read(mvc.perform(as(org, admin, json(post("/core/output-connections"), BODY))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.response.id");
        mvc.perform(as(org, integrator, json(post("/core/output-connections"), BODY.replace("훅", "훅2")))).andExpect(status().isCreated());
        mvc.perform(as(org, operator, json(post("/core/output-connections"), BODY.replace("훅", "훅3")))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        mvc.perform(as(org, operator, json(patch("/core/output-connections/" + id), "{\"enabled\":false,\"baseVersion\":1}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(org, operator, get("/core/output-connections"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(2));
        mvc.perform(as(org, operator, delete("/core/output-connections/" + id))).andExpect(status().isForbidden());
        mvc.perform(as(org, viewer, get("/core/output-connections"))).andExpect(status().isForbidden());
        mvc.perform(as(org, analyst, json(post("/core/output-connections/" + id + "/replay-failed"), "{\"from\":\"2026-10-01T00:00:00Z\"}")))
                .andExpect(status().isForbidden());
        long other = fx.organization("other-out");
        long otherAdmin = fx.user(other, "other.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/output-connections/" + id))).andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, json(patch("/core/output-connections/" + id), "{\"enabled\":false,\"baseVersion\":1}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, delete("/core/output-connections/" + id))).andExpect(status().isNotFound());
        mvc.perform(as(other, otherAdmin, get("/core/output-connections/" + id + "/stats"))).andExpect(status().isNotFound());
    }
}
