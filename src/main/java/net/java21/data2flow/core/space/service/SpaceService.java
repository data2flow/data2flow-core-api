package net.java21.data2flow.core.space.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.core.space.domain.ScheduleSlot;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.domain.SpaceErrorCode;
import net.java21.data2flow.core.space.domain.SpaceType;
import net.java21.data2flow.core.space.dto.SpaceDtos.Blockers;
import net.java21.data2flow.core.space.dto.SpaceDtos.Counts;
import net.java21.data2flow.core.space.dto.SpaceDtos.CreateSpaceRequest;
import net.java21.data2flow.core.space.dto.SpaceDtos.EffectiveTarget;
import net.java21.data2flow.core.space.dto.SpaceDtos.MoveSpaceRequest;
import net.java21.data2flow.core.space.dto.SpaceDtos.SpaceDetailResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.SpaceNode;
import net.java21.data2flow.core.space.dto.SpaceDtos.SpaceRef;
import net.java21.data2flow.core.space.dto.SpaceDtos.SpaceResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.TargetsResponse;
import net.java21.data2flow.core.space.repository.FloorplanRepository;
import net.java21.data2flow.core.space.repository.SpaceRepository;
import net.java21.data2flow.core.space.repository.SpaceRepository.DeviceCount;
import net.java21.data2flow.core.space.repository.SpaceRepository.NewSpace;
import net.java21.data2flow.core.space.repository.SpaceRepository.SpaceAttributes;
import net.java21.data2flow.core.space.repository.SpaceSettingsRepository;
import net.java21.data2flow.core.space.repository.SpaceSettingsRepository.SlotRow;
import net.java21.data2flow.core.space.repository.SpaceSettingsRepository.TargetRow;
import net.java21.data2flow.core.space.service.SpaceAttributeRules.Draft;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 공간 트리(DEV-01.01 CRUD·이동·삭제, DEV-01.02 속성, DEV-10.01 사이트 속성). 조회 DEV_READ, 바꾸기 DEV_ADMIN(INTEGRATOR+).
 * 공간 범위가 있는 사용자(IAM-04.02)는 범위 안 공간만 보고 바꾸며, 범위 밖 공간 ID는 404 SPACE_NOT_FOUND다(IAM-04.05, AT-DEV-01.7).
 * 트리에서는 범위 안 공간의 조상을 이름만 있는 회색 노드({@code accessible=false})로 보여 준다(API-DSH-02와 같은 규칙).
 * 범위가 있는 사용자는 새 사이트(최상위)를 만들 수 없다(만들어도 자기 범위 밖이라 보이지 않으므로 403).
 */
@Service
public class SpaceService {

    static final int MAX_DEPTH = 6;
    private static final Set<String> PATCH_FIELDS = Set.of("name", "code", "timezone", "address", "latitude", "longitude", "usage",
            "areaM2", "capacity", "sortOrder", "baseVersion");

    private final SpaceRepository spaces;
    private final SpaceSettingsRepository settings;
    private final FloorplanRepository floorplans;
    private final SpaceSupport support;
    private final RoleChecker roleChecker;
    private final Clock clock;
    private final SpaceModes modes;

    public SpaceService(SpaceRepository spaces, SpaceSettingsRepository settings, FloorplanRepository floorplans,
                        SpaceSupport support, RoleChecker roleChecker, Clock clock, SpaceModes modes) {
        this.spaces = spaces;
        this.settings = settings;
        this.floorplans = floorplans;
        this.support = support;
        this.roleChecker = roleChecker;
        this.clock = clock;
        this.modes = modes;
    }

    /**
     * API-DEV-01 공간 트리.
     *
     * @param rootId  이 공간을 뿌리로(없으면 전체)
     * @param depth   뿌리부터 몇 단계까지(1이면 뿌리만, 없으면 전체)
     * @param include {@code counts}(하위 포함 기기·오프라인·알람 수), {@code mode}(현재 운영 모드), {@code targets}(유효 목표)
     */
    @Transactional(readOnly = true)
    public List<SpaceNode> tree(String rootId, Integer depth, String include) {
        return tree(rootId, depth, include, null);
    }

