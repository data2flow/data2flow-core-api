package net.java21.data2flow.core.analytics.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.analytics.domain.AnalyticsErrorCode;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository;
import net.java21.data2flow.core.analytics.repository.AnalyticsDataRepository;
import net.java21.data2flow.core.analytics.repository.AnalyticsDataRepository.Candidate;
import net.java21.data2flow.core.analytics.repository.AnalyticsDataRepository.CandidateFilter;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.common.RelayedErrorException;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 분석 템플릿 카탈로그·설정·입력 확인(ANA-01·03, API-ANA-01~05·17~19). 템플릿과 조직별 활성화의 원천은 analytics
 * ({@code templates}·{@code template_settings})이고, core는 권한 확인 뒤 내부 API로 읽고 쓴다.
 * <ul>
 *   <li>목록: {@code GET /internal/analytics/templates}(API-ANA-30)에 조직 설정({@code GET /internal/analytics/template-settings})의
 *       {@code enabled}를 합친다. 비활성은 includeDisabled=true일 때만</li>
 *   <li>질문으로 찾기(API-ANA-03, {@code keyword}): 이름·요약·대표 질문과 낱말이 겹치는 정도로 점수를 매긴다(mode=KEYWORD). ai 임베딩
 *       내부 API는 문서에 없어 SEMANTIC은 아직 내지 않는다</li>
 *   <li>실행 가능성(API-ANA-04, {@code view=runnable&spaceId=}): 필수 역할마다 그 공간(하위 포함)에 의미 조건이 맞는 후보가 있는지</li>
 *   <li>역할 후보(API-ANA-19)는 core 기준 정보(기기·모델 측정 항목·의미 태그·공간)로 계산하고 공간 범위로 거른다</li>
 * </ul>
 */
@Service
public class AnalyticsTemplateService {

    static final Set<String> CANDIDATE_KINDS = Set.of("DEVICE_METRIC", "SPACE_AGGREGATE", "DERIVED_METRIC");

    private final AnalyticsAccess access;
    private final AnalyticsClient analytics;
    private final AnalyticsDataRepository data;
    private final AnalysisRefRepository refs;
    private final BindingResolver resolver;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public AnalyticsTemplateService(AnalyticsAccess access, AnalyticsClient analytics, AnalyticsDataRepository data, AnalysisRefRepository refs,
                                    BindingResolver resolver, Audits audits, JsonMapper json, Clock clock) {
        this.resolver = resolver;
        this.access = access;
        this.analytics = analytics;
        this.data = data;
        this.refs = refs;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-ANA-01·03·04 목록·검색·실행 가능성 */
    @Transactional(readOnly = true)
    public ListApiResponse<JsonNode> list(String category, String kind, boolean includeDisabled, String keyword, String view, Long spaceId,
                                          Integer page, Integer size) {
        AccessGrant grant = access.view();
        PageParams params = PageParams.of(page, size);
        Map<String, Boolean> enabled = enabledMap();
        List<ObjectNode> items = new ArrayList<>();
        for (JsonNode t : templates()) {
            ObjectNode item = (ObjectNode) t.deepCopy();
            String key = t.path("key").asString("");
            item.put("enabled", enabled.getOrDefault(key, true));
            if (category != null && !category.equalsIgnoreCase(t.path("category").asString(""))
                    || kind != null && !kind.equalsIgnoreCase(t.path("kind").asString(""))
                    || !includeDisabled && !item.path("enabled").asBoolean()) {
                continue;
            }
            items.add(item);
        }
        String kw = PageParams.keyword(keyword);
        if (kw != null) {
            items = search(items, kw);
        }
        if ("runnable".equalsIgnoreCase(view)) {
            if (spaceId == null) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
            }
            if (!grant.spaceScope().includes(spaceId) || data.findSpaces(access.user().organizationId(), List.of(spaceId)).isEmpty()) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
            runnable(items, grant, spaceId);
        }
        int from = (int) Math.min(params.offset(), items.size());
        int to = Math.min(from + params.size(), items.size());
        return ListApiResponse.of(params, new ArrayList<JsonNode>(items.subList(from, to)), items.size());
    }

    /** API-ANA-02 상세(설명서) */
    public JsonNode detail(String templateKey, String version) {
        access.view();
        ObjectNode t = (ObjectNode) template(templateKey, version).deepCopy();
        t.put("enabled", enabledMap().getOrDefault(templateKey, true));
        return t;
    }

    /** 템플릿 상세(권한 확인 없이, 다른 서비스 계층용). 없으면 404 TEMPLATE_NOT_FOUND */
    public JsonNode template(String templateKey, String version) {
        requireKey(templateKey);
        return analytics.call(HttpMethod.GET, "/internal/analytics/templates/" + templateKey, InternalHttp.query("version", version), null,
                AnalyticsErrorCode.TEMPLATE_NOT_FOUND);
    }

