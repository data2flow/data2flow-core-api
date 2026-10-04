package net.java21.data2flow.core.source;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DSC-09.12 커넥터 템플릿 11종, DSC-09.05 인증 방식 매트릭스(API-DSC-56·58), DSC-09.06 TLS(운영 소스 검증 끄기 거부·인증서 만료 표시)
 * — TC-DSC-270·275·305·306·307(core 쪽)
 */
class ConnectorTemplateIT extends SourceItSupport {

    @Test
    @DisplayName("[DSC-09.12][TC-DSC-305·307] 템플릿 11종(ChirpStack v4·아카데미 iot-data·TTS v3·AWS IoT·Azure·HiveMQ·EMQX·Sparkplug B·RabbitMQ·Kafka·Milesight), preset으로 주소·디코더가 채워진다")
    void templates() throws Exception {
        String body = mvc.perform(as(org, operator, get("/core/connectors"))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        List<String> keys = JsonPath.read(body, "$.response.templates[*].key");
        assertThat(keys).contains("chirpstack-v4", "academy-iot-data", "tts-v3", "aws-iot-core", "azure-iot-hub", "hivemq-cloud", "emqx",
                "sparkplug-b", "rabbitmq", "kafka", "milesight-gateway");
        assertThat(keys).hasSizeGreaterThanOrEqualTo(11);
        mvc.perform(as(org, operator, get("/core/connector-templates/tts-v3"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.connectorKey").value("tts-v3")).andExpect(jsonPath("$.response.builtin").value(true))
                .andExpect(jsonPath("$.response.preset.connection.host").value("eu1.cloud.thethings.network"))
                .andExpect(jsonPath("$.response.preset.decoderKey").value("generic-json"));
        mvc.perform(as(org, operator, get("/core/connector-templates/nope"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("CONNECTOR_NOT_FOUND"));
        mvc.perform(as(org, operator, get("/core/connectors/kafka/schema"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.authSecretKinds.SASL_SCRAM_512.required[0]").value("PASSWORD"))
                .andExpect(jsonPath("$.response.authSecretKinds.MTLS.required.length()").value(2))
                .andExpect(jsonPath("$.response.jsonSchema.required[0]").value("bootstrapServers"));
    }

    @Test
    @DisplayName("[DSC-09.05][DSC-09.06][TC-DSC-270·275] Kafka SASL 소스: PASSWORD 저장, 방식에 없는 TOKEN 400 SOURCE_AUTH_UNSUPPORTED, 빈 값 SOURCE_SECRET_REQUIRED, 운영 소스 TLS 끄기 400, CA 인증서 만료 표시")
    void connectorSecretsAndTls() throws Exception {
        String kafka = """
                {"code":"kafka-src","name":"Kafka","type":"CONNECTOR","connectorKey":"kafka",
                 "connection":{"bootstrapServers":"kafka.example.com:9093","topics":["iot.telemetry"],"securityProtocol":"SASL_SSL",
                               "saslMechanism":"SCRAM-SHA-512","username":"d2f"},
                 "secret":{"kind":"PASSWORD","value":"kafka-pw"},"decoderKey":"generic-json","decoderConfig":{"deviceIdFrom":"$.deviceId","metrics":[{"path":"$.t","key":"temperature"}]}}""";
        long id = createSource(kafka);
        mvc.perform(as(org, integrator, json(put("/core/sources/" + id + "/secrets/token"), "{\"value\":\"t\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_AUTH_UNSUPPORTED"));
        mvc.perform(as(org, integrator, json(put("/core/sources/" + id + "/secrets/PASSWORD"), "{\"value\":\"\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_SECRET_REQUIRED"));
        String pem = new String(getClass().getResourceAsStream("/fixtures/tls/test-ca.pem").readAllBytes(), StandardCharsets.US_ASCII);
        mvc.perform(as(org, integrator, json(put("/core/sources/" + id + "/secrets/CA_CERT"), "{\"value\":\"" + pem.replace("\n", "\\n") + "\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response.kind").value("CA_CERT"));
        mvc.perform(as(org, integrator, json(put("/core/sources/" + id + "/secrets/CA_CERT"), "{\"value\":\"not a pem\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PEM"));
        mvc.perform(as(org, operator, get("/core/sources/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.secrets[?(@.kind == 'CA_CERT')].certificateExpiresAt").isNotEmpty());
        mvc.perform(as(org, integrator, json(post("/core/sources"), kafka.replace("kafka-src", "kafka-two")
                        .replace("\"secret\":{\"kind\":\"PASSWORD\",\"value\":\"kafka-pw\"}", "\"secret\":{\"kind\":\"TOKEN\",\"value\":\"t\"}"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_AUTH_UNSUPPORTED"));
        String amqp = """
                {"code":"amqp-src","name":"RabbitMQ","type":"CONNECTOR","connectorKey":"amqp091",
                 "connection":{"url":"amqps://mq.example.com:5671","queue":"telemetry","tlsInsecure":true},"decoderKey":"generic-json","decoderConfig":{"deviceIdFrom":"$.deviceId","metrics":[{"path":"$.t","key":"temperature"}]}}""";
        mvc.perform(as(org, integrator, json(post("/core/sources"), amqp))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("SOURCE_TLS_VERIFY_REQUIRED"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), amqp.replace("\"decoderKey\"", "\"isDev\":true,\"decoderKey\""))))
                .andExpect(status().isCreated());
        // MQTT 구독 소스의 tls 객체(최소 버전·검증 끄기)
        mvc.perform(as(org, integrator, json(post("/core/sources"), mqttBody("mqtt-tls", null)
                        .replace("\"auth\":\"HEADER\"", "\"tls\":{\"minVersion\":\"1.0\"},\"auth\":\"HEADER\""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("MIN_TLS_1_2"));
    }
}
