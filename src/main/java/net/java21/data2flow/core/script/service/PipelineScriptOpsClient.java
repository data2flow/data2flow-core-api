package net.java21.data2flow.core.script.service;

import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.config.CoreProperties;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * data2flow-pipeline 스크립트 M5 내부 API(SCR-api §2): API-SCR-35 테스트 케이스 일괄 실행, API-SCR-36 운영 지표, API-SCR-37 수식 컴파일,
 * API-SCR-38 수식 미리 보기. 4xx(예: 38의 SCRIPT_FORMULA_INVALID)는 그대로 전하고 5xx·연결 실패는 503.
 */
@Component
public class PipelineScriptOpsClient {

    private final InternalHttp http;

    public PipelineScriptOpsClient(CoreProperties properties, JsonMapper json) {
        // 케이스 50개 × 실행 50ms + 컴파일 여유
        this.http = new InternalHttp("pipeline", properties.pipelineBaseUrl(), Duration.ofSeconds(20), json);
    }

    /** API-SCR-35 {organizationId, kind, code, scriptId?, moduleRefs?, cases[]} → {passed, failed, results[]} */
    public JsonNode runTestCases(Map<String, Object> body) {
        return http.call(HttpMethod.POST, "/internal/pipeline/scripts/test-cases/run", null, body);
    }

    /** API-SCR-36 → {points[], warnings[]} */
    public JsonNode stats(long organizationId, long scriptId, Instant from, Instant to, String step) {
        return http.call(HttpMethod.GET, "/internal/pipeline/scripts/" + scriptId + "/stats",
                InternalHttp.query("organizationId", organizationId, "from", from.toString(), "to", to.toString(), "step", step), null);
    }

    /** API-SCR-37 {organizationId, expression, knownKeys?} → {ok, compiledJs, inputs[], windows{}, error?} */
    public JsonNode compileFormula(long organizationId, String expression) {
        return http.call(HttpMethod.POST, "/internal/pipeline/formula-metrics/compile", null,
                Map.of("organizationId", organizationId, "expression", expression));
    }

    /** API-SCR-38 {organizationId, expression, deviceId, hours} → {series[], inputs{}} */
    public JsonNode previewFormula(long organizationId, String expression, long deviceId, int hours) {
        return http.call(HttpMethod.POST, "/internal/pipeline/formula-metrics/preview", null,
                Map.of("organizationId", organizationId, "expression", expression, "deviceId", deviceId, "hours", hours));
    }
}