    /**
     * 공간 트리 + 가상 필터(SIM-01.01, API-SIM-10 "공간 목록 virtual 필터·배지"): {@code virtual=false}면 가상 공간을 빼고,
     * {@code true}면 가상 공간과 그 조상만 보인다. 노드마다 {@code virtual}·{@code sandbox}를 준다.
     */
    @Transactional(readOnly = true)
    public List<SpaceNode> tree(String rootId, Integer depth, String include, Boolean virtual) {
        roleChecker.require(Permission.DEV_READ);
        CurrentUser user = support.user();
        long org = user.organizationId();
        Long root = SpaceSupport.parseId(rootId, "rootId");
        if (depth != null && depth < 1) {
            throw SpaceSupport.invalid("depth", "Min");
        }
        Set<String> includes = include == null ? Set.of() : Arrays.stream(include.split(","))
                .map(s -> s.strip().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());

        List<Space> all = filterVirtual(spaces.listActive(org), virtual);
        SpaceScope scope = roleChecker.spaceScope();
        Map<Long, Space> byId = new LinkedHashMap<>();
        all.forEach(s -> byId.put(s.id(), s));
        Set<Long> accessible = new HashSet<>();
        Set<Long> shown = new HashSet<>();
        for (Space s : all) {
            if (scope.unrestricted() || scope.allowedSpaceIds().contains(s.id())) {
                accessible.add(s.id());
                shown.addAll(s.pathIds());
            }
        }
        shown.retainAll(byId.keySet());
        if (root != null && !accessible.contains(root)) {
            throw new BusinessException(SpaceErrorCode.SPACE_NOT_FOUND);
        }

        Map<Long, List<Space>> children = new HashMap<>();
        for (Space s : all) {
            if (shown.contains(s.id()) && s.parentId() != null) {
                children.computeIfAbsent(s.parentId(), k -> new ArrayList<>()).add(s);
            }
        }
        Comparator<Space> order = Comparator.comparingInt(Space::sortOrder).thenComparing(Space::name).thenComparingLong(Space::id);
        children.values().forEach(list -> list.sort(order));

        TreeContext ctx = new TreeContext(byId, children, accessible, depth, includes,
                includes.contains("counts") ? spaces.countDevicesBySpace(org) : Map.of(),
                includes.contains("targets") ? settings.findTargets(org, accessible) : List.of(),
                includes.contains("mode") ? settings.findSlots(org, byId.keySet()) : List.of(), clock.instant());
        List<Space> roots = root != null ? List.of(byId.get(root))
                : all.stream().filter(s -> shown.contains(s.id()) && s.parentId() == null).sorted(order).toList();
        return roots.stream().map(s -> node(s, 1, ctx)).toList();
    }

    static List<Space> filterVirtual(List<Space> all, Boolean virtual) {
        if (virtual == null) {
            return all;
        }
        if (!virtual) {
            return all.stream().filter(s -> !s.virtual()).toList();
        }
        Set<Long> keep = new HashSet<>();
        for (Space s : all) {
            if (s.virtual()) {
                keep.addAll(s.pathIds());
            }
        }
        return all.stream().filter(s -> keep.contains(s.id())).toList();
    }

    private record TreeContext(Map<Long, Space> byId, Map<Long, List<Space>> children, Set<Long> accessible, Integer depth,
                               Set<String> includes, Map<Long, DeviceCount> deviceCounts, List<TargetRow> targets,
                               List<SlotRow> slots, Instant now) {
    }

    private SpaceNode node(Space s, int level, TreeContext ctx) {
        boolean ok = ctx.accessible().contains(s.id());
        List<SpaceNode> kids = ctx.depth() != null && level >= ctx.depth() ? List.of()
                : ctx.children().getOrDefault(s.id(), List.of()).stream().map(c -> node(c, level + 1, ctx)).toList();
        if (!ok) {
            return new SpaceNode(Long.toString(s.id()), SpaceSupport.id(s.parentId()), s.type().name(), s.name(), null, s.depth(),
                    s.sortOrder(), false, null, null, null, kids, s.virtual() ? Boolean.TRUE : null, null);
        }
        Counts counts = ctx.includes().contains("counts") ? counts(s, ctx) : null;
        String mode = null;
        List<EffectiveTarget> targets = null;
        if (ctx.includes().contains("mode") || ctx.includes().contains("targets")) {
            List<Space> chain = s.pathIds().stream().map(ctx.byId()::get).filter(java.util.Objects::nonNull).toList();
            if (ctx.includes().contains("mode")) {
                List<ScheduleSlot> slots = SpaceInheritance.schedule(chain, ctx.slots()).slots();
                mode = SpaceInheritance.mode(chain, slots, ctx.now()).mode();
            }
            if (ctx.includes().contains("targets")) {
                targets = SpaceInheritance.targets(chain, ctx.targets()).effective();
            }
        }
        return new SpaceNode(Long.toString(s.id()), SpaceSupport.id(s.parentId()), s.type().name(), s.name(), s.code(), s.depth(),
                s.sortOrder(), true, counts, mode, targets, kids, s.virtual(), s.sandbox() ? Boolean.TRUE : null);
    }

