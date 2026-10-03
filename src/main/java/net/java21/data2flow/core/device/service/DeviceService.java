package net.java21.data2flow.core.device.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.event.DeviceChanged.Change;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.contracts.web.SortParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.device.domain.Device;
import net.java21.data2flow.core.device.domain.DeviceErrorCode;
import net.java21.data2flow.core.device.domain.DeviceFilter;
import net.java21.data2flow.core.device.domain.DeviceRules;
import net.java21.data2flow.core.device.domain.References.ModelRef;
import net.java21.data2flow.core.device.domain.References.SourceRef;
import net.java21.data2flow.core.device.dto.DeviceDtos.CreateDeviceRequest;
import net.java21.data2flow.core.device.dto.DeviceDtos.DeviceDetailResponse;
import net.java21.data2flow.core.device.dto.DeviceDtos.DeviceStatusResponse;
import net.java21.data2flow.core.device.dto.DeviceDtos.DeviceSummaryResponse;
import net.java21.data2flow.core.device.dto.DeviceDtos.TagRequest;
import net.java21.data2flow.core.device.dto.DeviceDtos.TagResponse;
import net.java21.data2flow.core.device.dto.DeviceDtos.TagResult;
import net.java21.data2flow.core.device.repository.DeviceQueryRepository;
import net.java21.data2flow.core.device.repository.DeviceRepository;
import net.java21.data2flow.core.device.repository.DeviceRepository.NewDevice;
import net.java21.data2flow.core.device.repository.DiscoveryRepository;
import net.java21.data2flow.core.devicegroup.service.DynamicGroupMembership;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 기기 조회·등록·수정·상태·삭제·태그(DEV-02.01·02.02·02.04·02.08·02.10). 권한: 조회 DEV_READ, 공간·태그·상태 DEV_PLACE,
 * 등록·이름·모델·주기·삭제 DEV_ADMIN(design/api/DEV-api.md 머리말). 바뀌면 같은 트랜잭션에서 이벤트·설정 버전·동적 그룹을 갱신한다.
 */
@Service
public class DeviceService {

    static final List<String> SORT_FIELDS = List.of("name", "lastSeenAt", "createdAt", "updatedAt");
    private static final Set<String> ADMIN_FIELDS = Set.of("name", "modelId", "expectedIntervalSec", "offlineMultiplier", "kind");

    private final RoleChecker roleChecker;
    private final DeviceAccess access;
    private final DeviceRepository devices;
    private final DeviceQueryRepository queries;
    private final DiscoveryRepository discovery;
    private final DeviceViews views;
    private final DeviceEvents events;
    private final DynamicGroupMembership groups;
    private final Audits audits;
    private final Clock clock;

    public DeviceService(RoleChecker roleChecker, DeviceAccess access, DeviceRepository devices, DeviceQueryRepository queries,
                         DiscoveryRepository discovery, DeviceViews views, DeviceEvents events, DynamicGroupMembership groups,
                         Audits audits, Clock clock) {
        this.roleChecker = roleChecker;
        this.access = access;
        this.devices = devices;
        this.queries = queries;
        this.discovery = discovery;
        this.views = views;
        this.events = events;
        this.groups = groups;
        this.audits = audits;
        this.clock = clock;
    }

    /** 목록 쿼리 파라미터(API-DEV-11) */
    public record ListQuery(String q, List<String> status, List<String> connectivity, List<String> kind, String modelId, String spaceId,
                            Boolean includeDescendants, String sourceId, List<String> tag, String groupId, Boolean virtual,
                            String onboarding, List<String> sort) {
    }

    /** API-DEV-11 */
    @Transactional(readOnly = true)
    public ListApiResponse<DeviceSummaryResponse> list(ListQuery query, Integer page, Integer size) {
        roleChecker.require(Permission.DEV_READ);
        DeviceFilter filter = filter(query);
        PageParams params = PageParams.of(page, size);
        String orderBy = SortParams.parse(query.sort(), SORT_FIELDS, new SortParams.Order("name", true))
                .toOrderBy(DeviceQueryRepository.SORT_COLUMNS);
        long org = filter.organizationId();
        List<DeviceSummaryResponse> items = views.summaries(org, queries.list(filter, orderBy, params.size(), params.offset()));
        return ListApiResponse.of(params, items, queries.count(filter));
    }

