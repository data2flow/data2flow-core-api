package net.java21.data2flow.core.analytics.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.analytics.domain.AnalyticsErrorCode;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository.AnalysisRef;
import net.java21.data2flow.core.analytics.repository.AnalysisSettingsRepository;
import net.java21.data2flow.core.analytics.repository.AnalyticsDataRepository;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.retention.domain.DataClass;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 데이터셋(API-ANA-20, ANA-10.01)·KPI(API-ANA-21, BR-ANA-20)·조직 분석 설정(API-ANA-25).
 * <ul>
 *   <li>데이터셋의 원천은 analytics({@code datasets}·{@code dataset_versions}). 조회 V, 쓰기 A 이상. 바인딩 공간이 요청자 범위 밖이면
 *       쓰기 403, 조회 404 DATASET_NOT_FOUND(TC-ANA-179)</li>
 *   <li>KPI는 analytics 한 곳에서 계산한다(API-ANA-37). 공간이 범위 밖이거나 없으면 404(존재를 숨김, TC-ANA-174)</li>
 *   <li>결과 보관 기간은 보관 정책 ANALYSIS_RESULT 행(30~1,095일, 기본 365)</li>
 * </ul>
 */
@Service
public class AnalyticsDataService {

    static final Set<String> KPI_KEYS = Set.of("COMPLIANCE_RATE", "UTILIZATION_RATE", "ALARM_RATE", "SENSOR_AVAILABILITY", "EQUIPMENT_RUNTIME");

