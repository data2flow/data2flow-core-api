package net.java21.data2flow.core.flow.domain;

import net.java21.data2flow.contracts.message.MessageSchemas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** FLW-08.02·FLW-01.02: core 노드 카탈로그(API-FLW-30)의 모든 노드는 contracts flow-node-type.v1을 통과하고 error 출력 포트를 가진다 */
class NodeCatalogSchemaTest {

    @Test
    @DisplayName("[FLW-01.02][FLW-08.02] 카탈로그 16종 모두 flow-node-type.v1 스키마 통과, 트리거를 포함해 마지막 출력은 error(type=error)")
    void everyNodeHasErrorPortAndMatchesSchema() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        JsonNode all;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(NodeCatalog.RESOURCE)) {
            all = json.readTree(in);
        }
        assertThat(all.size()).isEqualTo(16);
        for (JsonNode node : all.values()) {
            MessageSchemas.assertValid(MessageSchemas.FLOW_NODE_TYPE, node);
            JsonNode outputs = node.path("outputs");
            JsonNode last = outputs.get(outputs.size() - 1);
            assertThat(last.path("name").asString("")).as(node.path("type").asString("")).isEqualTo("error");
            assertThat(last.path("type").asString("")).isEqualTo("error");
        }
        assertThat(NodeCatalog.load(json).find("trigger.telemetry").orElseThrow().outputs()).hasSize(2);
    }
}
