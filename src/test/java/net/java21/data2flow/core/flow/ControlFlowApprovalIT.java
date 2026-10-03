package net.java21.data2flow.core.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FLW-05.06·UC-FLW-23: 제어 노드 배포 권한(FLOW_DEPLOY_CONTROL)과 조직 설정 "제어 노드 배포 승인". 승인 대기 동안 실행 중 버전은 그대로이고,
 * 승인하면 원자적으로 바뀌며 감사에 요청자·승인자가 남는다.
 */
class ControlFlowApprovalIT extends FlowItSupport {

    @Test
    @DisplayName("[FLW-05.06][AT-FLW-03.5] 제어 노드를 더한 적용: FLOW_DEPLOY_CONTROL 없으면 403 + 감사 ACCESS_DENIED, 대상 공간이 범위 밖이면 404 — TC-FLW-117")
    void deployControlPermission() throws Exception {
        String flowId = create(operator, "운영자 플로우", hotThenCool(lab, 27, 24));
        mvc.perform(as(org, operator, json(post("/core/flows/" + flowId + "/apply"),
                        "{\"version\":1,\"baseVersion\":0,\"acknowledgedRisks\":true}")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        assertThat(auditCount(org, "ACCESS_DENIED")).isEqualTo(1);
        // 공간 범위가 다른 공간만인 통합 담당자: 대상(실습실)이 범위 밖 → 404
        long otherSite = data.site(org, "별관");
        String visible = create(integrator, "범위 확인", hotThenCool(lab, 27, 24));
        data.spaceScope(org, integrator, List.of(otherSite));
        mvc.perform(as(org, integrator, get("/core/flows/" + visible))).andExpect(status().isNotFound());
        data.spaceScope(org, integrator, List.of(lab, otherSite));
        jdbc.sql("UPDATE data2flow_core.flows SET related_space_ids = CAST(:s AS bigint[]) WHERE id = CAST(:id AS uuid)")
                .param("s", "{" + otherSite + "}").param("id", visible).update();
        String outside = hotThenCool(otherSite, 27, 24).replace("\"spaceId\":\"" + otherSite + "\",\"relation\":\"controls\"",
                "\"spaceId\":\"" + site + "\",\"relation\":\"controls\"");
        mvc.perform(as(org, integrator, json(put("/core/flows/" + visible + "/draft"), "{\"baseVersion\":1,\"definition\":" + outside + "}")))
                .andExpect(status().isOk());
        mvc.perform(as(org, integrator, json(post("/core/flows/" + visible + "/apply"),
                        "{\"version\":2,\"baseVersion\":0,\"acknowledgedRisks\":true}")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[FLW-05.06][AT-FLW-23.1][AT-FLW-23.2] 승인 필요 조직: 적용 요청 → 202 FLOW_APPROVAL_REQUIRED(PENDING_APPROVAL, 실행 버전 유지) → ADMIN 승인 → 원자적 전환·감사(요청자·승인자) — TC-FLW-114·115·120")
    void approvalFlow() throws Exception {
        jdbc.sql("INSERT INTO data2flow_core.control_settings (organization_id, require_approval_for_control_nodes) VALUES (:org, true)")
                .param("org", org).update();
        String flowId = create(integrator, "승인 플로우", monitorOnly(lab));
        mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/apply"), "{\"version\":1,\"baseVersion\":0}")))
                .andExpect(status().isOk());
        mvc.perform(as(org, integrator, json(put("/core/flows/" + flowId + "/draft"),
                        "{\"baseVersion\":1,\"definition\":" + hotThenCool(lab, 27, 24) + "}")))
                .andExpect(status().isOk());
        mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/validate"), "{}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.approvalRequired").value(true));
        MvcResult pending = mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/apply"),
                        "{\"version\":2,\"baseVersion\":1,\"acknowledgedRisks\":true,\"memo\":\"냉방 추가\"}")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.header.resultCode").value("FLOW_APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.header.resultMessage").value("승인 요청을 보냈습니다")).andReturn();
        String approvalId = read(pending, "$.response.approvalId");
        mvc.perform(as(org, integrator, get("/core/flows/" + flowId)))
                .andExpect(jsonPath("$.response.flow.activeVersion").value(1)).andExpect(jsonPath("$.response.flow.status").value("ACTIVE"));
        mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/apply"),
                        "{\"version\":2,\"baseVersion\":1,\"acknowledgedRisks\":true}")))
                .andExpect(status().isConflict());
        // 목록: 요청자는 자기 것, 승인자는 전체
        mvc.perform(as(org, integrator, get("/core/flows/approvals").param("status", "PENDING")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].flowName").value("승인 플로우"))
                .andExpect(jsonPath("$.responses[0].hasControlNode").value(true))
                .andExpect(jsonPath("$.responses[0].memo").value("냉방 추가"));
        mvc.perform(as(org, operator, get("/core/flows/approvals"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(as(org, viewer, get("/core/flows/approvals"))).andExpect(status().isForbidden());
        mvc.perform(as(org, integrator, post("/core/flow-approvals/" + approvalId + "/approve"))).andExpect(status().isForbidden());

        mvc.perform(as(org, admin, post("/core/flow-approvals/" + approvalId + "/approve")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("APPROVED"))
                .andExpect(jsonPath("$.response.appliedVersion").value(2))
                .andExpect(jsonPath("$.response.decidedBy.userId").value(Long.toString(admin)));
        mvc.perform(as(org, integrator, get("/core/flows/" + flowId)))
                .andExpect(jsonPath("$.response.flow.activeVersion").value(2));
        String detail = jdbc.sql("SELECT detail::text FROM data2flow_core.audit_logs WHERE organization_id = :org AND action = 'FLOW_APPROVED'")
                .param("org", org).query(String.class).single();
        assertThat(detail).contains("\"requestedBy\": \"" + integrator + "\"").contains("\"approvedBy\": \"" + admin + "\"");
        mvc.perform(as(org, admin, post("/core/flow-approvals/" + approvalId + "/approve")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("FLOW_STATE_CONFLICT"));
        mvc.perform(as(org, admin, post("/core/flow-approvals/999999/approve"))).andExpect(status().isNotFound());

        // 반려: 사유 필수, 버전 REJECTED, 실행 버전 그대로
        mvc.perform(as(org, integrator, json(put("/core/flows/" + flowId + "/draft"),
                        "{\"baseVersion\":2,\"definition\":" + hotThenCool(lab, 29, 22) + "}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.draftVersion").value(3));
        String second = read(mvc.perform(as(org, integrator, json(post("/core/flows/" + flowId + "/apply"),
                "{\"version\":3,\"baseVersion\":2,\"acknowledgedRisks\":true}"))).andExpect(status().isAccepted()).andReturn(),
                "$.response.approvalId");
        mvc.perform(as(org, admin, json(post("/core/flow-approvals/" + second + "/reject"), "{}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        mvc.perform(as(org, admin, json(post("/core/flow-approvals/" + second + "/reject"), "{\"reason\":\"설정 온도가 너무 낮음\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("REJECTED"))
                .andExpect(jsonPath("$.response.reason").value("설정 온도가 너무 낮음"));
        mvc.perform(as(org, admin, get("/core/flows/" + flowId + "/versions")))
                .andExpect(jsonPath("$.responses[0].state").value("REJECTED")).andExpect(jsonPath("$.responses[1].state").value("ACTIVE"));
        mvc.perform(as(org, admin, get("/core/flows/approvals").param("status", "REJECTED")))
                .andExpect(jsonPath("$.totalCount").value(1));
        assertThat(auditCount(org, "FLOW_APPROVAL_REQUESTED")).isEqualTo(2);
        assertThat(auditCount(org, "FLOW_APPROVAL_REJECTED")).isEqualTo(1);
    }
}
