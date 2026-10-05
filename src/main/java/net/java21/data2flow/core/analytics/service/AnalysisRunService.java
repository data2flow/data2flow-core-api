package net.java21.data2flow.core.analytics.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.analytics.domain.AnalyticsErrorCode;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository.AnalysisRef;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.InternalHttp;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 분석 실행·결과·모델·피드백(API-ANA-08~10·12~15·23·24, ANA-04·05·07). 실행 요청(API-ANA-08)은 core가 충분성을 다시 확인한다:
 * <ul>
 *   <li>FAIL → 409 ANALYSIS_INSUFFICIENT_DATA {reason}, 시계열 50개·원본 500만 포인트·템플릿 최대 기간을 넘으면 409 ANALYSIS_LIMIT_EXCEEDED
 *       (BR-ANA-07, TC-ANA-109·187)</li>
 *   <li>WARN인데 acknowledgeWarnings=false → 400 ANALYSIS_WARNING_NOT_ACKNOWLEDGED(BR-ANA-05, TC-ANA-088)</li>
 *   <li>그 뒤 API-ANA-33 {@code {analysisId, trigger, mode}}. 템플릿이 fast이고 5만 포인트 이하면 SYNC, 아니면 ASYNC. 장기 토큰(MCP)으로 온
 *       요청은 trigger=API</li>
 * </ul>
 * 결과의 기술 오류 상세({@code errorDetail})는 INTEGRATOR 이상만 본다(BR-ANA-16). 남의 분석·공간 범위 밖 분석의 실행은 404.
 */
@Service
public class AnalysisRunService {

    static final int MAX_SERIES = 50;
    static final long MAX_RAW_POINTS = 5_000_000L;
    static final long SYNC_MAX_POINTS = 50_000L;
    static final Set<String> LIMIT_CODES = Set.of("TOO_MANY_SERIES", "TOO_MANY_POINTS", "MAX_SERIES_EXCEEDED", "MAX_POINTS_EXCEEDED",
            "PERIOD_TOO_LONG", "MAX_PERIOD_EXCEEDED", "ANALYSIS_LIMIT_EXCEEDED");

