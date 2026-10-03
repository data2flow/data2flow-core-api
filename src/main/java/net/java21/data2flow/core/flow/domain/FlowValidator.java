package net.java21.data2flow.core.flow.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import net.java21.data2flow.contracts.capability.AttributeConstraint;
import net.java21.data2flow.contracts.capability.CapabilityCatalog;
import net.java21.data2flow.contracts.capability.CommandArgsValidator;
import net.java21.data2flow.contracts.capability.CommandValidation;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.flow.FlowDefinition;
import net.java21.data2flow.contracts.flow.FlowNode;
import net.java21.data2flow.contracts.flow.FlowNodeType;
import net.java21.data2flow.core.source.domain.JsonSchemaLite;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 적용 전 검증(FLW-06.06, API-FLW-06, BR-FLW-02·03·04·05·16·37). 저장은 검증 오류가 있어도 되고(BR-FLW-05) 적용만 막는다.
 *
 * <table>
 *   <tr><th>code</th><th>뜻</th></tr>
 *   <tr><td>NO_TRIGGER</td><td>트리거 노드가 없다(CATCH 플로우는 제외, BR-FLW-02)</td></tr>
 *   <tr><td>UNCONNECTED</td><td>입력이 없는 비트리거 노드, 없는 노드를 잇는 연결선</td></tr>
 *   <tr><td>TYPE_MISMATCH</td><td>트리거로 들어가는 연결선, 포트 타입 불일치(BR-FLW-03)</td></tr>
 *   <tr><td>CYCLE</td><td>순환(BR-FLW-04)</td></tr>
 *   <tr><td>INVALID_CONFIG</td><td>설정이 노드 설정 스키마에 맞지 않음, 제어 명령 인자 오류(contracts CommandArgsValidator),
 *       priority가 AUTO가 아님(BR-FLW-37), 노드 ID 중복</td></tr>
 *   <tr><td>UNKNOWN_NODE_TYPE·UNKNOWN_PORT·INVALID_DEFINITION</td><td>모르는 노드 종류, 없는 출력 포트, 정의 형식 오류</td></tr>
 *   <tr><td>TARGET_MISSING</td><td>대상 공간·기기가 조직에 없다</td></tr>
 *   <tr><td>LIMIT</td><td>노드 200개 초과(BR-FLW-16)</td></tr>
 * </table>
 * 경고: TARGET_EMPTY(대상에 측정·제어 기기가 아직 없음, 적용은 됨 — TC-FLW-020).
 */
public final class FlowValidator {

