package net.java21.data2flow.core.analytics.service;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.identity.CurrentUserHolder;
import net.java21.data2flow.core.analytics.domain.AnalyticsErrorCode;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository.AnalysisRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사이트별 쾌적도 점수(DEV-10.02, API-DEV-25 {@code comfortScore}). 쾌적도 분석({@code comfort-index}, ANA-02.01) 중 바인딩 공간이 모두 그 사이트 안이고
 * 보는 사람 범위 안인 분석의 <b>최근 성공 실행</b>에서 핵심 수치 {@code score}(없으면 첫 수치)를 0~100 정수로 준다. 해당 분석이 없거나
 * analytics가 응답하지 않으면 null(사이트 목록은 그대로 보인다).
 */
@Component
public class SiteComfortScores {

    static final String TEMPLATE = "comfort-index";
    private static final Logger log = LoggerFactory.getLogger(SiteComfortScores.class);

    private final AnalysisRefRepository refs;
    private final AnalyticsClient analytics;

    public SiteComfortScores(AnalysisRefRepository refs, AnalyticsClient analytics) {
        this.refs = refs;
        this.analytics = analytics;
    }

    /** 사이트 ID → 그 사이트 하위 공간 ID들. 결과는 사이트 ID → 점수(없으면 키 없음) */
    public Map<Long, Integer> scores(long organizationId, SpaceScope scope, Map<Long, ? extends Collection<Long>> siteSpaces) {
        Map<Long, Integer> out = new HashMap<>();
        List<AnalysisRef> analyses = refs.listSucceeded(organizationId, TEMPLATE);
        if (analyses.isEmpty()) {
            return out;
        }
        for (Map.Entry<Long, ? extends Collection<Long>> site : siteSpaces.entrySet()) {
            Set<Long> inSite = Set.copyOf(site.getValue());
            for (AnalysisRef a : analyses) {
                if (a.spaceScopeIds().isEmpty() || !inSite.containsAll(a.spaceScopeIds()) || !AnalyticsAccess.covers(scope, a)) {
                    continue;
                }
                Integer score = score(organizationId, a);
                if (score != null) {
                    out.put(site.getKey(), score);
                    break;
                }
            }
        }
        return out;
    }

    private Integer score(long organizationId, AnalysisRef a) {
        try {
            JsonNode r = CurrentUserHolder.callAs(new CurrentUser(a.ownerUserId(), organizationId), () -> analytics.call(HttpMethod.GET,
                    "/internal/analytics/runs/" + a.lastSucceededRunId(), null, null, AnalyticsErrorCode.ANALYSIS_RUN_NOT_FOUND));
            JsonNode metrics = r == null ? null : r.path("result").path("summary").get("metrics");
            if (metrics == null || !metrics.isArray() || metrics.isEmpty()) {
                return null;
            }
            JsonNode chosen = null;
            for (JsonNode m : metrics.values()) {
                if ("score".equals(m.path("key").asString("")) || "comfortScore".equals(m.path("key").asString(""))) {
                    chosen = m;
                    break;
                }
            }
            JsonNode value = (chosen == null ? metrics.get(0) : chosen).get("value");
            return value != null && value.isNumber() ? (int) Math.round(value.asDouble()) : null;
        } catch (RuntimeException ex) {
            log.debug("사이트 쾌적도 읽기 실패(null로 표시): {}", ex.toString());
            return null;
        }
    }
}
