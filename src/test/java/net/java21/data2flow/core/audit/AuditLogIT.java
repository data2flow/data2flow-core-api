package net.java21.data2flow.core.audit;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.common.MaintenanceJobs;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** IAM-06.01~06.04 감사 로그(INSERT 전용, 검색·상세·CSV, 외부 기록 접수)와 NFR-12.02 접속 기록 위·변조 방지 */
class AuditLogIT extends IntegrationTestSupport {

    @Autowired
    MaintenanceJobs jobs;

    private long org;
    private long admin;

    private void setUp() {
        org = fx.organization("audit");
        admin = fx.user(org, "boss.admin", "ADMIN");
    }

    private void record(String body) throws Exception {
        mvc.perform(json(post("/internal/core/audit-logs").header("X-CALLER-SERVICE", "data2flow-action"), body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.response.status").value("ACCEPTED"));
    }

    @Test
    @DisplayName("[IAM-06.02][AT-IAM-13.4][NFR-12.02] 애플리케이션 DB 계정의 UPDATE·DELETE·TRUNCATE는 거부된다 — TC-IAM-170·172, TC-NFR-070")
    void appendOnly() throws Exception {
        setUp();
        record("{\"organizationId\":" + org + ",\"actorType\":\"SERVICE\",\"actorId\":\"x\",\"action\":\"TEST_EVENT\",\"result\":\"SUCCESS\"}");
        for (String sql : new String[]{
                "UPDATE data2flow_core.audit_logs SET action = 'TAMPERED' WHERE organization_id = " + org,
                "DELETE FROM data2flow_core.audit_logs WHERE organization_id = " + org,
                "TRUNCATE data2flow_core.audit_logs"}) {
            assertThatThrownBy(() -> jdbc.sql(sql).update())
                    .isInstanceOf(DataAccessException.class)
                    .satisfies(ex -> assertThat(((SQLException) ex.getCause()).getSQLState()).isEqualTo("42501"));
        }
        assertThat(auditCount(org, "TEST_EVENT")).isEqualTo(1);
    }

    @Test
    @DisplayName("[IAM-06.04][AT-IAM-13.2] 자동 제어 감사 상세: actor_type=FLOW, flowId·버전·노드·트리거 메시지, 실행 기록 링크 — TC-IAM-168·174")
    void flowCause() throws Exception {
        setUp();
        record("""
                {"organizationId":%d,"actorType":"FLOW","actorId":"flow-1","actorName":"냉방 자동화","action":"DEVICE_COMMAND_SENT",
                 "targetType":"DEVICE","targetId":"42","result":"SUCCESS","detail":{"command":"ON"},
                 "cause":{"flowId":"flow-1","flowVersion":13,"nodeId":"n7","triggerMessageId":"m-99"},"ip":"10.0.0.5","requestId":"req-1"}"""
                .formatted(org));
        String list = mvc.perform(as(org, admin, get("/core/audit-logs").param("actorType", "FLOW")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses.length()").value(1))
                .andExpect(jsonPath("$.size").value(50))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(list, "$.responses[0].id");
        mvc.perform(as(org, admin, get("/core/audit-logs/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.actorType").value("FLOW"))
                .andExpect(jsonPath("$.response.cause.flowVersion").value(13))
                .andExpect(jsonPath("$.response.cause.executionUrl").value("/flows/flow-1/executions?triggerMessageId=m-99"))
                .andExpect(jsonPath("$.response.detail.callerService").value("data2flow-action"))
                .andExpect(jsonPath("$.response.ip").value("10.0.0.5"));
        long other = fx.organization("other");
        long otherAdmin = fx.user(other, "other.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, get("/core/audit-logs/" + id))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[IAM-06.01][AT-IAM-13.3] 비밀값은 기록 전에 *** 로 가린다(변경 전후 값) — TC-IAM-169")
    void secretsMasked() throws Exception {
        setUp();
        record("""
                {"organizationId":%d,"actorType":"USER","actorId":"%d","action":"SOURCE_UPDATED","targetType":"SOURCE","targetId":"3",
                 "result":"SUCCESS","detail":{"password":{"before":"old-pass","after":"new-pass"},"host":"mqtt.local"}}"""
                .formatted(org, admin));
        String detail = jdbc.sql("SELECT detail::text FROM data2flow_core.audit_logs WHERE organization_id = :o AND action = 'SOURCE_UPDATED'")
                .param("o", org).query(String.class).single();
        assertThat(detail).contains("***").doesNotContain("old-pass").doesNotContain("new-pass").contains("mqtt.local");
        String actorName = jdbc.sql("SELECT actor_name FROM data2flow_core.audit_logs WHERE organization_id = :o AND action = 'SOURCE_UPDATED'")
                .param("o", org).query(String.class).single();
        assertThat(actorName).isEqualTo("이름 boss.admin");
    }

    @Test
    @DisplayName("[IAM-06.03][AT-IAM-13.1] 기간·행위자·행위로 검색하고 커서로 다음 페이지를 이어 읽는다(최신순) — TC-IAM-166·173")
    void searchWithCursor() throws Exception {
        setUp();
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofMinutes(1));
            record("{\"organizationId\":" + org + ",\"actorType\":\"USER\",\"actorId\":\"" + admin + "\",\"action\":\"RULE_UPDATED\","
                    + "\"targetId\":\"r" + i + "\",\"result\":\"SUCCESS\",\"occurredAt\":\"" + clock.instant() + "\"}");
        }
        record("{\"organizationId\":" + org + ",\"actorType\":\"SYSTEM\",\"action\":\"OTHER_EVENT\",\"result\":\"FAILURE\"}");

        String page1 = mvc.perform(as(org, admin, get("/core/audit-logs").param("actor", "이름 boss").param("action", "rule_updated")
                        .param("size", "2").param("from", clock.instant().minus(Duration.ofDays(1)).toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses.length()").value(2))
                .andExpect(jsonPath("$.responses[0].targetId").value("r4"))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");
        String page2 = mvc.perform(as(org, admin, get("/core/audit-logs").param("actor", admin + "").param("action", "RULE_UPDATED")
                        .param("size", "2").param("cursor", cursor)))
                .andExpect(jsonPath("$.responses[0].targetId").value("r2"))
                .andReturn().getResponse().getContentAsString();
        String cursor2 = JsonPath.read(page2, "$.nextCursor");
        mvc.perform(as(org, admin, get("/core/audit-logs").param("action", "RULE_UPDATED").param("size", "2").param("cursor", cursor2)))
                .andExpect(jsonPath("$.responses.length()").value(1)).andExpect(jsonPath("$.nextCursor").doesNotExist());
        mvc.perform(as(org, admin, get("/core/audit-logs").param("result", "failure"))).andExpect(jsonPath("$.responses.length()").value(1));

        mvc.perform(as(org, admin, get("/core/audit-logs").param("from", "2024-01-01T00:00:00Z"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"));
        mvc.perform(as(org, admin, get("/core/audit-logs").param("cursor", "%%%"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, get("/core/audit-logs").param("actorType", "ROBOT"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, get("/core/audit-logs").param("ip", "not-ip"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, get("/core/audit-logs").param("result", "MAYBE"))).andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, get("/core/audit-logs").param("ip", "10.0.0.1"))).andExpect(status().isOk());
        long viewer = fx.user(org, "view.user", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/audit-logs"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[IAM-06.03] CSV 내보내기: UTF-8 BOM, 수식 주입 방지, 감사 AUDIT_EXPORTED")
    void exportCsv() throws Exception {
        setUp();
        record("{\"organizationId\":" + org + ",\"actorType\":\"USER\",\"actorId\":\"" + admin + "\",\"actorName\":\"=cmd|calc\","
                + "\"action\":\"RULE_UPDATED\",\"result\":\"SUCCESS\"}");
        byte[] csv = mvc.perform(as(org, admin, json(post("/core/audit-logs/export"), "{\"action\":\"RULE_UPDATED\"}")))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿id,occurredAt").contains("\"'=cmd|calc\"").contains("RULE_UPDATED");
        assertThat(auditCount(org, "AUDIT_EXPORTED")).isEqualTo(1);
        mvc.perform(as(org, admin, post("/core/audit-logs/export"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("[IAM-06.01] 외부 기록 접수 검증: 조직·행위 형식·결과 값이 틀리면 400, 조직이 없으면 X-ORG-ID를 쓴다")
    void externalValidation() throws Exception {
        setUp();
        mvc.perform(json(post("/internal/core/audit-logs"), "{\"actorType\":\"USER\",\"action\":\"X_Y\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("organizationId"));
        mvc.perform(json(post("/internal/core/audit-logs"), "{\"organizationId\":1,\"actorType\":\"USER\",\"action\":\"bad\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/internal/core/audit-logs"), "{\"organizationId\":1,\"actorType\":\"ALIEN\",\"action\":\"X_Y\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("actorType"));
        mvc.perform(as(org, admin, json(post("/internal/core/audit-logs"), "{\"actorType\":\"USER\",\"action\":\"ON_BEHALF\",\"result\":\"DENIED\"}")))
                .andExpect(status().isAccepted());
        assertThat(auditCount(org, "ON_BEHALF")).isEqualTo(1);
    }

    @Test
    @DisplayName("[IAM-06.02][ERD §10] 정리 작업은 감사 로그 월 파티션을 3개월 앞까지 만든다(멱등)")
    void partitionsCreated() {
        assertThat(jobs.runOnce()).isTrue();
        assertThat(jobs.runOnce()).isTrue();
        Long count = jdbc.sql("""
                SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                 WHERE i.inhparent = 'data2flow_core.audit_logs'::regclass AND c.relname IN
                   ('audit_logs_y2026m10', 'audit_logs_y2026m11', 'audit_logs_y2026m12', 'audit_logs_y2027m01')""")
                .query(Long.class).single();
        assertThat(count).isEqualTo(4);
    }
}
