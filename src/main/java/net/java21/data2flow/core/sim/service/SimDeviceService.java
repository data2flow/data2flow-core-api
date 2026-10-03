package net.java21.data2flow.core.sim.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.event.DeviceChanged;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.device.repository.DeviceRepository;
import net.java21.data2flow.core.device.service.DeviceEvents;
import net.java21.data2flow.core.flow.dto.FlowDtos.TemplateResult;
import net.java21.data2flow.core.flow.service.FlowTemplateService;
import net.java21.data2flow.core.sim.domain.SimErrorCode;
import net.java21.data2flow.core.sim.repository.SimRepository;
import net.java21.data2flow.core.sim.repository.SimRepository.VirtualDevice;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.service.SpaceSupport;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 가상 기기·키트·데모 프리셋 기준 정보 사가(SIM-09.01·09.07·04.05·11.01, API-SIM-05·06·07·09·18). core가 DEV 기기(virtual, 조직 SIM 소스,
 * 모델 = 유형의 실측 모델 또는 가상 모델 {@code SIM-<유형>})를 먼저 만들고 simulator에 설정을 만든다. 한 트랜잭션이라 simulator가 실패하면
 * core 행도 되돌린다. 가상 장비 모델에는 조직 가상 드라이버(VIRTUAL)를 연결해 제어 창구가 바로 명령을 보낼 수 있다(SIM-03.02).
 */
@Service
public class SimDeviceService {

    /** 조직당 가상 기기 한도(SIM-11.01) */
    public static final int MAX_DEVICES = 500;
    static final String AUDIT_DEVICE_CREATED = "SIM_DEVICE_CREATED";
    static final String AUDIT_DEVICE_CHANGED = "SIM_DEVICE_PROPERTIES_CHANGED";
    static final String AUDIT_DEVICE_DELETED = "SIM_DEVICE_DELETED";
    static final String AUDIT_PRESET_PREPARED = "SIM_PRESET_PREPARED";

