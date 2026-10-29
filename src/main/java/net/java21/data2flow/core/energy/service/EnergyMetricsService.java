package net.java21.data2flow.core.energy.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.identity.DataflowHeaders;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.analytics.service.AnalyticsClient;
import net.java21.data2flow.core.energy.domain.EnergyErrorCode;
import net.java21.data2flow.core.energy.dto.EnergyMetricDtos.Breakdown;
import net.java21.data2flow.core.energy.repository.EnergyBaselineRepository;
import net.java21.data2flow.core.energy.repository.EnergyBaselineRepository.BaselineRow;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository.SpaceRow;
import net.java21.data2flow.core.energy.repository.EnergyConfigRepository.TreeRow;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 에너지 지표(API-ENE-10)와 하위 단위 순위(API-ENE-11). 롤업 계산(BR-ENE-01·02·07)은 analytics가 하고(API-ENE-85), core는 권한 범위로 자른
 * 공간 트리와 상위 계량기 여부를 넘긴다. 범위가 제한된 사용자는 허용 공간만 보이고 상위 합계도 그 범위로 계산된다(TC-ENE-019).
 * 기준선 비교(BASELINE)는 범위 공간의 활성 기준선 버전 모델 키를 넘기고, 응답 compare에 baselineVersionId를 붙인다(ENE-01.04)
 */
@Service
public class EnergyMetricsService {

    static final String PATH = "/internal/analytics/energy/metrics";
    private static final Set<String> SORTS = Set.of("KWH", "KRW", "EUI", "DIFF");

    private final RoleChecker roleChecker;
    private final EnergyAccess access;
    private final EnergyConfigRepository config;
    private final AnalyticsClient analytics;
    private final EnergyBaselineRepository baselines;

    public EnergyMetricsService(RoleChecker roleChecker, EnergyAccess access, EnergyConfigRepository config, AnalyticsClient analytics,
                                EnergyBaselineRepository baselines) {
        this.roleChecker = roleChecker;
        this.access = access;
        this.config = config;
        this.analytics = analytics;
        this.baselines = baselines;
    }

    @Transactional(readOnly = true)
    public JsonNode metrics(String scopeSpaceId, String from, String to, String granularity, String compare) {
        JsonNode r = call(scopeSpaceId, from, to, granularity == null ? "DAY" : granularity.toUpperCase(Locale.ROOT),
                compare == null || compare.isBlank() ? null : compare.toUpperCase(Locale.ROOT));
        if (r instanceof ObjectNode o) {
            o.remove("children");
            o.remove("other");
        }
        return r;
    }

    @Transactional(readOnly = true)
    public Breakdown breakdown(String scopeSpaceId, String from, String to, String by, String sort, Integer page, Integer size) {
        if (by != null && !"CHILD_SPACE".equalsIgnoreCase(by)) {
            // 장비별(DEVICE)은 energy_daily가 공간 단위라 원천별 집계를 더한 뒤 연다
            throw EnergyAccess.invalid(CommonErrorCode.INVALID_REQUEST, "by", "CHILD_SPACE만 됩니다");
        }
        String key = sort == null ? "KWH" : sort.toUpperCase(Locale.ROOT);
        if (!SORTS.contains(key)) {
            throw EnergyAccess.invalid(CommonErrorCode.INVALID_REQUEST, "sort", String.join(", ", SORTS));
        }
        JsonNode r = call(scopeSpaceId, from, to, "MONTH", "PREVIOUS_PERIOD");
        List<JsonNode> items = new ArrayList<>();
        if (r != null && r.path("children").isArray()) {
            r.path("children").values().forEach(items::add);
        }
        String field = switch (key) {
            case "KRW" -> "krw";
            case "EUI" -> "euiKwhPerM2";
            case "DIFF" -> "diffRatio";
            default -> "kwh";
        };
        items.sort(Comparator.comparing((JsonNode n) -> n.path(field).isNumber() ? n.path(field).asDouble() : null,
                Comparator.nullsLast(Comparator.reverseOrder())));
        PageParams p = PageParams.of(page, size);
        int fromIndex = (int) Math.min(items.size(), p.offset());
        List<JsonNode> pageItems = items.subList(fromIndex, Math.min(items.size(), fromIndex + p.size()));
        JsonNode other = r == null ? null : r.get("other");
        return new Breakdown(pageItems, other, p.page(), p.size(), items.size());
    }

    private JsonNode call(String scopeSpaceId, String from, String to, String granularity, String compare) {
        Prepared p = prepare(scopeSpaceId, from, to, granularity, compare);
        JsonNode r = analytics.call(HttpMethod.POST, PATH, null, p.body(), EnergyErrorCode.SPACE_NOT_FOUND);
        return finish(r, p);
    }

