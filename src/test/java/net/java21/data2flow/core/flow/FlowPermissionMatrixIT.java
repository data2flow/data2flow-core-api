package net.java21.data2flow.core.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * FLW-05.06 · IAM-04.04: 역할 × 플로우 API 권한 표(허용 2xx, 거부 403 PERMISSION_DENIED, 다른 조직 플로우 404 FLOW_NOT_FOUND) — TC-FLW-116.
 * FLOW_READ(ANALYST+): 목록·상세·버전, FLOW_WRITE(OPERATOR+): 초안·검증·적용(제어 노드 없음)·템플릿, FLOW_DEPLOY_CONTROL(INTEGRATOR+): 제어 노드 적용,
 * FLOW_APPROVE(ADMIN): 승인.
 */
class FlowPermissionMatrixIT extends FlowItSupport {

    record Call(String name, HttpMethod method, String path, String body) {
    }

    @Test
    @DisplayName("[FLW-05.06][AT-FLW-01.3][AT-FLW-03.5] 역할 × API 매트릭스(2개 조직, 5개 역할) — TC-FLW-116·110·111")
    void matrix() throws Exception {
        String monitor = create(admin, "감시", monitorOnly(lab));
        String control = create(admin, "냉방", hotThenCool(lab, 27, 24));
        long other = fx.organization("other");
        long otherAdmin = fx.user(other, "other.admin", "ADMIN");
        List<Call> calls = List.of(
                new Call("list", HttpMethod.GET, "/core/flows", null),
                new Call("detail", HttpMethod.GET, "/core/flows/" + monitor, null),
                new Call("versions", HttpMethod.GET, "/core/flows/" + monitor + "/versions", null),
                new Call("nodes", HttpMethod.GET, "/core/flow-nodes", null),
                new Call("validate", HttpMethod.POST, "/core/flows/" + monitor + "/validate", "{}"),
                new Call("templates", HttpMethod.GET, "/core/flow-templates", null),
                new Call("draft", HttpMethod.PUT, "/core/flows/" + monitor + "/draft", "{\"baseVersion\":1,\"definition\":" + monitorOnly(lab) + "}"),
                new Call("apply", HttpMethod.POST, "/core/flows/" + monitor + "/apply", "{\"version\":%d,\"baseVersion\":0}"),
                new Call("applyControl", HttpMethod.POST, "/core/flows/" + control + "/apply",
                        "{\"version\":1,\"baseVersion\":0,\"acknowledgedRisks\":true}"));
        Map<String, Map<String, Integer>> expected = Map.of(
                "VIEWER", Map.of("list", 403, "detail", 403, "versions", 403, "nodes", 403, "validate", 403, "templates", 403, "draft", 403,
                        "apply", 403, "applyControl", 403),
                "ANALYST", Map.of("list", 200, "detail", 200, "versions", 200, "nodes", 200, "validate", 200, "templates", 403, "draft", 403,
                        "apply", 403, "applyControl", 403),
                "OPERATOR", Map.of("list", 200, "detail", 200, "versions", 200, "nodes", 200, "validate", 200, "templates", 200, "draft", 200,
                        "apply", 200, "applyControl", 403),
                "INTEGRATOR", Map.of("list", 200, "detail", 200, "versions", 200, "nodes", 200, "validate", 200, "templates", 200,
                        "draft", 200, "apply", 200, "applyControl", 200));
        Map<String, Long> users = Map.of("VIEWER", viewer, "ANALYST", analyst, "OPERATOR", operator, "INTEGRATOR", integrator);
        for (String role : List.of("VIEWER", "ANALYST", "OPERATOR", "INTEGRATOR")) {
            for (Call c : calls) {
                String body = c.body();
                if ("apply".equals(c.name())) {
                    Integer draft = jdbc.sql("SELECT draft_version FROM data2flow_core.flows WHERE id = CAST(:id AS uuid)")
                            .param("id", monitor).query(Integer.class).optional().orElse(null);
                    Integer active = jdbc.sql("SELECT active_version FROM data2flow_core.flows WHERE id = CAST(:id AS uuid)")
                            .param("id", monitor).query(Integer.class).optional().orElse(null);
                    body = "{\"version\":" + (draft == null ? 1 : draft) + ",\"baseVersion\":" + (active == null ? 0 : active) + "}";
                }
                if ("draft".equals(c.name())) {
                    Integer draft = jdbc.sql("SELECT coalesce(draft_version, active_version) FROM data2flow_core.flows WHERE id = CAST(:id AS uuid)")
                            .param("id", monitor).query(Integer.class).single();
                    body = "{\"baseVersion\":" + draft + ",\"definition\":" + monitorOnly(lab) + "}";
                }
                var req = request(c.method(), c.path());
                int status = mvc.perform(as(org, users.get(role), body == null ? req : json(req, body))).andReturn().getResponse().getStatus();
                assertThat(status).as(role + " " + c.name()).isEqualTo(expected.get(role).get(c.name()));
            }
        }
        // 다른 조직: 같은 플로우 ID는 404 FLOW_NOT_FOUND
        int status = mvc.perform(as(other, otherAdmin, request(HttpMethod.GET, "/core/flows/" + monitor))).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(404);
        int instantiate = mvc.perform(as(other, otherAdmin, json(request(HttpMethod.POST, "/core/flow-templates/hot-then-cool/instantiate"),
                "{\"name\":\"남의 공간\",\"params\":{\"spaceId\":\"" + lab + "\"}}"))).andReturn().getResponse().getStatus();
        assertThat(instantiate).isEqualTo(404);
        int approve = mvc.perform(as(org, integrator, request(HttpMethod.POST, "/core/flow-approvals/1/approve"))).andReturn().getResponse()
                .getStatus();
        assertThat(approve).isEqualTo(403);
    }
}
