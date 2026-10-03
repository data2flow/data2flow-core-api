package net.java21.data2flow.core.source;

import net.java21.data2flow.contracts.connector.AckMode;
import net.java21.data2flow.contracts.connector.AuthMethod;
import net.java21.data2flow.contracts.connector.ConnectorCatalogEntry;
import net.java21.data2flow.contracts.connector.ConnectorCategory;
import net.java21.data2flow.contracts.connector.PayloadFormat;
import net.java21.data2flow.contracts.connector.ScalingMode;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.ConnectorCatalogReported;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DSC-09.01 커넥터 카탈로그(API-DSC-55·56): 기본 유형 3개(시드) + ingress 보고(EVT-DSC-09)로 늘어나는 커넥터, 스키마 기반 설정 검증
 * (BR-DSC-22, 스키마에 없는 필드 거부), 템플릿, 플랫폼 브로커 안내(API-DSC-23) — TC-DSC-230·231
 */
class ConnectorCatalogIT extends SourceItSupport {

    @Autowired
    JsonMapper json;

    @Test
    @DisplayName("[DSC-09.01][AT-DSC-12.1] 기본 유형 3개와 템플릿, 분류·검색 필터, 스키마(x-ui → uiHints), 템플릿 preset, 없는 키 404")
    void basicCatalog() throws Exception {
        mvc.perform(as(org, operator, get("/core/connectors"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.connectors[?(@.connectorKey=='mqtt')].sourceType").value("MQTT_SUBSCRIBE"))
                .andExpect(jsonPath("$.response.connectors[?(@.connectorKey=='mqtt')].transports[3]").value("wss"))
                .andExpect(jsonPath("$.response.connectors[?(@.connectorKey=='mqtt')].lossPossible").value(false))
                .andExpect(jsonPath("$.response.connectors[?(@.connectorKey=='platform-broker')].category").value("PLATFORM"))
                .andExpect(jsonPath("$.response.connectors[?(@.connectorKey=='simulation')].scaling").value("SCALABLE"))
                .andExpect(jsonPath("$.response.templates[?(@.key=='academy-iot-data')].connectorKey").value("mqtt"));
        mvc.perform(as(org, operator, get("/core/connectors").param("category", "platform"))).andExpect(jsonPath("$.response.connectors.length()").value(2));
        mvc.perform(as(org, operator, get("/core/connectors").param("q", "WSS"))).andExpect(jsonPath("$.response.connectors[0].connectorKey").value("mqtt"));
        mvc.perform(as(org, operator, get("/core/connectors/mqtt/schema"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.key").value("mqtt"))
                .andExpect(jsonPath("$.response.jsonSchema.properties.url.type").value("string"))
                .andExpect(jsonPath("$.response.uiHints.tabs[0].name").value("connection"));
        mvc.perform(as(org, operator, get("/core/connectors/nope/schema"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("CONNECTOR_NOT_FOUND"));
        mvc.perform(as(org, operator, get("/core/connector-templates/chirpstack-v4"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.preset.topics[0].topic").value("application/+/device/+/event/up"))
                .andExpect(jsonPath("$.response.preset.decoderKey").value("chirpstack-v4"))
                .andExpect(jsonPath("$.response.builtin").value(true));
        mvc.perform(as(org, operator, get("/core/connector-templates/none"))).andExpect(status().isNotFound());
        mvc.perform(as(org, operator, get("/core/platform-broker"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.response.wssUrl").value("wss://iot-data.java21.net/mqtt"))
                .andExpect(jsonPath("$.response.topicRules.length()").value(4));
    }

    @Test
    @DisplayName("[DSC-09.01][AT-DSC-12.2][BR-DSC-22·23] ingress 보고로 nats 커넥터가 생기고, CONNECTOR 소스는 커넥터 스키마로 검증(모르는 필드 거부), 끈 커넥터는 409")
    void reportedConnectorAndSchemaValidation() throws Exception {
        var schema = json.readTree("""
                {"type":"object","additionalProperties":false,"required":["servers"],
                 "properties":{"servers":{"type":"array","minItems":1,"items":{"type":"string","pattern":"^nats://"}},
                               "subject":{"type":"string","minLength":1,"maxLength":20},"batch":{"type":"integer","minimum":1,"maximum":100},
                               "durable":{"type":"boolean"},"mode":{"enum":["PUSH","PULL"]}},
                 "x-ui":{"tabs":[{"name":"connection","fields":["servers"]}]}}""");
        var entry = new ConnectorCatalogEntry("nats", "NATS JetStream", "1.2.0", ConnectorCategory.QUEUE, schema,
                Set.of(AuthMethod.NONE, AuthMethod.TOKEN), Set.of(PayloadFormat.JSON), AckMode.AFTER_WRITE, ScalingMode.SCALABLE, false);
        deliver(EventType.CONNECTOR_CATALOG_REPORTED, org, new ConnectorCatalogReported("ingress-0", List.of(entry)), clock.instant());
        mvc.perform(as(org, operator, get("/core/connectors").param("category", "QUEUE")))
                .andExpect(jsonPath("$.response.connectors[?(@.connectorKey=='nats')].name").value("NATS JetStream"))
                .andExpect(jsonPath("$.response.connectors[?(@.connectorKey=='nats')].sourceType").value("CONNECTOR"));

        String base = """
                {"code":"nats-src","name":"NATS","type":"CONNECTOR","connectorKey":"nats","connection":%s,"decoderKey":"generic-json",
                 "decoderConfig":{"deviceIdFrom":"$.dev","metrics":[{"path":"$.v","key":"co2"}]}}""";
        String[][] bad = {
                {"{\"servers\":[\"nats://a\"],\"oops\":1}", "connection.oops"},
                {"{}", "connection.servers"},
                {"{\"servers\":[\"http://a\"]}", "connection.servers[0]"},
                {"{\"servers\":[\"nats://a\"],\"batch\":0}", "connection.batch"},
                {"{\"servers\":[\"nats://a\"],\"durable\":\"yes\"}", "connection.durable"},
                {"{\"servers\":[\"nats://a\"],\"mode\":\"PUSHY\"}", "connection.mode"},
                {"{\"servers\":[\"nats://a\"],\"subject\":\"\"}", "connection.subject"},
                {"{\"servers\":[]}", "connection.servers"},
        };
        for (String[] c : bad) {
            mvc.perform(as(org, integrator, json(post("/core/sources"), base.formatted(c[0]))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.header.resultCode").value("SOURCE_CONFIG_INVALID"))
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        mvc.perform(as(org, integrator, json(post("/core/sources"), base.formatted("{\"servers\":[\"nats://a:4222\"],\"batch\":10,\"mode\":\"PULL\"}"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.response.connectorKey").value("nats"))
                .andExpect(jsonPath("$.response.connectorVersion").value("1.2.0"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), base.replace("\"nats\"", "\"kafka-x\"").formatted("{}"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.header.resultCode").value("CONNECTOR_NOT_FOUND"));
        mvc.perform(as(org, integrator, json(post("/core/sources"), base.replace(",\"connectorKey\":\"nats\"", "").formatted("{}"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("connectorKey"));
        jdbc.sql("UPDATE data2flow_core.connector_catalogs SET enabled = false, disabled_reason = 'GPL-3.0' WHERE connector_key = 'nats'").update();
        try {
            mvc.perform(as(org, integrator, json(post("/core/sources"), base.replace("nats-src", "nats-2").formatted("{\"servers\":[\"nats://a\"]}"))))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.header.resultCode").value("CONNECTOR_UNAVAILABLE"))
                    .andExpect(jsonPath("$.header.resultMessage").value("이 커넥터는 지금 사용할 수 없습니다: GPL-3.0"));
            // 다시 보고돼도 운영 판단(enabled)은 유지
            deliver(EventType.CONNECTOR_CATALOG_REPORTED, org, new ConnectorCatalogReported("ingress-1", List.of(entry)), clock.instant());
            mvc.perform(as(org, operator, get("/core/connectors").param("q", "nats")))
                    .andExpect(jsonPath("$.response.connectors[0].enabled").value(false));
        } finally {
            // 카탈로그는 테스트마다 비우지 않는 시스템 공용 테이블이라 지운다
            jdbc.sql("DELETE FROM data2flow_core.data_sources WHERE connector_key = 'nats'").update();
            jdbc.sql("DELETE FROM data2flow_core.connector_catalogs WHERE connector_key = 'nats'").update();
        }
    }
}
