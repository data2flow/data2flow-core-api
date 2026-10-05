package net.java21.data2flow.core.analytics.service;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.core.analytics.domain.AnalyticsErrorCode;
import net.java21.data2flow.core.analytics.repository.AnalyticsDataRepository;
import net.java21.data2flow.core.analytics.repository.AnalyticsDataRepository.DeviceRef;
import net.java21.data2flow.core.analytics.repository.AnalyticsDataRepository.SpaceRef;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 바인딩(역할 → 데이터 연결, ANA domain-model Binding) 판정(BR-ANA-02·03, ANA-01.05).
 * <ul>
 *   <li>역할: 템플릿 RoleSpec에 있는 이름이어야 하고 연결 개수가 min~max(필수 역할은 1개 이상), 의미 조건(semantic)이 있으면 측정 항목의
 *       의미 태그가 같아야 한다 → 400 ANALYSIS_BINDING_INVALID "{role} 역할에 {min}~{max}개의 {semantic} 데이터를 연결하세요"(TC-ANA-024)</li>
 *   <li>데이터: DEVICE_METRIC은 deviceId+metricKey, SPACE_AGGREGATE는 spaceId+metricKey, DERIVED_METRIC은 metricKey와 deviceId 또는 spaceId.
 *       없는 기기·공간·형식 오류도 ANALYSIS_BINDING_INVALID</li>
 *   <li>권한: 기기의 공간·공간 집계의 공간이 요청자 공간 범위 밖이면 403 PERMISSION_DENIED(API-ANA-05, BR-ANA-03)</li>
 * </ul>
 * 결과의 공간 ID 목록은 분석 색인({@code space_scope_ids})과 analytics 저장 요청의 {@code spaceScopeIds}가 된다.
 */
@Component
public class BindingResolver {

    static final Set<String> KINDS = Set.of("DEVICE_METRIC", "SPACE_AGGREGATE", "DERIVED_METRIC");

    private final AnalyticsDataRepository data;

    public BindingResolver(AnalyticsDataRepository data) {
        this.data = data;
    }

    /** 판정 결과 */
    public record Resolved(List<Long> spaceIds, List<Long> deviceIds, int seriesCount, String targetSummary) {
    }

    private record Source(String role, String kind, Long deviceId, Long spaceId, String metricKey, String label) {
    }

    /**
     * @param roles 템플릿 RoleSpec 배열(없으면 역할 판정을 건너뛴다 — analytics가 다시 판정한다)
     */
    public Resolved resolve(long organizationId, SpaceScope scope, JsonNode bindings, JsonNode roles) {
        if (bindings == null || !bindings.isArray()) {
            throw bindingInvalid("bindings", 1, 1, "");
        }
        List<Source> sources = new ArrayList<>();
        for (JsonNode b : bindings.values()) {
            String role = text(b, "role");
            if (role == null || !b.path("sources").isArray()) {
                throw bindingInvalid(role == null ? "?" : role, 1, 1, "");
            }
            for (JsonNode s : b.path("sources").values()) {
                String kind = text(s, "kind");
                Long deviceId = id(s, "deviceId");
                Long spaceId = id(s, "spaceId");
                String metricKey = text(s, "metricKey");
                boolean ok = kind != null && KINDS.contains(kind) && metricKey != null && switch (kind) {
                    case "DEVICE_METRIC" -> deviceId != null;
                    case "SPACE_AGGREGATE" -> spaceId != null;
                    default -> deviceId != null || spaceId != null;
                };
                if (!ok) {
                    throw bindingInvalid(role, 1, 1, "");
                }
                sources.add(new Source(role, kind, deviceId, spaceId, metricKey, text(s, "label")));
            }
        }
        if (roles != null && roles.isArray()) {
            checkRoles(organizationId, sources, roles);
        }
        Set<Long> deviceIds = new TreeSet<>();
        Set<Long> directSpaceIds = new TreeSet<>();
        for (Source s : sources) {
            if (s.deviceId() != null) {
                deviceIds.add(s.deviceId());
            } else {
                directSpaceIds.add(s.spaceId());
            }
        }
        Map<Long, DeviceRef> devices = data.findDevices(organizationId, deviceIds);
        Map<Long, SpaceRef> spaces = data.findSpaces(organizationId, directSpaceIds);
        Set<Long> spaceIds = new TreeSet<>();
        for (Source s : sources) {
            Long spaceId;
            if (s.deviceId() != null) {
                DeviceRef d = devices.get(s.deviceId());
                if (d == null || d.spaceId() == null) {
                    throw bindingInvalid(s.role(), 1, 1, "");
                }
                spaceId = d.spaceId();
            } else {
                if (!spaces.containsKey(s.spaceId())) {
                    throw bindingInvalid(s.role(), 1, 1, "");
                }
                spaceId = s.spaceId();
            }
            if (!scope.includes(spaceId)) {
                throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
            }
            spaceIds.add(spaceId);
        }
        return new Resolved(new ArrayList<>(spaceIds), new ArrayList<>(deviceIds), sources.size(), summary(sources, devices, spaces));
    }