    /** 하위(자기 포함, 범위 안만) 기기 수 */
    private static Counts counts(Space s, TreeContext ctx) {
        long devices = 0;
        long offline = 0;
        for (Map.Entry<Long, DeviceCount> e : ctx.deviceCounts().entrySet()) {
            Space owner = ctx.byId().get(e.getKey());
            if (owner != null && ctx.accessible().contains(owner.id()) && s.isAncestorOrSelfOf(owner)) {
                devices += e.getValue().devices();
                offline += e.getValue().offline();
            }
        }
        return new Counts(devices, offline, 0);
    }

    /** 공간 상세(GET /core/spaces/{space-id}): 속성 + 조상·유효 시간대·개수·평면도 여부·목표·시간표·모드 */
    @Transactional(readOnly = true)
    public SpaceDetailResponse detail(long spaceId) {
        Space s = support.visible(spaceId, Permission.DEV_READ);
        long org = s.organizationId();
        List<Space> chain = support.chain(s);
        List<SpaceRef> ancestors = chain.stream().filter(a -> a.id() != s.id())
                .map(a -> new SpaceRef(Long.toString(a.id()), a.name(), a.type().name())).toList();
        List<Long> chainIds = chain.stream().map(Space::id).toList();
        TargetsResponse targets = SpaceInheritance.targets(chain, settings.findTargets(org, chainIds));
        SpaceInheritance.EffectiveSchedule schedule = SpaceInheritance.schedule(chain, settings.findSlots(org, chainIds));
        SpaceResponse r = SpaceSupport.toResponse(s);
        return new SpaceDetailResponse(r.id(), r.parentId(), r.type(), r.name(), r.code(), r.path(), r.depth(), r.sortOrder(),
                r.usage(), r.areaM2(), r.capacity(), r.timezone(), r.address(), r.latitude(), r.longitude(), r.kmaNx(), r.kmaNy(),
                r.status(), r.version(), r.updatedAt(), ancestors, SpaceSupport.zone(chain).getId(),
                spaces.countChildren(org, s.id()), spaces.countDevicesUnder(org, s.path()),
                floorplans.findBySpace(org, s.id()).isPresent(), targets, SpaceInheritance.scheduleResponse(chain, schedule),
                SpaceInheritance.modeResponse(modes.modeOf(chain, schedule.slots(), clock.instant())), s.virtual(), s.sandbox());
    }

    /** API-DEV-02 공간 만들기 */
    @Transactional
    public SpaceResponse create(CreateSpaceRequest req) {
        CurrentUser user = support.user();
        long org = user.organizationId();
        Long parentId = SpaceSupport.parseId(req.parentId(), "parentId");
        Space parent = null;
        if (parentId != null) {
            parent = support.visible(parentId, Permission.DEV_ADMIN);
        } else {
            roleChecker.require(Permission.DEV_ADMIN);
            if (!roleChecker.spaceScope().unrestricted()) {
                throw new BusinessException(CommonErrorCode.PERMISSION_DENIED);
            }
        }
        if (parent != null && parent.virtual()) {
            // 가상 공간 아래에는 실제 공간을 둘 수 없다(TC-SIM-001). 가상 공간은 가상 환경 API(API-SIM-10)로 만든다
            throw SpaceSupport.invalid("parentId", "VIRTUAL_PARENT");
        }
        SpaceType type = SpaceType.parse(req.type()).orElseThrow(() -> SpaceSupport.invalid("type", "INVALID"));
        if (!type.allowedUnder(parent == null ? null : parent.type())) {
            throw new BusinessException(SpaceErrorCode.SPACE_TYPE_INVALID);
        }
        int depth = parent == null ? 1 : parent.depth() + 1;
        if (depth > MAX_DEPTH) {
            throw new BusinessException(SpaceErrorCode.SPACE_DEPTH_EXCEEDED);
        }
        SpaceAttributes attributes = SpaceAttributeRules.validate(type, new Draft(req.name(), req.code(), req.sortOrder(),
                req.usage(), req.areaM2(), req.capacity(), req.timezone(), req.address(), req.latitude(), req.longitude()));
        requireUniqueName(org, parentId, attributes.name(), null);
        requireUniqueCode(org, attributes.code(), null);
        Instant now = clock.instant();
        long id;
        try {
            id = spaces.insert(new NewSpace(org, parentId, parent == null ? "/" : parent.path(), type, depth, attributes,
                    user.userId(), now));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(SpaceErrorCode.SPACE_NAME_DUPLICATE);
        }
        Space created = spaces.findById(org, id).orElseThrow();
        support.audit(SpaceSupport.AUDIT_SPACE_CREATED, id, Map.of("type", type.name(), "name", created.name(),
                "parentId", parentId == null ? "" : parentId.toString()));
        support.changed(org, id, created.path(), "CREATED", created.version());
        return SpaceSupport.toResponse(created);
    }

