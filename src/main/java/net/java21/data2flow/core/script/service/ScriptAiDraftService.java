package net.java21.data2flow.core.script.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.analytics.domain.AnalyticsErrorCode;
import net.java21.data2flow.core.analytics.service.AiClient;
import net.java21.data2flow.core.common.RelayedErrorException;
import net.java21.data2flow.core.script.dto.ScriptDtos.TestRunRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 스크립트 AI 작성 도우미(API-SCR-16 {@code POST /core/scripts/ai-draft}, SCR-03.07·AIA-04.01·04.02). SCRIPT_WRITE(INTEGRATOR 이상)이고,
 * 장기 토큰 요청은 받지 않는다(사람이 편집기에서만, BR-AIA-07). 요청을 확인한 뒤 ai 내부 {@code POST /internal/ai/script-drafts}로 넘기고,
 * 응답 {@code {code, explanation, testRun, staticCheck}}을 돌려준다. 저장·배포는 하지 않는다(BR-SCR-18 — 배포는 사람이 기존 배포 API로).
 * ai 내부 API는 원본 ID가 아니라 payload를 받으므로(design/openapi/ai-internal.yaml {@code samples}) core가 {@code sampleRawMessageIds}를
 * 테스트 실행과 같은 규칙(같은 조직·공간 범위, 밖이면 404)으로 꺼내 넘기고, 초안이 오면 정적 검사(API-SCR-30)와 첫 샘플 테스트 실행(API-SCR-31)을
 * 붙인다. 검사·실행이 실패해도 초안은 돌려준다(그 항목만 null).
 * ai 장애·한도 소진·시간 초과는 503 SCRIPT_AI_UNAVAILABLE(TC-SCR-065), 요청 형식 오류(400)는 그대로 전한다.
 */
@Service
public class ScriptAiDraftService {

    static final Set<String> KINDS = Set.of("DECODE", "TRANSFORM");

    private static final Logger log = LoggerFactory.getLogger(ScriptAiDraftService.class);

    private final RoleChecker roleChecker;
    private final AiClient ai;
    private final ScriptTestRunService testRuns;
    private final PipelineScriptClient pipeline;
    private final JsonMapper json;

    public ScriptAiDraftService(RoleChecker roleChecker, AiClient ai, ScriptTestRunService testRuns, PipelineScriptClient pipeline,
                                JsonMapper json) {
        this.roleChecker = roleChecker;
        this.ai = ai;
        this.testRuns = testRuns;
        this.pipeline = pipeline;
        this.json = json;
    }

    public JsonNode draft(JsonNode body) {
        roleChecker.require(Permission.SCRIPT_WRITE);
        roleChecker.requireInteractive();
        if (body == null || !body.isObject()) {
            throw invalid("body");
        }
        String kind = body.path("kind").asString("").toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw invalid("kind");
        }
        String requirement = body.path("requirement").asString("").strip();
        if (requirement.isEmpty() || requirement.length() > 2000) {
            throw invalid("requirement");
        }
        JsonNode samples = body.get("sampleRawMessageIds");
        if (samples != null && !samples.isNull() && (!samples.isArray() || samples.size() > 5)) {
            throw invalid("sampleRawMessageIds");
        }
        long org = roleChecker.currentUser().organizationId();
        List<String> sampleIds = new java.util.ArrayList<>();
        ArrayNode payloads = json.createArrayNode();
        if (samples != null && samples.isArray()) {
            for (JsonNode id : samples) {
                String raw = id.isNumber() ? id.asString() : id.asString("");
                payloads.add(testRuns.sampleInput(org, raw).path("payload"));
                sampleIds.add(raw);
            }
        }
        ObjectNode request = json.createObjectNode();
        request.put("kind", kind);
        request.put("requirement", requirement);
        request.set("samples", payloads);
        if (body.hasNonNull("currentCode")) {
            request.set("currentCode", body.get("currentCode"));
        }
        JsonNode draft;
        try {
            draft = ai.call(HttpMethod.POST, "/internal/ai/script-drafts", request);
        } catch (RelayedErrorException ex) {
            if (ex.status() == 400) {
                throw ex;
            }
            throw new BusinessException(AnalyticsErrorCode.SCRIPT_AI_UNAVAILABLE);
        } catch (BusinessException ex) {
            throw new BusinessException(AnalyticsErrorCode.SCRIPT_AI_UNAVAILABLE);
        }
        ObjectNode out = json.createObjectNode();
        String code = draft == null ? "" : draft.path("code").asString("");
        out.put("code", code);
        out.put("explanation", draft == null ? "" : draft.path("explanation").asString(""));
        out.set("testRun", null);
        out.set("staticCheck", null);
        if (code.isBlank()) {
            return out;
        }
        try {
            out.set("staticCheck", json.valueToTree(pipeline.check(org, kind, code, null)));
        } catch (BusinessException | RelayedErrorException ex) {
            log.warn("AI 초안 정적 검사 실패: {}", ex.getMessage());
        }
        if (!sampleIds.isEmpty()) {
            try {
                out.set("testRun", testRuns.testRun(new TestRunRequest(kind, code, null, sampleIds.getFirst(), null, null)));
            } catch (BusinessException | RelayedErrorException ex) {
                log.warn("AI 초안 테스트 실행 실패: {}", ex.getMessage());
            }
        }
        return out;
    }

    private static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "Invalid", null)));
    }
}