    /** 이 조직에서 켜져 있는가(설정이 없으면 켜짐) */
    public boolean enabled(String templateKey) {
        return enabledMap().getOrDefault(templateKey, true);
    }

    /** API-ANA-05 데이터 충분성 확인 — 바인딩·공간 권한 확인 뒤 API-ANA-31 */
    public JsonNode check(String templateKey, JsonNode body) {
        AccessGrant grant = access.run();
        if (body == null || !body.isObject()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        JsonNode template = template(templateKey, text(body, "version"));
        resolver.resolve(access.user().organizationId(), grant.spaceScope(), body.get("bindings"), template.get("roles"));
        return analytics.call(HttpMethod.POST, "/internal/analytics/templates/" + templateKey + "/check", null, body,
                AnalyticsErrorCode.TEMPLATE_NOT_FOUND);
    }

    /** API-ANA-19 역할 후보(공간 권한·의미 조건으로 거름) */
    @Transactional(readOnly = true)
    public ListApiResponse<Map<String, Object>> candidates(String templateKey, String role, Long spaceId, String keyword, String kind,
                                                           Integer page, Integer size) {
        AccessGrant grant = access.run();
        CurrentUser user = access.user();
        JsonNode spec = null;
        for (JsonNode r : template(templateKey, null).path("roles").values()) {
            if (role.equals(r.path("name").asString(null))) {
                spec = r;
            }
        }
        if (spec == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        String k = kind == null || kind.isBlank() ? "DEVICE_METRIC" : kind.strip().toUpperCase(Locale.ROOT);
        if (!CANDIDATE_KINDS.contains(k)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        List<Long> spaces = null;
        if (spaceId != null) {
            if (!grant.spaceScope().includes(spaceId)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
            spaces = data.subtree(user.organizationId(), spaceId);
        }
        List<Long> allowed = grant.spaceScope().unrestricted() ? null : new ArrayList<>(grant.spaceScope().allowedSpaceIds());
        String semantic = spec.hasNonNull("semantic") ? spec.get("semantic").asString() : null;
        CandidateFilter filter = new CandidateFilter(user.organizationId(), k, semantic, spaces, allowed, PageParams.keyword(keyword), true);
        PageParams params = PageParams.of(page, size);
        List<Map<String, Object>> items = data.candidates(filter, params.size(), params.offset()).stream().map(AnalyticsTemplateService::candidate)
                .toList();
        return ListApiResponse.of(params, items, data.countCandidates(filter));
    }

    /** API-ANA-17 템플릿 설정 목록(AD) — analytics 설정에 core 색인의 일정·실시간 수를 덧붙이지 않고 그대로 준다 */
    public JsonNode settings(Integer page, Integer size) {
        access.admin();
        return analytics.envelope(HttpMethod.GET, "/internal/analytics/template-settings", InternalHttp.query("page", page, "size", size), null,
                null);
    }

    /** API-ANA-18 템플릿 켜기·끄기(AD). 끄면 그 템플릿의 일정은 PAUSED, 실시간은 중지(BR-ANA-17, 다시 켜도 자동 재개 없음) */
    @Transactional
    public JsonNode updateSetting(String templateKey, JsonNode body) {
        access.admin();
        CurrentUser user = access.user();
        requireKey(templateKey);
        if (body == null || !body.path("enabled").isBoolean()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        boolean enabled = body.get("enabled").asBoolean();
        JsonNode result = analytics.call(HttpMethod.PUT, "/internal/analytics/template-settings/" + templateKey, null,
                Map.of("enabled", enabled), AnalyticsErrorCode.TEMPLATE_NOT_FOUND);
        List<Long> paused = enabled ? List.of() : refs.pauseTemplate(user.organizationId(), templateKey, clock.instant());
        audits.record(audits.event(user.organizationId(), enabled ? "TEMPLATE_ENABLED" : "TEMPLATE_DISABLED").actor(user)
                .target("ANALYSIS_TEMPLATE", templateKey).detail("pausedAnalyses", paused.size()));
        return result;
    }

    // ------------------------------------------------------------------ 내부

    private List<JsonNode> templates() {
        JsonNode envelope = analytics.envelope(HttpMethod.GET, "/internal/analytics/templates", InternalHttp.query("size", 100), null, null);
        List<JsonNode> out = new ArrayList<>();
        JsonNode list = envelope == null ? null : envelope.has("responses") ? envelope.get("responses") : envelope.get("response");
        if (list != null && list.isArray()) {
            list.values().forEach(out::add);
        }
        return out;
    }

    /** 조직의 템플릿 켜짐 여부. analytics가 설정 API를 아직 안 주면(404) 모두 켜짐으로 본다 */
    Map<String, Boolean> enabledMap() {
        Map<String, Boolean> out = new HashMap<>();
        JsonNode envelope;
        try {
            envelope = analytics.envelope(HttpMethod.GET, "/internal/analytics/template-settings", InternalHttp.query("size", 100), null, null);
        } catch (RelayedErrorException ex) {
            if (ex.status() == 404) {
                return out;
            }
            throw ex;
        } catch (BusinessException ex) {
            if (ex.getErrorCode() == CommonErrorCode.RESOURCE_NOT_FOUND) {
                return out;
            }
            throw ex;
        }
        JsonNode list = envelope == null ? null : envelope.get("responses");
        if (list != null && list.isArray()) {
            for (JsonNode s : list.values()) {
                if (s.hasNonNull("key") && s.path("enabled").isBoolean()) {
                    out.put(s.get("key").asString(), s.get("enabled").asBoolean());
                }
            }
        }
        return out;
    }

    /** 질문으로 찾기(KEYWORD): 낱말 겹침 비율이 가장 큰 문장으로 점수(0~1). 점수 0은 뺀다 */
    static List<ObjectNode> search(List<ObjectNode> items, String keyword) {
        String[] words = keyword.toLowerCase(Locale.ROOT).split("[\\s,.?!·]+");
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode item : items) {
            double best = 0;
            String matched = null;
            List<String> sentences = new ArrayList<>();
            List<String> questions = new ArrayList<>();
            item.path("questions").values().forEach(q -> questions.add(q.asString("")));
            sentences.addAll(questions);
            sentences.add(item.path("name").asString(""));
            sentences.add(item.path("summary").asString(""));
            sentences.add(item.path("key").asString(""));
            for (String sentence : sentences) {
                String s = sentence.toLowerCase(Locale.ROOT);
                int hit = 0;
                int total = 0;
                for (String w : words) {
                    if (w.isBlank()) {
                        continue;
                    }
                    total++;
                    if (s.contains(w)) {
                        hit++;
                    }
                }
                double score = total == 0 ? 0 : (double) hit / total;
                if (score > best) {
                    best = score;
                    matched = questions.contains(sentence) ? sentence : null;
                }
            }
            if (best > 0) {
                item.put("score", Math.round(best * 1000) / 1000.0);
                item.put("matchedQuestion", matched);
                item.put("mode", "KEYWORD");
                out.add(item);
            }
        }
        out.sort(Comparator.comparingDouble((ObjectNode n) -> n.path("score").asDouble()).reversed());
        return out;
    }

    /** 실행 가능성: 필수 역할마다 그 공간(하위 포함)에 의미 조건이 맞는 후보가 하나라도 있는가 */
    private void runnable(List<ObjectNode> items, AccessGrant grant, long spaceId) {
        long org = access.user().organizationId();
        List<Long> spaces = data.subtree(org, spaceId);
        List<Long> allowed = grant.spaceScope().unrestricted() ? null : new ArrayList<>(grant.spaceScope().allowedSpaceIds());
        Map<String, Boolean> cache = new LinkedHashMap<>();
        for (ObjectNode item : items) {
            ArrayNode missing = item.arrayNode();
            for (JsonNode r : item.path("roles").values()) {
                boolean required = r.path("required").asBoolean(false) || r.path("min").asInt(0) > 0;
                if (!required) {
                    continue;
                }
                String semantic = r.hasNonNull("semantic") ? r.get("semantic").asString() : null;
                boolean found = cache.computeIfAbsent(String.valueOf(semantic), s -> data.countCandidates(
                        new CandidateFilter(org, "DEVICE_METRIC", semantic, spaces, allowed, null, false)) > 0);
                if (!found) {
                    ObjectNode m = missing.addObject();
                    m.put("role", r.path("name").asString(""));
                    m.put("semantic", semantic);
                }
            }
            item.put("runnable", missing.isEmpty());
            item.set("missingRoles", missing);
        }
    }

    static Map<String, Object> candidate(Candidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", c.kind());
        m.put("deviceId", c.deviceId() == null ? null : c.deviceId().toString());
        m.put("spaceId", c.spaceId() == null ? null : c.spaceId().toString());
        m.put("metricKey", c.metricKey());
        m.put("label", c.label());
        m.put("semantic", c.semantic());
        m.put("unit", c.unit());
        m.put("lastSeenAt", c.lastSeenAt());
        return m;
    }

    static void requireKey(String templateKey) {
        if (templateKey == null || !templateKey.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new BusinessException(AnalyticsErrorCode.TEMPLATE_NOT_FOUND);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asString();
    }
}
