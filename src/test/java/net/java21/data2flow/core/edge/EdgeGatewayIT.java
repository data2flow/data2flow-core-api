package net.java21.data2flow.core.edge;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.EdgeEvent;
import net.java21.data2flow.core.support.LoopItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 엣지 등록·설정 배포·업데이트·원격 명령(DSC-08.03·08.04, API-DSC-62·64·65·67·78·79, EVT-DSC-10) — TC-DSC-213·217 */
class EdgeGatewayIT extends LoopItSupport {

    static final String FP = "a".repeat(64);
    long site;

    @BeforeEach
    void site() {
        site = data.site(org, "본관");
    }

    private String[] createEdge(String name) throws Exception {
        String body = mvc.perform(as(org, integrator, json(post("/core/edges"), "{\"name\":\"" + name + "\",\"siteId\":\"" + site + "\"}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new String[]{JsonPath.read(body, "$.response.id"), JsonPath.read(body, "$.response.registrationToken")};
    }

    private String register(String token) throws Exception {
        return mvc.perform(json(post("/internal/core/edges/register"),
                        "{\"token\":\"" + token + "\",\"agentVersion\":\"1.0.0\",\"arch\":\"arm64\",\"certFingerprint\":\"" + FP + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String heartbeat(String id, String extra) throws Exception {
        return mvc.perform(json(post("/internal/core/edges/" + id + "/heartbeat"),
                        "{\"organizationId\":\"" + org + "\",\"bufferUsedBytes\":1024,\"bufferItems\":3,\"throughput\":5.5" + extra + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("[DSC-08.03][AT-DSC-22.1][TC-DSC-213] 등록 토큰(24시간·1회용·해시만) → ONLINE·인증서 지문, 재사용·만료 401, 하트비트 끊기면 OFFLINE")
    void registration() throws Exception {
        String[] e = createEdge("1층 엣지");
        assertThat(e[1]).startsWith("d2fe_");
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.edge_registration_tokens WHERE token_hash = :t").param("t", e[1])
                .query(Long.class).single()).isZero();
        mvc.perform(as(org, operator, get("/core/edges/" + e[0]))).andExpect(jsonPath("$.response.status").value("REGISTERING"))
                .andExpect(jsonPath("$.response.site.name").value("본관"));
        String r = register(e[1]);
        assertThat((String) JsonPath.read(r, "$.response.edgeId")).isEqualTo(e[0]);
        mvc.perform(as(org, operator, get("/core/edges/" + e[0]))).andExpect(jsonPath("$.response.status").value("ONLINE"))
                .andExpect(jsonPath("$.response.certFingerprint").value(FP)).andExpect(jsonPath("$.response.agentVersion").value("1.0.0"));
        assertThat((String) JsonPath.read(register(e[1]), "$.header.resultCode")).isEqualTo("EDGE_TOKEN_INVALID");
        assertThat((String) JsonPath.read(register("d2fe_nope"), "$.header.resultCode")).isEqualTo("EDGE_TOKEN_INVALID");
        String[] late = createEdge("2층 엣지");
        clock.advance(Duration.ofHours(25));
        assertThat((String) JsonPath.read(register(late[1]), "$.header.resultCode")).isEqualTo("EDGE_TOKEN_INVALID");
        // 재발급하면 새 토큰으로 등록할 수 있다
        String again = JsonPath.read(mvc.perform(as(org, integrator, post("/core/edges/" + late[0] + "/registration-tokens")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.response.registrationToken");
        assertThat((String) JsonPath.read(register(again), "$.response.edgeId")).isEqualTo(late[0]);
        mvc.perform(as(org, integrator, post("/core/edges/" + late[0] + "/registration-tokens"))).andExpect(status().isConflict());
        // 첫 엣지는 25시간 하트비트가 없었다
        mvc.perform(as(org, operator, get("/core/edges").param("siteId", Long.toString(site)))).andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.responses[0].status").value("OFFLINE"));
        heartbeat(e[0], ",\"version\":\"1.0.0\"");
        mvc.perform(as(org, operator, get("/core/edges/" + e[0]))).andExpect(jsonPath("$.response.status").value("ONLINE"))
                .andExpect(jsonPath("$.response.bufferItems").value(3));
        assertThat(auditCount(org, "EDGE_REGISTERED")).isEqualTo(2);
        mvc.perform(as(org, integrator, json(post("/core/edges"), "{\"name\":\"1층 엣지\",\"siteId\":\"" + site + "\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        long room = data.space(org, site, "ROOM", "실습실");
        mvc.perform(as(org, integrator, json(post("/core/edges"), "{\"name\":\"x\",\"siteId\":\"" + room + "\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_SITE"));
        mvc.perform(as(org, integrator, json(patch("/core/edges/" + e[0]), "{\"name\":\"1층 엣지(교체)\",\"baseVersion\":0}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.name").value("1층 엣지(교체)"));
    }

    @Test
    @DisplayName("[DSC-08.03][BR-DSC-32][TC-DSC-213] 설정 판 배포 → 하트비트로 내려감 → APPLIED 보고, 실패하면 FAILED_ROLLED_BACK, 예전 판으로 되돌리기, 설정 변경 EDGE")
    void configDeploy() throws Exception {
        String[] e = createEdge("엣지");
        register(e[1]);
        String targets = "{\"targets\":[{\"connectorKey\":\"modbus-tcp\",\"config\":{\"host\":\"10.0.0.5\",\"intervalSec\":30}}],\"decoders\":{}}";
        mvc.perform(as(org, integrator, json(post("/core/edges/" + e[0] + "/config-versions"), targets))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.version").value(1));
        mvc.perform(as(org, integrator, json(post("/core/edges/" + e[0] + "/config-versions"), "{\"targets\":[{\"connectorKey\":\"BAD KEY\"}]}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, integrator, post("/core/edges/" + e[0] + "/config-versions/1/deploy"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.desired").value(true));
        assertThat(configMessages(org)).anyMatch(m -> m.contains("\"EDGE\""));
        String hb = heartbeat(e[0], "");
        assertThat((Integer) JsonPath.read(hb, "$.response.desiredConfig.version")).isEqualTo(1);
        assertThat((String) JsonPath.read(hb, "$.response.desiredConfig.targets[0].connectorKey")).isEqualTo("modbus-tcp");
        heartbeat(e[0], ",\"configResult\":{\"version\":1,\"result\":\"APPLIED\"}");
        assertThat((Object) JsonPath.read(heartbeat(e[0], ""), "$.response.desiredConfig")).isNull();
        mvc.perform(as(org, integrator, json(post("/core/edges/" + e[0] + "/config-versions"), targets.replace("30", "10"))))
                .andExpect(status().isCreated());
        mvc.perform(as(org, integrator, post("/core/edges/" + e[0] + "/config-versions/2/deploy"))).andExpect(status().isOk());
        heartbeat(e[0], ",\"configResult\":{\"version\":2,\"result\":\"FAILED_ROLLED_BACK\",\"error\":\"포트 열기 실패\"}");
        mvc.perform(as(org, operator, get("/core/edges/" + e[0] + "/config-versions")))
                .andExpect(jsonPath("$.response[0].result").value("FAILED_ROLLED_BACK")).andExpect(jsonPath("$.response[0].error").value("포트 열기 실패"))
                .andExpect(jsonPath("$.response[1].applied").value(true));
        mvc.perform(as(org, integrator, post("/core/edges/" + e[0] + "/config-versions/2/rollback"))).andExpect(status().isConflict());
        mvc.perform(as(org, integrator, post("/core/edges/" + e[0] + "/config-versions/1/rollback"))).andExpect(status().isConflict());
        mvc.perform(as(org, integrator, post("/core/edges/" + e[0] + "/config-versions/9/deploy"))).andExpect(status().isNotFound());
        // EVT-DSC-10 반영(ingress 생산)
        deliver(EventType.EDGE_CONFIG_APPLIED, org, EdgeEvent.configApplied(Long.parseLong(e[0]), 2, "APPLIED", clock.instant()), clock.instant());
        deliver(EventType.EDGE_BUFFER_DROPPED, org, EdgeEvent.bufferDropped(Long.parseLong(e[0]), 12, clock.instant()), clock.instant());
        mvc.perform(as(org, operator, get("/core/edges/" + e[0] + "/config-versions"))).andExpect(jsonPath("$.response[0].result").value("APPLIED"));
        mvc.perform(as(org, operator, get("/core/edges/" + e[0]))).andExpect(jsonPath("$.response.droppedItems").value(12));
    }

    @Test
    @DisplayName("[DSC-08.04][AT-DSC-24.1·24.2][TC-DSC-217] 승인한 엣지만 업데이트(자동 없음), 하트비트로 내려가 UPDATING, 목표 버전 보고면 SUCCEEDED, 5분 뒤 이전 버전이면 FAILED_ROLLED_BACK")
    void updates() throws Exception {
        String[] e = createEdge("엣지");
        register(e[1]);
        mvc.perform(as(org, operator, get("/core/edges/" + e[0] + "/updates"))).andExpect(jsonPath("$.response.latestVersion").value("1.1.0"))
                .andExpect(jsonPath("$.response.updates").isEmpty());
        mvc.perform(as(org, operator, get("/core/edges/" + e[0]))).andExpect(jsonPath("$.response.updateAvailable").value(true));
        // 승인 전 하트비트에는 업데이트가 없다(AT-DSC-24.1)
        assertThat((Object) JsonPath.read(heartbeat(e[0], ",\"version\":\"1.0.0\""), "$.response.pendingUpdate")).isNull();
        mvc.perform(as(org, integrator, json(post("/core/edges/" + e[0] + "/updates"), "{\"toVersion\":\"9.9.9\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_PUBLISHED"));
        String updateId = JsonPath.read(mvc.perform(as(org, integrator, json(post("/core/edges/" + e[0] + "/updates"), "{\"toVersion\":\"1.1.0\"}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.response.updateId");
        mvc.perform(as(org, integrator, json(post("/core/edges/" + e[0] + "/updates"), "{\"toVersion\":\"1.1.0\"}"))).andExpect(status().isConflict());
        String hb = heartbeat(e[0], ",\"version\":\"1.0.0\"");
        assertThat((String) JsonPath.read(hb, "$.response.pendingUpdate.updateId")).isEqualTo(updateId);
        mvc.perform(as(org, operator, get("/core/edges/" + e[0]))).andExpect(jsonPath("$.response.status").value("UPDATING"));
        clock.advance(Duration.ofSeconds(60));
        heartbeat(e[0], ",\"version\":\"1.1.0\"");
        mvc.perform(as(org, operator, get("/core/edges/" + e[0] + "/updates"))).andExpect(jsonPath("$.response.updates[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.response.currentVersion").value("1.1.0"));
        mvc.perform(as(org, operator, get("/core/edges/" + e[0]))).andExpect(jsonPath("$.response.status").value("ONLINE"))
                .andExpect(jsonPath("$.response.updateAvailable").value(false));

        String[] f = createEdge("두 번째");
        register(f[1]);
        mvc.perform(as(org, integrator, json(post("/core/edges/" + f[0] + "/updates"), "{\"toVersion\":\"1.1.0\"}"))).andExpect(status().isCreated());
        heartbeat(f[0], ",\"version\":\"1.0.0\"");
        clock.advance(Duration.ofMinutes(5));
        heartbeat(f[0], ",\"version\":\"1.0.0\"");
        mvc.perform(as(org, operator, get("/core/edges/" + f[0] + "/updates")))
                .andExpect(jsonPath("$.response.updates[0].status").value("FAILED_ROLLED_BACK"));
        assertThat(auditCount(org, "EDGE_UPDATE_FAILED_ROLLED_BACK")).isEqualTo(1);
        // 연결되지 않은 엣지는 승인할 수 없다
        clock.advance(Duration.ofMinutes(5));
        mvc.perform(as(org, integrator, json(post("/core/edges/" + f[0] + "/updates"), "{\"toVersion\":\"1.1.0\"}"))).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("[DSC-08.03][TC-DSC-213] 재시작·로그 수집은 다음 하트비트로 한 번 전달, 폐기는 되돌릴 수 없고 하트비트에 revoked=true, 폐기 뒤 삭제")
    void commandsAndRevoke() throws Exception {
        String[] e = createEdge("엣지");
        register(e[1]);
        mvc.perform(as(org, integrator, post("/core/edges/" + e[0] + "/restart"))).andExpect(status().isAccepted());
        mvc.perform(as(org, integrator, json(post("/core/edges/" + e[0] + "/collect-logs"), "{\"minutes\":30}"))).andExpect(status().isAccepted());
        mvc.perform(as(org, integrator, json(post("/core/edges/" + e[0] + "/collect-logs"), "{\"minutes\":0}"))).andExpect(status().isBadRequest());
        String hb = heartbeat(e[0], "");
        assertThat((java.util.List<String>) JsonPath.read(hb, "$.response.commands[*].kind")).containsExactly("RESTART", "COLLECT_LOGS");
        assertThat((Integer) JsonPath.read(hb, "$.response.commands[1].args.minutes")).isEqualTo(30);
        assertThat((java.util.List<String>) JsonPath.read(heartbeat(e[0], ""), "$.response.commands")).isEmpty();
        mvc.perform(as(org, integrator, delete("/core/edges/" + e[0]))).andExpect(status().isConflict());
        mvc.perform(as(org, integrator, post("/core/edges/" + e[0] + "/revoke"))).andExpect(status().isAccepted());
        assertThat((Boolean) JsonPath.read(heartbeat(e[0], ""), "$.response.revoked")).isTrue();
        mvc.perform(as(org, integrator, post("/core/edges/" + e[0] + "/restart"))).andExpect(status().isConflict());
        mvc.perform(as(org, operator, get("/core/edges/" + e[0]))).andExpect(jsonPath("$.response.status").value("REVOKED"));
        mvc.perform(as(org, integrator, delete("/core/edges/" + e[0]))).andExpect(status().isNoContent());
        mvc.perform(json(post("/internal/core/edges/" + e[0] + "/heartbeat"), "{\"organizationId\":\"" + org + "\"}")).andExpect(status().isNotFound());
        assertThat(auditCount(org, "EDGE_REVOKE")).isEqualTo(1);
    }
}