    public static final int MAX_NODES = 200;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    /** 검증 결과 한 줄(API-FLW-06 errors·warnings, api-rules §5 모양 {field, code, message}, ADR-044) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Issue(String field, String code, String message) {

        /** field의 노드 ID({@code nodes[<id>]…}). 노드가 아니면 null */
        public String nodeId() {
            if (field == null || !field.startsWith("nodes[")) {
                return null;
            }
            int end = field.indexOf(']');
            return end < 0 ? null : field.substring(6, end);
        }
    }

    /**
     * @param hasControlNode 제어·장면 노드가 있다
     * @param outOfScope     사용자 공간 범위 밖이라 보이지 않는 대상(적용 시 404, TC-FLW-117)
     */
    public record Result(List<Issue> errors, List<Issue> warnings, boolean hasControlNode, Set<String> outOfScope,
                         FlowDefinition definition) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /** 대상 판정(공간·기기의 존재·범위, 대상 기기 수) */
    public interface Targets {

        enum Status { OK, MISSING, OUT_OF_SCOPE }

        Status space(long spaceId);

        Status device(long deviceId);

        /** 공간(하위 포함 여부)에서 그 관계로 묶인 기기 수. capability가 있으면 그 기능이 있는 기기만 */
        long related(long spaceId, String relation, boolean includeChildren, String capability);

        CapabilityCatalog capabilities();

        /** 조직 절대 한계 중 기능 하나 */
        Map<String, AttributeConstraint> absoluteLimits(String capability);
    }

    private final NodeCatalog catalog;
    private final JsonMapper json;

    public FlowValidator(NodeCatalog catalog, JsonMapper json) {
        this.catalog = catalog;
        this.json = json;
    }

    /** 정의 JSON을 계약 모양으로 읽는다. 못 읽으면 빈 값 */
    public Optional<FlowDefinition> parse(JsonNode definition) {
        if (definition == null || !definition.isObject()) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.treeToValue(definition, FlowDefinition.class));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public Result validate(JsonNode raw, String kind, Targets targets) {
        return validate(raw, kind, targets, true);
    }

    /**
     * @param structural true면 구조·설정 검사(트리거·연결·포트·순환·설정 스키마·노드 수)도 한다. flow-engine 컴파일러 검증(API-FLW-84)을
     *                   받았으면 false로 업무 검사(대상 존재·범위, 제어 명령 인자, 우선순위)만 한다(ADR-044)
     */
    public Result validate(JsonNode raw, String kind, Targets targets, boolean structural) {
        List<Issue> errors = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        Set<String> outOfScope = new LinkedHashSet<>();
        Optional<FlowDefinition> parsed = parse(raw);
        if (parsed.isEmpty()) {
            errors.add(new Issue("definition", "INVALID_DEFINITION", "플로우 정의 형식이 올바르지 않습니다"));
            return new Result(errors, warnings, false, outOfScope, null);
        }
        FlowDefinition def = parsed.get();
        if (structural && !FlowDefinition.SCHEMA.equals(def.schema())) {
            errors.add(new Issue("schema", "INVALID_DEFINITION", "schema는 " + FlowDefinition.SCHEMA + "여야 합니다"));
        }
        if (structural && def.nodes().size() > MAX_NODES) {
            errors.add(new Issue("nodes", "LIMIT", "플로우당 노드는 " + MAX_NODES + "개까지입니다"));
        }
        Map<String, FlowNode> nodes = new LinkedHashMap<>();
        boolean hasControl = false;
        boolean hasTrigger = false;
        for (FlowNode node : def.nodes()) {
            if (nodes.putIfAbsent(node.id(), node) != null && structural) {
                errors.add(new Issue("nodes[" + node.id() + "].id", "INVALID_CONFIG", "노드 ID가 겹칩니다"));
                continue;
            }
            Optional<FlowNodeType> type = catalog.find(node.type());
            if (type.isEmpty()) {
                if (!structural) {
                    continue;
                }
                errors.add(new Issue("nodes[" + node.id() + "].type", "UNKNOWN_NODE_TYPE", "모르는 노드 종류입니다: " + node.type()));
                continue;
            }
            hasControl |= catalog.isControl(node.type());
            hasTrigger |= catalog.isTrigger(node.type());
            if (Boolean.TRUE.equals(node.disabled())) {
                continue;
            }
            JsonNode config = node.config() == null ? json.createObjectNode() : node.config();
            for (FieldErrorDetail e : structural ? JsonSchemaLite.validate(type.get().configSchema(), config, "") : List.<FieldErrorDetail>of()) {
                String field = e.field() == null || e.field().isEmpty() ? "" : "." + e.field();
                errors.add(new Issue("nodes[" + node.id() + "].config" + field, "INVALID_CONFIG", "설정이 올바르지 않습니다(" + e.code() + ")"));
            }
            checkTargets(node, config, targets, errors, warnings, outOfScope);
            if ("action.control".equals(node.type())) {
                checkControl(node, config, targets, errors);
            }
        }
        if (structural) {
            if (!hasTrigger && !"CATCH".equals(kind)) {
                errors.add(new Issue("nodes", "NO_TRIGGER", "트리거 노드가 하나 이상 있어야 합니다"));
            }
            checkWires(def, nodes, errors);
            checkCycle(def, nodes, errors);
        }
        return new Result(errors, warnings, hasControl, outOfScope, def);
    }

    private void checkWires(FlowDefinition def, Map<String, FlowNode> nodes, List<Issue> errors) {
        Set<String> withInput = new HashSet<>();
        Set<String> withOutput = new HashSet<>();
        int i = 0;
        for (FlowDefinition.Wire w : def.wires()) {
            String path = "wires[" + i++ + "]";
            FlowNode from = nodes.get(w.from());
            FlowNode to = nodes.get(w.to());
            if (from == null || to == null) {
                errors.add(new Issue(path, "UNCONNECTED", "없는 노드를 잇습니다"));
                continue;
            }
            if (w.from().equals(w.to())) {
                errors.add(new Issue("nodes[" + w.from() + "]", "CYCLE", "노드가 자기 자신으로 이어집니다"));
                continue;
            }
            withInput.add(w.to());
            withOutput.add(w.from());
            Optional<FlowNodeType> fromType = catalog.find(from.type());
            Optional<FlowNodeType> toType = catalog.find(to.type());
            if (fromType.isEmpty() || toType.isEmpty()) {
                continue;
            }
            String outType = outputType(fromType.get(), from, w.port());
            if (outType == null) {
                errors.add(new Issue(path + ".port", "UNKNOWN_PORT", "없는 출력 포트입니다: " + w.port()));
                continue;
            }
            if (toType.get().inputs().isEmpty()) {
                errors.add(new Issue(path, "TYPE_MISMATCH", "트리거 노드에는 입력을 연결할 수 없습니다"));
                continue;
            }
            String inType = toType.get().inputs().getFirst().type();
            if (!compatible(outType, inType)) {
                errors.add(new Issue(path, "TYPE_MISMATCH", "포트 타입이 맞지 않습니다: " + outType + " → " + inType));
            }
        }
        for (FlowNode node : nodes.values()) {
            if (Boolean.TRUE.equals(node.disabled()) || catalog.find(node.type()).isEmpty()) {
                continue;
            }
            boolean trigger = catalog.isTrigger(node.type());
            if (!trigger && !withInput.contains(node.id())) {
                errors.add(new Issue("nodes[" + node.id() + "]", "UNCONNECTED", "입력이 연결되지 않았습니다"));
            } else if (trigger && !withOutput.contains(node.id()) && nodes.size() > 1) {
                errors.add(new Issue("nodes[" + node.id() + "]", "UNCONNECTED", "출력이 연결되지 않았습니다"));
            }
        }
    }

    /** 출력 포트 타입. 고정 포트, 동적 포트(switch 케이스·JS outN), 공통 error. 기본 출력(port 없음)은 첫 포트 */
    static String outputType(FlowNodeType type, FlowNode node, String port) {
        if (port == null || port.isBlank()) {
            return type.outputs().isEmpty() ? null : type.outputs().getFirst().type();
        }
        if (FlowNodeType.ERROR_PORT.equals(port)) {
            return "error";
        }
        for (FlowNodeType.Port p : type.outputs()) {
            if (p.name().equals(port)) {
                return p.type();
            }
        }
        if (node.outputs() != null && node.outputs().contains(port)) {
            return "message";
        }
        boolean dynamic = type.outputs().stream().anyMatch(p -> Boolean.TRUE.equals(p.dynamic()));
        if (dynamic) {
            if ("transform.js".equals(type.type()) && port.matches("out(10|[1-9])")) {
                return "message";
            }
            JsonNode cases = node.config() == null ? null : node.config().get("cases");
            if (cases != null && cases.isArray()) {
                for (JsonNode c : cases.values()) {
                    if (port.equals(c.path("name").asString(""))) {
                        return "message";
                    }
                }
            }
        }
        return null;
    }

    /** BR-FLW-03: 같은 타입, 한쪽이 any, error → message */
    static boolean compatible(String out, String in) {
        return out.equals(in) || "any".equals(out) || "any".equals(in) || ("error".equals(out) && "message".equals(in));
    }

    private static void checkCycle(FlowDefinition def, Map<String, FlowNode> nodes, List<Issue> errors) {
        Map<String, List<String>> edges = new HashMap<>();
        for (FlowDefinition.Wire w : def.wires()) {
            if (nodes.containsKey(w.from()) && nodes.containsKey(w.to()) && !w.from().equals(w.to())) {
                edges.computeIfAbsent(w.from(), k -> new ArrayList<>()).add(w.to());
            }
        }
        Map<String, Integer> color = new HashMap<>();
        for (String start : nodes.keySet()) {
            String cycleAt = dfs(start, edges, color);
            if (cycleAt != null) {
                errors.add(new Issue("nodes[" + cycleAt + "]", "CYCLE", "연결선이 순환합니다"));
                return;
            }
        }
    }

    private static String dfs(String node, Map<String, List<String>> edges, Map<String, Integer> color) {
        Integer c = color.get(node);
        if (c != null) {
            return c == 1 ? node : null;
        }
        color.put(node, 1);
        for (String next : edges.getOrDefault(node, List.of())) {
            String found = dfs(next, edges, color);
            if (found != null) {
                return found;
            }
        }
        color.put(node, 2);
        return null;
    }

    private void checkTargets(FlowNode node, JsonNode config, Targets targets, List<Issue> errors, List<Issue> warnings,
                              Set<String> outOfScope) {
        JsonNode target = config.get("target");
        if (target == null || !target.isObject()) {
            return;
        }
        String path = "nodes[" + node.id() + "].config.target";
        JsonNode devices = target.get("deviceIds");
        if (devices != null && devices.isArray() && !devices.isEmpty()) {
            for (JsonNode d : devices.values()) {
                Long id = id(d);
                Targets.Status status = id == null ? Targets.Status.MISSING : targets.device(id);
                if (status == Targets.Status.MISSING) {
                    errors.add(new Issue(path + ".deviceIds", "TARGET_MISSING", "대상 기기가 없습니다: " + d.asString("")));
                } else if (status == Targets.Status.OUT_OF_SCOPE) {
                    outOfScope.add("device:" + id);
                }
            }
            return;
        }
        Long spaceId = id(target.get("spaceId"));
        if (target.hasNonNull("spaceId") && spaceId == null) {
            errors.add(new Issue(path + ".spaceId", "TARGET_MISSING", "대상 공간이 없습니다"));
            return;
        }
        if (spaceId == null) {
            return;
        }
        Targets.Status status = targets.space(spaceId);
        if (status == Targets.Status.MISSING) {
            errors.add(new Issue(path + ".spaceId", "TARGET_MISSING", "대상 공간이 없습니다: " + spaceId));
            return;
        }
        if (status == Targets.Status.OUT_OF_SCOPE) {
            outOfScope.add("space:" + spaceId);
            return;
        }
        boolean control = "action.control".equals(node.type());
        String relation = control ? "controls" : target.path("relation").asString("measures");
        String capability = control ? config.path("capability").asString(target.path("capability").asString(null)) : null;
        boolean children = target.path("includeChildren").asBoolean(false);
        if (targets.related(spaceId, relation, children, capability) == 0) {
            warnings.add(new Issue(path, "TARGET_EMPTY", control ? "대상 기기 없음: 이 공간을 제어하는 " + capability + " 기기가 없습니다" : "대상 기기 없음: 이 공간을 측정하는 기기가 없습니다"));
        }
    }

    private void checkControl(FlowNode node, JsonNode config, Targets targets, List<Issue> errors) {
        String path = "nodes[" + node.id() + "].config";
        if (config.hasNonNull("priority") && !"AUTO".equals(config.get("priority").asString(""))) {
            errors.add(new Issue(path + ".priority", "INVALID_CONFIG", "제어 노드의 우선순위는 AUTO로 고정입니다"));
        }
        String capability = config.path("capability").asString(null);
        String command = config.path("command").asString(null);
        JsonNode args = config.get("args");
        if (capability == null || command == null || args == null || !args.isObject()) {
            return;
        }
        CommandValidation result = CommandArgsValidator.validate(targets.capabilities(), capability, command, json.convertValue(args, MAP),
                Map.of(), targets.absoluteLimits(capability));
        for (var v : result.violations()) {
            errors.add(new Issue(path + "." + v.field(), "INVALID_CONFIG", v.reason().resultCode() + ": " + v.message()));
        }
    }

    static Long id(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isString()) {
            try {
                return Long.parseLong(node.asString().strip());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
