package net.java21.data2flow.core.sim.service;

import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.common.InternalHttp.Raw;
import net.java21.data2flow.core.config.LoopProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * data2flow-simulator 내부 API(SIM-api §2, API-SIM-30~34). core-api가 외부 경로 {@code /api/v1/core/sim/**}를 열고 권한 확인·기준 정보
 * 생성 뒤 넘긴다. 조직은 요청자 신원({@code X-ORG-ID})으로 전달되고, simulator 오류(SIM_* 4xx, {@code errors[].field})는 그대로 전한다.
 */
@Component
public class SimulatorClient {

    private final InternalHttp http;

    public SimulatorClient(LoopProperties properties, JsonMapper json) {
        this.http = new InternalHttp("simulator", properties.simulatorBaseUrl(), properties.relayTimeout(), json);
    }

    /** {@code response}(204면 null) */
    public JsonNode call(HttpMethod method, String path, Map<String, ?> query, Object body) {
        return http.call(method, path, query, body);
    }

    /** 응답 상태와 {@code response} */
    public InternalHttp.Result send(HttpMethod method, String path, Map<String, ?> query, Object body) {
        return http.send(method, path, query, body, null);
    }

    /** 봉투 전체(목록) */
    public JsonNode envelope(HttpMethod method, String path, Map<String, ?> query, Object body) {
        return http.envelope(method, path, query, body);
    }

    /** multipart 등 이미 만든 본문 */
    public JsonNode callRaw(HttpMethod method, String path, Map<String, ?> query, byte[] body, MediaType contentType) {
        return http.callRaw(method, path, query, body, contentType);
    }

    /** 파일 내려받기(봉투 없음) */
    public Raw download(String path, Map<String, ?> query) {
        return http.download(path, query);
    }
}
