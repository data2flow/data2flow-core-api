package net.java21.data2flow.core.analytics.service;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;
import net.java21.data2flow.core.analytics.domain.AnalyticsErrorCode;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository.AnalysisRef;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 분석 결과 위젯 데이터(DSH-04.04, API-DSH-09 {@code analysis} → {@code {runId, finishedAt, chart?, metrics?[]}}, ANA-05.06).
 * <ul>
 *   <li>항상 그 분석의 <b>최근 SUCCEEDED</b> 실행 결과를 쓴다(일정 실행으로 새 결과가 나오면 자동으로 바뀜, 실패 실행은 무시 BR-DSH-11·BR-ANA-15)</li>
 *   <li>분석을 지웠으면 {@code {deleted: true}}("삭제된 분석" 표시), 아직 성공 실행이 없으면 runId=null</li>
 *   <li>분석의 공간이 보는 사람 범위 밖이면 그 위젯만 403 WIDGET_DATA_FORBIDDEN(BR-DSH-09)</li>
 *   <li>chartId가 있으면 그 차트(ChartSpec), 없으면 첫 차트. metricKeys가 있으면 그 핵심 수치만. show=chart|metrics|both(기본 both)</li>
 * </ul>
 * 결과는 분석 소유자 신원으로 analytics(API-ANA-34)에서 읽는다(공유 링크처럼 로그인 사용자가 없어도 같은 결과).
 */
@Component
public class AnalysisWidgetData {

    private final AnalysisRefRepository refs;
    private final AnalyticsClient analytics;

    public AnalysisWidgetData(AnalysisRefRepository refs, AnalyticsClient analytics) {
        this.refs = refs;
        this.analytics = analytics;
    }

    /**
     * @param forbidden 범위 밖일 때 던질 예외(대시보드 쪽 WIDGET_DATA_FORBIDDEN)
     */
    public Map<String, Object> data(long organizationId, SpaceScope scope, JsonNode options, java.util.function.Supplier<RuntimeException> forbidden) {
        Long analysisId = AnalysisService.longOrNull(options == null ? null : options.get("analysisId"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analysisId", analysisId == null ? null : Long.toString(analysisId));
        Optional<AnalysisRef> found = analysisId == null ? Optional.empty() : refs.find(organizationId, analysisId);
        if (found.isEmpty() || !found.get().active()) {
            out.put("deleted", true);
            out.put("runId", null);
            return out;
        }
        AnalysisRef ref = found.get();
        if (!AnalyticsAccess.covers(scope, ref)) {
            throw forbidden.get();
        }
        out.put("deleted", false);
        out.put("analysisName", ref.name());
        out.put("templateKey", ref.templateKey());
        if (ref.lastSucceededRunId() == null) {
            out.put("runId", null);
            out.put("finishedAt", null);
            return out;
        }
        JsonNode r;
        try {
            r = CurrentUserHolder.callAs(new CurrentUser(ref.ownerUserId(), organizationId), () -> analytics.call(HttpMethod.GET,
                    "/internal/analytics/runs/" + ref.lastSucceededRunId(), null, null, AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND));
        } catch (BusinessException ex) {
            if (ex.getErrorCode() != AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND) {
                throw ex;
            }
            out.put("runId", null);
            out.put("finishedAt", null);
            return out;
        }
        JsonNode run = r == null ? null : r.has("run") ? r.get("run") : r;
        JsonNode result = r == null ? null : r.get("result");
        out.put("runId", Long.toString(ref.lastSucceededRunId()));
        out.put("finishedAt", run != null && run.hasNonNull("finishedAt") ? run.get("finishedAt").asString()
                : ref.lastSucceededAt() == null ? null : ref.lastSucceededAt().toString());
        String show = options.path("show").asString("both");
        if (!"metrics".equals(show)) {
            out.put("chart", chart(result, BindingResolver.text(options, "chartId")));
        }
        if (!"chart".equals(show)) {
            out.put("metrics", metrics(result, options.get("metricKeys")));
        }
        if (result != null && result.has("caveats")) {
            out.put("caveats", result.get("caveats"));
        }
        if (result != null && result.has("expiresAt")) {
            out.put("expiresAt", result.get("expiresAt"));
        }
        return out;
    }

    static JsonNode chart(JsonNode result, String chartId) {
        if (result == null || !result.path("charts").isArray()) {
            return null;
        }
        for (JsonNode c : result.get("charts").values()) {
            if (chartId == null || chartId.equals(c.path("id").asString(null))) {
                return c;
            }
        }
        return null;
    }

    static List<JsonNode> metrics(JsonNode result, JsonNode keys) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode metrics = result == null ? null : result.path("summary").get("metrics");
        if (metrics == null || !metrics.isArray()) {
            return out;
        }
        Set<String> wanted = new HashSet<>();
        if (keys != null && keys.isArray()) {
            keys.values().forEach(k -> wanted.add(k.asString("")));
        }
        for (JsonNode m : metrics.values()) {
            if (wanted.isEmpty() || wanted.contains(m.path("key").asString(""))) {
                out.add(m);
            }
        }
        return out;
    }
}
