package net.java21.data2flow.core.flow.service;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.message.FlowDebugMessage;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.messaging.MessagingNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 플로우 라이브 뷰 중계(API-FLW-40, FLW-03.01~03.03, EVT-FLW-01, ADR-048). 파드마다 임시 큐 하나({@code core.debug.{uuid}},
 * {@code x-max-length} 1000·{@code x-overflow=drop-head}, 손실 허용)를 두고, 열린 편집기가 있는 플로우의 {@code flow.{flowId}}만
 * {@code data2flow.debug}에 바인딩한다. 받은 {@link FlowDebugMessage} 봉투를 펼쳐 화면 메시지({@code node.stats}·{@code node.sample})로
 * 보낸다. 샘플의 {@code payload.spaceId}가 사용자 공간 범위 밖이면 값을 지우고 {@code masked=true}로 보낸다(엔진은 가리지 않음).
 *
 * <p>클라이언트 메시지: {@code {type: subscribe, nodes?: [nodeId], samples: boolean}}(기본 전체 노드·샘플 포함), {@code {type: ping}} → pong.
 */
public class FlowLiveViewHub implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FlowLiveViewHub.class);
    /** 한 연결의 보내기 대기 상한(느린 연결은 끊는다) */
    static final int SEND_TIME_LIMIT_MS = 5_000;
    static final int BUFFER_LIMIT = 512 * 1024;

    /** 연결 하나: 사용자 범위·구독 조건 */
    public static final class Viewer {
        final WebSocketSession session;
        final String flowId;
        final SpaceScope scope;
        volatile Set<String> nodes;
        volatile boolean samples = true;

        Viewer(WebSocketSession session, String flowId, SpaceScope scope) {
            this.session = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_LIMIT);
            this.flowId = flowId;
            this.scope = scope;
        }
    }

    private final ConnectionFactory connectionFactory;
    private final RabbitAdmin admin;
    private final MessageCodec codec;
    private final JsonMapper json;
    private final String queueName;
    private final Map<String, Set<Viewer>> viewers = new ConcurrentHashMap<>();
    private final Map<String, Viewer> bySession = new ConcurrentHashMap<>();
    private volatile SimpleMessageListenerContainer container;
    private volatile boolean running;

    public FlowLiveViewHub(ConnectionFactory connectionFactory, MessageCodec codec, JsonMapper json, String queueName) {
        this.queueName = queueName;
        this.connectionFactory = connectionFactory;
        this.admin = new RabbitAdmin(connectionFactory);
        this.codec = codec;
        this.json = json;
    }

    /** 연결 열림: 이 플로우를 처음 보는 연결이면 바인딩을 건다 */
    public void open(WebSocketSession session, String flowId, SpaceScope scope) {
        Viewer viewer = new Viewer(session, flowId, scope);
        bySession.put(session.getId(), viewer);
        Set<Viewer> set = viewers.computeIfAbsent(flowId, k -> new CopyOnWriteArraySet<>());
        boolean first;
        synchronized (set) {
            first = set.isEmpty();
            set.add(viewer);
        }
        if (first) {
            bind(flowId);
        }
    }

    /** 연결 닫힘: 마지막 연결이면 바인딩을 푼다 */
    public void close(WebSocketSession session) {
        Viewer viewer = bySession.remove(session.getId());
        if (viewer == null) {
            return;
        }
        Set<Viewer> set = viewers.get(viewer.flowId);
        if (set == null) {
            return;
        }
        boolean last;
        synchronized (set) {
            set.remove(viewer);
            last = set.isEmpty();
            if (last) {
                viewers.remove(viewer.flowId, set);
            }
        }
        if (last) {
            unbind(viewer.flowId);
        }
    }

    /** 클라이언트 메시지(subscribe·ping) */
    public void onClientMessage(WebSocketSession session, String payload) {
        Viewer viewer = bySession.get(session.getId());
        if (viewer == null) {
            return;
        }
        JsonNode msg;
        try {
            msg = json.readTree(payload);
        } catch (RuntimeException ex) {
            return;
        }
        String type = msg.path("type").asString("");
        if ("ping".equals(type)) {
            send(viewer, json.createObjectNode().put("type", "pong"));
        } else if ("subscribe".equals(type)) {
            if (msg.path("nodes").isArray() && !msg.get("nodes").isEmpty()) {
                Set<String> nodes = new HashSet<>();
                msg.get("nodes").forEach(n -> nodes.add(n.asString()));
                viewer.nodes = nodes;
            } else {
                viewer.nodes = null;
            }
            viewer.samples = !msg.has("samples") || msg.get("samples").asBoolean(true);
        }
    }

    public int viewerCount() {
        return bySession.size();
    }

    /** 디버그 메시지 하나를 이 플로우를 보는 연결들에 보낸다(형식 오류는 버림) */
    void deliver(byte[] body) {
        FlowDebugMessage message;
        try {
            message = codec.read(body, FlowDebugMessage.class);
        } catch (RuntimeException ex) {
            log.debug("라이브 뷰 메시지 형식 오류라 버립니다: {}", ex.getMessage());
            return;
        }
        Set<Viewer> set = viewers.get(message.flowId());
        if (set == null || set.isEmpty()) {
            return;
        }
        for (Viewer viewer : set) {
            ObjectNode out = render(message, viewer);
            if (out != null) {
                send(viewer, out);
            }
        }
    }

    /** API-FLW-40 화면 메시지. 이 연결이 받지 않을 메시지면 null */
    ObjectNode render(FlowDebugMessage m, Viewer viewer) {
        ObjectNode out = json.createObjectNode();
        out.put("t", m.t().toString());
        out.put("version", m.version());
        out.put("instanceId", m.instanceId());
        if (m.type() == FlowDebugMessage.Type.NODE_STATS) {
            out.put("type", "node.stats");
            ArrayNode nodes = out.putArray("nodes");
            for (FlowDebugMessage.NodeStats s : m.stats()) {
                if (viewer.nodes == null || viewer.nodes.contains(s.nodeId())) {
                    nodes.add(json.valueToTree(s));
                }
            }
            return out;
        }
        if (m.type() != FlowDebugMessage.Type.NODE_SAMPLE || !viewer.samples) {
            return null;
        }
        FlowDebugMessage.NodeSample s = m.sample();
        if (viewer.nodes != null && !viewer.nodes.contains(s.nodeId())) {
            return null;
        }
        boolean masked = s.masked() || (!viewer.scope.unrestricted() && FlowRunService.outOfScope(s.payload(), viewer.scope));
        out.put("type", "node.sample");
        out.put("nodeId", s.nodeId());
        out.put("messageId", s.messageId());
        out.put("direction", s.direction() == FlowDebugMessage.NodeSample.Direction.IN ? "in" : "out");
        if (s.port() != null) {
            out.put("port", s.port());
        }
        if (masked || s.payload() == null) {
            out.putNull("payload");
        } else {
            out.set("payload", s.payload());
        }
        out.put("masked", masked);
        return out;
    }

    private void send(Viewer viewer, JsonNode message) {
        try {
            if (viewer.session.isOpen()) {
                viewer.session.sendMessage(new TextMessage(json.writeValueAsString(message)));
            }
        } catch (IOException | RuntimeException ex) {
            log.debug("라이브 뷰 전송 실패(연결 닫음): {}", ex.toString());
            try {
                viewer.session.close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException ignored) {
                // 이미 닫힘
            }
        }
    }

    private void bind(String flowId) {
        try {
            admin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE, MessagingNames.EXCHANGE_DEBUG,
                    FlowDebugMessage.routingKey(flowId), null));
        } catch (RuntimeException ex) {
            log.warn("라이브 뷰 바인딩 실패 flow={}: {}", flowId, ex.toString());
        }
    }

    private void unbind(String flowId) {
        try {
            admin.removeBinding(new Binding(queueName, Binding.DestinationType.QUEUE, MessagingNames.EXCHANGE_DEBUG,
                    FlowDebugMessage.routingKey(flowId), null));
        } catch (RuntimeException ex) {
            log.debug("라이브 뷰 바인딩 해제 실패 flow={}: {}", flowId, ex.toString());
        }
    }

    // ------------------------------------------------------------------ 수명

    /** 큐와 exchange 선언(연결이 새로 열릴 때마다 다시: 임시 큐는 연결과 함께 사라지므로 바인딩도 다시 건다) */
    void declare() {
        admin.declareExchange(new TopicExchange(MessagingNames.EXCHANGE_DEBUG, true, false));
        admin.declareQueue(queue(queueName));
        viewers.keySet().forEach(this::bind);
    }

    public static Queue queue(String name) {
        return new Queue(name, false, true, true, Map.of("x-queue-type", "classic", "x-max-length", 1000, "x-overflow", "drop-head"));
    }

    @Override
    public void start() {
        try {
            declare();
        } catch (RuntimeException ex) {
            log.warn("라이브 뷰 큐 선언 실패(연결되면 다시): {}", ex.toString());
        }
        connectionFactory.addConnectionListener(connection -> {
            try {
                declare();
            } catch (RuntimeException ex) {
                log.warn("라이브 뷰 큐 다시 선언 실패: {}", ex.toString());
            }
        });
        SimpleMessageListenerContainer c = new SimpleMessageListenerContainer(connectionFactory);
        c.setQueueNames(queueName);
        c.setAcknowledgeMode(AcknowledgeMode.NONE);
        c.setExclusive(true);
        c.setMissingQueuesFatal(false);
        c.setMessageListener(message -> deliver(message.getBody()));
        c.afterPropertiesSet();
        c.start();
        container = c;
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        SimpleMessageListenerContainer c = container;
        if (c != null) {
            c.stop();
        }
        for (Viewer viewer : bySession.values()) {
            try {
                viewer.session.close(CloseStatus.GOING_AWAY);
            } catch (IOException ignored) {
                // 이미 닫힘
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public String queueName() {
        return queueName;
    }
}