    private final AnalyticsAccess access;
    private final AnalyticsClient analytics;
    private final AnalyticsTemplateService templates;
    private final AnalysisRefRepository refs;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public AnalysisRunService(AnalyticsAccess access, AnalyticsClient analytics, AnalyticsTemplateService templates, AnalysisRefRepository refs,
                              Audits audits, JsonMapper json, Clock clock) {
        this.access = access;
        this.analytics = analytics;
        this.templates = templates;
        this.refs = refs;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-ANA-08 실행 요청 — 202 {runId, status, queuePosition?} */
    @Transactional
    public Map<String, Object> run(long analysisId, JsonNode body) {
        AccessGrant grant = access.run();
        CurrentUser user = access.user();
        AnalysisRef ref = access.visible(analysisId, grant);
        if (!templates.enabled(ref.templateKey())) {
            throw new BusinessException(AnalyticsErrorCode.TEMPLATE_DISABLED);
        }
        boolean acknowledged = body != null && body.path("acknowledgeWarnings").asBoolean(false);
        JsonNode analysis = analytics.call(HttpMethod.GET, "/internal/analytics/analyses/" + analysisId, null, null,
                AnalyticsErrorCode.ANALYSIS_NOT_FOUND);
        String version = BindingResolver.text(analysis, "templateVersion");
        JsonNode template = templates.template(ref.templateKey(), version);
        ObjectNode check = json.createObjectNode();
        check.put("version", version);
        for (String f : new String[]{"bindings", "period", "resolution", "qualityFilter", "includeVirtual"}) {
            if (analysis.has(f)) {
                check.set(f, analysis.get(f));
            }
        }
        JsonNode result = analytics.call(HttpMethod.POST, "/internal/analytics/templates/" + ref.templateKey() + "/check", null, check,
                AnalyticsErrorCode.TEMPLATE_NOT_FOUND);
        evaluate(result, acknowledged);
        long points = result.path("stats").path("points").asLong(Long.MAX_VALUE);
        String mode = template.path("fast").asBoolean(false) && points <= SYNC_MAX_POINTS ? "SYNC" : "ASYNC";
        String trigger = user.viaAccessToken() ? "API" : "MANUAL";
        Map<String, Object> out = request(user.organizationId(), analysisId, trigger, mode);
        audits.record(audits.event(user.organizationId(), "ANALYSIS_RUN_REQUESTED").actor(user).target("ANALYSIS", Long.toString(analysisId))
                .detail("runId", out.get("runId")).detail("trigger", trigger).detail("mode", mode));
        return out;
    }

    /** 실행 요청(API-ANA-33)과 색인 반영. 일정 실행기도 쓴다 */
    Map<String, Object> request(long org, long analysisId, String trigger, String mode) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("analysisId", Long.toString(analysisId));
        req.put("trigger", trigger);
        req.put("mode", mode);
        InternalHttp.Result res = analytics.send(HttpMethod.POST, "/internal/analytics/runs", null, req, null, AnalyticsErrorCode.ANALYSIS_NOT_FOUND);
        JsonNode r = res.response();
        JsonNode run = r != null && r.has("run") ? r.get("run") : r;
        Long runId = AnalysisService.longOrNull(run == null ? null : run.has("runId") ? run.get("runId") : run.get("id"));
        if (runId == null) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        String status = run.path("status").asString(res.status() == 200 ? "SUCCEEDED" : "PENDING");
        Instant finishedAt = run.hasNonNull("finishedAt") ? Instant.parse(run.get("finishedAt").asString()) : null;
        refs.recordRun(org, analysisId, runId, status, finishedAt);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", Long.toString(runId));
        out.put("status", status);
        if (run.hasNonNull("queuePosition")) {
            out.put("queuePosition", run.get("queuePosition").asInt());
        }
        return out;
    }

    /** 충분성 판정 결과로 실행을 막는다(BR-ANA-04·05·07) */
    static void evaluate(JsonNode check, boolean acknowledged) {
        String level = check == null ? "OK" : check.path("level").asString("OK");
        JsonNode stats = check == null ? null : check.get("stats");
        boolean overLimit = stats != null && (stats.path("seriesCount").asInt(0) > MAX_SERIES
                || stats.path("estimatedRawPoints").asLong(0) > MAX_RAW_POINTS);
        String suggest = "1h";
        String reason = null;
        if (check != null) {
            for (JsonNode issue : check.path("issues").values()) {
                String code = issue.path("code").asString("");
                JsonNode fix = issue.get("fix");
                if (fix != null && "SET_RESOLUTION".equals(fix.path("type").asString(""))) {
                    suggest = fix.path("value").asString(suggest);
                }
                if ("FAIL".equals(issue.path("severity").asString(""))) {
                    if (LIMIT_CODES.contains(code)) {
                        overLimit = true;
                    } else if (reason == null) {
                        reason = issue.path("message").asString(code);
                    }
                }
            }
        }
        if (overLimit) {
            throw new BusinessException(AnalyticsErrorCode.ANALYSIS_LIMIT_EXCEEDED, suggest);
        }
        if ("FAIL".equals(level)) {
            throw new BusinessException(AnalyticsErrorCode.ANALYSIS_INSUFFICIENT_DATA, reason == null ? "" : reason);
        }
        if ("WARN".equals(level) && !acknowledged) {
            throw new BusinessException(AnalyticsErrorCode.ANALYSIS_WARNING_NOT_ACKNOWLEDGED);
        }
    }

