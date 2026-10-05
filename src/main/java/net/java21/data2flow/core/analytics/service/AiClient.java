package net.java21.data2flow.core.analytics.service;

import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.config.AnalyticsProperties;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * data2flow-ai 내부 API({@code http://data2flow-ai/internal/ai/**}). 지금은 스크립트 AI 초안(API-SCR-16 → {@code POST /internal/ai/script-drafts})
 * 하나를 부른다. 요청자 신원({@code X-USER-ID}·{@code X-ORG-ID})을 함께 보내 ai가 그 사용자의 한도·권한으로 처리한다(BR-AIA-05).
 */
@Component
public class AiClient {

    private final InternalHttp http;

    public AiClient(AnalyticsProperties properties, JsonMapper json) {
        this.http = new InternalHttp("ai", properties.aiBaseUrl(), properties.relayTimeout().plusSeconds(20), json);
    }

    public JsonNode call(HttpMethod method, String path, Object body) {
        return http.call(method, path, null, body);
    }
}