    /** 목록 조건을 검증하고 사용자 공간 범위를 붙인다(IAM-04.06) */
    public DeviceFilter filter(ListQuery query) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        List<String> statuses = upper(query.status(), DeviceRules.STATUSES, "status", errors);
        List<String> connectivity = upper(query.connectivity(), DeviceRules.CONNECTIVITIES, "connectivity", errors);
        List<String> kinds = upper(query.kind(), DeviceRules.KINDS, "kind", errors);
        String onboarding = query.onboarding() == null || query.onboarding().isBlank() ? null : query.onboarding().toUpperCase(Locale.ROOT);
        if (onboarding != null && !Set.of("COMPLETE", "INCOMPLETE").contains(onboarding)) {
            errors.add(new FieldErrorDetail("onboarding", "INVALID", query.onboarding()));
        }
        Long modelId = optionalId(query.modelId(), "modelId", errors);
        Long spaceId = optionalId(query.spaceId(), "spaceId", errors);
        Long sourceId = optionalId(query.sourceId(), "sourceId", errors);
        Long groupId = optionalId(query.groupId(), "groupId", errors);
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        List<String> tags = query.tag() == null ? List.of() : query.tag().stream().filter(t -> t != null && !t.isBlank()).map(String::strip).toList();
        // virtual=true면 가상 기기까지 모두, 없거나 false면 실제 기기만(API-DEV-11 virtual 기본 false)
        Boolean virtual = Boolean.TRUE.equals(query.virtual()) ? null : Boolean.FALSE;
        return new DeviceFilter(access.organizationId(), PageParams.keyword(query.q()), statuses, connectivity, kinds, modelId, spaceId,
                query.includeDescendants() == null || query.includeDescendants(), sourceId, tags, groupId, virtual, onboarding,
                access.allowedSpaces(), null);
    }

    /** API-DEV-23 */
    @Transactional(readOnly = true)
    public DeviceDetailResponse get(long deviceId) {
        roleChecker.require(Permission.DEV_READ);
        return views.detail(access.device(deviceId, Permission.DEV_READ));
    }

    /**
     * API-DEV-12 수동 등록(DEV-02.04): 모델·공간 필수라 바로 ACTIVE(BR-DEV-05). 같은 소스의 같은 외부 ID(정규화 비교)는 409
     * DEVICE_DUPLICATE(AT-DEV-04.1). 삭제(거부)했던 같은 키는 그 행을 다시 쓰고 무시 목록에서 뺀다.
     */
    @Transactional
    public DeviceDetailResponse create(CreateDeviceRequest req) {
        roleChecker.require(Permission.DEV_ADMIN);
        Device device = register(new Registration(DeviceAccess.id(req.sourceId(), "sourceId"), req.externalId(), req.name(), req.kind(),
                DeviceAccess.id(req.modelId(), "modelId"), DeviceAccess.id(req.spaceId(), "spaceId"), req.expectedIntervalSec(),
                req.offlineMultiplier(), req.tags(), Boolean.TRUE.equals(req.virtual())), "tags");
        audits.record(audits.event(device.organizationId(), DeviceAudits.DEVICE_CREATED).actor(roleChecker.currentUser())
                .target("DEVICE", Long.toString(device.id())).detail("externalId", device.externalId()).detail("sourceId", device.sourceId()));
        return views.detail(device);
    }

    /** 수동·CSV 등록 한 건(검증 포함). 실패하면 BusinessException */
    public record Registration(long sourceId, String externalId, String name, String kind, long modelId, long spaceId,
                               Integer expectedIntervalSec, BigDecimal offlineMultiplier, List<String> tags, boolean virtual) {
    }

    /** 등록 검증만(CSV dryRun). 문제가 없으면 정규화한 외부 ID */
    public String validate(Registration r, String tagField) {
        SourceRef source = access.source(r.sourceId());
        access.assignableModel(r.modelId(), "modelId");
        roleChecker.require(Permission.DEV_ADMIN, access.space(r.spaceId()).id(), DeviceErrorCode.SPACE_NOT_FOUND);
        String ext = DeviceRules.normalizeExternalId(r.externalId());
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (ext == null || ext.isEmpty() || ext.length() > DeviceRules.MAX_EXTERNAL_ID) {
            errors.add(new FieldErrorDetail("externalId", "INVALID", null));
        }
        if (r.name() == null || r.name().isBlank() || r.name().strip().length() > DeviceRules.MAX_NAME) {
            errors.add(new FieldErrorDetail("name", "INVALID", null));
        }
        if (r.kind() == null || !DeviceRules.KINDS.contains(r.kind())) {
            errors.add(new FieldErrorDetail("kind", "INVALID", r.kind()));
        }
        if (r.expectedIntervalSec() != null && (r.expectedIntervalSec() < 10 || r.expectedIntervalSec() > 86400)) {
            errors.add(new FieldErrorDetail("expectedIntervalSec", "Range", "10~86400"));
        }
        if (r.offlineMultiplier() != null && (r.offlineMultiplier().compareTo(new BigDecimal("1.5")) < 0
                || r.offlineMultiplier().compareTo(BigDecimal.TEN) > 0)) {
            errors.add(new FieldErrorDetail("offlineMultiplier", "Range", "1.5~10"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        if (DeviceRules.cleanTags(r.tags(), tagField).size() > DeviceRules.MAX_TAGS) {
            throw new BusinessException(DeviceErrorCode.DEVICE_TAG_LIMIT);
        }
        devices.findBySourceAndExternalId(source.organizationId(), source.id(), ext).filter(d -> !d.deleted()).ifPresent(d -> {
            throw new BusinessException(DeviceErrorCode.DEVICE_DUPLICATE);
        });
        return ext;
    }

    /** 검증 후 저장(ACTIVE). 이벤트 포함, 감사는 호출자 */
    public Device register(Registration r, String tagField) {
        String ext = validate(r, tagField);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        Instant now = clock.instant();
        NewDevice row = new NewDevice(org, r.sourceId(), ext, r.name().strip(), r.kind(), r.modelId(), r.spaceId(), null,
                DeviceRules.ACTIVE, r.virtual(), r.expectedIntervalSec(), r.offlineMultiplier(), now, user.userId(), false, null, "{}",
                user.userId(), now);
        Device existing = devices.findBySourceAndExternalId(org, r.sourceId(), ext).orElse(null);
        long id;
        if (existing != null) {
            devices.revive(existing.id(), row);
            id = existing.id();
        } else {
            id = devices.insert(row).orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_DUPLICATE));
        }
        devices.replaceTags(org, id, DeviceRules.cleanTags(r.tags(), tagField), now);
        discovery.deleteIgnoreEntry(org, r.sourceId(), ext);
        Device device = devices.findById(org, id).orElseThrow();
        events.changed(device, Change.CREATED, List.of());
        return device;
    }

    /**
     * API-DEV-13 부분 수정(온 키만). 공간·태그는 DEV_PLACE, 이름·모델·주기·종류는 DEV_ADMIN. ACTIVE 기기에서 모델·공간을 비울 수 없다(BR-DEV-05).
     * 모델의 종류와 기기 종류가 달라도 저장한다(경고는 화면에서).
     */
    @Transactional
    public DeviceDetailResponse update(long deviceId, JsonNode body) {
        roleChecker.require(Permission.DEV_PLACE);
        long base = VersionCheck.baseVersion(body);
        Device before = access.device(deviceId, Permission.DEV_PLACE);
        if (ADMIN_FIELDS.stream().anyMatch(body::has)) {
            roleChecker.require(Permission.DEV_ADMIN, access.scopeKey(before.spaceId()), DeviceErrorCode.DEVICE_NOT_FOUND);
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        String name = before.name();
        if (body.has("name")) {
            JsonNode n = body.get("name");
            name = n.isString() ? n.stringValue().strip() : "";
            if (name.isEmpty() || name.length() > DeviceRules.MAX_NAME) {
                errors.add(new FieldErrorDetail("name", "INVALID", null));
            }
        }
        String kind = before.kind();
        if (body.has("kind")) {
            JsonNode k = body.get("kind");
            kind = k.isString() ? k.stringValue() : "";
            if (!DeviceRules.KINDS.contains(kind)) {
                errors.add(new FieldErrorDetail("kind", "INVALID", null));
            }
        }
        Long modelId = body.has("modelId") ? nullableId(body.get("modelId"), "modelId", errors) : before.modelId();
        Long spaceId = body.has("spaceId") ? nullableId(body.get("spaceId"), "spaceId", errors) : before.spaceId();
        Integer interval = before.expectedIntervalSec();
        if (body.has("expectedIntervalSec")) {
            JsonNode v = body.get("expectedIntervalSec");
            interval = v.isNull() ? null : v.isIntegralNumber() ? v.intValue() : -1;
            if (interval != null && (interval < 10 || interval > 86400)) {
                errors.add(new FieldErrorDetail("expectedIntervalSec", "Range", "10~86400"));
            }
        }
        BigDecimal multiplier = before.offlineMultiplier();
        if (body.has("offlineMultiplier")) {
            JsonNode v = body.get("offlineMultiplier");
            multiplier = v.isNull() ? null : v.isNumber() ? v.decimalValue() : BigDecimal.ZERO;
            if (multiplier != null && (multiplier.compareTo(new BigDecimal("1.5")) < 0 || multiplier.compareTo(BigDecimal.TEN) > 0)) {
                errors.add(new FieldErrorDetail("offlineMultiplier", "Range", "1.5~10"));
            }
        }
        List<String> tags = null;
        if (body.has("tags")) {
            JsonNode t = body.get("tags");
            if (!t.isArray()) {
                errors.add(new FieldErrorDetail("tags", "INVALID", null));
            } else {
                tags = new ArrayList<>();
                for (JsonNode item : t.values()) {
                    tags.add(item.isString() ? item.stringValue() : "");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        VersionCheck.require(base, before.version());
        if (DeviceRules.ACTIVE.equals(before.status()) && modelId == null) {
            throw new BusinessException(DeviceErrorCode.DEVICE_MODEL_REQUIRED);
        }
        if (DeviceRules.ACTIVE.equals(before.status()) && spaceId == null) {
            throw new BusinessException(DeviceErrorCode.DEVICE_SPACE_REQUIRED);
        }
        if (modelId != null && !modelId.equals(before.modelId())) {
            access.assignableModel(modelId, "modelId");
        }
        if (spaceId != null && !spaceId.equals(before.spaceId())) {
            access.space(spaceId);
        }
        if (tags != null) {
            tags = DeviceRules.cleanTags(tags, "tags");
            if (tags.size() > DeviceRules.MAX_TAGS) {
                throw new BusinessException(DeviceErrorCode.DEVICE_TAG_LIMIT);
            }
        }
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        Instant now = clock.instant();
        VersionCheck.requireUpdated(devices.update(org, deviceId, (int) base, name, kind, modelId, spaceId, interval, multiplier,
                user.userId(), now));
        List<String> fields = new ArrayList<>();
        Map<String, Object> beforeMap = new LinkedHashMap<>();
        Map<String, Object> afterMap = new LinkedHashMap<>();
        diff("name", before.name(), name, fields, beforeMap, afterMap);
        diff("kind", before.kind(), kind, fields, beforeMap, afterMap);
        diff("modelId", before.modelId(), modelId, fields, beforeMap, afterMap);
        diff("spaceId", before.spaceId(), spaceId, fields, beforeMap, afterMap);
        diff("expectedIntervalSec", before.expectedIntervalSec(), interval, fields, beforeMap, afterMap);
        diff("offlineMultiplier", before.offlineMultiplier(), multiplier, fields, beforeMap, afterMap);
        if (tags != null) {
            List<String> oldTags = devices.findTags(org, deviceId);
            devices.replaceTags(org, deviceId, tags, now);
            diff("tags", oldTags, tags, fields, beforeMap, afterMap);
        }
        Device after = devices.findById(org, deviceId).orElseThrow();
        events.changed(after, Change.UPDATED, fields);
        audits.record(audits.event(org, DeviceAudits.DEVICE_UPDATED).actor(user).target("DEVICE", Long.toString(deviceId))
                .detail("before", beforeMap).detail("after", afterMap));
        return views.detail(after);
    }

    /** API-DEV-16 활성화(INACTIVE → ACTIVE). 모델·공간이 있어야 한다(BR-DEV-05) */
    @Transactional
    public DeviceStatusResponse activate(long deviceId, int baseVersion) {
        return transition(deviceId, baseVersion, DeviceRules.INACTIVE, DeviceRules.ACTIVE, Change.ACTIVATED, DeviceAudits.DEVICE_ACTIVATED);
    }

    /** API-DEV-16 비활성화(ACTIVE → INACTIVE). 데이터는 계속 저장되고 규칙·분석에서 빠진다(DEV-02.02) */
    @Transactional
    public DeviceStatusResponse deactivate(long deviceId, int baseVersion) {
        return transition(deviceId, baseVersion, DeviceRules.ACTIVE, DeviceRules.INACTIVE, Change.DEACTIVATED,
                DeviceAudits.DEVICE_DEACTIVATED);
    }

    private DeviceStatusResponse transition(long deviceId, int baseVersion, String from, String to, Change change, String audit) {
        roleChecker.require(Permission.DEV_PLACE);
        Device device = access.device(deviceId, Permission.DEV_PLACE);
        VersionCheck.require(baseVersion, device.version());
        if (!from.equals(device.status())) {
            throw new BusinessException(DeviceErrorCode.DEVICE_STATE_CONFLICT);
        }
        if (DeviceRules.ACTIVE.equals(to) && device.modelId() == null) {
            throw new BusinessException(DeviceErrorCode.DEVICE_MODEL_REQUIRED);
        }
        if (DeviceRules.ACTIVE.equals(to) && device.spaceId() == null) {
            throw new BusinessException(DeviceErrorCode.DEVICE_SPACE_REQUIRED);
        }
        CurrentUser user = roleChecker.currentUser();
        VersionCheck.requireUpdated(devices.updateStatus(user.organizationId(), deviceId, baseVersion, List.of(from), to, user.userId(),
                clock.instant()));
        Device after = devices.findById(user.organizationId(), deviceId).orElseThrow();
        events.changed(after, change, List.of("status"));
        audits.record(audits.event(user.organizationId(), audit).actor(user).target("DEVICE", Long.toString(deviceId))
                .detail("from", from).detail("to", to));
        return new DeviceStatusResponse(Long.toString(deviceId), after.status(), after.version());
    }

    /**
     * API-DEV-17 삭제(소프트 삭제 status=DELETED). BR-DEV-10의 참조(열린 작업 지시·활성 플로우)는 M4·M5 기능이라 M2에는 없다.
     * 그룹에서 빼고 플랫폼 브로커 자격을 폐기한다. 시계열은 보관 정책까지 남는다.
     */
    @Transactional
    public void delete(long deviceId, Integer baseVersion) {
        roleChecker.require(Permission.DEV_ADMIN);
        if (baseVersion == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("baseVersion", "REQUIRED", null)));
        }
        Device device = access.device(deviceId, Permission.DEV_ADMIN);
        VersionCheck.require(baseVersion, device.version());
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        Instant now = clock.instant();
        VersionCheck.requireUpdated(devices.updateStatus(org, deviceId, baseVersion, DeviceRules.STATUSES, DeviceRules.DELETED,
                user.userId(), now));
        devices.updateCredentialsRevoked(org, deviceId, now);
        groups.removeDevice(org, deviceId);
        Device after = devices.findById(org, deviceId).orElseThrow();
        events.changed(after, Change.DELETED, List.of("status"));
        audits.record(audits.event(org, DeviceAudits.DEVICE_DELETED).actor(user).target("DEVICE", Long.toString(deviceId))
                .detail("externalId", device.externalId()).detail("status", device.status()));
    }

    /**
     * API-DEV-21 태그 일괄 더하기·빼기(DEV-02.10, BR-DEV-11). 기기별 결과: 20개를 넘으면 그 기기만 DEVICE_TAG_LIMIT,
     * 보이지 않는 기기는 DEVICE_NOT_FOUND. 대소문자만 다른 태그는 같은 태그로 보고 처음 표기를 유지한다(AT-DEV-08.2).
     */
    @Transactional
    public TagResponse tag(TagRequest req) {
        roleChecker.require(Permission.DEV_PLACE);
        List<String> add = DeviceRules.cleanTags(req.add(), "add");
        List<String> remove = DeviceRules.cleanTags(req.remove(), "remove");
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        Instant now = clock.instant();
        List<TagResult> results = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String raw : req.deviceIds()) {
            long id = DeviceAccess.id(raw, "deviceIds");
            Device device = access.find(id).orElse(null);
            if (device == null) {
                results.add(new TagResult(raw, false, DeviceErrorCode.DEVICE_NOT_FOUND.code(), null));
                continue;
            }
            List<String> current = devices.findTags(org, id);
            List<String> merged = DeviceRules.mergeTags(current, add, remove);
            if (merged == null) {
                results.add(new TagResult(raw, false, DeviceErrorCode.DEVICE_TAG_LIMIT.code(), current));
                continue;
            }
            if (!merged.equals(current)) {
                devices.replaceTags(org, id, merged, now);
                devices.updateVersion(org, id, user.userId(), now);
                events.changed(devices.findById(org, id).orElseThrow(), Change.UPDATED, List.of("tags"));
                changed.add(raw);
            }
            results.add(new TagResult(raw, true, null, merged));
        }
        if (!changed.isEmpty()) {
            audits.record(audits.event(org, DeviceAudits.DEVICE_TAGS_CHANGED).actor(user).target("DEVICE", String.join(",", changed))
                    .detail("add", add).detail("remove", remove).detail("deviceIds", changed));
        }
        return new TagResponse(results);
    }

    private static void diff(String field, Object before, Object after, List<String> fields, Map<String, Object> b,
                             Map<String, Object> a) {
        boolean same = before instanceof BigDecimal x && after instanceof BigDecimal y ? x.compareTo(y) == 0 : Objects.equals(before, after);
        if (!same) {
            fields.add(field);
            b.put(field, before);
            a.put(field, after);
        }
    }

    private static Long nullableId(JsonNode node, String field, List<FieldErrorDetail> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        String raw = node.isString() ? node.stringValue() : node.isIntegralNumber() ? node.toString() : "";
        if (!raw.matches("\\d{1,18}")) {
            errors.add(new FieldErrorDetail(field, "INVALID", null));
            return null;
        }
        return Long.valueOf(raw);
    }

    private static Long optionalId(String raw, String field, List<FieldErrorDetail> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (!raw.strip().matches("\\d{1,18}")) {
            errors.add(new FieldErrorDetail(field, "INVALID", raw));
            return null;
        }
        return Long.valueOf(raw.strip());
    }

    private static List<String> upper(List<String> raw, Set<String> allowed, String field, List<FieldErrorDetail> errors) {
        List<String> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            for (String part : value.split(",")) {
                String v = part.strip().toUpperCase(Locale.ROOT);
                if (v.isEmpty()) {
                    continue;
                }
                if (!allowed.contains(v)) {
                    errors.add(new FieldErrorDetail(field, "INVALID", part));
                } else if (!result.contains(v)) {
                    result.add(v);
                }
            }
        }
        return result;
    }
}