    private void checkRoles(long organizationId, List<Source> sources, JsonNode roles) {
        Map<String, JsonNode> specs = new HashMap<>();
        for (JsonNode r : roles.values()) {
            String name = text(r, "name");
            if (name != null) {
                specs.put(name, r);
            }
        }
        Map<String, Integer> counts = new HashMap<>();
        Set<String> metricKeys = new LinkedHashSet<>();
        for (Source s : sources) {
            if (!specs.containsKey(s.role())) {
                throw bindingInvalid(s.role(), 0, 0, "");
            }
            counts.merge(s.role(), 1, Integer::sum);
            metricKeys.add(s.metricKey());
        }
        Map<String, String> semantics = data.metricSemantics(organizationId, metricKeys);
        for (Map.Entry<String, JsonNode> e : specs.entrySet()) {
            JsonNode spec = e.getValue();
            boolean required = spec.path("required").asBoolean(false);
            int min = spec.path("min").isNumber() ? spec.path("min").asInt() : (required ? 1 : 0);
            if (required && min < 1) {
                min = 1;
            }
            int max = spec.path("max").isNumber() ? spec.path("max").asInt() : Integer.MAX_VALUE;
            String semantic = text(spec, "semantic");
            int count = counts.getOrDefault(e.getKey(), 0);
            String label = semantic != null ? semantic : text(spec, "type") == null ? "" : text(spec, "type");
            if (count < min || count > max) {
                throw bindingInvalid(e.getKey(), min, max == Integer.MAX_VALUE ? count : max, label);
            }
            if (semantic != null) {
                for (Source s : sources) {
                    if (s.role().equals(e.getKey()) && !semantic.equals(semantics.get(s.metricKey()))) {
                        throw bindingInvalid(e.getKey(), min, max == Integer.MAX_VALUE ? count : max, label);
                    }
                }
            }
        }
    }

    private static String summary(List<Source> sources, Map<Long, DeviceRef> devices, Map<Long, SpaceRef> spaces) {
        if (sources.isEmpty()) {
            return null;
        }
        Source first = sources.getFirst();
        String head = first.label();
        if (head == null) {
            String owner = first.deviceId() != null ? devices.get(first.deviceId()).name() : spaces.get(first.spaceId()).name();
            head = owner + " · " + first.metricKey();
        }
        String text = sources.size() == 1 ? head : head + " +" + (sources.size() - 1);
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asString().strip();
        return s.isEmpty() ? null : s;
    }

    static Long id(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isIntegralNumber() && v.asLong() > 0) {
            return v.asLong();
        }
        String s = v.isString() ? v.asString().strip() : "";
        if (!s.matches("[1-9][0-9]{0,18}")) {
            throw bindingInvalid(field, 1, 1, "");
        }
        return Long.parseLong(s);
    }

    static BusinessException bindingInvalid(String role, int min, int max, String semantic) {
        return new BusinessException(AnalyticsErrorCode.ANALYSIS_BINDING_INVALID, role, min, max, semantic);
    }
}
