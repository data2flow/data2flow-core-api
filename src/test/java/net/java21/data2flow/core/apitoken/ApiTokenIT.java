package net.java21.data2flow.core.apitoken;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.apitoken.service.ApiTokenJobs;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 장기 토큰(API 키·MCP)·서비스 계정(IAM-05.01~05.04, IAM-04.07, API-IAM-40~46). 실제 PostgreSQL로 발급(원문 1회·해시만)·검증(introspection용
 * API-IAM-46)·범위·승인·폐기·교체 유예·서비스 계정 비활성화·토큰 요청의 권한 판정을 확인한다.
 */
class ApiTokenIT extends IntegrationTestSupport {

    @Autowired
    ApiTokenJobs jobs;

    long org;
    long admin;
    long integrator;
    long analyst;
    long viewer;

    @BeforeEach
    void setUp() {
        org = fx.organization("tok");
        admin = fx.user(org, "tok.admin", "ADMIN");
        integrator = fx.user(org, "tok.integrator", "INTEGRATOR");
        analyst = fx.user(org, "tok.analyst", "ANALYST");
        viewer = fx.user(org, "tok.viewer", "VIEWER");
    }

    private String issueBody(String kind, String name, String scopes, Instant expiresAt) {
        return "{\"kind\":\"" + kind + "\",\"name\":\"" + name + "\",\"scopes\":[" + scopes + "],\"expiresAt\":"
                + (expiresAt == null ? "null" : "\"" + expiresAt + "\"") + "}";
    }

    private MvcResult issue(long user, String body) throws Exception {
        return mvc.perform(json(as(org, user, post("/core/api-tokens")), body)).andReturn();
    }

    private String verifyHash(String token) throws Exception {
        MvcResult r = mvc.perform(json(post("/internal/core/api-tokens/verify").header("X-CALLER-SERVICE", "data2flow-auth"),
                "{\"tokenHash\":\"" + Tokens.sha256Hex(token) + "\",\"ip\":\"203.0.113.7\"}")).andExpect(status().isOk()).andReturn();
        return body(r);
    }

