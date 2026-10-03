package net.java21.data2flow.core.flow.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.java21.data2flow.contracts.flow.FlowDefinition;
import net.java21.data2flow.contracts.flow.FlowNode;
import net.java21.data2flow.contracts.flow.FlowNodeType;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 버전 비교(API-FLW-04 version-diff)와 적용 전 변경 요약(API-FLW-06 changeSummary·risky). 노드는 ID로 짝짓는다(BR-FLW-01).
 * 설정이 바뀐 노드의 상태 정책은 노드 카탈로그 {@code statePolicy}(설정 필드 → KEEP·RESET·MIGRATE)에서 가장 강한 것(RESET > MIGRATE > KEEP)이고,
 * 정책표에 없는 필드는 RESET으로 본다(flow-engine-and-live-reload.md §4.3). 이름·위치만 바뀌면 상태 정책 없이 fields만 준다.
 */
public final class FlowDiff {

    private FlowDiff() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Changed(String nodeId, List<String> fields, String statePolicy) {
    }

    public record Wires(List<String> added, List<String> removed) {
    }

    /** API-FLW-04 응답 */
    public record Diff(List<String> added, List<String> removed, List<Changed> changed, Wires wires, List<String> settings) {
    }

    public record Removed(String nodeId, boolean retainedState) {
    }

    public record ChangedPolicy(String nodeId, String statePolicy) {
    }

    /** API-FLW-06 changeSummary */
    public record Summary(List<String> added, List<Removed> removed, List<ChangedPolicy> changed) {
    }

    /** API-FLW-06 risky */
    public record Risky(boolean controlNodesChanged, boolean executionModeChanged) {
    }

    public static Diff diff(FlowDefinition from, FlowDefinition to, NodeCatalog catalog) {
        Map<String, FlowNode> a = index(from);
        Map<String, FlowNode> b = index(to);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<Changed> changed = new ArrayList<>();
        for (String id : b.keySet()) {
            if (!a.containsKey(id)) {
                added.add(id);
            }
        }
        for (String id : a.keySet()) {
            FlowNode before = a.get(id);
            FlowNode after = b.get(id);
            if (after == null) {
                removed.add(id);
                continue;
            }
            List<String> fields = new ArrayList<>();
            if (!Objects.equals(before.type(), after.type())) {
                fields.add("type");
            }
            if (!Objects.equals(before.name(), after.name())) {
                fields.add("name");
            }
            if (!Objects.equals(before.position(), after.position())) {
                fields.add("position");
            }
            if (!Objects.equals(before.disabled(), after.disabled())) {
                fields.add("disabled");
            }
            if (!Objects.equals(before.retry(), after.retry())) {
                fields.add("retry");
            }
            List<String> configFields = configChanges(before.config(), after.config());
            configFields.forEach(f -> fields.add("config." + f));
            if (!fields.isEmpty()) {
                String policy = null;
                if (!configFields.isEmpty() || fields.contains("type")) {
                    policy = fields.contains("type") ? "RESET" : policy(catalog, after.type(), configFields);
                }
                changed.add(new Changed(id, fields, policy));
            }
        }
        Set<String> wa = wires(from);
        Set<String> wb = wires(to);
        List<String> wiresAdded = wb.stream().filter(w -> !wa.contains(w)).toList();
        List<String> wiresRemoved = wa.stream().filter(w -> !wb.contains(w)).toList();
        List<String> settings = new ArrayList<>();
        if (from == null || !Objects.equals(from.mode(), to.mode())) {
            settings.add("mode");
        }
        if (from == null || !Objects.equals(from.variables(), to.variables())) {
            settings.add("variables");
        }
        if (from == null || !Objects.equals(from.subflows(), to.subflows())) {
            settings.add("subflows");
        }
        if (from == null) {
            settings.clear();
        }
        return new Diff(added, removed, changed, new Wires(wiresAdded, wiresRemoved), settings);
    }

    /** 현재 ACTIVE(없으면 null) 대비 요약. 삭제한 노드의 상태는 24시간 남는다(retainedState=true) */
    public static Summary summary(FlowDefinition active, FlowDefinition next, NodeCatalog catalog) {
        Diff d = diff(active, next, catalog);
        List<ChangedPolicy> changed = d.changed().stream().filter(c -> c.statePolicy() != null)
                .map(c -> new ChangedPolicy(c.nodeId(), c.statePolicy())).toList();
        return new Summary(d.added(), d.removed().stream().map(id -> new Removed(id, true)).toList(), changed);
    }

    /** 제어·장면 노드의 추가·삭제·설정 변경, 실행 모드 변경(BR-FLW-08) */
    public static Risky risky(FlowDefinition active, FlowDefinition next, NodeCatalog catalog) {
        Map<String, FlowNode> a = index(active);
        Map<String, FlowNode> b = index(next);
        boolean control = false;
        Set<String> ids = new LinkedHashSet<>(a.keySet());
        ids.addAll(b.keySet());
        for (String id : ids) {
            FlowNode before = a.get(id);
            FlowNode after = b.get(id);
            boolean isControl = (before != null && catalog.isControl(before.type())) || (after != null && catalog.isControl(after.type()));
            if (!isControl) {
                continue;
            }
            if (before == null || after == null || !Objects.equals(before.type(), after.type())
                    || !Objects.equals(before.config(), after.config()) || !Objects.equals(before.disabled(), after.disabled())) {
                control = true;
                break;
            }
        }
        boolean mode = active != null && !Objects.equals(active.mode(), next.mode());
        return new Risky(control, mode);
    }

    private static String policy(NodeCatalog catalog, String type, List<String> fields) {
        JsonNode table = catalog.find(type).map(FlowNodeType::statePolicy).orElse(null);
        int strongest = 0;
        for (String field : fields) {
            String top = field.contains(".") ? field.substring(0, field.indexOf('.')) : field;
            String p = table == null || !table.has(top) ? "RESET" : table.get(top).asString("RESET");
            strongest = Math.max(strongest, rank(p));
        }
        return switch (strongest) {
            case 2 -> "RESET";
            case 1 -> "MIGRATE";
            default -> "KEEP";
        };
    }

    private static int rank(String policy) {
        return switch (policy) {
            case "RESET" -> 2;
            case "MIGRATE" -> 1;
            default -> 0;
        };
    }

    /** 설정에서 바뀐 최상위 필드 이름 */
    static List<String> configChanges(JsonNode before, JsonNode after) {
        List<String> fields = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        if (before != null && before.isObject()) {
            keys.addAll(before.propertyNames());
        }
        if (after != null && after.isObject()) {
            keys.addAll(after.propertyNames());
        }
        for (String key : keys) {
            JsonNode x = before == null ? null : before.get(key);
            JsonNode y = after == null ? null : after.get(key);
            if (!Objects.equals(x, y)) {
                fields.add(key);
            }
        }
        return fields;
    }

    private static Map<String, FlowNode> index(FlowDefinition def) {
        Map<String, FlowNode> map = new LinkedHashMap<>();
        if (def != null) {
            def.nodes().forEach(n -> map.putIfAbsent(n.id(), n));
        }
        return map;
    }

    private static Set<String> wires(FlowDefinition def) {
        Set<String> set = new LinkedHashSet<>();
        if (def != null) {
            def.wires().forEach(w -> set.add(w.from() + (w.port() == null ? "" : ":" + w.port()) + "->" + w.to()));
        }
        return set;
    }
}
