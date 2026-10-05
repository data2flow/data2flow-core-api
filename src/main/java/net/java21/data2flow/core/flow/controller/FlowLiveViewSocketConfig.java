package net.java21.data2flow.core.flow.controller;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.message.MessageCodec;
import net.java21.data2flow.contracts.web.ErrorMessages;
import net.java21.data2flow.contracts.web.ErrorResponse;
import net.java21.data2flow.core.flow.service.FlowLiveViewHub;
import net.java21.data2flow.core.flow.service.FlowSupport;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * API-FLW-40 플로우 라이브 뷰 WebSocket {@code /core/stream/flows/{flow-id}}(gateway {@code /api/v1/core/stream/**} → stripPrefix(2)).
 * 연결 전(업그레이드 요청) 권한을 본다: FLOW_READ가 없으면 403, 보이지 않는 플로우면 404 FLOW_NOT_FOUND(JSON 오류 본문, BFF가 resultCode를
 * 전한다). 신원은 gateway가 넣은 {@code X-USER-ID}·{@code X-ORG-ID}다(ADR-021). 메시지 중계는 {@link FlowLiveViewHub}가 한다.
 * 편집 참여 채널(API-FLW-42 {@code /presence})은 아직 없다.
 */
@Configuration
@EnableWebSocket
@ConditionalOnProperty(prefix = "data2flow.core.live", name = "flow-view-enabled", havingValue = "true", matchIfMissing = true)
public class FlowLiveViewSocketConfig implements WebSocketConfigurer {

    static final String PATH = "/core/stream/flows/{flow-id}";
    static final String ATTR_FLOW = "flowId";
    static final String ATTR_SCOPE = "spaceScope";

    private final RoleChecker roleChecker;
    private final FlowSupport flows;
    private final ErrorMessages messages;
    private final JsonMapper json;
    private final FlowLiveViewHub hub;

    public FlowLiveViewSocketConfig(RoleChecker roleChecker, FlowSupport flows, ErrorMessages messages, JsonMapper json,
                                    FlowLiveViewHub hub) {
        this.roleChecker = roleChecker;
        this.flows = flows;
        this.messages = messages;
        this.json = json;
        this.hub = hub;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 브라우저는 BFF에만 닿고(Origin 검사는 BFF), core에는 내부망의 gateway만 붙는다
        registry.addHandler(new Handler(hub), PATH).addInterceptors(new Guard()).setAllowedOriginPatterns("*");
    }

    /** 업그레이드 전 권한 확인 */
    final class Guard implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                       Map<String, Object> attributes) {
            String path = request.getURI().getPath();
            String flowId = path.substring(path.lastIndexOf('/') + 1);
            try {
                roleChecker.require(Permission.FLOW_READ);
                UUID id = flows.require(roleChecker.currentUser().organizationId(), flowId).id();
                attributes.put(ATTR_FLOW, id.toString());
                attributes.put(ATTR_SCOPE, roleChecker.spaceScope());
                return true;
            } catch (BusinessException ex) {
                response.setStatusCode(HttpStatus.valueOf(ex.getErrorCode().httpStatus()));
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                try {
                    response.getBody().write(json.writeValueAsString(ErrorResponse.of(ex.getErrorCode().code(),
                            messages.resolve(ex.getErrorCode(), ex.getArgs()), ex.getErrors())).getBytes(StandardCharsets.UTF_8));
                } catch (java.io.IOException ignored) {
                    // 본문 없이 상태 코드만
                }
                return false;
            }
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {
        }
    }

    static final class Handler extends TextWebSocketHandler {

        private final FlowLiveViewHub hub;

        Handler(FlowLiveViewHub hub) {
            this.hub = hub;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            hub.open(session, (String) session.getAttributes().get(ATTR_FLOW), (SpaceScope) session.getAttributes().get(ATTR_SCOPE));
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            hub.onClientMessage(session, message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            hub.close(session);
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            hub.close(session);
        }
    }

    /** 허브: 파드마다 임시 큐 하나 */
    @Configuration
    static class HubConfig {

        @Bean
        FlowLiveViewHub flowLiveViewHub(ConnectionFactory connectionFactory, MessageCodec codec, JsonMapper json) {
            return new FlowLiveViewHub(connectionFactory, codec, json, "core.debug." + UUID.randomUUID());
        }
    }
}
