package net.java21.data2flow.core.sim.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.sim.domain.SimErrorCode;
import net.java21.data2flow.core.sim.repository.SimRepository;
import net.java21.data2flow.core.sim.repository.SimRepository.VirtualSpace;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.domain.SpaceErrorCode;
import net.java21.data2flow.core.space.domain.SpaceType;
import net.java21.data2flow.core.space.repository.SpaceRepository;
import net.java21.data2flow.core.space.repository.SpaceRepository.NewSpace;
import net.java21.data2flow.core.space.repository.SpaceRepository.SpaceAttributes;
import net.java21.data2flow.core.space.service.SpaceSupport;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 가상 공간(SIM-01.01·01.02·07.03, API-SIM-10·24). 기준 정보는 DEV 공간 트리에 {@code is_virtual=true}로 core가 만들고(가상 뿌리 SITE
 * "가상 환경" 아래 또는 지정한 부모 아래 — 실제 공간 아래 가상 공간은 되고 반대는 안 된다), 물리 설정은 simulator
 * {@code PUT /internal/sim/spaces/{id}}에 둔다. 두 단계는 한 트랜잭션 안에서 하고 simulator가 실패하면 core 행도 되돌린다(사가 보상).
 */
@Service
public class SimSpaceService {

    static final String AUDIT_CREATED = "SIM_SPACE_CREATED";
    static final String AUDIT_CHANGED = "SIM_SPACE_CHANGED";
    static final String AUDIT_DELETED = "SIM_SPACE_DELETED";
    static final String AUDIT_SANDBOX = "SIM_SANDBOX_CHANGED";
    static final Set<String> PRESETS = Set.of("CLASSROOM", "OFFICE", "MEETING", "CUSTOM");
    static final String VIRTUAL_ROOT = "가상 환경";

