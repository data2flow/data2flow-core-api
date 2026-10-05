package net.java21.data2flow.core.flow;

import net.java21.data2flow.contracts.message.FlowDebugMessage;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import net.java21.data2flow.core.alarm.AlarmItSupport;
import net.java21.data2flow.core.flow.service.FlowLiveViewHub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** API-FLW-40 라이브 뷰 WebSocket: data2flow.debug(EVT-FLW-01 봉투) → 권한 확인한 연결에 node.stats·node.sample 화면 메시지 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.datasource.hikari.maximum-pool-size=3")
@DirtiesContext
class FlowLiveViewIT extends AlarmItSupport {

    @LocalServerPort
    int port;
    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    FlowLiveViewHub hub;
    @Autowired
    JsonMapper json;

    final MessageCodec codec = MessageCodec.create();

    String flow() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO data2flow_core.flows (id, organization_id, name, status, active_version, created_by, updated_by)
                        VALUES (CAST(:id AS uuid), :org, '라이브 뷰 시험', 'ACTIVE', 1, 0, 0)""")
                .param("id", id).param("org", org).update();
        return id;
    }

    WebSocketSession connect(String flowId, long userId, BlockingQueue<JsonNode> inbox) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("X-USER-ID", Long.toString(userId));
        headers.add("X-ORG-ID", Long.toString(org));
        return new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                inbox.add(json.readTree(message.getPayload()));
            }
        }, headers, URI.create("ws://localhost:" + port + "/core/stream/flows/" + flowId)).join();
    }

    void publish(FlowDebugMessage m) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(MessagingNames.EXCHANGE_DEBUG, m.routingKey(), new Message(codec.write(m), props));
    }

    JsonNode next(BlockingQueue<JsonNode> inbox, String type) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < until) {
            JsonNode n = inbox.poll(500, TimeUnit.MILLISECONDS);
            if (n != null && type.equals(n.path("type").asString())) {
                return n;
            }
        }
        return null;
    }

    @Test
    @DisplayName("[FLW-03.01][FLW-03.03][TC-FLW-062] 라이브 뷰 연결: 엔진 node.stats·node.sample 봉투를 펼쳐 보내고, ping에는 pong, 닫으면 바인딩을 푼다")
    void relaysStatsAndSamples() throws Exception {
        String flowId = flow();
        BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        WebSocketSession session = connect(flowId, operator, inbox);
        await().atMost(Duration.ofSeconds(10)).until(() -> hub.viewerCount() == 1);
        session.sendMessage(new TextMessage("{\"type\":\"subscribe\",\"samples\":true}"));
        // 바인딩이 걸린 뒤 보낸 메시지가 도착할 때까지 1초마다 다시 보낸다(손실 허용 큐)
        JsonNode stats = null;
        for (int i = 0; i < 10 && stats == null; i++) {
            publish(FlowDebugMessage.stats(flowId, "engine-0", clock.instant(), 3,
                    List.of(new FlowDebugMessage.NodeStats("n-threshold1", 12, Map.of("true", 1L, "false", 11L), 0, clock.instant(),
                            FlowDebugMessage.NodeStatus.OK, null))));
            stats = next(inbox, "node.stats");
        }
        assertThat(stats).isNotNull();
        assertThat(stats.path("version").asInt()).isEqualTo(3);
        assertThat(stats.path("nodes").get(0).path("nodeId").asString()).isEqualTo("n-threshold1");
        assertThat(stats.path("nodes").get(0).path("out").path("true").asLong()).isEqualTo(1);

        publish(FlowDebugMessage.sample(flowId, "engine-0", clock.instant(), 3, new FlowDebugMessage.NodeSample("n-threshold1", "m-1",
                FlowDebugMessage.NodeSample.Direction.OUT, "true", json.readTree("{\"spaceId\":\"" + lab + "\",\"value\":29}"), false)));
        JsonNode sample = next(inbox, "node.sample");
        assertThat(sample).isNotNull();
        assertThat(sample.path("direction").asString()).isEqualTo("out");
        assertThat(sample.path("port").asString()).isEqualTo("true");
        assertThat(sample.path("masked").asBoolean()).isFalse();
        assertThat(sample.path("payload").path("value").asInt()).isEqualTo(29);

        session.sendMessage(new TextMessage("{\"type\":\"ping\"}"));
        assertThat(next(inbox, "pong")).isNotNull();
        session.close();
        await().atMost(Duration.ofSeconds(10)).until(() -> hub.viewerCount() == 0);
    }

    @Test
    @DisplayName("[FLW-03.01][API-FLW-40] 없는(또는 보이지 않는) 플로우는 404, FLOW_READ가 없으면(VIEWER) 403으로 업그레이드 전에 거절")
    void rejectedBeforeUpgrade() {
        assertThatThrownBy(() -> connect(UUID.randomUUID().toString(), operator, new LinkedBlockingQueue<>()))
                .isInstanceOf(CompletionException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> connect(flow(), viewer, new LinkedBlockingQueue<>()))
                .isInstanceOf(CompletionException.class).hasMessageContaining("403");
        assertThat(hub.viewerCount()).isZero();
    }
}
