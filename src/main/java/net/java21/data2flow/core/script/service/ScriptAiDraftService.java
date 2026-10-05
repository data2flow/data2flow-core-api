package net.java21.data2flow.core.script.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.analytics.domain.AnalyticsErrorCode;
import net.java21.data2flow.core.analytics.service.AiClient;
import net.java21.data2flow.core.common.RelayedErrorException;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 스크립트 AI 작성 도우미(API-SCR-16 {@code POST /core/scripts/ai-draft}, SCR-03.07·AIA-04.01·04.02). SCRIPT_WRITE(INTEGRATOR 이상)이고,
 * 장기 토큰 요청은 받지 않는다(사람이 편집기에서만, BR-AIA-07). 요청을 확인한 뒤 ai 내부 {@code POST /internal/ai/script-drafts}로 넘기고,
 * 응답 {@code {code, explanation, testRun, staticCheck}}을 그대로 돌려준다. 저장·배포는 하지 않는다(BR-SCR-18 — 배포는 사람이 기존 배포 API로).
 * ai 장애·한도 소진·시간 초과는 503 SCRIPT_AI_UNAVAILABLE(TC-SCR-065), 요청 형식 오류(400)는 그대로 전한다.
 */
@Service
public class ScriptAiDraftService {

    static final Set<String> KINDS = Set.of("DECODE", "TRANSFORM");

    private final RoleChecker roleChecker;
    private final AiClient ai;

    public ScriptAiDraftService(RoleChecker roleChecker, AiClient ai) {
        this.roleChecker = roleChecker;
        this.ai = ai;
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
        try {
            return ai.call(HttpMethod.POST, "/internal/ai/script-drafts", body);
        } catch (RelayedErrorException ex) {
            if (ex.status() == 400) {
                throw ex;
            }
            throw new BusinessException(AnalyticsErrorCode.SCRIPT_AI_UNAVAILABLE);
        } catch (BusinessException ex) {
            throw new BusinessException(AnalyticsErrorCode.SCRIPT_AI_UNAVAILABLE);
        }
    }

    private static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "Invalid", null)));
    }
}