    private final SpaceRepository spaces;
    private final SpaceSupport spaceSupport;
    private final SimRepository sim;
    private final SimulatorClient simulator;
    private final CoreEventPublisher publisher;
    private final RoleChecker roleChecker;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public SimSpaceService(SpaceRepository spaces, SpaceSupport spaceSupport, SimRepository sim, SimulatorClient simulator,
                           CoreEventPublisher publisher, RoleChecker roleChecker, Audits audits, JsonMapper json, Clock clock) {
        this.spaces = spaces;
        this.spaceSupport = spaceSupport;
        this.sim = sim;
        this.simulator = simulator;
        this.publisher = publisher;
        this.roleChecker = roleChecker;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** 보이는 가상 공간. 없거나 실제 공간·범위 밖이면 404 SIM_NOT_FOUND */
    public Space virtualSpace(long spaceId) {
        long orgId = roleChecker.currentUser().organizationId();
        Space space = spaces.findById(orgId, spaceId).filter(Space::virtual).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        roleChecker.requireSpace(space.id(), SimErrorCode.SIM_NOT_FOUND);
        return space;
    }

    /** API-SIM-10 목록(문서 보충): core 가상 공간 + simulator 물리 설정·현재값 — SIM_READ */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        roleChecker.require(Permission.SIM_READ);
        long orgId = roleChecker.currentUser().organizationId();
        Map<String, JsonNode> physics = new HashMap<>();
        JsonNode remote = simulator.call(HttpMethod.GET, "/internal/sim/spaces", null, null);
        if (remote != null && remote.isArray()) {
            remote.values().forEach(n -> physics.put(n.path("spaceId").asString(""), n));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (VirtualSpace s : sim.listVirtualSpaces(orgId)) {
            if (!roleChecker.spaceScope().includes(s.id())) {
                continue;
            }
            result.add(view(s.id(), s.name(), s.parentId(), s.type(), s.sandbox(), physics.get(Long.toString(s.id()))));
        }
        return result;
    }

    /** API-SIM-10 상세 — SIM_READ */
    @Transactional(readOnly = true)
    public Map<String, Object> get(long spaceId) {
        roleChecker.require(Permission.SIM_READ);
        Space s = virtualSpace(spaceId);
        JsonNode remote = simulator.call(HttpMethod.GET, "/internal/sim/spaces/" + spaceId, null, null);
        return view(s.id(), s.name(), s.parentId(), s.type().name(), s.sandbox(), remote);
    }

    /** API-SIM-10 만들기 {name, parentId?, preset, physics?} — SIM_MANAGE, 201 */
    @Transactional
    public Map<String, Object> create(JsonNode body) {
        roleChecker.require(Permission.SIM_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        if (body == null || !body.isObject()) {
            throw SpaceSupport.invalid("body", "NotNull");
        }
        String name = body.path("name").asString("").strip();
        if (name.isEmpty() || name.length() > 100) {
            throw SpaceSupport.invalid("name", "Size");
        }
        String preset = preset(body);
        Long parentId = body.hasNonNull("parentId") ? SpaceSupport.parseId(body.get("parentId").asString(""), "parentId") : null;
        Space created = createSpace(user, name, parentId, preset, body.get("physics"));
        JsonNode remote = simulator.call(HttpMethod.GET, "/internal/sim/spaces/" + created.id(), null, null);
        return view(created.id(), created.name(), created.parentId(), created.type().name(), created.sandbox(), remote);
    }

    /**
     * 가상 공간 하나를 만든다(키트·프리셋도 씀): core 공간(virtual) → simulator 물리 설정. parentId가 없으면 가상 뿌리 아래.
     * simulator 실패는 예외로 올려 트랜잭션을 되돌린다.
     */
    @Transactional
    public Space createSpace(CurrentUser user, String name, Long parentId, String preset, JsonNode physics) {
        long orgId = user.organizationId();
        Space parent = parentId == null ? virtualRoot(user) : spaces.findById(orgId, parentId)
                .orElseThrow(() -> new BusinessException(SpaceErrorCode.SPACE_NOT_FOUND));
        roleChecker.requireSpace(parent.id(), SpaceErrorCode.SPACE_NOT_FOUND);
        SpaceType type = SpaceType.ROOM.allowedUnder(parent.type()) ? SpaceType.ROOM : SpaceType.ZONE;
        if (parent.depth() + 1 > 6) {
            throw new BusinessException(SpaceErrorCode.SPACE_DEPTH_EXCEEDED);
        }
        BigDecimal area = physics != null && physics.path("areaM2").isNumber() ? BigDecimal.valueOf(physics.get("areaM2").asDouble()) : null;
        String usage = "CUSTOM".equals(preset) ? null : preset;
        Instant now = clock.instant();
        long id;
        try {
            id = spaces.insert(new NewSpace(orgId, parent.id(), parent.path(), type, parent.depth() + 1,
                    new SpaceAttributes(name, null, 0, usage, area, null, null, null, null, null, null, null), user.userId(), now));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(SpaceErrorCode.SPACE_NAME_DUPLICATE);
        }
        spaces.markVirtual(orgId, id);
        ObjectNode request = json.createObjectNode();
        request.put("name", name);
        request.put("preset", preset);
        if (physics != null && physics.isObject()) {
            request.set("physics", physics);
        }
        simulator.call(HttpMethod.PUT, "/internal/sim/spaces/" + id, null, request);
        Space created = spaces.findById(orgId, id).orElseThrow();
        spaceSupport.changed(orgId, id, created.path(), "CREATED", created.version());
        audits.record(audits.event(orgId, AUDIT_CREATED).actor(user).target("SPACE", Long.toString(id)).detail("name", name)
                .detail("preset", preset));
        return created;
    }

    /** 가상 뿌리 SITE "가상 환경"(없으면 만든다) */
    Space virtualRoot(CurrentUser user) {
        long orgId = user.organizationId();
        Long rootId = sim.findVirtualRoot(orgId).orElse(null);
        if (rootId == null) {
            Instant now = clock.instant();
            String name = VIRTUAL_ROOT;
            if (spaces.existsSiblingName(orgId, null, name, null)) {
                name = VIRTUAL_ROOT + " " + UUID.randomUUID().toString().substring(0, 4);
            }
            rootId = spaces.insert(new NewSpace(orgId, null, "/", SpaceType.SITE, 1,
                    new SpaceAttributes(name, null, 999, null, null, null, null, null, null, null, null, null), user.userId(), now));
            spaces.markVirtual(orgId, rootId);
            Space root = spaces.findById(orgId, rootId).orElseThrow();
            spaceSupport.changed(orgId, rootId, root.path(), "CREATED", root.version());
            return root;
        }
        return spaces.findById(orgId, rootId).orElseThrow();
    }

    /** API-SIM-10 수정 {name?, preset, physics, baseVersion} — SIM_MANAGE */
    @Transactional
    public Map<String, Object> update(long spaceId, JsonNode body) {
        roleChecker.require(Permission.SIM_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        Space s = virtualSpace(spaceId);
        if (body == null || !body.isObject()) {
            throw SpaceSupport.invalid("body", "NotNull");
        }
        String name = s.name();
        if (body.hasNonNull("name")) {
            name = body.get("name").asString("").strip();
            if (name.isEmpty() || name.length() > 100) {
                throw SpaceSupport.invalid("name", "Size");
            }
            if (!name.equals(s.name())) {
                try {
                    sim.renameSpace(user.organizationId(), spaceId, name, user.userId(), clock.instant());
                } catch (DuplicateKeyException ex) {
                    throw new BusinessException(SpaceErrorCode.SPACE_NAME_DUPLICATE);
                }
            }
        }
        ObjectNode request = (ObjectNode) body.deepCopy();
        request.put("name", name);
        if (!request.hasNonNull("preset")) {
            request.put("preset", "CUSTOM");
        }
        JsonNode remote = simulator.call(HttpMethod.PUT, "/internal/sim/spaces/" + spaceId, null, request);
        Space after = spaces.findById(user.organizationId(), spaceId).orElseThrow();
        spaceSupport.changed(user.organizationId(), spaceId, after.path(), "UPDATED", after.version());
        audits.record(audits.event(user.organizationId(), AUDIT_CHANGED).actor(user).target("SPACE", Long.toString(spaceId)));
        return view(after.id(), after.name(), after.parentId(), after.type().name(), after.sandbox(), remote);
    }

    /** API-SIM-10 삭제 — SIM_MANAGE. 실행 중이면 simulator가 409 SIM_SPACE_BUSY. 하위 공간이 있으면 409 SPACE_NOT_EMPTY */
    @Transactional
    public void delete(long spaceId) {
        roleChecker.require(Permission.SIM_MANAGE);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        Space s = virtualSpace(spaceId);
        if (spaces.countChildren(orgId, spaceId) > 0) {
            throw new BusinessException(SpaceErrorCode.SPACE_NOT_EMPTY);
        }
        simulator.call(HttpMethod.DELETE, "/internal/sim/spaces/" + spaceId, null, null);
        Instant now = clock.instant();
        sim.deleteDevicesInSpace(orgId, spaceId, user.userId(), now);
        spaces.archive(orgId, spaceId, user.userId(), now);
        spaceSupport.changed(orgId, spaceId, s.path(), "DELETED", s.version() + 1L);
        audits.record(audits.event(orgId, AUDIT_DELETED).actor(user).target("SPACE", Long.toString(spaceId)).detail("name", s.name()));
    }

    /**
     * API-SIM-24 샌드박스 지정·해제 {sandbox} — SIM_ADMIN. 실제(virtual=false) 기기가 있는 공간 트리는 403 SIM_SANDBOX_VIOLATION
     * (TC-SIM-081). {@code data2flow.config}에 {@code ConfigChangedMessage(entityType=SIM_SANDBOX)}를 보내 action이 1초 안에 반영한다.
     */
    @Transactional
    public Map<String, Object> sandbox(long spaceId, JsonNode body) {
        roleChecker.require(Permission.SIM_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long orgId = user.organizationId();
        if (body == null || !body.hasNonNull("sandbox") || !body.get("sandbox").isBoolean()) {
            throw SpaceSupport.invalid("sandbox", "NotNull");
        }
        boolean value = body.get("sandbox").asBoolean();
        Space s = spaces.findById(orgId, spaceId).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        roleChecker.requireSpace(s.id(), SimErrorCode.SIM_NOT_FOUND);
        if (value && spaces.existsRealDeviceUnder(orgId, s.path())) {
            throw new BusinessException(SimErrorCode.SIM_SANDBOX_VIOLATION);
        }
        Instant now = clock.instant();
        spaces.updateSandbox(orgId, spaceId, value, user.userId(), now);
        if (s.virtual()) {
            simulator.call(HttpMethod.PUT, "/internal/sim/spaces/" + spaceId + "/sandbox", null, Map.of("sandbox", value));
        }
        publisher.config(new ConfigChangedMessage(ConfigChangedMessage.VERSION, UUID.randomUUID(),
                ConfigChangedMessage.EntityType.SIM_SANDBOX, Long.toString(spaceId), s.version() + 1L, ConfigChangedMessage.Op.UPSERT,
                Long.toString(orgId), now));
        audits.record(audits.event(orgId, AUDIT_SANDBOX).actor(user).target("SPACE", Long.toString(spaceId)).detail("sandbox", value));
        return Map.of("spaceId", Long.toString(spaceId), "sandbox", value);
    }

    static String preset(JsonNode body) {
        String preset = body.hasNonNull("preset") ? body.get("preset").asString("").strip().toUpperCase(Locale.ROOT) : "CUSTOM";
        if (!PRESETS.contains(preset)) {
            throw SpaceSupport.invalid("preset", "Pattern");
        }
        return preset;
    }

    /** 응답 모양(API-SIM-10 부록 A + 목록 현재값) */
    static Map<String, Object> view(long spaceId, String name, Long parentId, String type, boolean sandbox, JsonNode remote) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("spaceId", Long.toString(spaceId));
        view.put("name", name);
        view.put("parentId", parentId == null ? null : Long.toString(parentId));
        view.put("type", type);
        view.put("virtual", true);
        view.put("sandbox", sandbox);
        if (remote != null && remote.isObject()) {
            for (String field : List.of("preset", "physics", "deviceCount", "volumeM3", "current", "version", "updatedAt")) {
                if (remote.has(field)) {
                    view.put(field, remote.get(field));
                }
            }
        }
        return view;
    }
}