    private static String body(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static MockHttpServletRequestBuilder viaToken(long orgId, long userId, String tokenId, String scopes, MockHttpServletRequestBuilder b) {
        return as(orgId, userId, b).header("X-ACCESS-TOKEN-ID", tokenId).header("X-TOKEN-SCOPE", scopes);
    }

    private List<String> blacklistPayloads() {
        return jdbc.sql("SELECT payload::text FROM data2flow_core.outboxes WHERE organization_id = :org AND exchange = 'data2flow-auth' ORDER BY id")
                .param("org", org).query(String.class).list();
    }

    @Test
    @DisplayName("[IAM-05.01][IAM-04.07][AT-IAM-12.1] ANALYST가 read:telemetry MCP 토큰 발급 → 원문 1회, DB에는 해시만, 검증하면 범위·한도 60/분 — TC-IAM-145·163")
    void issueShowsTokenOnceAndStoresHashOnly() throws Exception {
        MvcResult r = issue(analyst, issueBody("MCP", "claude", "\"read:telemetry\"", MutableClockPlus(Duration.ofDays(90))));

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(r.getResponse().getHeader("Location")).startsWith("/api/v1/core/api-tokens/");
        String token = JsonPath.read(body(r), "$.response.token");
        String id = JsonPath.read(body(r), "$.response.id");
        assertThat(token).startsWith("data2flow_").hasSize(53);
        assertThat((String) JsonPath.read(body(r), "$.response.prefix")).isEqualTo(token.substring(0, 12));
        assertThat((String) JsonPath.read(body(r), "$.response.status")).isEqualTo("ACTIVE");
        Map<String, Object> row = jdbc.sql("SELECT token_hash, row_to_json(t)::text AS raw FROM data2flow_core.api_tokens t WHERE id = :id")
                .param("id", Long.parseLong(id)).query().singleRow();
        assertThat(row.get("token_hash")).isEqualTo(Tokens.sha256Hex(token));
        assertThat((String) row.get("raw")).doesNotContain(token.substring(10));
        assertThat(jdbc.sql("SELECT count(*) FROM data2flow_core.audit_logs WHERE organization_id = :org AND detail::text LIKE :t")
                .param("org", org).param("t", "%" + token.substring(10) + "%").query(Long.class).single()).isZero();
        assertThat(auditCount(org, "API_TOKEN_CREATED")).isEqualTo(1);

        String verified = verifyHash(token);
        assertThat((Boolean) JsonPath.read(verified, "$.response.active")).isTrue();
        assertThat((String) JsonPath.read(verified, "$.response.userId")).isEqualTo(Long.toString(analyst));
        assertThat((String) JsonPath.read(verified, "$.response.org")).isEqualTo(Long.toString(org));
        assertThat((String) JsonPath.read(verified, "$.response.kind")).isEqualTo("MCP");
        assertThat((String) JsonPath.read(verified, "$.response.tokenId")).isEqualTo(id);
        assertThat((List<String>) JsonPath.read(verified, "$.response.scopes")).containsExactly("read:telemetry");
        assertThat((Integer) JsonPath.read(verified, "$.response.rateLimitPerMin")).isEqualTo(60);

        // IAM-05.03: 목록에 마지막 사용 시각·IP, 원문 없음
        MvcResult list = mvc.perform(as(org, analyst, get("/core/api-tokens?owner=me"))).andExpect(status().isOk()).andReturn();
        assertThat(body(list)).doesNotContain(token);
        assertThat((String) JsonPath.read(body(list), "$.responses[0].lastUsedIp")).isEqualTo("203.0.113.7");
        assertThat((String) JsonPath.read(body(list), "$.responses[0].lastUsedAt")).isNotNull();
        assertThat((Integer) JsonPath.read(body(list), "$.totalCount")).isEqualTo(1);
    }

    private Instant MutableClockPlus(Duration d) {
        return clock.instant().plus(d);
    }

    @Test
    @DisplayName("[IAM-04.07][AT-IAM-12.3] 발급자 권한을 넘는 범위 → 400 API_TOKEN_SCOPE_EXCEEDED(VIEWER mcp:write도 400, 읽기만 요청하면 발급 권한 없음 403) — TC-IAM-149·TC-AIA-077")
    void scopeBeyondIssuer() throws Exception {
        Instant exp = MutableClockPlus(Duration.ofDays(30));
        mvc.perform(json(as(org, analyst, post("/core/api-tokens")), issueBody("API_KEY", "k1", "\"control:devices\"", exp)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("API_TOKEN_SCOPE_EXCEEDED"))
                .andExpect(jsonPath("$.header.resultMessage").value("내 권한을 넘는 범위는 지정할 수 없습니다"));
        mvc.perform(json(as(org, viewer, post("/core/api-tokens")), issueBody("MCP", "k2", "\"mcp:write\"", exp)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("API_TOKEN_SCOPE_EXCEEDED"));
        mvc.perform(json(as(org, viewer, post("/core/api-tokens")), issueBody("MCP", "k3", "\"read:telemetry\"", exp)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.header.resultCode").value("PERMISSION_DENIED"));
        mvc.perform(json(as(org, analyst, post("/core/api-tokens")), issueBody("MCP", "k4", "\"read:everything\"", exp)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("[IAM-05.02] 만료일 없음·1년 초과 → 400 API_TOKEN_EXPIRY_INVALID, 같은 이름 다시 → 400, 조직 50개 → 409 API_TOKEN_LIMIT_EXCEEDED — TC-IAM-154·155")
    void expiryAndLimits() throws Exception {
        mvc.perform(json(as(org, analyst, post("/core/api-tokens")), issueBody("MCP", "k", "\"read:devices\"", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("API_TOKEN_EXPIRY_INVALID"))
                .andExpect(jsonPath("$.header.resultMessage").value("만료일은 오늘부터 1년 이내여야 합니다"));
        mvc.perform(json(as(org, analyst, post("/core/api-tokens")), issueBody("MCP", "k", "\"read:devices\"",
                        MutableClockPlus(Duration.ofDays(367)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("API_TOKEN_EXPIRY_INVALID"));
        assertThat(issue(analyst, issueBody("MCP", "k", "\"read:devices\"", MutableClockPlus(Duration.ofDays(365)))).getResponse().getStatus())
                .isEqualTo(201);
        mvc.perform(json(as(org, analyst, post("/core/api-tokens")), issueBody("MCP", "k", "\"read:devices\"", MutableClockPlus(Duration.ofDays(5)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("name"));
        for (int i = 0; i < 49; i++) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.api_tokens (organization_id, owner_type, owner_id, kind, name, token_prefix, token_hash, scopes,
                                   expires_at, created_by, created_at)
                            VALUES (:org, 'USER', :u, 'MCP', :name, 'data2flow_ab', :hash, '{read:devices}', :exp, :u, :now)""")
                    .param("org", org).param("u", admin).param("name", "bulk" + i).param("hash", Tokens.sha256Hex("bulk" + i + org))
                    .param("exp", java.time.OffsetDateTime.ofInstant(MutableClockPlus(Duration.ofDays(10)), java.time.ZoneOffset.UTC))
                    .param("now", java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC)).update();
        }
        mvc.perform(json(as(org, analyst, post("/core/api-tokens")), issueBody("MCP", "k51", "\"read:devices\"", MutableClockPlus(Duration.ofDays(5)))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("API_TOKEN_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.header.resultMessage").value("API 키 한도를 넘었습니다"));
        mvc.perform(as(org, analyst, delete("/core/api-tokens/987654321")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("API_TOKEN_NOT_FOUND"));
    }

    @Test
    @DisplayName("[IAM-04.07][BR-IAM-19] 쓰기 범위는 INTEGRATOR가 요청하면 PENDING_APPROVAL(검증 비활성) → ADMIN 승인 뒤 ACTIVE, 거절·재승인 409 — TC-IAM-153")
    void writeScopeNeedsApproval() throws Exception {
        MvcResult r = issue(integrator, issueBody("MCP", "writer", "\"read:devices\",\"mcp:write\"", MutableClockPlus(Duration.ofDays(30))));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) JsonPath.read(body(r), "$.response.status")).isEqualTo("PENDING_APPROVAL");
        String token = JsonPath.read(body(r), "$.response.token");
        String id = JsonPath.read(body(r), "$.response.id");
        assertThat((Boolean) JsonPath.read(verifyHash(token), "$.response.active")).isFalse();

        mvc.perform(as(org, integrator, post("/core/api-tokens/" + id + "/approve"))).andExpect(status().isForbidden());
        mvc.perform(json(as(org, admin, post("/core/api-tokens/" + id + "/approve")), "{\"reason\":\"ok\"}")).andExpect(status().isNoContent());
        assertThat((Boolean) JsonPath.read(verifyHash(token), "$.response.active")).isTrue();
        mvc.perform(as(org, admin, post("/core/api-tokens/" + id + "/reject")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("API_TOKEN_STATE_CONFLICT"));
        assertThat(auditCount(org, "API_TOKEN_APPROVED")).isEqualTo(1);

        MvcResult second = issue(integrator, issueBody("API_KEY", "writer2", "\"write:devices\"", MutableClockPlus(Duration.ofDays(30))));
        String id2 = JsonPath.read(body(second), "$.response.id");
        mvc.perform(as(org, admin, post("/core/api-tokens/" + id2 + "/reject"))).andExpect(status().isNoContent());
        mvc.perform(as(org, admin, get("/core/api-tokens?owner=all&status=REJECTED")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.responses[0].id").value(id2));
        mvc.perform(as(org, analyst, get("/core/api-tokens?owner=all"))).andExpect(status().isForbidden());

        // ADMIN이 직접 발급한 쓰기 범위는 바로 ACTIVE
        MvcResult own = issue(admin, issueBody("MCP", "adm", "\"control:devices\"", MutableClockPlus(Duration.ofDays(30))));
        assertThat((String) JsonPath.read(body(own), "$.response.status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("[IAM-05.03][AT-IAM-12.4] 폐기 → 바로 검증 비활성(1분 이내 401), auth에 토큰 ID 폐기 알림(API-IAM-37b tokenIds), 남의 토큰은 404 — TC-IAM-150·164")
    void revoke() throws Exception {
        MvcResult r = issue(analyst, issueBody("API_KEY", "ci", "\"read:telemetry\"", MutableClockPlus(Duration.ofDays(30))));
        String token = JsonPath.read(body(r), "$.response.token");
        String id = JsonPath.read(body(r), "$.response.id");

        mvc.perform(as(org, viewer, delete("/core/api-tokens/" + id))).andExpect(status().isNotFound());
        mvc.perform(as(org, analyst, delete("/core/api-tokens/" + id))).andExpect(status().isNoContent());
        mvc.perform(as(org, analyst, delete("/core/api-tokens/" + id))).andExpect(status().isNoContent());

        assertThat((Boolean) JsonPath.read(verifyHash(token), "$.response.active")).isFalse();
        assertThat(blacklistPayloads()).anySatisfy(p -> assertThat(p).contains("\"tokenIds\": [\"" + id + "\"]").contains("API_TOKEN_REVOKED"));
        assertThat(auditCount(org, "API_TOKEN_REVOKED")).isEqualTo(1);
    }

    @Test
    @DisplayName("[IAM-05.03][BR-IAM-34][AT-IAM-18.1] 서비스 계정 키 교체(유예 2시간) → 2시간 동안 두 키 모두 유효, 이후 이전 키 거부·정리 작업이 REVOKED — TC-IAM-157")
    void rotateWithGrace() throws Exception {
        String account = JsonPath.read(body(mvc.perform(json(as(org, admin, post("/core/service-accounts")),
                "{\"name\":\"ERP 연동\",\"description\":\"읽기\"}")).andExpect(status().isCreated()).andReturn()), "$.response.id");
        MvcResult r = issue(admin, "{\"kind\":\"API_KEY\",\"name\":\"erp\",\"scopes\":[\"read:devices\"],\"expiresAt\":\""
                + MutableClockPlus(Duration.ofDays(60)) + "\",\"serviceAccountId\":\"" + account + "\"}");
        String oldToken = JsonPath.read(body(r), "$.response.token");
        String oldId = JsonPath.read(body(r), "$.response.id");
        String verified = verifyHash(oldToken);
        assertThat((String) JsonPath.read(verified, "$.response.ownerType")).isEqualTo("SERVICE_ACCOUNT");
        assertThat((String) JsonPath.read(verified, "$.response.ownerId")).isEqualTo(account);
        assertThat((Object) JsonPath.read(verified, "$.response.userId")).isNull();
        assertThat((Integer) JsonPath.read(verified, "$.response.rateLimitPerMin")).isEqualTo(600);

        MvcResult rotated = mvc.perform(json(as(org, admin, post("/core/api-tokens/" + oldId + "/rotate")), "{\"graceHours\":2}"))
                .andExpect(status().isOk()).andReturn();
        String newToken = JsonPath.read(body(rotated), "$.response.token");
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat((Boolean) JsonPath.read(verifyHash(oldToken), "$.response.active")).isTrue();
        assertThat((Boolean) JsonPath.read(verifyHash(newToken), "$.response.active")).isTrue();
        mvc.perform(json(as(org, admin, post("/core/api-tokens/" + oldId + "/rotate")), "{\"graceHours\":2}"))
                .andExpect(status().isConflict());
        mvc.perform(json(as(org, admin, post("/core/api-tokens/" + oldId + "/rotate")), "{\"graceHours\":25}"))
                .andExpect(status().isBadRequest());

        clock.advance(Duration.ofHours(2).plusSeconds(1));
        assertThat((Boolean) JsonPath.read(verifyHash(oldToken), "$.response.active")).isFalse();
        assertThat((Boolean) JsonPath.read(verifyHash(newToken), "$.response.active")).isTrue();
        assertThat(jobs.runOnce()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM data2flow_core.api_tokens WHERE id = :id").param("id", Long.parseLong(oldId))
                .query(String.class).single()).isEqualTo("REVOKED");
        assertThat(blacklistPayloads()).anySatisfy(p -> assertThat(p).contains(oldId).contains("API_TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("[IAM-05.01][BR-IAM-35][AT-IAM-18.3·18.4] 서비스 계정 토큰은 범위 권한만(read:telemetry로 제어 403·관리 403), 비활성화하면 키 즉시 거부 — TC-IAM-160·161·162")
    void serviceAccountTokens() throws Exception {
        String account = JsonPath.read(body(mvc.perform(json(as(org, admin, post("/core/service-accounts")), "{\"name\":\"bot\"}"))
                .andExpect(status().isCreated()).andReturn()), "$.response.id");
        mvc.perform(json(as(org, admin, post("/core/service-accounts")), "{\"name\":\"BOT\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(as(org, analyst, post("/core/service-accounts")), "{\"name\":\"x\"}")).andExpect(status().isForbidden());
        MvcResult r = issue(admin, "{\"kind\":\"MCP\",\"name\":\"bot\",\"scopes\":[\"read:telemetry\"],\"expiresAt\":\""
                + MutableClockPlus(Duration.ofDays(60)) + "\",\"serviceAccountId\":\"" + account + "\"}");
        String token = JsonPath.read(body(r), "$.response.token");
        String tokenId = JsonPath.read(body(r), "$.response.id");
        long space = data.site(org, "본관");
        long device = data.device(org, data.source(org, "s1"), "dev-1", "ACTIVE", space, null);

        // 서비스 계정 ID가 우연히 ADMIN 사용자 ID와 같아도 사용자 권한으로 해석하지 않는다
        long saId = Long.parseLong(account);
        mvc.perform(viewToken(saId, tokenId, "read:telemetry", get("/core/users"))).andExpect(status().isForbidden());
        mvc.perform(json(viewToken(saId, tokenId, "read:telemetry", post("/core/devices/" + device + "/commands"))
                .header("Idempotency-Key", "k-1"), "{\"capability\":\"Switch\",\"command\":\"on\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(viewToken(admin, tokenId, "read:telemetry", get("/core/users"))).andExpect(status().isForbidden());
        // 토큰으로 토큰 관리는 못 한다(BR-IAM-19)
        mvc.perform(json(viewToken(saId, tokenId, "read:telemetry", post("/core/api-tokens")),
                issueBody("MCP", "x", "\"read:telemetry\"", MutableClockPlus(Duration.ofDays(1))))).andExpect(status().isForbidden());

        mvc.perform(as(org, admin, get("/core/service-accounts"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.responses[0].tokenCount").value(1));
        mvc.perform(as(org, admin, post("/core/service-accounts/" + account + "/disable"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("DISABLED")).andExpect(jsonPath("$.response.tokenCount").value(0));
        assertThat((Boolean) JsonPath.read(verifyHash(token), "$.response.active")).isFalse();
        assertThat(blacklistPayloads()).anySatisfy(p -> assertThat(p).contains(tokenId).contains("SERVICE_ACCOUNT_DISABLED"));
        mvc.perform(as(org, admin, post("/core/service-accounts/987654/disable")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SERVICE_ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.header.resultMessage").value("서비스 계정을 찾을 수 없습니다"));
        mvc.perform(json(as(org, admin, post("/core/api-tokens")), "{\"kind\":\"MCP\",\"name\":\"bot2\",\"scopes\":[\"read:telemetry\"],"
                        + "\"expiresAt\":\"" + MutableClockPlus(Duration.ofDays(1)) + "\",\"serviceAccountId\":\"" + account + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("SERVICE_ACCOUNT_NOT_FOUND"));
    }

    private MockHttpServletRequestBuilder viewToken(long userId, String tokenId, String scopes, MockHttpServletRequestBuilder b) {
        return viaToken(org, userId, tokenId, scopes, b);
    }

    @Test
    @DisplayName("[IAM-04.07][IAM-04.06] 사용자 토큰은 역할 ∩ 범위 ∩ 토큰 공간 범위: read:devices 토큰으로 기기 목록 200, 토큰 공간 밖 공간 404, 폐기 뒤 403")
    void userTokenNarrowsSpaces() throws Exception {
        long siteA = data.site(org, "A동");
        long siteB = data.site(org, "B동");
        MvcResult r = issue(analyst, "{\"kind\":\"MCP\",\"name\":\"a-only\",\"scopes\":[\"read:devices\"],\"spaceScope\":[\"" + siteA
                + "\"],\"expiresAt\":\"" + MutableClockPlus(Duration.ofDays(10)) + "\"}");
        String id = JsonPath.read(body(r), "$.response.id");
        mvc.perform(viaToken(org, analyst, id, "read:devices", get("/core/spaces/" + siteA))).andExpect(status().isOk());
        mvc.perform(viaToken(org, analyst, id, "read:devices", get("/core/spaces/" + siteB))).andExpect(status().isNotFound());
        mvc.perform(as(org, analyst, get("/core/spaces/" + siteB))).andExpect(status().isOk());
        mvc.perform(viaToken(org, analyst, id, "read:devices", get("/core/devices"))).andExpect(status().isOk());
        // 다른 사용자 ID를 붙여도 토큰 소유자가 아니면 권한 없음
        mvc.perform(viaToken(org, admin, id, "read:devices", get("/core/devices"))).andExpect(status().isForbidden());

        // 제한된 사용자가 범위 밖 공간을 지정하면 400
        data.spaceScope(org, analyst, List.of(siteA));
        mvc.perform(json(as(org, analyst, post("/core/api-tokens")), "{\"kind\":\"MCP\",\"name\":\"b\",\"scopes\":[\"read:devices\"],\"spaceScope\":[\""
                        + siteB + "\"],\"expiresAt\":\"" + MutableClockPlus(Duration.ofDays(10)) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("API_TOKEN_SCOPE_EXCEEDED"));
        MvcResult defaulted = issue(analyst, issueBody("MCP", "dflt", "\"read:devices\"", MutableClockPlus(Duration.ofDays(10))));
        String did = JsonPath.read(body(defaulted), "$.response.id");
        assertThat(jdbc.sql("SELECT space_scope::text FROM data2flow_core.api_tokens WHERE id = :id").param("id", Long.parseLong(did))
                .query(String.class).single()).isEqualTo("{" + siteA + "}");

        mvc.perform(as(org, analyst, delete("/core/api-tokens/" + id))).andExpect(status().isNoContent());
        mvc.perform(viaToken(org, analyst, id, "read:devices", get("/core/devices"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[IAM-01.04][AT-IAM-10.1] 사용자를 비활성화하면 그 사람의 장기 토큰도 즉시 거부되고 auth에 폐기 알림")
    void disablingUserRevokesTokens() throws Exception {
        MvcResult r = issue(analyst, issueBody("MCP", "mine", "\"read:devices\"", MutableClockPlus(Duration.ofDays(10))));
        String token = JsonPath.read(body(r), "$.response.token");
        String id = JsonPath.read(body(r), "$.response.id");
        mvc.perform(json(as(org, admin, post("/core/users/" + analyst + "/disable")), "{\"reason\":\"퇴사\"}"))
                .andExpect(status().isNoContent());
        assertThat((Boolean) JsonPath.read(verifyHash(token), "$.response.active")).isFalse();
        assertThat(blacklistPayloads()).anySatisfy(p -> assertThat(p).contains(id).contains("USER_DISABLED"));
    }

    @Test
    @DisplayName("[IAM-05.02] 만료된 토큰은 검증 비활성, 정리 작업이 EXPIRED로 바꾼다. 형식이 틀린 해시·다른 배포 조직 토큰도 비활성")
    void expiry() throws Exception {
        MvcResult r = issue(analyst, issueBody("MCP", "short", "\"read:devices\"", MutableClockPlus(Duration.ofHours(1))));
        String token = JsonPath.read(body(r), "$.response.token");
        clock.advance(Duration.ofHours(1));
        assertThat((Boolean) JsonPath.read(verifyHash(token), "$.response.active")).isFalse();
        assertThat(jobs.runOnce()).isEqualTo(1);
        mvc.perform(as(org, analyst, get("/core/api-tokens"))).andExpect(jsonPath("$.responses[0].status").value("EXPIRED"));
        mvc.perform(json(post("/internal/core/api-tokens/verify"), "{\"tokenHash\":\"nothex\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.active").value(false));
        mvc.perform(post("/internal/core/api-tokens/verify")).andExpect(status().isOk()).andExpect(jsonPath("$.response.active").value(false));
    }
}
