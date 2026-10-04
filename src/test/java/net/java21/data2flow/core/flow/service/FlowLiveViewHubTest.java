package net.java21.data2flow.core.flow.service;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.message.FlowDebugMessage;
import net.java21.data2flow.contracts.message.MessageCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FlowLiveViewHubTest {

    final JsonMapper json = MessageCodec.newMapper();
    final FlowLiveViewHub hub = new FlowLiveViewHub(Mockito.mock(ConnectionFactory.class), MessageCodec.create(), json, "q");

    FlowDebugMessage sample(String payload) {
        return FlowDebugMessage.sample("f-1", "engine-0", Instant.parse("2026-10-04T00:00:00Z"), 2, new FlowDebugMessage.NodeSample("n-1", "m-1",
                FlowDebugMessage.NodeSample.Direction.IN, null, json.readTree(payload), false));
    }

    FlowLiveViewHub.Viewer viewer(SpaceScope scope) {
        WebSocketSession session = Mockito.mock(WebSocketSession.class);
        Mockito.when(session.getId()).thenReturn("s");
        return new FlowLiveViewHub.Viewer(session, "f-1", scope);
    }

    @Test
    @DisplayName("[FLW-03.02][TC-FLW-064] 공간 범위가 제한된 사용자에게 범위 밖 공간의 샘플은 값을 지우고 masked=true(ADR-048)")
    void masksOutOfScope() {
        JsonNode out = hub.render(sample("{\"spaceId\":\"9\",\"value\":1}"), viewer(SpaceScope.only(Set.of(5L))));
        assertThat(out.path("masked").asBoolean()).isTrue();
        assertThat(out.path("payload").isNull()).isTrue();
        JsonNode in = hub.render(sample("{\"spaceId\":\"5\",\"value\":1}"), viewer(SpaceScope.only(Set.of(5L))));
        assertThat(in.path("masked").asBoolean()).isFalse();
        assertThat(in.path("payload").path("value").asInt()).isEqualTo(1);
        assertThat(in.path("type").asString()).isEqualTo("node.sample");
        assertThat(in.path("direction").asString()).isEqualTo("in");
    }

    @Test
    @DisplayName("[FLW-03.01] subscribe nodes·samples 조건: 고른 노드만, samples=false면 샘플 없음")
    void subscriptionFilter() {
        FlowLiveViewHub.Viewer v = viewer(SpaceScope.all());
        v.nodes = Set.of("n-2");
        assertThat(hub.render(sample("{}"), v)).isNull();
        v.nodes = null;
        v.samples = false;
        assertThat(hub.render(sample("{}"), v)).isNull();
    }
}
