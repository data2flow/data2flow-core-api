package net.java21.data2flow.core.source.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.secret.Secret;
import net.java21.data2flow.core.source.service.SourceSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DSC-01.03 Webhook 설정·DSC-09.05 인증 방식 매트릭스·DSC-09.06 TLS 규칙 — TC-DSC-018·269·274(core 쪽) */
class SourceM5RulesTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private static ObjectNode obj(String s) {
        return (ObjectNode) JSON.readTree(s);
    }

    @Test
    @DisplayName("[DSC-01.03][TC-DSC-018] Webhook 설정: 키 형식(16~64자), 허용 오차 30~3600초, 요청 ID 헤더 이름, 모르는 필드 거부")
    void webhookConnection() {
        SourceConfigValidator v = SourceConfigValidator.start();
        v.connection("WEBHOOK", obj("{\"sourceKey\":\"abcdefABCDEF0123_-\",\"toleranceSec\":300,\"idHeader\":\"X-D2F-Request-Id\"}"), false);
        assertThat(v.hasErrors()).isFalse();
        SourceConfigValidator bad = SourceConfigValidator.start();
        bad.connection("WEBHOOK", obj("{\"sourceKey\":\"short\",\"toleranceSec\":5,\"idHeader\":\"bad header\",\"url\":\"x\",\"topic\":\"a/#\"}"), false);
        assertThat(bad.errors()).extracting(e -> e.field() + ":" + e.code()).contains("connection.sourceKey:Pattern",
                "connection.toleranceSec:Range", "connection.idHeader:Pattern", "connection.topic:Pattern");
    }

    @Test
    @DisplayName("[DSC-09.06][TC-DSC-274][BR-DSC-29] TLS: 검증 끄기는 개발 소스만, 최소 TLS 1.2, SNI·인증서 고정 형식")
    void tls() {
        SourceConfigValidator dev = SourceConfigValidator.start();
        dev.tls(obj("{\"tls\":{\"verify\":false,\"minVersion\":\"1.3\",\"sni\":\"broker.example.com\",\"pinnedSha256\":[\"" + "a".repeat(64) + "\"]}}"), true);
        assertThat(dev.hasErrors()).isFalse();
        assertThatThrownBy(() -> SourceConfigValidator.start().tls(obj("{\"tls\":{\"verify\":false}}"), false))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).getErrorCode().code())
                .isEqualTo("SOURCE_TLS_VERIFY_REQUIRED");
        assertThatThrownBy(() -> SourceConfigValidator.start().tls(obj("{\"tlsInsecure\":true}"), false)).isInstanceOf(BusinessException.class);
        SourceConfigValidator v = SourceConfigValidator.start();
        v.tls(obj("{\"tls\":{\"minVersion\":\"1.1\",\"sni\":\"bad host!\",\"pinnedSha256\":[\"nope\"],\"x\":1}}"), true);
        assertThat(v.errors()).extracting(e -> e.field() + ":" + e.code()).contains("connection.tls.minVersion:MIN_TLS_1_2",
                "connection.tls.sni:Pattern", "connection.tls.pinnedSha256:Pattern", "connection.tls.x:UNKNOWN_FIELD");
        SourceConfigValidator t = SourceConfigValidator.start();
        t.tls(obj("{\"tls\":\"on\"}"), true);
        assertThat(t.errors()).extracting(e -> e.field()).containsExactly("connection.tls");
    }

    @Test
    @DisplayName("[DSC-09.05][TC-DSC-269] 인증 방식 매트릭스: 커넥터 인증 방식별 필수·선택 비밀값, 설정에 방식이 없으면 넓게, Webhook은 HMAC_KEY만")
    void authMatrix() {
        assertThat(SourceSecrets.requiredKinds("CONNECTOR", "SASL_SCRAM_512")).containsExactly("PASSWORD");
        assertThat(SourceSecrets.allowedKinds("CONNECTOR", "MTLS")).containsExactlyInAnyOrder("CLIENT_CERT", "CLIENT_KEY", "CA_CERT");
        assertThat(SourceSecrets.allowedKinds("CONNECTOR", "TOKEN")).contains("TOKEN", "SAS_KEY");
        assertThat(SourceSecrets.requiredKinds("CONNECTOR", "TOKEN")).isEmpty();
        assertThat(SourceSecrets.allowedKinds("CONNECTOR", "ANY")).contains("OAUTH2_CLIENT", "AWS_KEYS", "GCP_SERVICE_ACCOUNT");
        assertThat(SourceSecrets.allowedKinds("WEBHOOK", "NONE")).containsExactly("HMAC_KEY");
        assertThat(SourceSecrets.requiredKinds("WEBHOOK", "NONE")).containsExactly("HMAC_KEY");
        Map<String, Map<String, List<String>>> m = SourceSecrets.matrixFor(List.of("NONE", "SASL_PLAIN", "MTLS", "UNKNOWN"));
        assertThat(m).containsOnlyKeys("NONE", "SASL_PLAIN", "MTLS");
        assertThat(m.get("MTLS").get("required")).containsExactly("CLIENT_CERT", "CLIENT_KEY");
        assertThat(m.get("NONE").get("optional")).containsExactly("CA_CERT");
        assertThatThrownBy(() -> SourceSecrets.checkKinds("CONNECTOR", "SASL_PLAIN", Map.of("TOKEN", Secret.of("t"))))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).getErrorCode().code())
                .isEqualTo("SOURCE_AUTH_UNSUPPORTED");
        assertThat(SourceSecrets.parse(JSON.readTree("{\"kind\":\"sas_key\",\"value\":\"k\"}"))).containsOnlyKeys("SAS_KEY");
        assertThat(SourceModels.authOf("CONNECTOR", obj("{\"auth\":{\"type\":\"bearer\"}}"))).isEqualTo("BEARER");
        assertThat(SourceModels.authOf("CONNECTOR", obj("{\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"PLAIN\"}"))).isEqualTo("SASL_PLAIN");
        assertThat(SourceModels.authOf("CONNECTOR", obj("{\"securityProtocol\":\"SASL_SSL\",\"saslMechanism\":\"SCRAM-SHA-256\"}")))
                .isEqualTo("SASL_SCRAM_256");
        assertThat(SourceModels.authOf("CONNECTOR", obj("{}"))).isEqualTo("ANY");
        assertThat(SourceModels.authOf("WEBHOOK", obj("{}"))).isEqualTo("NONE");
        assertThat(SourceModels.authOf("MQTT_SUBSCRIBE", obj("{\"auth\":\"userpass\"}"))).isEqualTo("USERPASS");
    }

    @Test
    @DisplayName("[DSC-09.06][TC-DSC-274] 인증서 비밀값: PEM 인증서의 만료 시각을 기록하고, 인증서가 아니면 400 SOURCE_CONFIG_INVALID(PEM)")
    void certificate() throws IOException {
        String pem = new String(getClass().getResourceAsStream("/fixtures/tls/test-ca.pem").readAllBytes(), StandardCharsets.US_ASCII);
        assertThat(SourceSecrets.certificateNotAfter("CA_CERT", Secret.of(pem))).isAfter(java.time.Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(SourceSecrets.certificateNotAfter("PASSWORD", Secret.of("x"))).isNull();
        assertThatThrownBy(() -> SourceSecrets.certificateNotAfter("CLIENT_CERT", Secret.of("not a cert"))).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrors().getFirst().code()).isEqualTo("PEM");
    }
}
