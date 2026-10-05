package net.java21.data2flow.core.board.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository.AnalysisRef;
import net.java21.data2flow.core.analytics.service.AnalyticsAccess;
import net.java21.data2flow.core.analytics.service.AnalysisService;
import net.java21.data2flow.core.board.repository.DashboardBoardRepository.DashboardRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 분석 결과 위젯 고정(API-DSH-08 {@code POST /core/dashboards/{dashboard-id}/widgets/pin-analysis}, ANA-05.06·DSH-04.04).
 * A 이상(ANALYTICS_RUN)이고 대시보드를 고칠 수 있어야 하며(DASHBOARD_WRITE + 소유자 또는 ORG 공개 편집 권한) 분석이 보여야 한다(아니면 404).
 * 빈 격자 자리(기존 위젯 아래, 기본 12×8)에 {@code analysis} 위젯을 넣고 판(version)을 올린다. placement {x, y, w, h}를 주면 그 자리
 * (겹치면 400 DASHBOARD_LAYOUT_INVALID).
 */
@Service
public class AnalysisPinService {

    static final int DEFAULT_W = 12;
    static final int DEFAULT_H = 8;

    private final RoleChecker roleChecker;
    private final DashboardBoardService boards;
    private final AnalyticsAccess analytics;
    private final JsonMapper json;

    public AnalysisPinService(RoleChecker roleChecker, DashboardBoardService boards, AnalyticsAccess analytics, JsonMapper json) {
        this.roleChecker = roleChecker;
        this.boards = boards;
        this.analytics = analytics;
        this.json = json;
    }

    @Transactional
    public Map<String, Object> pin(long dashboardId, JsonNode body) {
        AccessGrant grant = analytics.run();
        roleChecker.require(Permission.DASHBOARD_WRITE);
        CurrentUser user = roleChecker.currentUser();
        Long analysisId = AnalysisService.longOrNull(body == null ? null : body.get("analysisId"));
        if (analysisId == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        AnalysisRef ref = analytics.visible(analysisId, grant);
        DashboardRow before = boards.visible(dashboardId);
        ObjectNode layout = (ObjectNode) json.readTree(before.layout());
        ArrayNode widgets = layout.has("widgets") && layout.get("widgets").isArray() ? (ArrayNode) layout.get("widgets") : layout.putArray("widgets");
        Set<String> ids = new HashSet<>();
        int bottom = 0;
        for (JsonNode w : widgets.values()) {
            ids.add(w.path("id").asString(""));
            bottom = Math.max(bottom, w.path("y").asInt(0) + w.path("h").asInt(0));
        }
        String widgetId = "analysis-" + ref.analysisId();
        for (int n = 2; ids.contains(widgetId); n++) {
            widgetId = "analysis-" + ref.analysisId() + "-" + n;
        }
        JsonNode placement = body.get("placement");
        ObjectNode widget = widgets.addObject();
        widget.put("id", widgetId);
        widget.put("type", "analysis");
        widget.put("x", placement != null && placement.has("x") ? placement.get("x").asInt() : 0);
        widget.put("y", placement != null && placement.has("y") ? placement.get("y").asInt() : bottom);
        widget.put("w", placement != null && placement.has("w") ? placement.get("w").asInt() : DEFAULT_W);
        widget.put("h", placement != null && placement.has("h") ? placement.get("h").asInt() : DEFAULT_H);
        widget.putArray("targets");
        ObjectNode options = widget.putObject("options");
        options.put("analysisId", Long.toString(ref.analysisId()));
        String chartId = body.hasNonNull("chartId") ? body.get("chartId").asString() : null;
        JsonNode metricKeys = body.get("metricKeys");
        boolean hasMetrics = metricKeys != null && metricKeys.isArray() && !metricKeys.isEmpty();
        if (chartId != null) {
            options.put("chartId", chartId);
        }
        if (hasMetrics) {
            options.set("metricKeys", metricKeys);
        }
        options.put("show", chartId != null && !hasMetrics ? "chart" : chartId == null && hasMetrics ? "metrics" : "both");
        ObjectNode update = json.createObjectNode();
        update.set("layout", layout);
        update.put("baseVersion", before.version());
        int version = boards.update(dashboardId, update).version();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dashboardId", Long.toString(dashboardId));
        out.put("widgetId", widgetId);
        out.put("version", version);
        return out;
    }
}
