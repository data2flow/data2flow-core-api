package net.java21.data2flow.core.notify.service;

import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.config.LoopProperties;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * data2flow-action notification 패키지 내부 API(OPS-api §5, RUL-api API-RUL-27). 채널 SPI 구현·발송 이력은 action에 있다(ADR-033).
 * 4xx는 그대로 전하고 5xx·연결 실패는 503(ADR-045).
 */
@Component
public class NotificationActionClient {

    private final InternalHttp http;

    public NotificationActionClient(LoopProperties properties, JsonMapper json) {
        this.http = new InternalHttp("action", properties.actionBaseUrl(), properties.relayTimeout().plusSeconds(4), json);
    }

    /** API-OPS-34 원천: 등록된 채널 SPI 목록 {@code GET /internal/action/notifications/channel-types} */
    public JsonNode channelTypes() {
        return http.envelope(HttpMethod.GET, "/internal/action/notifications/channel-types", null, null);
    }

    /** API-OPS-31 테스트 발송 {@code POST /internal/action/notifications/channels/test} {type, config, secret} → {ok, latencyMs, providerResponse} */
    public JsonNode testChannel(Map<String, Object> body) {
        return http.call(HttpMethod.POST, "/internal/action/notifications/channels/test", null, body);
    }

    /** API-RUL-27 발송 이력(커서 목록 봉투 그대로) */
    public JsonNode deliveries(Map<String, Object> query) {
        return http.envelope(HttpMethod.GET, "/internal/action/notifications/deliveries", query, null);
    }

    /** API-OPS-33 재발송 */
    public JsonNode resend(String deliveryId) {
        return http.call(HttpMethod.POST, "/internal/action/notifications/deliveries/" + deliveryId + "/resend", null, null);
    }

    /** 저장 뒤 웹훅 등록(텔레그램 setWebhook) {@code POST /internal/action/notifications/channels/{id}/webhook} */
    public JsonNode registerWebhook(long channelId) {
        return http.call(HttpMethod.POST, "/internal/action/notifications/channels/" + channelId + "/webhook", null, Map.of());
    }

    /** 메신저 연결 딥링크 {@code POST /internal/action/notifications/links} {channel, organizationId, userId, code, expiresAt} */
    public JsonNode link(Map<String, Object> body) {
        return http.call(HttpMethod.POST, "/internal/action/notifications/links", null, body);
    }
}