    /** API-DEV-03 속성 수정(PATCH, 온 키만). type·parentId는 바꿀 수 없다(이동은 API-DEV-04) */
    @Transactional
    public SpaceResponse update(long spaceId, JsonNode body) {
        Space s = support.locked(spaceId, Permission.DEV_ADMIN);
        long org = s.organizationId();
        if (body == null || !body.isObject()) {
            throw SpaceSupport.invalid("body", "NotNull");
        }
        long base = VersionCheck.baseVersion(body);
        List<FieldErrorDetail> errors = new ArrayList<>();
        for (String field : body.propertyNames()) {
            if (!PATCH_FIELDS.contains(field)) {
                errors.add(new FieldErrorDetail(field, "IMMUTABLE", null));
            }
        }
        Draft d = new Draft(
                text(body, "name", s.name(), errors), text(body, "code", s.code(), errors),
                integer(body, "sortOrder", s.sortOrder(), errors), text(body, "usage", s.usage(), errors),
                decimal(body, "areaM2", s.areaM2(), errors), integer(body, "capacity", s.capacity(), errors),
                text(body, "timezone", s.timezone(), errors), text(body, "address", s.address(), errors),
                decimal(body, "latitude", s.latitude(), errors), decimal(body, "longitude", s.longitude(), errors));
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        VersionCheck.require(base, s.version());
        SpaceAttributes a = SpaceAttributeRules.validate(s.type(), d);
        if (!a.name().equalsIgnoreCase(s.name())) {
            requireUniqueName(org, s.parentId(), a.name(), s.id());
        }
        if (a.code() != null && !a.code().equals(s.code())) {
            requireUniqueCode(org, a.code(), s.id());
        }
        try {
            VersionCheck.requireUpdated(spaces.updateAttributes(org, s.id(), (int) base, a, support.user().userId(), clock.instant()));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(SpaceErrorCode.SPACE_NAME_DUPLICATE);
        }
        Space updated = spaces.findById(org, s.id()).orElseThrow();
        List<String> fields = new ArrayList<>(body.propertyNames());
        fields.remove("baseVersion");
        support.audit(SpaceSupport.AUDIT_SPACE_UPDATED, s.id(), Map.of("fields", fields));
        support.changed(org, s.id(), updated.path(), "UPDATED", updated.version());
        return SpaceSupport.toResponse(updated);
    }

