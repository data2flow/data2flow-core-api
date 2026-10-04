package net.java21.data2flow.core.device.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.device.repository.DeviceHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 기기 변경 이력(API-DEV-27 {@code GET /core/devices/{device-id}/history}, DEV-02.07). DEV_READ + 기기 공간 범위(남의 것·범위 밖 404).
 * 항목: {@code {at, actor{type, id, name}, action, result, changes}} — changes는 감사 detail의 {@code changes}(없으면 detail 전체).
 */
@Service
public class DeviceHistoryService {

    private final DeviceAccess access;
    private final DeviceHistoryRepository history;
    private final RoleChecker roleChecker;
    private final JsonMapper json;

    public DeviceHistoryService(DeviceAccess access, DeviceHistoryRepository history, RoleChecker roleChecker, JsonMapper json) {
        this.access = access;
        this.history = history;
        this.roleChecker = roleChecker;
        this.json = json;
    }

    /** detail에 before·after가 있으면 바뀐 필드만 {필드: [이전,이후]}, changes가 있으면 그것, 아니면 detail 그대로 */
    Object changes(JsonNode detail) {
        if (detail == null) {
            return null;
        }
        if (detail.has("changes")) {
            return detail.get("changes");
        }
        if (detail.path("before").isObject() && detail.path("after").isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            detail.get("before").propertyNames().forEach(keys::add);
            detail.get("after").propertyNames().forEach(keys::add);
            for (String k : keys) {
                JsonNode a = detail.get("before").get(k);
                JsonNode b = detail.get("after").get(k);
                if (a == null ? b != null : !a.equals(b)) {
                    out.put(k, java.util.Arrays.asList(a, b));
                }
            }
            return out;
        }
        return detail;
    }

    @Transactional(readOnly = true)
    public ListApiResponse<Map<String, Object>> list(long deviceId, Integer page, Integer size) {
        roleChecker.require(Permission.DEV_READ);
        access.device(deviceId, Permission.DEV_READ);
        long orgId = access.organizationId();
        PageParams params = PageParams.of(page, size);
        return ListApiResponse.of(params, history.list(orgId, deviceId, params.size(), params.offset()).stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", Long.toString(h.id()));
            m.put("at", h.at());
            Map<String, Object> actor = new LinkedHashMap<>();
            actor.put("type", h.actorType());
            actor.put("id", h.actorId());
            actor.put("name", h.actorName());
            m.put("actor", actor);
            m.put("action", h.action());
            m.put("result", h.result());
            JsonNode detail = h.detail() == null ? null : json.readTree(h.detail());
            m.put("changes", changes(detail));
            return m;
        }).toList(), history.count(orgId, deviceId));
    }
}
