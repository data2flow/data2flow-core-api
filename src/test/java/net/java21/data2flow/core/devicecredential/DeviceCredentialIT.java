package net.java21.data2flow.core.devicecredential;

import com.jayway.jsonpath.JsonPath;
import net.java21.data2flow.core.common.Tokens;
import net.java21.data2flow.core.device.DeviceTestData;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DSC-03.02 플랫폼 브로커 기기 자격 발급·폐기(API-DSC-20~22, BR-DSC-14, ADR-029) */
class DeviceCredentialIT extends IntegrationTestSupport {

    @Autowired
    PasswordEncoder encoder;

    private long org;
    private long admin;
    private long device;
    private long mqttDevice;

    @BeforeEach
    void setUp() {
        DeviceTestData d = new DeviceTestData(jdbc);
        org = fx.organization("cred");
        admin = fx.user(org, "cred.admin", "ADMIN");
        long room = data.space(org, data.site(org, "본관"), "ROOM", "실습실");
        long broker = d.source(org, "pb", "PLATFORM_BROKER", "AUTO_REGISTER", null, null, 100);
        device = data.device(org, broker, "esp-02", "ACTIVE", room, null);
        mqttDevice = data.device(org, data.source(org, "cs"), "lora-1", "ACTIVE", room, null);
    }

