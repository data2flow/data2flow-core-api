package net.java21.data2flow.core.control.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.InternalHttp;
import net.java21.data2flow.core.control.domain.ControlErrorCode;
import net.java21.data2flow.core.control.dto.SafetyDtos;
import net.java21.data2flow.core.control.repository.SafetyRepository;
import net.java21.data2flow.core.control.repository.SceneScheduleRepository;
import net.java21.data2flow.core.control.repository.SceneScheduleRepository.ItemValues;
import net.java21.data2flow.core.control.repository.SceneScheduleRepository.SceneRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 장면(ACT-05.01, API-ACT-10~12, BR-ACT-16)과 일괄 제어(ACT-02.06, API-ACT-05, BR-ACT-17) 중계. 정의는 core, 실행·미리보기·일괄은 action
 * 제어 창구(ADR-049). 장면 항목은 100개까지(400 SCENE_ITEM_LIMIT_EXCEEDED), 일괄 대상은 500대까지(400 COMMAND_BULK_LIMIT_EXCEEDED).
 * 조회 DEV_READ, 장면 수정 SCENE_MANAGE, 실행 SCENE_RUN, 일괄 DEVICE_CONTROL.
 */
@Service
public class SceneService {

    public static final int MAX_ITEMS = 100;
    public static final int MAX_BULK = 500;

