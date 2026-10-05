package net.java21.data2flow.core.analytics.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.analytics.domain.AnalysisSchedule;
import net.java21.data2flow.core.analytics.domain.AnalyticsErrorCode;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository.AnalysisRef;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository.Filter;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository.Upsert;
import net.java21.data2flow.core.analytics.repository.AnalyticsDataRepository;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 분석 정의(API-ANA-06·07·11·22, ANA-03·04.01·06). 정의의 원천은 analytics(API-ANA-32)이고, core는
 * <ol>
 *   <li>권한(A 이상, 수정·삭제는 소유자·ADMIN), 템플릿 활성(409 TEMPLATE_DISABLED), 바인딩(BR-ANA-02·03), 일정 형식(ANALYSIS_SCHEDULE_INVALID)을 판정하고</li>
 *   <li>{@code ownerUserId}·{@code spaceScopeIds}를 붙여 analytics에 저장한 뒤</li>
 *   <li>같은 트랜잭션에서 색인({@code analysis_refs}: 공간 범위·일정·다음 실행 시각)을 고친다</li>
 * </ol>
 * 목록은 색인으로 거르고 페이징한다(공간 범위 밖 분석은 개수에도 섞이지 않는다, BR-ANA-03). 일정은 core 1분 실행기가 실행한다
 * ({@link AnalysisScheduler}, UC-ANA-10 3단계).
 */
@Service
public class AnalysisService {

    static final Set<String> SCHEDULE_STATES = Set.of("ACTIVE", "PAUSED", "STOPPED_BY_FAILURE");

