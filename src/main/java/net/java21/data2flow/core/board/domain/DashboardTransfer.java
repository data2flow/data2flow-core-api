package net.java21.data2flow.core.board.domain;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 대시보드 정의 JSON 가져오기·내보내기(DSH-04.08, TC-DSH-050). 내보낸 파일은
 * {@code {formatVersion: 1, exportedAt, dashboard{schemaVersion: 1, name, description, layout, variables, timeRange, resolution, refresh},
 * targets[{ref, kind, deviceId?, deviceExternalId?, deviceName?, spaceId?, spaceName?, metricKey?}]}}이다. ref는 {@code 위젯id/대상순번}.
 * <p>가져올 때 대상 ID를 이 조직 것으로 다시 맞춘다: 같은 ID의 기기가 있고 외부 ID도 같으면 그대로, 아니면 외부 ID로, 공간은 같은 ID → 이름으로 찾는다.
 * 못 찾은 대상은 빼고 {@code unmapped[{ref, reason}]}로 돌려준다("매핑 필요"). 대상을 빼서 위젯의 최소 대상 수보다 적어지면 위젯도 빼고
 * {@code reason=WIDGET_REMOVED}로 알린다. 형식 버전이 다르거나 구조가 틀리면 400 DASHBOARD_LAYOUT_INVALID.
 */
public final class DashboardTransfer {

    public static final int FORMAT_VERSION = 1;

    private DashboardTransfer() {
    }

    /** 이 조직에서 대상을 찾는 방법 */
    public interface Lookup {
        /** 이 ID의 기기가 이 조직에 있으면 외부 ID(없으면 빈 값) */
        Optional<String> deviceExternalId(long deviceId);

        Optional<Long> deviceByExternalId(String externalId);

        boolean spaceExists(long spaceId);

        Optional<Long> spaceByName(String name);
    }

    public record Mapped(ObjectNode dashboard, List<Unmapped> unmapped) {
    }

    public record Unmapped(String ref, String reason) {
    }

    /** 가져오기 본문 → 이 조직에 맞춘 대시보드 정의 */
    public static Mapped map(JsonNode body, Lookup lookup) {
        if (body == null || !body.isObject() || body.path("formatVersion").asInt(-1) != FORMAT_VERSION) {
            throw invalid("formatVersion", "UNSUPPORTED_VERSION");
        }
        JsonNode dashboard = body.get("dashboard");
        if (dashboard == null || !dashboard.isObject()) {
            throw invalid("dashboard", "REQUIRED");
        }
        if (dashboard.has("schemaVersion") && dashboard.path("schemaVersion").asInt(-1) != FORMAT_VERSION) {
            throw invalid("dashboard.schemaVersion", "UNSUPPORTED_VERSION");
        }
        ObjectNode copy = (ObjectNode) dashboard.deepCopy();
        JsonNode widgets = copy.path("layout").path("widgets");
        if (!widgets.isArray()) {
            throw invalid("dashboard.layout.widgets", "TYPE");
        }
        Map<String, JsonNode> exported = new HashMap<>();
        for (JsonNode t : body.path("targets").values()) {
            exported.put(t.path("ref").asString(""), t);
        }
        Set<String> variables = DashboardLayoutValidator.validateVariables(copy.get("variables"));
        List<Unmapped> unmapped = new ArrayList<>();
        ArrayNode kept = ((ArrayNode) widgets).arrayNode();
        for (JsonNode w : widgets.values()) {
            ObjectNode widget = (ObjectNode) w;
            String widgetId = widget.path("id").asString("");
            JsonNode targets = widget.get("targets");
            if (targets != null && targets.isArray()) {
                ArrayNode mappedTargets = ((ArrayNode) targets).arrayNode();
                int index = 0;
                for (JsonNode t : targets.values()) {
                    String ref = widgetId + "/" + index++;
                    ObjectNode target = (ObjectNode) t.deepCopy();
                    String reason = mapTarget(target, exported.get(ref), lookup);
                    if (reason == null) {
                        mappedTargets.add(target);
                    } else {
                        unmapped.add(new Unmapped(ref, reason));
                    }
                }
                widget.set("targets", mappedTargets);
                int min = WidgetTypes.find(widget.path("type").asString("")).map(WidgetTypes.WidgetType::minTargets).orElse(0);
                if (mappedTargets.size() < min) {
                    unmapped.add(new Unmapped(widgetId, "WIDGET_REMOVED"));
                    continue;
                }
            }
            kept.add(widget);
        }
        ((ObjectNode) copy.get("layout")).set("widgets", kept);
        copy.remove("schemaVersion");
        DashboardLayoutValidator.validate(copy.get("layout"), variables);
        return new Mapped(copy, unmapped);
    }

    /** null이면 매핑 성공, 아니면 사유 */
    private static String mapTarget(ObjectNode target, JsonNode exported, Lookup lookup) {
        String kind = target.path("kind").asString("");
        boolean device = WidgetTypes.DEVICE_METRIC.equals(kind) || WidgetTypes.DEVICE.equals(kind);
        String field = device ? "deviceId" : "spaceId";
        JsonNode node = target.get(field);
        String raw = node == null || node.isNull() ? "" : node.isNumber() ? node.asString() : node.asString("");
        if (raw.startsWith("${")) {
            return null; // 변수 참조는 그대로
        }
        if (!raw.matches("\\d{1,18}")) {
            return device ? "DEVICE_NOT_FOUND" : "SPACE_NOT_FOUND";
        }
        long id = Long.parseLong(raw);
        if (device) {
            String externalId = exported == null ? null : text(exported, "deviceExternalId");
            Optional<String> here = lookup.deviceExternalId(id);
            if (here.isPresent() && (externalId == null || externalId.equalsIgnoreCase(here.get()))) {
                return null;
            }
            Optional<Long> byExternal = externalId == null ? Optional.empty() : lookup.deviceByExternalId(externalId);
            if (byExternal.isPresent()) {
                target.put(field, Long.toString(byExternal.get()));
                return null;
            }
            return "DEVICE_NOT_FOUND";
        }
        String spaceName = exported == null ? null : text(exported, "spaceName");
        if (lookup.spaceExists(id) && (exported == null || spaceName == null || id == Long.parseLong(text(exported, "spaceId", "0")))) {
            return null;
        }
        Optional<Long> byName = spaceName == null ? Optional.empty() : lookup.spaceByName(spaceName);
        if (byName.isPresent()) {
            target.put(field, Long.toString(byName.get()));
            return null;
        }
        return "SPACE_NOT_FOUND";
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asString("").isBlank() ? null : v.asString();
    }

    private static String text(JsonNode node, String field, String fallback) {
        String v = text(node, field);
        return v == null || !v.matches("\\d{1,18}") ? fallback : v;
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(BoardErrorCode.DASHBOARD_LAYOUT_INVALID, List.of(new FieldErrorDetail(field, code, null)));
    }
}
