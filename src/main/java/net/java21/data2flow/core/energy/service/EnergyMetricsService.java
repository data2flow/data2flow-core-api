package net.java21.data2flow.core.energy.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.analytics.service.AnalyticsClient;
import net.java21.data2flow.core.energy.domain.EnergyErrorCode;
import net.java21.data2flow.core.energy.dto.EnergyMetricDtos.Breakdown;
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

/**
 * 에너지 지표(API-ENE-10)와 하위 단위 순위(API-ENE-11). 롤업 계산(BR-ENE-01·02·07)은 analytics가 하고(API-ENE-85), core는 권한 범위로 자른
 * 공간 트리와 상위 계량기 여부를 넘긴다. 범위가 제한된 사용자는 허용 공간만 보이고 상위 합계도 그 범위로 계산된다(TC-ENE-019)
 */
@Service
public class EnergyMetricsService {

    static final String PATH = "/internal/analytics/energy/metrics";
    private static final Set<String> SORTS = Set.of("KWH", "KRW", "EUI", "DIFF");

    private final RoleChecker roleChecker;
    private final EnergyAccess access;
    private final EnergyConfigRepository config;
    private final AnalyticsClient analytics;

    public EnergyMetricsService(RoleChecker roleChecker, EnergyAccess access, EnergyConfigRepository config, AnalyticsClient analytics) {
        this.roleChecker = roleChecker;
        this.access = access;
        this.config = config;
        this.analytics = analytics;
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
        long orgId = roleChecker.currentUser().organizationId();
        Long scope = EnergyAccess.id(scopeSpaceId, EnergyErrorCode.SPACE_NOT_FOUND);
        String prefix = null;
        if (scope != null) {
            SpaceRow space = access.space(Permission.ENE_READ, orgId, scope);
            prefix = space.path();
        } else {
            roleChecker.require(Permission.ENE_READ);
        }
        List<Map<String, Object>> spaces = new ArrayList<>();
        for (TreeRow t : config.spaceTree(orgId, prefix, roleChecker.spaceScope())) {
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
        JsonNode r = analytics.call(HttpMethod.POST, PATH, null, body, EnergyErrorCode.SPACE_NOT_FOUND);
        stringIds(r);
        return r;
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