    private final AnalyticsAccess access;
    private final AnalyticsClient analytics;
    private final BindingResolver resolver;
    private final AnalyticsDataRepository data;
    private final AnalysisSettingsRepository settings;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public AnalyticsDataService(AnalyticsAccess access, AnalyticsClient analytics, BindingResolver resolver, AnalyticsDataRepository data,
                                AnalysisSettingsRepository settings, Audits audits, JsonMapper json, Clock clock) {
        this.access = access;
        this.analytics = analytics;
        this.resolver = resolver;
        this.data = data;
        this.settings = settings;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 데이터셋(API-ANA-20)

    @Transactional(readOnly = true)
    public JsonNode datasets(Integer page, Integer size) {
        AccessGrant grant = access.view();
        JsonNode envelope = analytics.envelope(HttpMethod.GET, "/internal/analytics/datasets", InternalHttp.query("page", page, "size", size),
                null, null);
        if (envelope == null || grant.spaceScope().unrestricted() || !envelope.path("responses").isArray()) {
            return envelope;
        }
        ObjectNode copy = (ObjectNode) envelope.deepCopy();
        ArrayNode kept = copy.arrayNode();
        for (JsonNode d : envelope.get("responses").values()) {
            Long id = AnalysisService.longOrNull(d.has("datasetId") ? d.get("datasetId") : d.get("id"));
            if (id != null && visible(grant.spaceScope(), fetchDataset(id))) {
                kept.add(d);
            }
        }
        copy.set("responses", kept);
        return copy;
    }

    @Transactional(readOnly = true)
    public JsonNode dataset(long datasetId) {
        AccessGrant grant = access.view();
        JsonNode d = fetchDataset(datasetId);
        if (!visible(grant.spaceScope(), d)) {
            throw new BusinessException(AnalyticsErrorCode.DATASET_NOT_FOUND);
        }
        return d;
    }

    @Transactional(readOnly = true)
    public JsonNode datasetVersions(long datasetId, Integer page, Integer size) {
        dataset(datasetId);
        return analytics.envelope(HttpMethod.GET, "/internal/analytics/datasets/" + datasetId + "/versions",
                InternalHttp.query("page", page, "size", size), null, AnalyticsErrorCode.DATASET_NOT_FOUND);
    }

    /** 만들기 — 201 */
    @Transactional
    public JsonNode createDataset(JsonNode body) {
        AccessGrant grant = access.run();
        CurrentUser user = access.user();
        validate(grant, body);
        ObjectNode req = (ObjectNode) body.deepCopy();
        req.put("createdBy", Long.toString(user.userId()));
        JsonNode saved = analytics.call(HttpMethod.POST, "/internal/analytics/datasets", null, req, null);
        audits.record(audits.event(user.organizationId(), "DATASET_CREATED").actor(user)
                .target("DATASET", saved == null ? null : BindingResolver.text(saved, "datasetId")));
        return saved;
    }

    /** 고치기 — 버전이 오른다(BR-ANA-21). baseVersion은 analytics가 확인 */
    @Transactional
    public JsonNode updateDataset(long datasetId, JsonNode body) {
        AccessGrant grant = access.run();
        CurrentUser user = access.user();
        if (!visible(grant.spaceScope(), fetchDataset(datasetId))) {
            throw new BusinessException(AnalyticsErrorCode.DATASET_NOT_FOUND);
        }
        validate(grant, body);
        ObjectNode req = (ObjectNode) body.deepCopy();
        req.put("updatedBy", Long.toString(user.userId()));
        JsonNode saved = analytics.call(HttpMethod.PUT, "/internal/analytics/datasets/" + datasetId, null, req, AnalyticsErrorCode.DATASET_NOT_FOUND);
        audits.record(audits.event(user.organizationId(), "DATASET_UPDATED").actor(user).target("DATASET", Long.toString(datasetId)));
        return saved;
    }

    /** 지우기 — 204. 분석이 쓰고 있으면 analytics가 409로 막는다 */
    @Transactional
    public void deleteDataset(long datasetId) {
        AccessGrant grant = access.run();
        CurrentUser user = access.user();
        if (!visible(grant.spaceScope(), fetchDataset(datasetId))) {
            throw new BusinessException(AnalyticsErrorCode.DATASET_NOT_FOUND);
        }
        analytics.call(HttpMethod.DELETE, "/internal/analytics/datasets/" + datasetId, null, null, AnalyticsErrorCode.DATASET_NOT_FOUND);
        audits.record(audits.event(user.organizationId(), "DATASET_DELETED").actor(user).target("DATASET", Long.toString(datasetId)));
    }

    /** 분석을 최신 데이터셋 버전으로 올림 — 소유자·ADMIN */
    @Transactional
    public JsonNode upgradeDataset(long datasetId, long analysisId) {
        AccessGrant grant = access.run();
        CurrentUser user = access.user();
        AnalysisRef ref = access.visible(analysisId, grant);
        access.requireOwnerOrAdmin(ref);
        if (!visible(grant.spaceScope(), fetchDataset(datasetId))) {
            throw new BusinessException(AnalyticsErrorCode.DATASET_NOT_FOUND);
        }
        JsonNode r = analytics.call(HttpMethod.POST, "/internal/analytics/datasets/" + datasetId + "/analyses/" + analysisId + "/upgrade-dataset",
                null, null, AnalyticsErrorCode.DATASET_NOT_FOUND);
        audits.record(audits.event(user.organizationId(), "ANALYSIS_UPDATED").actor(user).target("ANALYSIS", Long.toString(analysisId))
                .detail("datasetId", Long.toString(datasetId)).detail("datasetVersion", r == null ? null : r.get("datasetVersion")));
        return r;
    }

    private JsonNode fetchDataset(long datasetId) {
        return analytics.call(HttpMethod.GET, "/internal/analytics/datasets/" + datasetId, null, null, AnalyticsErrorCode.DATASET_NOT_FOUND);
    }

    private void validate(AccessGrant grant, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw AnalysisService.invalid("body");
        }
        String name = BindingResolver.text(body, "name");
        if (name == null || name.length() > 100) {
            throw AnalysisService.invalid("name");
        }
        resolver.resolve(access.user().organizationId(), grant.spaceScope(), body.get("bindings"), null);
    }