    @Test
    @DisplayName("[DSC-03.02] 발급: 비밀번호·서명 키를 한 번만(DB는 해시), 다시 발급하면 이전 것 폐기, 폐기 두 번이면 409 CREDENTIAL_REVOKED — TC-DSC-107·109·110")
    void issueAndRevoke() throws Exception {
        String body = mvc.perform(as(org, admin, json(post("/core/devices/" + device + "/credentials"), "{\"type\":\"PASSWORD\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.username").value("esp-02"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.response.credentialId");
        String password = JsonPath.read(body, "$.response.password");
        String key = JsonPath.read(body, "$.response.signingKey");
        String[] hashes = jdbc.sql("SELECT password_hash, signing_key_hash FROM data2flow_core.device_credentials WHERE id = :id")
                .param("id", Long.parseLong(id)).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).single();
        assertThat(encoder.matches(password, hashes[0])).isTrue();
        assertThat(hashes[1]).isEqualTo(Tokens.sha256Hex(key));
        assertThat(auditCount(org, "DEVICE_CREDENTIAL_ISSUED")).isEqualTo(1);

        mvc.perform(as(org, admin, post("/core/devices/" + device + "/credentials"))).andExpect(status().isCreated());
        mvc.perform(as(org, admin, get("/core/devices/" + device + "/credentials")))
                .andExpect(jsonPath("$.response", hasSize(2)))
                .andExpect(jsonPath("$.response[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.response[1].status").value("REVOKED"))
                .andExpect(jsonPath("$.response[0].password").doesNotExist());
        String active = JsonPath.read(mvc.perform(as(org, admin, get("/core/devices/" + device + "/credentials")))
                .andReturn().getResponse().getContentAsString(), "$.response[0].id");
        mvc.perform(as(org, admin, post("/core/devices/" + device + "/credentials/" + active + "/revoke")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.status").value("REVOKED"))
                .andExpect(jsonPath("$.response.revokedAt").exists());
        mvc.perform(as(org, admin, post("/core/devices/" + device + "/credentials/" + active + "/revoke")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("CREDENTIAL_REVOKED"));
        mvc.perform(as(org, admin, post("/core/devices/" + device + "/credentials/999999/revoke")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("CREDENTIAL_NOT_FOUND"));
    }

    @Test
    @DisplayName("[DSC-03.02] 플랫폼 브로커가 아닌 기기 409 SOURCE_STATE_CONFLICT, 지난 만료 400, 권한 SRC_ADMIN(OPERATOR 403, 조회는 SRC_READ), 다른 조직 404 — TC-DSC-108·111")
    void rulesAndPermissions() throws Exception {
        mvc.perform(as(org, admin, post("/core/devices/" + mqttDevice + "/credentials")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_STATE_CONFLICT"));
        mvc.perform(as(org, admin, json(post("/core/devices/" + device + "/credentials"), "{\"expiresAt\":\"2026-01-01T00:00:00Z\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(org, admin, json(post("/core/devices/" + device + "/credentials"), "{\"type\":\"CERT\"}")))
                .andExpect(status().isBadRequest());
        long op = fx.user(org, "cred.op", "OPERATOR");
        mvc.perform(as(org, op, post("/core/devices/" + device + "/credentials"))).andExpect(status().isForbidden());
        mvc.perform(as(org, op, get("/core/devices/" + device + "/credentials"))).andExpect(status().isOk());
        long viewer = fx.user(org, "cred.viewer", "VIEWER");
        mvc.perform(as(org, viewer, get("/core/devices/" + device + "/credentials"))).andExpect(status().isForbidden());
        long other = fx.organization("cred2");
        long otherAdmin = fx.user(other, "cred2.admin", "ADMIN");
        mvc.perform(as(other, otherAdmin, post("/core/devices/" + device + "/credentials"))).andExpect(status().isNotFound());
        // 같은 사용자 이름을 다른 기기가 쓰고 있으면 기기 ID를 붙인다
        long broker2 = new DeviceTestData(jdbc).source(org, "pb2", "PLATFORM_BROKER", "AUTO_REGISTER", null, null, 100);
        long twin = data.device(org, broker2, "esp-02", "ACTIVE", null, null);
        mvc.perform(as(org, admin, post("/core/devices/" + device + "/credentials"))).andExpect(status().isCreated());
        mvc.perform(as(org, admin, post("/core/devices/" + twin + "/credentials")))
                .andExpect(jsonPath("$.response.username").value("esp-02-" + twin));
        assertThat(List.of(device, twin)).hasSize(2);
    }

    @Test
    @DisplayName("[DSC-03.02][DSC-03.03] API-DSC-72 서명 키 내부 API: ACTIVE·만료 전·PLATFORM_BROKER만, 폐기·만료·삭제 기기는 빠짐, deviceKey는 소스 패턴 적용 소문자, ID는 문자열 — TC-DSC-107·114")
    void internalSigningKeys() throws Exception {
        String issued = mvc.perform(as(org, admin, post("/core/devices/" + device + "/credentials")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String key = JsonPath.read(issued, "$.response.signingKey");
        String credentialId = JsonPath.read(issued, "$.response.credentialId");
        String body = signingKeys();
        assertThat(JsonPath.<List<String>>read(body, "$.response.keys[?(@.deviceId == '" + device + "')].signingKey")).containsExactly(key);
        assertThat(JsonPath.<List<String>>read(body, "$.response.keys[?(@.deviceId == '" + device + "')].credentialId"))
                .containsExactly(credentialId);
        assertThat(JsonPath.<List<String>>read(body, "$.response.keys[?(@.deviceId == '" + device + "')].organizationId"))
                .containsExactly(Long.toString(org));
        assertThat(JsonPath.<List<Object>>read(body, "$.response.keys[?(@.deviceId == '" + device + "')].sourceId").getFirst())
                .isInstanceOf(String.class);
        Number v1 = JsonPath.read(body, "$.response.version");

        // 소스 패턴: deviceKeyPattern "ESP-{externalId}" → 소문자 "esp-up-1"
        long broker3 = new DeviceTestData(jdbc).source(org, "pb3", "PLATFORM_BROKER", "AUTO_REGISTER", null, null, 100);
        jdbc.sql("UPDATE data2flow_core.data_sources SET connection = CAST(:c AS jsonb) WHERE id = :id")
                .param("c", "{\"deviceKeyPattern\":\"ESP-{externalId}\"}").param("id", broker3).update();
        long patterned = data.device(org, broker3, "UP-1", "ACTIVE", null, null);
        mvc.perform(as(org, admin, post("/core/devices/" + patterned + "/credentials"))).andExpect(status().isCreated());
        body = signingKeys();
        assertThat(JsonPath.<List<String>>read(body, "$.response.keys[?(@.deviceId == '" + patterned + "')].deviceKey"))
                .containsExactly("esp-up-1");
        assertThat(((Number) JsonPath.read(body, "$.response.version")).longValue()).isGreaterThan(v1.longValue());

        // 만료·삭제 기기·폐기는 빠진다
        jdbc.sql("UPDATE data2flow_core.device_credentials SET expires_at = :t WHERE device_id = :d")
                .param("t", java.sql.Timestamp.from(clock.instant().minusSeconds(1))).param("d", patterned).update();
        jdbc.sql("UPDATE data2flow_core.devices SET status = 'DELETED' WHERE id = :d").param("d", device).update();
        assertThat(JsonPath.<List<Object>>read(signingKeys(), "$.response.keys[?(@.organizationId == '" + org + "')]")).isEmpty();
        jdbc.sql("UPDATE data2flow_core.devices SET status = 'ACTIVE' WHERE id = :d").param("d", device).update();
        assertThat(JsonPath.<List<Object>>read(signingKeys(), "$.response.keys[?(@.organizationId == '" + org + "')]")).hasSize(1);
        mvc.perform(as(org, admin, post("/core/devices/" + device + "/credentials/" + credentialId + "/revoke"))).andExpect(status().isOk());
        assertThat(JsonPath.<List<Object>>read(signingKeys(), "$.response.keys[?(@.organizationId == '" + org + "')]")).isEmpty();
        // 암호문이 망가진 행은 빼고 나머지는 준다(키 값은 로그에 없음)
        mvc.perform(as(org, admin, post("/core/devices/" + device + "/credentials"))).andExpect(status().isCreated());
        jdbc.sql("UPDATE data2flow_core.device_credentials SET signing_key_enc = :e WHERE device_id = :d AND status = 'ACTIVE'")
                .param("e", new byte[] {1, 2, 3, 4}).param("d", device).update();
        assertThat(JsonPath.<List<Object>>read(signingKeys(), "$.response.keys[?(@.organizationId == '" + org + "')]")).isEmpty();
    }

    private String signingKeys() throws Exception {
        return mvc.perform(get("/internal/core/device-credentials/signing-keys").header("X-CALLER-SERVICE", "data2flow-ingress"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
