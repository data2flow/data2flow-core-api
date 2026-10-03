package net.java21.data2flow.core.control.service;

import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.common.InternalHttp.Result;
import net.java21.data2flow.core.config.LoopProperties;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * data2flow-action 내부 API(ACT-api §5.2). 명령 실행·상태·드라이버 호출은 action의 제어 창구가 맡고 core는 권한·공간 범위를 본 뒤 넘긴다.
 * 오류(4xx)는 그대로 화면에 전한다(거부된 명령의 {@code response.commandId} 포함).
 */
@Component
public class ActionClient {

    private final InternalHttp http;

    public ActionClient(LoopProperties properties, JsonMapper json) {
        this.http = new InternalHttp("action", properties.actionBaseUrl(), properties.relayTimeout().plusSeconds(4), json);
    }

    /** API-ACT-01 → {@code POST /internal/action/commands}. 상태(200·202)를 그대로 돌려준다 */
    public Result command(Map<String, Object> body, String idempotencyKey) {
        return http.send(HttpMethod.POST, "/internal/action/commands", null, body,
                h -> h.set(DataflowHeaders.IDEMPOTENCY_KEY, idempotencyKey));
    }

    public JsonNode getCommand(String commandId) {
        return http.call(HttpMethod.GET, "/internal/action/commands/" + commandId, null, null);
    }

    /** API-ACT-02 기기별 명령 이력(커서 목록 봉투 그대로) → {@code GET /internal/action/devices/{device-id}/commands} */
    public JsonNode deviceCommands(long deviceId, Map<String, Object> query) {
        return http.envelope(HttpMethod.GET, "/internal/action/devices/" + deviceId + "/commands", query, null);
    }

    public JsonNode cancel(String commandId) {
        return http.call(HttpMethod.POST, "/internal/action/commands/" + commandId + "/cancel", null, null);
    }

    public JsonNode control(long deviceId) {
        return http.call(HttpMethod.GET, "/internal/action/devices/" + deviceId + "/control", null, null);
    }

    public JsonNode shadow(long deviceId) {
        return http.call(HttpMethod.GET, "/internal/action/devices/" + deviceId + "/shadow", null, null);
    }

    public void releaseManualOverride(long deviceId, String capability) {
        http.call(HttpMethod.DELETE, "/internal/action/devices/" + deviceId + "/manual-override",
                InternalHttp.query("capability", capability), null);
    }

    public JsonNode healthcheck(long driverId) {
        return http.call(HttpMethod.POST, "/internal/action/drivers/" + driverId + "/healthcheck", null, null);
    }

    public JsonNode metrics(long driverId, String window) {
        return http.call(HttpMethod.GET, "/internal/action/drivers/" + driverId + "/metrics", InternalHttp.query("window", window), null);
    }
}