    /** API-DEV-04 이동: 자기 하위로는 못 옮기고(SPACE_MOVE_CYCLE), 종류 순서·깊이 6을 지킨다. 하위 전체의 path·depth를 함께 바꾼다 */
    @Transactional
    public SpaceResponse move(long spaceId, MoveSpaceRequest req) {
        Space s = support.locked(spaceId, Permission.DEV_ADMIN);
        long org = s.organizationId();
        Long newParentId = SpaceSupport.parseId(req == null ? null : req.newParentId(), "newParentId");
        if (newParentId == null) {
            throw SpaceSupport.invalid("newParentId", "NotNull");
        }
        if (req.baseVersion() != null) {
            VersionCheck.require(req.baseVersion(), s.version());
        }
        Space parent = support.locked(newParentId, Permission.DEV_ADMIN);
        if (s.isAncestorOrSelfOf(parent)) {
            throw new BusinessException(SpaceErrorCode.SPACE_MOVE_CYCLE);
        }
        if (!s.type().allowedUnder(parent.type())) {
            throw new BusinessException(SpaceErrorCode.SPACE_TYPE_INVALID);
        }
        int newDepth = parent.depth() + 1;
        int delta = newDepth - s.depth();
        if (spaces.maxDepthUnder(org, s.path()) + delta > MAX_DEPTH) {
            throw new BusinessException(SpaceErrorCode.SPACE_DEPTH_EXCEEDED);
        }
        boolean sameParent = parent.id() == (s.parentId() == null ? -1 : s.parentId());
        if (!sameParent) {
            requireUniqueName(org, parent.id(), s.name(), s.id());
        }
        String oldPath = s.path();
        String newPath = parent.path() + s.id() + "/";
        Instant now = clock.instant();
        spaces.updateParent(org, s.id(), parent.id(), req.sortOrder() == null ? s.sortOrder() : req.sortOrder(),
                support.user().userId(), now);
        try {
            spaces.updateSubtreePath(org, oldPath, newPath, delta);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(SpaceErrorCode.SPACE_NAME_DUPLICATE);
        }
        Space moved = spaces.findById(org, s.id()).orElseThrow();
        support.audit(SpaceSupport.AUDIT_SPACE_MOVED, s.id(), Map.of("fromParentId", SpaceSupport.id(s.parentId()) == null ? ""
                : SpaceSupport.id(s.parentId()), "toParentId", Long.toString(parent.id()), "fromPath", Space.eventPath(oldPath),
                "toPath", Space.eventPath(newPath)));
        support.changed(org, s.id(), moved.path(), "MOVED", moved.version());
        return SpaceSupport.toResponse(moved);
    }

    /** API-DEV-05 삭제(보관). 하위 공간·기기·평면도 마커·작업 지시가 있으면 409 SPACE_NOT_EMPTY + blockers(BR-DEV-03) */
    @Transactional
    public void delete(long spaceId) {
        Space s = support.locked(spaceId, Permission.DEV_ADMIN);
        long org = s.organizationId();
        // 작업 지시(DEV-08)는 M5에서 생긴다. 그때 열린 작업 지시 수를 여기에 더한다
        Blockers blockers = new Blockers(spaces.countChildren(org, s.id()), spaces.countDevices(org, s.id()),
                spaces.countMarkers(org, s.id()), 0);
        if (blockers.children() + blockers.devices() + blockers.markers() + blockers.workOrders() > 0) {
            throw new SpaceNotEmptyException(blockers);
        }
        spaces.archive(org, s.id(), support.user().userId(), clock.instant());
        support.audit(SpaceSupport.AUDIT_SPACE_DELETED, s.id(), Map.of("name", s.name(), "path", Space.eventPath(s.path())));
        support.changed(org, s.id(), s.path(), "DELETED", s.version() + 1L);
    }

    private void requireUniqueName(long org, Long parentId, String name, Long excludeId) {
        if (spaces.existsSiblingName(org, parentId, name, excludeId)) {
            throw new BusinessException(SpaceErrorCode.SPACE_NAME_DUPLICATE);
        }
    }

    private void requireUniqueCode(long org, String code, Long excludeId) {
        if (code != null && spaces.existsCode(org, code, excludeId)) {
            throw new BusinessException(SpaceErrorCode.SPACE_CODE_DUPLICATE);
        }
    }

    private static String text(JsonNode body, String field, String current, List<FieldErrorDetail> errors) {
        if (!body.has(field)) {
            return current;
        }
        JsonNode node = body.get(field);
        if (node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            errors.add(new FieldErrorDetail(field, "Type", null));
            return current;
        }
        return node.asString();
    }

    private static Integer integer(JsonNode body, String field, Integer current, List<FieldErrorDetail> errors) {
        if (!body.has(field)) {
            return current;
        }
        JsonNode node = body.get(field);
        if (node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            errors.add(new FieldErrorDetail(field, "Type", null));
            return current;
        }
        return node.asInt();
    }

    private static BigDecimal decimal(JsonNode body, String field, BigDecimal current, List<FieldErrorDetail> errors) {
        if (!body.has(field)) {
            return current;
        }
        JsonNode node = body.get(field);
        if (node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            errors.add(new FieldErrorDetail(field, "Type", null));
            return current;
        }
        return node.decimalValue();
    }
}