    private final SimRepository sim;
    private final SimBootstrap bootstrap;
    private final SimSpaceService spaces;
    private final SimulatorClient simulator;
    private final FlowTemplateService templates;
    private final DeviceRepository devices;
    private final DeviceEvents deviceEvents;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public SimDeviceService(SimRepository sim, SimBootstrap bootstrap, SimSpaceService spaces, SimulatorClient simulator,
                            FlowTemplateService templates, DeviceRepository devices, DeviceEvents deviceEvents, RoleChecker roleChecker,
                            Audits audits, JsonMapper json, Clock clock) {
        this.sim = sim;
        this.bootstrap = bootstrap;
        this.spaces = spaces;
        this.simulator = simulator;
        this.templates = templates;
        this.devices = devices;
        this.deviceEvents = deviceEvents;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** 만든 기기 하나 */
    public record Placed(long deviceId, String name, String typeKey, String externalId, long sourceId, String relation) {
    }

    /** 배치할 유형 하나(카탈로그 유형 노드, 수, 이름 머리, 관계) */
    record Item(JsonNode type, int count, String namePrefix, String relation) {
    }

    /** API-SIM-05 가상 기기 배치 {typeId, spaceId, count(1~50), namePrefix?, profileId?, reportMode?} — SIM_MANAGE */
    @Transactional
    public Map<String, Object> place(JsonNode body) {
        roleChecker.require(Permission.SIM_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        if (body == null || !body.isObject()) {
            throw SpaceSupport.invalid("body", "NotNull");
        }
        String typeId = body.path("typeId").asString("").strip();
        if (typeId.isEmpty()) {
            throw SpaceSupport.invalid("typeId", "NotNull");
        }
        long spaceId = SpaceSupport.parseId(body.path("spaceId").asString(""), "spaceId") == null ? -1
                : SpaceSupport.parseId(body.path("spaceId").asString(""), "spaceId");
        int count = body.path("count").asInt(0);
        if (count < 1 || count > 50) {
            throw SpaceSupport.invalid("count", "Range");
        }
        Space space = targetSpace(spaceId);
        JsonNode type = catalogType(catalog(), typeId);
        String prefix = body.hasNonNull("namePrefix") ? body.get("namePrefix").asString("").strip() : type.path("name").asString("가상 기기");
        String reportMode = body.hasNonNull("reportMode") ? body.get("reportMode").asString("ALWAYS").toUpperCase(Locale.ROOT) : "ALWAYS";
        if (!reportMode.equals("ALWAYS") && !reportMode.equals("RUN_ONLY")) {
            throw SpaceSupport.invalid("reportMode", "Pattern");
        }
        List<Placed> placed = provision(user, space.id(), List.of(new Item(type, count, prefix.isEmpty() ? "가상 기기" : prefix, null)));
        ArrayNode list = json.createArrayNode();
        for (Placed p : placed) {
            ObjectNode d = list.addObject();
            d.put("deviceId", p.deviceId());
            d.put("typeId", typeId);
            d.put("typeKey", p.typeKey());
            d.put("spaceId", space.id());
            d.put("name", p.name());
            d.put("externalId", p.externalId());
            d.put("sourceId", p.sourceId());
            d.put("reportMode", reportMode);
            if (body.hasNonNull("profileId")) {
                d.set("profileId", body.get("profileId"));
            }
        }
        simulator.call(HttpMethod.POST, "/internal/sim/devices/batch-create", null, Map.of("devices", list));
        audits.record(audits.event(user.organizationId(), AUDIT_DEVICE_CREATED).actor(user).target("SPACE", Long.toString(space.id()))
                .detail("typeKey", placed.getFirst().typeKey()).detail("count", placed.size()));
        return Map.of("devices", placed.stream().map(p -> Map.of("deviceId", Long.toString(p.deviceId()), "name", p.name())).toList());
    }

    /** API-SIM-06 키트 배치 {spaceId? | newSpace{name, parentId?, preset}, profileOverrides?} — SIM_MANAGE, 원자적(BR-SIM-19) */
    @Transactional
    public JsonNode placeKit(String kitKey, JsonNode body) {
        roleChecker.require(Permission.SIM_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        JsonNode catalog = catalog();
        JsonNode kit = null;
        for (JsonNode k : catalog.path("kits").values()) {
            if (kitKey.equals(k.path("key").asString(""))) {
                kit = k;
            }
        }
        if (kit == null) {
            throw new BusinessException(SimErrorCode.SIM_NOT_FOUND);
        }
        Space space;
        if (body != null && body.hasNonNull("newSpace")) {
            JsonNode ns = body.get("newSpace");
            String name = ns.path("name").asString("").strip();
            if (name.isEmpty() || name.length() > 100) {
                throw SpaceSupport.invalid("newSpace.name", "Size");
            }
            Long parentId = ns.hasNonNull("parentId") ? SpaceSupport.parseId(ns.get("parentId").asString(""), "newSpace.parentId") : null;
            space = spaces.createSpace(user, name, parentId, SimSpaceService.preset(ns), ns.get("physics"));
        } else {
            Long spaceId = body == null ? null : SpaceSupport.parseId(body.path("spaceId").asString(""), "spaceId");
            if (spaceId == null) {
                throw SpaceSupport.invalid("spaceId", "NotNull");
            }
            space = targetSpace(spaceId);
        }
        List<Item> items = new ArrayList<>();
        for (JsonNode item : kit.path("items").values()) {
            items.add(new Item(catalogTypeByKey(catalog, item.path("typeKey").asString("")), item.path("count").asInt(1),
                    item.path("namePrefix").asString(item.path("typeKey").asString("")), item.path("relation").asString(null)));
        }
        List<Placed> placed = provision(user, space.id(), items);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("spaceId", Long.toString(space.id()));
        request.put("devices", placed.stream().map(SimDeviceService::placedRequest).toList());
        if (body != null && body.hasNonNull("profileOverrides")) {
            request.put("profileOverrides", body.get("profileOverrides"));
        }
        JsonNode result = simulator.call(HttpMethod.POST, "/internal/sim/kits/" + kitKey + "/place", null, request);
        audits.record(audits.event(user.organizationId(), AUDIT_DEVICE_CREATED).actor(user).target("SPACE", Long.toString(space.id()))
                .detail("kit", kitKey).detail("count", placed.size()));
        return result;
    }

    /**
     * API-SIM-18 데모 프리셋 준비 — SIM_MANAGE, 원자적(BR-SIM-18). 프리셋 구성대로 가상 공간·기기를 만들고 simulator에 시나리오를 만든 뒤,
     * 딸린 플로우 템플릿(hot-then-cool 등)으로 플로우 초안을 만든다. 이미 준비됐으면 다시 만들지 않는다(reused=true).
     */
    @Transactional
    public Map<String, Object> preparePreset(String presetKey) {
        roleChecker.require(Permission.SIM_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        JsonNode preset = null;
        JsonNode presets = simulator.call(HttpMethod.GET, "/internal/sim/presets", null, null);
        for (JsonNode p : presets == null ? List.<JsonNode>of() : presets.values()) {
            if (presetKey.equals(p.path("key").asString(""))) {
                preset = p;
            }
        }
        if (preset == null) {
            throw new BusinessException(SimErrorCode.SIM_NOT_FOUND);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        if (!"NOT_PREPARED".equals(preset.path("state").asString("NOT_PREPARED"))) {
            JsonNode reused = simulator.call(HttpMethod.POST, "/internal/sim/presets/" + presetKey + "/prepare", null, Map.of());
            response.put("scenarioId", reused == null ? preset.path("scenarioId").asString(null) : reused.path("scenarioId").asString(null));
            response.put("spaceIds", reused == null ? List.of() : reused.path("spaceIds"));
            response.put("deviceIds", reused == null ? List.of() : reused.path("deviceIds"));
            response.put("flowIds", List.of());
            response.put("ruleIds", List.of());
            response.put("reused", true);
            return response;
        }
        JsonNode catalog = catalog();
        String spaceName = preset.path("spaceName").asString(preset.path("name").asString(presetKey));
        Space space = spaces.createSpace(user, uniqueSpaceName(user, spaceName), null,
                preset.path("spacePreset").asString("CLASSROOM").toUpperCase(Locale.ROOT), null);
        List<Item> items = new ArrayList<>();
        for (JsonNode item : preset.path("devices").values()) {
            items.add(new Item(catalogTypeByKey(catalog, item.path("typeKey").asString("")), item.path("count").asInt(1),
                    item.path("namePrefix").asString(item.path("typeKey").asString("")), item.path("relation").asString(null)));
        }
        List<Placed> placed = provision(user, space.id(), items);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("spaceId", Long.toString(space.id()));
        request.put("devices", placed.stream().map(SimDeviceService::placedRequest).toList());
        JsonNode prepared = simulator.call(HttpMethod.POST, "/internal/sim/presets/" + presetKey + "/prepare", null, request);
        List<String> flowIds = new ArrayList<>();
        for (JsonNode f : prepared == null ? List.<JsonNode>of() : prepared.path("flowTemplates").values()) {
            String key = f.path("templateKey").asString("");
            ObjectNode bindings = f.path("bindings").isObject() ? (ObjectNode) f.get("bindings").deepCopy() : json.createObjectNode();
            if (!bindings.hasNonNull("spaceId")) {
                bindings.put("spaceId", Long.toString(space.id()));
            }
            TemplateResult flow = templates.instantiateBindings(key, bindings, preset.path("name").asString(presetKey) + " · " + space.name());
            flowIds.add(flow.flowId());
        }
        audits.record(audits.event(user.organizationId(), AUDIT_PRESET_PREPARED).actor(user).target("SPACE", Long.toString(space.id()))
                .detail("preset", presetKey).detail("devices", placed.size()).detail("flows", flowIds.size()));
        response.put("scenarioId", prepared == null ? null : prepared.path("scenarioId").asString(null));
        response.put("spaceIds", List.of(Long.toString(space.id())));
        response.put("deviceIds", placed.stream().map(p -> Long.toString(p.deviceId())).toList());
        response.put("flowIds", flowIds);
        response.put("ruleIds", List.of());
        response.put("reused", false);
        return response;
    }

    /** API-SIM-09 가상 기기 설정 — SIM_READ */
    public JsonNode get(long deviceId) {
        roleChecker.require(Permission.SIM_READ);
        device(deviceId);
        return simulator.call(HttpMethod.GET, "/internal/sim/devices/" + deviceId, null, null);
    }

    /** API-SIM-09 설정 수정(바꿀 항목만) — SIM_MANAGE */
    public JsonNode patch(long deviceId, JsonNode body) {
        roleChecker.require(Permission.SIM_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        device(deviceId);
        JsonNode result = simulator.call(HttpMethod.PATCH, "/internal/sim/devices/" + deviceId, null, body);
        audits.record(audits.event(user.organizationId(), AUDIT_DEVICE_CHANGED).actor(user).target("DEVICE", Long.toString(deviceId))
                .detail("fields", body == null ? List.of() : new ArrayList<>(body.propertyNames())));
        return result;
    }

    /** API-SIM-07 가상 기기 삭제 — SIM_MANAGE. 실행 중 시나리오가 쓰면 simulator가 409 SIM_RUN_STATE_CONFLICT */
    @Transactional
    public void delete(long deviceId, boolean purgeData) {
        roleChecker.require(Permission.SIM_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        VirtualDevice d = device(deviceId);
        simulator.call(HttpMethod.DELETE, "/internal/sim/devices/" + deviceId, null, null);
        Instant now = clock.instant();
        sim.deleteVirtualDevice(user.organizationId(), deviceId, user.userId(), now);
        devices.findById(user.organizationId(), deviceId).ifPresent(x -> deviceEvents.changed(x, DeviceChanged.Change.DELETED, List.of()));
        if (purgeData) {
            sim.insertPurgeJob(user.organizationId(), "USER", List.of(), null, null, d.spaceId() == null ? List.of() : List.of(d.spaceId()),
                    user.userId(), now);
        }
        audits.record(audits.event(user.organizationId(), AUDIT_DEVICE_DELETED).actor(user).target("DEVICE", Long.toString(deviceId))
                .detail("purgeData", purgeData));
    }

    /** 보이는 가상 기기. 없거나 실제 기기·범위 밖이면 404 SIM_NOT_FOUND */
    public VirtualDevice device(long deviceId) {
        long orgId = roleChecker.currentUser().organizationId();
        VirtualDevice d = sim.findVirtualDevice(orgId, deviceId).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        if (d.spaceId() != null) {
            roleChecker.requireSpace(d.spaceId(), SimErrorCode.SIM_NOT_FOUND);
        } else if (!roleChecker.spaceScope().unrestricted()) {
            throw new BusinessException(SimErrorCode.SIM_NOT_FOUND);
        }
        return d;
    }

    /** 가상 기기는 가상 공간에만 둔다(실제 공간이면 400 SIM_TARGET_NOT_VIRTUAL) */
    private Space targetSpace(long spaceId) {
        long orgId = roleChecker.currentUser().organizationId();
        try {
            return spaces.virtualSpace(spaceId);
        } catch (BusinessException ex) {
            if (sim.listVirtualSpaces(orgId).stream().noneMatch(s -> s.id() == spaceId) && spaceExists(spaceId)) {
                throw new BusinessException(SimErrorCode.SIM_TARGET_NOT_VIRTUAL);
            }
            throw ex;
        }
    }

    private boolean spaceExists(long spaceId) {
        try {
            roleChecker.requireSpace(spaceId, SimErrorCode.SIM_NOT_FOUND);
            return true;
        } catch (BusinessException ex) {
            return false;
        }
    }

    /**
     * core 기준 정보 만들기: 한도 확인 → 기본값(SIM 소스·가상 드라이버) → 유형별 모델 → 기기(ACTIVE, virtual). 기기 변경 이벤트(EVT-DEV-01)와
     * 설정 변경(EVT-DEV-04)은 아웃박스로 같은 트랜잭션에 쓴다.
     */
    List<Placed> provision(CurrentUser user, long spaceId, List<Item> items) {
        long orgId = user.organizationId();
        int total = items.stream().mapToInt(Item::count).sum();
        if (sim.countVirtualDevices(orgId) + total > MAX_DEVICES) {
            throw new BusinessException(SimErrorCode.SIM_DEVICE_QUOTA_EXCEEDED);
        }
        SimBootstrap.Defaults defaults = bootstrap.ensure(orgId);
        Instant now = clock.instant();
        List<Placed> placed = new ArrayList<>();
        for (Item item : items) {
            JsonNode type = item.type();
            String key = type.path("key").asString("");
            boolean actuator = "ACTUATOR".equals(type.path("category").asString(""));
            long modelId = model(orgId, type, actuator, defaults.virtualDriverId(), now);
            String relation = item.relation() != null ? item.relation() : actuator ? "CONTROLS" : "MEASURES";
            for (int i = 1; i <= item.count(); i++) {
                String name = item.count() == 1 && items.size() > 1 ? item.namePrefix() : item.namePrefix() + "-" + i;
                if (name.length() > 100) {
                    name = name.substring(0, 100);
                }
                long id = sim.insertVirtualDevice(orgId, defaults.simSourceId(), null, name, actuator ? "ACTUATOR" : "SENSOR", modelId,
                        spaceId, user.userId(), now);
                devices.findById(orgId, id).ifPresent(d -> deviceEvents.changed(d, DeviceChanged.Change.CREATED, List.of()));
                String externalId = sim.findVirtualDevice(orgId, id).map(VirtualDevice::externalId).orElse(null);
                placed.add(new Placed(id, name, key, externalId, defaults.simSourceId(), relation));
            }
        }
        return placed;
    }

    /** 유형의 모델: 실측 모델 코드(linkedModelCode)가 조직에 있으면 그것, 없으면 가상 모델 SIM-<유형>(기능 포함). 장비 모델엔 가상 드라이버 연결 */
    private long model(long orgId, JsonNode type, boolean actuator, long virtualDriverId, Instant now) {
        String linked = type.path("linkedModelCode").asString(null);
        if (linked != null && !linked.isBlank()) {
            var existing = sim.findModelByCode(orgId, linked.strip());
            if (existing.isPresent()) {
                return existing.get().id();
            }
        }
        String key = type.path("key").asString("virtual");
        String code = ("SIM-" + key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", "-"));
        if (code.length() > 50) {
            code = code.substring(0, 50);
        }
        ArrayNode caps = json.createArrayNode();
        for (JsonNode cap : type.path("capabilities").values()) {
            ObjectNode c = caps.addObject();
            c.put("capability", cap.asString(""));
            c.putNull("constraints");
        }
        long modelId = sim.insertModel(orgId, code, type.path("name").asString(key), actuator ? "ACTUATOR" : "SENSOR",
                json.writeValueAsString(caps), SimBootstrap.SYSTEM_USER, now);
        if (actuator) {
            sim.bindDriverIfAbsent(orgId, modelId, virtualDriverId, now);
        }
        return modelId;
    }

    private String uniqueSpaceName(CurrentUser user, String base) {
        List<String> names = sim.listVirtualSpaces(user.organizationId()).stream().map(SimRepository.VirtualSpace::name).toList();
        String name = base;
        for (int i = 2; names.contains(name) && i < 1000; i++) {
            name = base + " " + i;
        }
        return name;
    }

    private JsonNode catalog() {
        JsonNode catalog = simulator.call(HttpMethod.GET, "/internal/sim/catalog", null, null);
        if (catalog == null) {
            throw new BusinessException(SimErrorCode.SIM_NOT_FOUND);
        }
        return catalog;
    }

    static JsonNode catalogType(JsonNode catalog, String typeId) {
        for (JsonNode t : catalog.path("types").values()) {
            if (typeId.equals(t.path("id").asString("")) || typeId.equals(t.path("key").asString(""))) {
                return t;
            }
        }
        throw new BusinessException(SimErrorCode.SIM_NOT_FOUND);
    }

    static JsonNode catalogTypeByKey(JsonNode catalog, String key) {
        return catalogType(catalog, key);
    }

    private static Map<String, Object> placedRequest(Placed p) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("deviceId", p.deviceId());
        d.put("typeKey", p.typeKey());
        d.put("name", p.name());
        d.put("externalId", p.externalId());
        d.put("sourceId", p.sourceId());
        return d;
    }
}