    /**
     * API-ENE-85 요청 본문(권한 범위로 자른 공간 트리, 기준선 비교면 활성 버전 모델 키). 월간 리포트(API-ENE-13)는 요청할 때 만든 본문을 저장해 두고
     * 생성 작업이 그대로 다시 보낸다(작업에는 요청자 권한 문맥이 없다)
     */
    public Prepared prepare(String scopeSpaceId, String from, String to, String granularity, String compare) {
        long orgId = roleChecker.currentUser().organizationId();
        Long scope = EnergyAccess.id(scopeSpaceId, EnergyErrorCode.SPACE_NOT_FOUND);
        String prefix = null;
        String scopeName = null;
        if (scope != null) {
            SpaceRow space = access.space(Permission.ENE_READ, orgId, scope);
            prefix = space.path();
            scopeName = space.name();
        } else {
            roleChecker.require(Permission.ENE_READ);
        }
        return body(orgId, scope, prefix, scopeName, roleChecker.spaceScope(), from, to, granularity, compare);
    }

    /**
     * 대시보드 위젯(energy-kpi·energy-trend, ENE-01.05)용 지표. 로그인 사용자가 없는 공유 링크에서도 쓰므로 위젯 문맥의 조직·공간 범위로 트리를 자르고
     * 조직 헤더를 직접 붙인다. 범위 밖이거나 없는 공간이면 {@code forbidden}
     */
    @Transactional(readOnly = true)
    public JsonNode widget(long orgId, SpaceScope allowed, Long spaceId, String from, String to, String granularity, String compare,
                           Supplier<RuntimeException> forbidden) {
        String prefix = null;
        String scopeName = null;
        if (spaceId != null) {
            SpaceRow space = config.space(orgId, spaceId).filter(s -> allowed.includes(s.id())).orElseThrow(forbidden);
            prefix = space.path();
            scopeName = space.name();
        }
        Prepared p = body(orgId, spaceId, prefix, scopeName, allowed, from, to, granularity, compare);
        JsonNode r = analytics.send(HttpMethod.POST, PATH, null, p.body(), h -> h.set(DataflowHeaders.ORG_ID, Long.toString(orgId)), null)
                .response();
        return finish(r, p);
    }

    private Prepared body(long orgId, Long scope, String prefix, String scopeName, SpaceScope allowed, String from, String to, String granularity,
                          String compare) {
        List<Map<String, Object>> spaces = new ArrayList<>();
        for (TreeRow t : config.spaceTree(orgId, prefix, allowed)) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", t.id());
            node.put("parentId", t.parentId());
            node.put("name", t.name());
            node.put("kind", t.type());
            node.put("areaM2", t.areaM2());
            node.put("coversDescendants", t.coversDescendants());
            spaces.add(node);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeSpaceId", scope);
        body.put("from", from);
        body.put("to", to);
        body.put("granularity", granularity);
        body.put("compare", compare);
        body.put("spaces", spaces);
        BaselineRow active = "BASELINE".equals(compare) && scope != null
                ? baselines.forSpace(orgId, scope).filter(b -> b.activeVersionId() != null && b.activeModelKey() != null).orElse(null) : null;
        if (active != null) {
            body.put("baselineKey", active.activeModelKey());
        }
        return new Prepared(body, active == null ? null : active.activeVersionId(), scopeName);
    }

    /** 응답 정리: 문자열 ID, 기준선 비교면 모델 키 대신 버전 ID */
    public static JsonNode finish(JsonNode r, Prepared p) {
        stringIds(r);
        if (p.baselineVersionId() != null && r != null && r.get("compare") instanceof ObjectNode c) {
            c.remove("baselineKey");
            c.put("baselineVersionId", Long.toString(p.baselineVersionId()));
        }
        return r;
    }

    /** API-ENE-85 요청 본문과 기준선 버전·범위 이름 */
    public record Prepared(Map<String, Object> body, Long baselineVersionId, String scopeName) {
    }

    /** analytics 숫자 ID → API 문자열 ID(api-rules §2) */
    private static void stringIds(JsonNode r) {
        if (r == null) {
            return;
        }
        if (r.get("scope") instanceof ObjectNode scope && scope.path("spaceId").isNumber()) {
            scope.put("spaceId", scope.path("spaceId").asString());
        }
        if (r.path("children").isArray()) {
            for (JsonNode c : r.path("children").values()) {
                if (c instanceof ObjectNode o && o.path("id").isNumber()) {
                    o.put("id", o.path("id").asString());
                }
            }
        }
    }
}