    private final AnalyticsAccess access;
    private final AnalyticsClient analytics;
    private final AnalyticsTemplateService templates;
    private final BindingResolver resolver;
    private final AnalysisRefRepository refs;
    private final AnalyticsDataRepository data;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public AnalysisService(AnalyticsAccess access, AnalyticsClient analytics, AnalyticsTemplateService templates, BindingResolver resolver,
                           AnalysisRefRepository refs, AnalyticsDataRepository data, Audits audits, JsonMapper json, Clock clock) {
        this.access = access;
        this.analytics = analytics;
        this.templates = templates;
        this.resolver = resolver;
        this.refs = refs;
        this.data = data;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-ANA-06 만들기 — 201 */
    @Transactional
    public JsonNode create(JsonNode body) {
        AccessGrant grant = access.run();
        CurrentUser user = access.user();
        Prepared p = prepare(grant, body, null);
        ObjectNode req = p.request();
        req.remove("baseVersion");
        req.put("ownerUserId", Long.toString(user.userId()));
        JsonNode saved = analytics.call(HttpMethod.POST, "/internal/analytics/analyses", null, req, AnalyticsErrorCode.TEMPLATE_NOT_FOUND);
        long analysisId = analysisId(saved);
        index(user.organizationId(), analysisId, user.userId(), p, saved, false);
        audits.record(audits.event(user.organizationId(), "ANALYSIS_CREATED").actor(user).target("ANALYSIS", Long.toString(analysisId))
                .detail("templateKey", p.templateKey()).detail("name", p.name()).detail("spaceScopeIds", p.resolved().spaceIds())
                .detail("schedule", p.schedule() == null ? null : p.schedule().cron()));
        return decorate(saved, user.organizationId(), analysisId);
    }

    /** API-ANA-06 수정 — 소유자 또는 ADMIN. baseVersion은 analytics가 확인한다(409 VERSION_CONFLICT) */
    @Transactional
    public JsonNode update(long analysisId, JsonNode body) {
        AccessGrant grant = access.run();
        CurrentUser user = access.user();
        AnalysisRef ref = access.visible(analysisId, grant);
        access.requireOwnerOrAdmin(ref);
        Prepared p = prepare(grant, body, ref);
        ObjectNode req = p.request();
        req.put("ownerUserId", Long.toString(ref.ownerUserId()));
        JsonNode saved = analytics.call(HttpMethod.PUT, "/internal/analytics/analyses/" + analysisId, null, req,
                AnalyticsErrorCode.ANALYSIS_NOT_FOUND);
        index(user.organizationId(), analysisId, ref.ownerUserId(), p, saved, ref.realtime());
        audits.record(audits.event(user.organizationId(), "ANALYSIS_UPDATED").actor(user).target("ANALYSIS", Long.toString(analysisId))
                .detail("templateKey", p.templateKey()).detail("name", p.name()).detail("spaceScopeIds", p.resolved().spaceIds())
                .detail("schedule", p.schedule() == null ? null : p.schedule().cron()));
        return decorate(saved, user.organizationId(), analysisId);
    }

    /** 분석 하나(마법사 편집·결과 화면) — V 이상, 공간 범위 안 */
    @Transactional(readOnly = true)
    public JsonNode get(long analysisId) {
        AccessGrant grant = access.view();
        access.visible(analysisId, grant);
        JsonNode a = analytics.call(HttpMethod.GET, "/internal/analytics/analyses/" + analysisId, null, null, AnalyticsErrorCode.ANALYSIS_NOT_FOUND);
        return decorate(a, access.user().organizationId(), analysisId);
    }

    /** API-ANA-07 목록 — V 이상. 색인으로 거르고 페이징(BR-ANA-03) */
    @Transactional(readOnly = true)
    public ListApiResponse<Map<String, Object>> list(String templateKey, String owner, String scheduleState, Boolean realtime, String keyword,
                                                     Integer page, Integer size) {
        AccessGrant grant = access.view();
        CurrentUser user = access.user();
        if (owner != null && !owner.isBlank() && !"me".equalsIgnoreCase(owner) && !"all".equalsIgnoreCase(owner)) {
            throw invalid("owner");
        }
        String state = scheduleState == null || scheduleState.isBlank() ? null : scheduleState.strip().toUpperCase(Locale.ROOT);
        if (state != null && !SCHEDULE_STATES.contains(state)) {
            throw invalid("scheduleState");
        }
        PageParams params = PageParams.of(page, size);
        Filter f = new Filter(user.organizationId(), "me".equalsIgnoreCase(owner) ? user.userId() : null,
                templateKey == null || templateKey.isBlank() ? null : templateKey.strip(), state, realtime, PageParams.keyword(keyword),
                grant.spaceScope().unrestricted() ? null : new ArrayList<>(grant.spaceScope().allowedSpaceIds()));
        List<Map<String, Object>> items = refs.list(f, params.size(), params.offset()).stream().map(AnalysisService::item).toList();
        return ListApiResponse.of(params, items, refs.count(f));
    }

    /** API-ANA-22 삭제 — 소유자 또는 ADMIN, 204. analytics에서 ARCHIVED·일정·실시간 중지, 색인도 ARCHIVED(고정 위젯은 "삭제된 분석") */
    @Transactional
    public void delete(long analysisId) {
        AccessGrant grant = access.view();
        CurrentUser user = access.user();
        AnalysisRef ref = access.visible(analysisId, grant);
        access.requireOwnerOrAdmin(ref);
        try {
            analytics.call(HttpMethod.DELETE, "/internal/analytics/analyses/" + analysisId, null, null, AnalyticsErrorCode.ANALYSIS_NOT_FOUND);
        } catch (BusinessException ex) {
            if (ex.getErrorCode() != AnalyticsErrorCode.ANALYSIS_NOT_FOUND) {
                throw ex;
            }
        }
        refs.archive(user.organizationId(), analysisId, clock.instant());
        audits.record(audits.event(user.organizationId(), "ANALYSIS_DELETED").actor(user).target("ANALYSIS", Long.toString(analysisId))
                .detail("name", ref.name()));
    }

    /** API-ANA-11 실시간 적용 켜기·끄기 — O 이상(API-ANA-35). 켤 때 비활성 템플릿이면 409 TEMPLATE_DISABLED */
    @Transactional
    public JsonNode realtime(long analysisId, JsonNode body) {
        AccessGrant grant = access.atLeast(AnalyticsAccess.Rank.OPERATOR);
        CurrentUser user = access.user();
        AnalysisRef ref = access.visible(analysisId, grant);
        if (body == null || !body.path("enabled").isBoolean()) {
            throw invalid("enabled");
        }
        boolean enabled = body.get("enabled").asBoolean();
        if (enabled && !templates.enabled(ref.templateKey())) {
            throw new BusinessException(AnalyticsErrorCode.TEMPLATE_DISABLED);
        }
        JsonNode result = analytics.call(HttpMethod.POST, "/internal/analytics/realtime/" + analysisId, null, Map.of("enabled", enabled),
                AnalyticsErrorCode.ANALYSIS_NOT_FOUND);
        refs.updateRealtime(user.organizationId(), analysisId, enabled, clock.instant());
        audits.record(audits.event(user.organizationId(), "ANALYSIS_UPDATED").actor(user).target("ANALYSIS", Long.toString(analysisId))
                .detail("realtime", enabled));
        ObjectNode out = result != null && result.isObject() ? (ObjectNode) result.deepCopy() : json.createObjectNode();
        out.put("analysisId", Long.toString(analysisId));
        out.put("realtime", enabled);
        if (!out.has("activeModelId")) {
            out.putNull("activeModelId");
        }
        return out;
    }

    /** API-ANA-11 실시간 이력 — V 이상. 권한 밖 기기의 이벤트는 뺀다(TC-ANA-139) */
    @Transactional(readOnly = true)
    public JsonNode realtimeEvents(long analysisId, String from, String to, Integer page, Integer size) {
        AccessGrant grant = access.view();
        access.visible(analysisId, grant);
        JsonNode envelope = analytics.envelope(HttpMethod.GET, "/internal/analytics/analyses/" + analysisId + "/realtime-events",
                InternalHttp.query("from", from, "to", to, "page", page, "size", size), null, AnalyticsErrorCode.ANALYSIS_NOT_FOUND);
        if (envelope == null || grant.spaceScope().unrestricted() || !envelope.path("responses").isArray()) {
            return envelope;
        }
        Set<Long> devices = new HashSet<>();
        for (JsonNode e : envelope.get("responses").values()) {
            Long id = longOrNull(e.get("deviceId"));
            if (id != null) {
                devices.add(id);
            }
        }
        Map<Long, Long> spaces = data.deviceSpaces(access.user().organizationId(), devices);
        ObjectNode copy = (ObjectNode) envelope.deepCopy();
        ArrayNode kept = copy.arrayNode();
        for (JsonNode e : envelope.get("responses").values()) {
            Long id = longOrNull(e.get("deviceId"));
            if (id == null || grant.spaceScope().includes(spaces.get(id))) {
                kept.add(e);
            }
        }
        copy.set("responses", kept);
        return copy;
    }

    // ------------------------------------------------------------------ 내부

    record Prepared(ObjectNode request, String name, String templateKey, String templateVersion, BindingResolver.Resolved resolved,
                    AnalysisSchedule schedule) {
    }

    private Prepared prepare(AccessGrant grant, JsonNode body, AnalysisRef existing) {
        if (body == null || !body.isObject()) {
            throw invalid("body");
        }
        long org = access.user().organizationId();
        String name = text(body, "name");
        if (name == null || name.length() > 100) {
            throw invalid("name");
        }
        String templateKey = text(body, "templateKey");
        if (templateKey == null && existing != null) {
            templateKey = existing.templateKey();
        }
        if (templateKey == null) {
            throw invalid("templateKey");
        }
        JsonNode template = templates.template(templateKey, text(body, "templateVersion"));
        if (!templates.enabled(templateKey)) {
            throw new BusinessException(AnalyticsErrorCode.TEMPLATE_DISABLED);
        }
        AnalysisSchedule schedule = AnalysisSchedule.parse(body.get("schedule"));
        JsonNode bindings = body.get("bindings");
        String datasetId = text(body, "datasetId");
        if ((bindings == null || bindings.isNull()) && datasetId != null) {
            if (!datasetId.matches("[1-9][0-9]{0,18}")) {
                throw new BusinessException(AnalyticsErrorCode.DATASET_NOT_FOUND);
            }
            bindings = analytics.call(HttpMethod.GET, "/internal/analytics/datasets/" + datasetId, null, null, AnalyticsErrorCode.DATASET_NOT_FOUND)
                    .get("bindings");
        }
        BindingResolver.Resolved resolved = resolver.resolve(org, grant.spaceScope(), bindings, template.get("roles"));
        ObjectNode req = (ObjectNode) body.deepCopy();
        req.put("templateKey", templateKey);
        req.put("name", name);
        ArrayNode scope = req.putArray("spaceScopeIds");
        resolved.spaceIds().forEach(id -> scope.add(Long.toString(id)));
        String version = text(body, "templateVersion");
        if (version == null) {
            version = text(template, "version");
        }
        return new Prepared(req, name, templateKey, version, resolved, schedule);
    }

    private void index(long org, long analysisId, long ownerUserId, Prepared p, JsonNode saved, boolean realtime) {
        Instant now = clock.instant();
        Instant next = p.schedule() == null ? null : p.schedule().next(now, zone(org));
        String version = text(saved, "templateVersion");
        refs.upsert(new Upsert(org, analysisId, p.name(), p.templateKey(), version == null ? p.templateVersion() : version, ownerUserId,
                p.resolved().spaceIds(), p.resolved().targetSummary(),
                p.schedule() == null ? null : json.writeValueAsString(Map.of("cron", p.schedule().cron())),
                p.schedule() == null ? null : "ACTIVE", next, saved != null && saved.path("realtime").isBoolean() ? saved.get("realtime").asBoolean()
                : realtime), now);
    }

    ZoneId zone(long org) {
        try {
            return ZoneId.of(data.organizationTimezone(org));
        } catch (RuntimeException ex) {
            return ZoneId.of("Asia/Seoul");
        }
    }

    /** analytics 응답에 core 색인의 일정 상태·다음 실행·소유자 이름을 덧붙인다 */
    private JsonNode decorate(JsonNode analysis, long org, long analysisId) {
        ObjectNode out = analysis != null && analysis.isObject() ? (ObjectNode) analysis.deepCopy() : json.createObjectNode();
        out.put("analysisId", Long.toString(analysisId));
        refs.find(org, analysisId).ifPresent(r -> {
            out.put("scheduleState", r.scheduleState());
            out.put("nextScheduledAt", r.nextRunAt() == null ? null : r.nextRunAt().toString());
            ObjectNode owner = out.putObject("owner");
            owner.put("userId", Long.toString(r.ownerUserId()));
            owner.put("name", r.ownerName());
        });
        return out;
    }

    static Map<String, Object> item(AnalysisRef r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", Long.toString(r.analysisId()));
        m.put("analysisId", Long.toString(r.analysisId()));
        m.put("name", r.name());
        m.put("templateKey", r.templateKey());
        m.put("templateVersion", r.templateVersion());
        m.put("targetSummary", r.targetSummary());
        if (r.lastRunId() == null) {
            m.put("lastRun", null);
        } else {
            Map<String, Object> last = new LinkedHashMap<>();
            last.put("id", Long.toString(r.lastRunId()));
            last.put("runId", Long.toString(r.lastRunId()));
            last.put("status", r.lastRunStatus());
            last.put("finishedAt", r.lastRunFinishedAt());
            m.put("lastRun", last);
        }
        m.put("nextScheduledAt", r.nextRunAt());
        m.put("scheduleState", r.scheduleState());
        m.put("realtime", r.realtime());
        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("id", Long.toString(r.ownerUserId()));
        owner.put("userId", Long.toString(r.ownerUserId()));
        owner.put("name", r.ownerName());
        m.put("owner", owner);
        return m;
    }

    static long analysisId(JsonNode saved) {
        Long id = saved == null ? null : longOrNull(saved.has("analysisId") ? saved.get("analysisId") : saved.get("id"));
        if (id == null) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        return id;
    }

    public static Long longOrNull(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isIntegralNumber()) {
            return v.asLong();
        }
        String s = v.asString("").strip();
        return s.matches("[0-9]{1,19}") ? Long.parseLong(s) : null;
    }

    static String text(JsonNode node, String field) {
        return BindingResolver.text(node, field);
    }

    static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "Invalid", null)));
    }
}