    /** API-ANA-09 실행 이력 */
    @Transactional(readOnly = true)
    public JsonNode runs(long analysisId, Integer page, Integer size) {
        AccessGrant grant = access.view();
        access.visible(analysisId, grant);
        JsonNode envelope = analytics.envelope(HttpMethod.GET, "/internal/analytics/analyses/" + analysisId + "/runs",
                InternalHttp.query("page", page, "size", size), null, AnalyticsErrorCode.ANALYSIS_NOT_FOUND);
        if (envelope == null || access.has(AnalyticsAccess.Rank.INTEGRATOR) || !envelope.path("responses").isArray()) {
            return envelope;
        }
        ObjectNode copy = (ObjectNode) envelope.deepCopy();
        ArrayNode list = (ArrayNode) copy.get("responses");
        list.values().forEach(r -> {
            if (r.isObject()) {
                ((ObjectNode) r).remove("errorDetail");
            }
        });
        return copy;
    }

    /** API-ANA-09 실행·결과 */
    @Transactional(readOnly = true)
    public JsonNode get(long analysisId, long runId) {
        AccessGrant grant = access.view();
        access.visible(analysisId, grant);
        JsonNode r = runOf(analysisId, runId);
        if (access.has(AnalyticsAccess.Rank.INTEGRATOR) || !r.path("run").isObject()) {
            return r;
        }
        ObjectNode copy = (ObjectNode) r.deepCopy();
        ((ObjectNode) copy.get("run")).remove("errorDetail");
        return copy;
    }

    /** 실행 하나(권한 확인 없이). 그 분석의 실행이 아니면 404 ANALYSIS_RUN_NOT_FOUND */
    JsonNode runOf(long analysisId, long runId) {
        JsonNode r = analytics.call(HttpMethod.GET, "/internal/analytics/runs/" + runId, null, null, AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND);
        JsonNode run = r == null ? null : r.has("run") ? r.get("run") : r;
        Long owner = run == null ? null : AnalysisService.longOrNull(run.get("analysisId"));
        if (owner == null || owner != analysisId) {
            throw new BusinessException(AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND);
        }
        return r;
    }

    /** API-ANA-10 취소 — A 이상. 끝난 실행은 409 ANALYSIS_RUN_STATE_CONFLICT(analytics 판정) */
    @Transactional
    public JsonNode cancel(long analysisId, long runId) {
        AccessGrant grant = access.run();
        CurrentUser user = access.user();
        access.visible(analysisId, grant);
        runOf(analysisId, runId);
        JsonNode run = analytics.call(HttpMethod.POST, "/internal/analytics/runs/" + runId + "/cancel", null, null,
                AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND);
        refs.recordRun(user.organizationId(), analysisId, runId, run == null ? "CANCELLED" : run.path("status").asString("CANCELLED"),
                clock.instant());
        audits.record(audits.event(user.organizationId(), "ANALYSIS_RUN_CANCELLED").actor(user).target("ANALYSIS", Long.toString(analysisId))
                .detail("runId", Long.toString(runId)));
        return run;
    }

    /** API-ANA-12 결과 내보내기 — A 이상. CSV·PNG 200, PDF 202(상태 그대로) */
    @Transactional(readOnly = true)
    public InternalHttp.Result export(long analysisId, long runId, JsonNode body) {
        AccessGrant grant = access.run();
        access.visible(analysisId, grant);
        runOf(analysisId, runId);
        return analytics.send(HttpMethod.POST, "/internal/analytics/analyses/" + analysisId + "/runs/" + runId + "/export", null, body, null,
                AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND);
    }

    /** API-ANA-13 실행 비교 */
    @Transactional(readOnly = true)
    public JsonNode compare(long analysisId, String base, String target) {
        AccessGrant grant = access.view();
        access.visible(analysisId, grant);
        return analytics.call(HttpMethod.GET, "/internal/analytics/analyses/" + analysisId + "/runs/compare",
                InternalHttp.query("base", base, "target", target), null, AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND);
    }

    /** API-ANA-14 재학습 요청 — I 이상, 202 */
    @Transactional
    public JsonNode train(long analysisId, JsonNode body) {
        AccessGrant grant = access.atLeast(AnalyticsAccess.Rank.INTEGRATOR);
        CurrentUser user = access.user();
        access.visible(analysisId, grant);
        JsonNode r = analytics.call(HttpMethod.POST, "/internal/analytics/analyses/" + analysisId + "/models/train", null,
                body == null ? json.createObjectNode() : body, AnalyticsErrorCode.ANALYSIS_NOT_FOUND);
        audits.record(audits.event(user.organizationId(), "ANALYSIS_MODEL_TRAIN_REQUESTED").actor(user)
                .target("ANALYSIS", Long.toString(analysisId)).detail("modelId", r == null ? null : BindingResolver.text(r, "modelId")));
        return r;
    }