    /** 바인딩 공간이 모두 범위 안인가. 기기가 지워져 공간을 알 수 없으면 범위 제한이 없는 사용자에게만 보인다 */
    private boolean visible(SpaceScope scope, JsonNode dataset) {
        if (scope.unrestricted()) {
            return true;
        }
        try {
            resolver.resolve(access.user().organizationId(), scope, dataset == null ? null : dataset.get("bindings"), null);
            return true;
        } catch (BusinessException ex) {
            return false;
        }
    }

    // ------------------------------------------------------------------ KPI(API-ANA-21)

    @Transactional(readOnly = true)
    public ListApiResponse<JsonNode> kpis(Long spaceId, String from, String to, String keys) {
        AccessGrant grant = access.view();
        CurrentUser user = access.user();
        if (spaceId == null || !grant.spaceScope().includes(spaceId) || data.findSpaces(user.organizationId(), List.of(spaceId)).isEmpty()) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        List<String> keyList = new ArrayList<>();
        if (keys == null || keys.isBlank()) {
            keyList.addAll(List.of("COMPLIANCE_RATE", "UTILIZATION_RATE", "ALARM_RATE", "SENSOR_AVAILABILITY", "EQUIPMENT_RUNTIME"));
        } else {
            for (String k : keys.split(",")) {
                String key = k.strip().toUpperCase(Locale.ROOT);
                if (!KPI_KEYS.contains(key)) {
                    throw AnalysisService.invalid("keys");
                }
                keyList.add(key);
            }
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("spaceId", Long.toString(spaceId));
        req.put("from", from);
        req.put("to", to);
        req.put("keys", keyList);
        JsonNode r = analytics.call(HttpMethod.POST, "/internal/analytics/kpis/compute", null, req, null);
        List<JsonNode> items = new ArrayList<>();
        JsonNode list = r == null ? null : r.has("kpis") ? r.get("kpis") : r;
        if (list != null && list.isArray()) {
            list.values().forEach(items::add);
        }
        return ListApiResponse.of(PageParams.of(1, Math.max(1, Math.min(items.size(), PageParams.MAX_SIZE))), items, items.size());
    }

    // ------------------------------------------------------------------ 설정(API-ANA-25)

    @Transactional(readOnly = true)
    public Map<String, Object> settings() {
        access.admin();
        return settingsResponse(access.user().organizationId());
    }

    @Transactional
    public Map<String, Object> updateSettings(JsonNode body) {
        access.admin();
        CurrentUser user = access.user();
        JsonNode days = body == null ? null : body.get("resultRetentionDays");
        int max = DataClass.ANALYSIS_RESULT.maxDays();
        if (days == null || !days.isIntegralNumber() || days.asInt() < 30 || days.asInt() > max) {
            throw AnalysisService.invalid("resultRetentionDays");
        }
        Instant now = clock.instant();
        settings.saveResultRetention(user.organizationId(), days.asInt(), user.userId(), now);
        audits.record(audits.event(user.organizationId(), "ANALYSIS_SETTINGS_CHANGED").actor(user).target("ORGANIZATION",
                Long.toString(user.organizationId())).detail("resultRetentionDays", days.asInt()));
        return settingsResponse(user.organizationId());
    }

    private Map<String, Object> settingsResponse(long org) {
        AnalysisSettingsRepository.Retention r = settings.findResultRetention(org).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resultRetentionDays", r == null ? DataClass.ANALYSIS_RESULT.defaultDays() : r.days());
        if (r == null || r.updatedBy() == null) {
            out.put("updatedBy", null);
        } else {
            Map<String, Object> by = new LinkedHashMap<>();
            by.put("userId", Long.toString(r.updatedBy()));
            by.put("name", r.updatedByName());
            out.put("updatedBy", by);
        }
        out.put("updatedAt", r == null ? null : r.updatedAt());
        return out;
    }
}
