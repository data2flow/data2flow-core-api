package net.java21.data2flow.core.sink;

import net.java21.data2flow.core.alarm.AlarmItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SinkConnectionIT extends AlarmItSupport {

    static final String PG = """
            {"name":"분석 DB","type":"POSTGRESQL","config":{"host":"db.example","port":5432,"database":"lab","ssl":true},
             "secret":{"username":"writer","password":"pw-1"}}""";

    String create() throws Exception {
        MvcResult r = mvc.perform(as(org, admin, json(post("/core/sink-connections"), PG))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.config.username").value("writer"))
                .andExpect(jsonPath("$.response.config.password").doesNotExist())
                .andExpect(jsonPath("$.response.secretConfigured").value(true))
                .andExpect(jsonPath("$.response.status").value("UNTESTED")).andReturn();
        return read(r, "$.response.sinkConnectionId");
    }

    @Test
    @DisplayName("[FLW-04.01][TC-FLW-081] 연결 저장: 비밀값은 응답에 없고 암호문으로만, 내부 API-FLW-85는 복호화한 secrets, 설정 변경 SINK_CONNECTION")
    void createAndInternal() throws Exception {
        String id = create();
        byte[] enc = jdbc.sql("SELECT secret_enc FROM data2flow_core.sink_connections WHERE id = :id").param("id", Long.parseLong(id))
                .query(byte[].class).single();
        assertThat(new String(enc, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("pw-1");
        mvc.perform(get("/internal/core/sink-connections/" + id)).andExpect(jsonPath("$.response.secrets.password").value("pw-1"))
                .andExpect(jsonPath("$.response.config.username").value("writer"))
                .andExpect(jsonPath("$.response.organizationId").value(org));
        mvc.perform(get("/internal/core/sink-connections/999999")).andExpect(status().isNotFound());
        assertThat(configMessages(org)).anyMatch(m -> m.contains("\"SINK_CONNECTION\""));
        mvc.perform(as(org, admin, json(patch("/core/sink-connections/" + id), "{\"secret\":{\"password\":\"pw-2\"},\"baseVersion\":1}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.version").value(2));
        mvc.perform(get("/internal/core/sink-connections/" + id)).andExpect(jsonPath("$.response.secrets.password").value("pw-2"));
        mvc.perform(as(org, admin, json(patch("/core/sink-connections/" + id), "{\"name\":\"x\",\"baseVersion\":1}")))
                .andExpect(status().isConflict());
        mvc.perform(as(org, analyst, get("/core/sink-connections"))).andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.responses[0].usedFlowCount").value(0));
        mvc.perform(as(org, operator, json(post("/core/sink-connections"), PG))).andExpect(status().isForbidden());
        mvc.perform(as(org, admin, json(post("/core/sink-connections"), PG.replace("POSTGRESQL", "ORACLE")))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[FLW-04.01][TC-FLW-082] 연결 테스트 실패 → 502 SINK_CONNECTION_TEST_FAILED(종류 AUTH), 상태 ERROR가 남는다. 성공이면 OK")
    void connectionTest() throws Exception {
        String id = create();
        STUB.ok("POST", "/internal/action/sinks/connections/" + id + "/test", 200,
                "{\"ok\":false,\"error\":{\"kind\":\"AUTH\",\"message\":\"password authentication failed\"}}");
        mvc.perform(as(org, admin, post("/core/sink-connections/" + id + "/test"))).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.header.resultCode").value("SINK_CONNECTION_TEST_FAILED"))
                .andExpect(jsonPath("$.header.resultMessage").value("연결할 수 없습니다: AUTH"))
                .andExpect(jsonPath("$.errors[0].code").value("AUTH"));
        mvc.perform(as(org, admin, get("/core/sink-connections/" + id))).andExpect(jsonPath("$.response.status").value("ERROR"))
                .andExpect(jsonPath("$.response.lastError").value("AUTH: password authentication failed"));
        STUB.reset();
        STUB.ok("POST", "/internal/action/sinks/connections/test", 200, "{\"ok\":true,\"latencyMs\":12}");
        mvc.perform(as(org, admin, json(post("/core/sink-connections/test"), PG))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.latencyMs").value(12));
        assertThat(STUB.received("POST", "/internal/action/sinks/connections/test").getFirst().body()).contains("\"password\":\"pw-1\"")
                .contains("\"username\":\"writer\"");
        STUB.ok("GET", "/internal/action/sinks/connections/" + id + "/schema", 200, "{\"exists\":false,\"columns\":[]}");
        mvc.perform(as(org, analyst, get("/core/sink-connections/" + id + "/schema").param("target", "t"))).andExpect(jsonPath("$.response.exists")
                .value(false));
        STUB.ok("POST", "/internal/action/sinks/connections/" + id + "/dead-letters/resend", 200, "{\"requested\":1,\"resent\":1,\"failed\":0}");
        mvc.perform(as(org, admin, json(post("/core/sink-connections/" + id + "/dead-letters/resend"), "{\"all\":true}")))
                .andExpect(jsonPath("$.response.resent").value(1));
    }

    @Test
    @DisplayName("[FLW-04.01][TC-FLW-083] 플로우가 쓰는 연결은 삭제 409 SINK_CONNECTION_IN_USE, 안 쓰면 204")
    void deleteInUse() throws Exception {
        String id = create();
        String flow = java.util.UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO data2flow_core.flows (id, organization_id, name, status, active_version, created_by, updated_by)
                        VALUES (CAST(:id AS uuid), :org, 'sink', 'ACTIVE', 1, 0, 0)""").param("id", flow).param("org", org).update();
        jdbc.sql("""
                        INSERT INTO data2flow_core.flow_versions (flow_id, version_no, organization_id, state, definition, definition_hash, created_by)
                        VALUES (CAST(:id AS uuid), 1, :org, 'ACTIVE', CAST(:def AS jsonb), repeat('0', 64), 0)""")
                .param("id", flow).param("org", org)
                .param("def", "{\"nodes\":[{\"id\":\"n1\",\"type\":\"sink.database\",\"config\":{\"connectionId\":\"" + id + "\"}}],\"wires\":[]}")
                .update();
        mvc.perform(as(org, analyst, get("/core/sink-connections"))).andExpect(jsonPath("$.responses[0].usedFlowCount").value(1));
        mvc.perform(as(org, admin, delete("/core/sink-connections/" + id))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.resultCode").value("SINK_CONNECTION_IN_USE"))
                .andExpect(jsonPath("$.header.resultMessage").value("사용 중인 연결입니다"));
        jdbc.sql("UPDATE data2flow_core.flows SET status = 'DELETED' WHERE id = CAST(:id AS uuid)").param("id", flow).update();
        mvc.perform(as(org, admin, delete("/core/sink-connections/" + id))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get("/core/sink-connections/" + id))).andExpect(status().isNotFound());
    }
}