    private final SceneScheduleRepository scenes;
    private final SafetyRepository safety;
    private final ActionClient action;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public SceneService(SceneScheduleRepository scenes, SafetyRepository safety, ActionClient action, RoleChecker roleChecker, Audits audits,
                        JsonMapper json, Clock clock) {
        this.scenes = scenes;
        this.safety = safety;
        this.action = action;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SafetyDtos.Scene> list() {
        roleChecker.require(Permission.DEV_READ);
        SpaceScope scope = roleChecker.spaceScope();
        return scenes.listScenes(roleChecker.currentUser().organizationId()).stream()
                .filter(s -> scope.unrestricted() || (s.spaceId() != null && scope.includes(s.spaceId()))).map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public SafetyDtos.Scene get(long id) {
        roleChecker.require(Permission.DEV_READ);
        return view(visible(roleChecker.currentUser().organizationId(), id));
    }

    @Transactional
    public SafetyDtos.Scene create(JsonNode body) {
        roleChecker.require(Permission.SCENE_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        Values v = values(orgId, body);
        if (scenes.existsSceneName(orgId, v.name(), null)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "Duplicated", null)));
        }
        long id;
        try {
            id = scenes.insertScene(orgId, v.name(), v.description(), v.spaceId(), v.items().size(), user.userId(), clock.instant());
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "Duplicated", null)));
        }
        scenes.replaceItems(orgId, id, v.items());
        audits.record(audits.event(orgId, "SCENE_CREATED").actor(user).target("SCENE", Long.toString(id)).detail("name", v.name()));
        return view(scenes.findScene(orgId, id).orElseThrow());
    }

    @Transactional
    public SafetyDtos.Scene update(long id, JsonNode body) {
        roleChecker.require(Permission.SCENE_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        visible(orgId, id);
        Values v = values(orgId, body);
        if (scenes.existsSceneName(orgId, v.name(), id)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "Duplicated", null)));
        }
        Integer base = body.hasNonNull("baseVersion") ? body.get("baseVersion").asInt() : null;
        if (scenes.updateScene(orgId, id, base, v.name(), v.description(), v.spaceId(), v.items().size(), user.userId(), clock.instant()) == 0) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT);
        }
        scenes.replaceItems(orgId, id, v.items());
        audits.record(audits.event(orgId, "SCENE_UPDATED").actor(user).target("SCENE", Long.toString(id)));
        return view(scenes.findScene(orgId, id).orElseThrow());
    }

    @Transactional
    public void delete(long id) {
        roleChecker.require(Permission.SCENE_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        visible(orgId, id);
        scenes.deleteScene(orgId, id);
        audits.record(audits.event(orgId, "SCENE_DELETED").actor(user).target("SCENE", Long.toString(id)));
    }

    /** API-ACT-11 실행(MANUAL) → action 202 {sceneRunId} */
    public InternalHttp.Result run(long id, String idempotencyKey) {
        roleChecker.require(Permission.SCENE_RUN);
        long orgId = roleChecker.currentUser().organizationId();
        SceneRow s = visible(orgId, id);
        if (s.itemCount() > MAX_ITEMS) {
            throw new BusinessException(ControlErrorCode.SCENE_ITEM_LIMIT_EXCEEDED);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("priority", "MANUAL");
        return action.runScene(id, body, idempotencyKey);
    }

    public JsonNode preview(long id) {
        roleChecker.require(Permission.DEV_READ);
        visible(roleChecker.currentUser().organizationId(), id);
        return action.previewScene(id);
    }

    public JsonNode sceneRun(String runId) {
        roleChecker.require(Permission.DEV_READ);
        return action.sceneRun(runId);
    }

    /** API-ACT-05 일괄 제어 {target:{deviceIds[]}|{spaceId, includeChildren, capability}, capability, command, args, preview?} */
    public InternalHttp.Result bulk(JsonNode body, String idempotencyKey) {
        roleChecker.require(Permission.DEVICE_CONTROL);
        long orgId = roleChecker.currentUser().organizationId();
        if (body == null || !body.path("target").isObject()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("target", "NotNull", null)));
        }
        JsonNode target = body.get("target");
        if (target.path("deviceIds").size() > MAX_BULK) {
            throw new BusinessException(ControlErrorCode.COMMAND_BULK_LIMIT_EXCEEDED);
        }
        SpaceScope scope = roleChecker.spaceScope();
        for (JsonNode d : target.path("deviceIds").values()) {
            long deviceId = SafetyService.parseId(d.asString(""), "target.deviceIds");
            Long space = safety.findDeviceSpace(orgId, deviceId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            if (!scope.unrestricted() && (space == 0 || !scope.includes(space))) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
        }
        if (target.hasNonNull("spaceId")) {
            long space = SafetyService.parseId(target.get("spaceId").asString(""), "target.spaceId");
            if (safety.findSpacePath(orgId, space).isEmpty() || !scope.includes(space)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
        }
        Map<String, Object> req = json.convertValue(body, new tools.jackson.core.type.TypeReference<Map<String, Object>>() { });
        return action.bulk(req, idempotencyKey);
    }

    public JsonNode bulkJob(String jobId) {
        roleChecker.require(Permission.DEV_READ);
        return action.bulkJob(jobId);
    }

    /** API-ACT-47 장면 정의(action 내부, 실행 때마다 읽음) */
    @Transactional(readOnly = true)
    public Map<String, Object> internal(long id) {
        SceneRow s = scenes.findSceneAnyOrganization(id).orElseThrow(() -> new BusinessException(ControlErrorCode.SCENE_NOT_FOUND));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sceneId", s.id());
        m.put("organizationId", s.organizationId());
        m.put("name", s.name());
        m.put("spaceId", s.spaceId());
        List<Object> items = new ArrayList<>();
        for (JsonNode i : json.readTree(s.items()).values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("target", i.get("target"));
            item.put("capability", i.path("capability").asString());
            item.put("desired", i.get("desired"));
            items.add(item);
        }
        m.put("items", items);
        return m;
    }

    record Values(String name, String description, Long spaceId, List<ItemValues> items) {
    }

    Values values(long orgId, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("body", "NotNull", null)));
        }
        String name = body.path("name").asString("").strip();
        if (name.isEmpty() || name.length() > 60) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "Size", null)));
        }
        String description = body.hasNonNull("description") ? body.get("description").asString("").strip() : null;
        if (description != null && description.length() > 500) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("description", "Size", null)));
        }
        SpaceScope scope = roleChecker.spaceScope();
        Long spaceId = null;
        if (body.hasNonNull("spaceId")) {
            spaceId = SafetyService.parseId(body.get("spaceId").asString(""), "spaceId");
            if (safety.findSpacePath(orgId, spaceId).isEmpty() || !scope.includes(spaceId)) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
            }
        }
        JsonNode items = body.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("items", "NotEmpty", null)));
        }
        if (items.size() > MAX_ITEMS) {
            throw new BusinessException(ControlErrorCode.SCENE_ITEM_LIMIT_EXCEEDED);
        }
        List<ItemValues> out = new ArrayList<>();
        int n = 0;
        for (JsonNode i : items.values()) {
            String field = "items[" + n++ + "]";
            JsonNode t = i.path("target");
            ObjectNode target = json.createObjectNode();
            if (t.hasNonNull("deviceId")) {
                long deviceId = SafetyService.parseId(t.get("deviceId").asString(""), field + ".target.deviceId");
                Long space = safety.findDeviceSpace(orgId, deviceId).orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
                if (!scope.unrestricted() && (space == 0 || !scope.includes(space))) {
                    throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
                }
                target.put("deviceId", deviceId);
            } else if (t.hasNonNull("spaceId")) {
                long space = SafetyService.parseId(t.get("spaceId").asString(""), field + ".target.spaceId");
                if (safety.findSpacePath(orgId, space).isEmpty() || !scope.includes(space)) {
                    throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
                }
                target.put("spaceId", space);
                target.put("relation", "controls");
                target.put("capability", t.path("capability").asString(i.path("capability").asString("")));
                target.put("includeChildren", !t.has("includeChildren") || t.get("includeChildren").asBoolean(true));
            } else {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field + ".target", "NotNull", null)));
            }
            String capability = i.path("capability").asString("").strip();
            if (capability.isEmpty() || !i.path("desired").isObject() || i.path("desired").isEmpty()) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field + ".desired", "NotEmpty", null)));
            }
            out.add(new ItemValues(json.writeValueAsString(target), capability, json.writeValueAsString(i.get("desired"))));
        }
        return new Values(name, description, spaceId, out);
    }

    SceneRow visible(long orgId, long id) {
        SceneRow s = scenes.findScene(orgId, id).orElseThrow(() -> new BusinessException(ControlErrorCode.SCENE_NOT_FOUND));
        SpaceScope scope = roleChecker.spaceScope();
        if (!scope.unrestricted() && (s.spaceId() == null || !scope.includes(s.spaceId()))) {
            throw new BusinessException(ControlErrorCode.SCENE_NOT_FOUND);
        }
        return s;
    }

    SafetyDtos.Scene view(SceneRow s) {
        List<JsonNode> items = new ArrayList<>(json.readTree(s.items()).values());
        return new SafetyDtos.Scene(Long.toString(s.id()), s.name(), s.description(), s.spaceId() == null ? null : Long.toString(s.spaceId()),
                s.itemCount(), items, s.version(), s.updatedAt());
    }
}