    /** API-ANA-23 모델 목록 — A 이상. 보이지 않는 분석의 모델은 뺀다 */
    @Transactional(readOnly = true)
    public JsonNode models(Integer page, Integer size) {
        AccessGrant grant = access.run();
        JsonNode envelope = analytics.envelope(HttpMethod.GET, "/internal/analytics/models", InternalHttp.query("page", page, "size", size), null,
                null);
        if (envelope == null || !envelope.path("responses").isArray()) {
            return envelope;
        }
        long org = access.user().organizationId();
        ObjectNode copy = (ObjectNode) envelope.deepCopy();
        ArrayNode kept = copy.arrayNode();
        for (JsonNode m : envelope.get("responses").values()) {
            Long analysisId = AnalysisService.longOrNull(m.get("analysisId"));
            if (analysisId != null && refs.find(org, analysisId).filter(AnalysisRefRepository.AnalysisRef::active)
                    .filter(r -> AnalyticsAccess.covers(grant.spaceScope(), r)).isPresent()) {
                kept.add(m);
            }
        }
        copy.set("responses", kept);
        return copy;
    }

    /** API-ANA-24 모델 적용 — I 이상. 나쁜 모델은 force 없이 409 MODEL_WORSE_THAN_ACTIVE(analytics 판정) */
    @Transactional
    public JsonNode activate(long modelId, JsonNode body) {
        access.atLeast(AnalyticsAccess.Rank.INTEGRATOR);
        CurrentUser user = access.user();
        JsonNode r = analytics.call(HttpMethod.POST, "/internal/analytics/models/" + modelId + "/activate", null,
                body == null ? Map.of("force", false) : body, AnalyticsErrorCode.ML_MODEL_NOT_FOUND);
        audits.record(audits.event(user.organizationId(), "ANALYSIS_MODEL_ACTIVATED").actor(user).target("ML_MODEL", Long.toString(modelId))
                .detail("force", body != null && body.path("force").asBoolean(false)));
        return r;
    }

    /** API-ANA-15 이상 피드백 — O 이상, 201. 실행을 가리키면 그 분석이 보여야 한다(아니면 404 ANALYSIS_RUN_NOT_FOUND) */
    @Transactional
    public JsonNode feedback(JsonNode body) {
        AccessGrant grant = access.atLeast(AnalyticsAccess.Rank.OPERATOR);
        if (body == null || !body.isObject() || !body.hasNonNull("runId") && !body.hasNonNull("realtimeEventId")) {
            throw AnalysisService.invalid("runId");
        }
        Long runId = AnalysisService.longOrNull(body.get("runId"));
        if (body.hasNonNull("runId")) {
            if (runId == null) {
                throw new BusinessException(AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND);
            }
            JsonNode r = analytics.call(HttpMethod.GET, "/internal/analytics/runs/" + runId, null, null, AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND);
            JsonNode run = r == null ? null : r.has("run") ? r.get("run") : r;
            Long analysisId = run == null ? null : AnalysisService.longOrNull(run.get("analysisId"));
            boolean visible = analysisId != null && refs.find(access.user().organizationId(), analysisId)
                    .filter(AnalysisRefRepository.AnalysisRef::active).filter(a -> AnalyticsAccess.covers(grant.spaceScope(), a)).isPresent();
            if (!visible) {
                throw new BusinessException(AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND);
            }
        }
        ObjectNode req = (ObjectNode) body.deepCopy();
        req.put("userId", Long.toString(access.user().userId()));
        return analytics.call(HttpMethod.POST, "/internal/analytics/feedback", null, req, AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND);
    }
}
