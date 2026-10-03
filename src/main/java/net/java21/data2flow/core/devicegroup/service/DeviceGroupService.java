package net.java21.data2flow.core.devicegroup.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.ConfigVersions;
import net.java21.data2flow.core.device.domain.DeviceErrorCode;
import net.java21.data2flow.core.device.dto.DeviceDtos.DeviceSummaryResponse;
import net.java21.data2flow.core.device.service.DeviceService;
import net.java21.data2flow.core.device.service.DeviceService.ListQuery;
import net.java21.data2flow.core.devicegroup.domain.DeviceGroup;
import net.java21.data2flow.core.devicegroup.domain.GroupCriteria;
import net.java21.data2flow.core.devicegroup.dto.DeviceGroupDtos.CreateGroupRequest;
import net.java21.data2flow.core.devicegroup.dto.DeviceGroupDtos.DeviceSample;
import net.java21.data2flow.core.devicegroup.dto.DeviceGroupDtos.GroupResponse;
import net.java21.data2flow.core.devicegroup.dto.DeviceGroupDtos.MembersAddedResponse;
import net.java21.data2flow.core.devicegroup.dto.DeviceGroupDtos.MembersRemovedResponse;
import net.java21.data2flow.core.devicegroup.dto.DeviceGroupDtos.PreviewResponse;
import net.java21.data2flow.core.devicegroup.dto.DeviceGroupDtos.Usage;
import net.java21.data2flow.core.devicegroup.repository.DeviceGroupRepository;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 기기 그룹(DEV-06.01 정적, DEV-06.02 동적, BR-DEV-12·13). 쓰기는 DEV_ADMIN, 조회·미리 보기는 DEV_READ. 그룹은 조직 단위 자원이고
 * 구성원 목록·미리 보기 표본은 사용자 공간 범위 안의 기기만 보인다(IAM-04.06). 한 기기는 여러 그룹에 속할 수 있다.
 */
@Service
public class DeviceGroupService {

    private static final Usage NO_USAGE = new Usage(0, 0, 0);
    private static final int SAMPLE = 20;

    private final RoleChecker roleChecker;
    private final DeviceGroupRepository groups;
    private final DynamicGroupMembership membership;
    private final DeviceService devices;
    private final CoreEventPublisher publisher;
    private final ConfigVersions configVersions;
    private final Audits audits;
    private final JsonMapper json;
    private final Clock clock;

    public DeviceGroupService(RoleChecker roleChecker, DeviceGroupRepository groups, DynamicGroupMembership membership,
                              DeviceService devices, CoreEventPublisher publisher, ConfigVersions configVersions, Audits audits,
                              JsonMapper json, Clock clock) {
        this.roleChecker = roleChecker;
        this.groups = groups;
        this.membership = membership;
        this.devices = devices;
        this.publisher = publisher;
        this.configVersions = configVersions;
        this.audits = audits;
        this.json = json;
        this.clock = clock;
    }

    /** API-DEV-34 목록 */
    @Transactional(readOnly = true)
    public ListApiResponse<GroupResponse> list(String q, String type, Integer page, Integer size) {
        roleChecker.require(Permission.DEV_READ);
        long org = roleChecker.currentUser().organizationId();
        String t = type == null || type.isBlank() ? null : type.toUpperCase(Locale.ROOT);
        if (t != null && !Set.of(DeviceGroup.STATIC, DeviceGroup.DYNAMIC).contains(t)) {
            throw invalid("type", "INVALID");
        }
        PageParams params = PageParams.of(page, size);
        String keyword = PageParams.keyword(q);
        String like = keyword == null ? null : keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<GroupResponse> items = groups.list(org, like, t, params.size(), params.offset()).stream().map(this::toResponse).toList();
        return ListApiResponse.of(params, items, groups.count(org, like, t));
    }

    /** 그룹 상세(GET /core/device-groups/{group-id}, 문서 추가 필요) */
    @Transactional(readOnly = true)
    public GroupResponse get(long groupId) {
        roleChecker.require(Permission.DEV_READ);
        return toResponse(load(groupId));
    }

    /** API-DEV-34 구성원(기기 목록 API-DEV-11 항목 모양, 사용자 공간 범위 적용) */
    @Transactional(readOnly = true)
    public ListApiResponse<DeviceSummaryResponse> members(long groupId, Integer page, Integer size) {
        roleChecker.require(Permission.DEV_READ);
        DeviceGroup group = load(groupId);
        return devices.list(new ListQuery(null, null, null, null, null, null, null, null, null, Long.toString(group.id()), true, null,
                null), page, size);
    }

    /** API-DEV-30 생성 */
    @Transactional
    public GroupResponse create(CreateGroupRequest req) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        String name = name(org, req.name(), null);
        boolean dynamic = DeviceGroup.DYNAMIC.equals(req.type());
        String criteriaJson = null;
        List<Long> deviceIds = List.of();
        if (dynamic) {
            GroupCriteria criteria = GroupCriteria.parse(req.criteria(), "criteria");
            requireSize(groups.countMatching(org, criteria, null));
            criteriaJson = json.writeValueAsString(req.criteria());
        } else {
            if (req.criteria() != null && !req.criteria().isNull()) {
                throw invalid("criteria", "STATIC_GROUP");
            }
            deviceIds = existing(org, req.deviceIds());
            requireSize(deviceIds.size());
        }
        Instant now = clock.instant();
        long id = groups.insert(org, name, req.type(), criteriaJson, blankToNull(req.description()), user.userId(), now);
        DeviceGroup group = groups.findById(org, id).orElseThrow();
        if (dynamic) {
            membership.recompute(group);
        } else if (!deviceIds.isEmpty()) {
            membership.membersChanged(group, groups.addMembers(org, id, deviceIds, DeviceGroup.STATIC, now), List.of());
        }
        changed(group);
        audits.record(audits.event(org, "DEVICE_GROUP_CREATED").actor(user).target("DEVICE_GROUP", Long.toString(id))
                .detail("name", name).detail("type", req.type()));
        return toResponse(groups.findById(org, id).orElseThrow());
    }

    /** API-DEV-31 부분 수정(name·description·criteria). baseVersion은 오면 확인한다 */
    @Transactional
    public GroupResponse update(long groupId, JsonNode body) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        DeviceGroup before = load(groupId);
        int base = before.version();
        if (body.has(VersionCheck.BASE_VERSION) && !body.get(VersionCheck.BASE_VERSION).isNull()) {
            base = (int) VersionCheck.baseVersion(body);
            VersionCheck.require(base, before.version());
        }
        String name = before.name();
        if (body.has("name")) {
            JsonNode n = body.get("name");
            if (!n.isString() || n.stringValue().isBlank() || n.stringValue().strip().length() > 100) {
                throw invalid("name", "INVALID");
            }
            name = name(org, n.stringValue(), groupId);
        }
        String description = before.description();
        if (body.has("description")) {
            JsonNode d = body.get("description");
            description = d.isNull() ? null : d.isString() ? blankToNull(d.stringValue()) : description;
            if (description != null && description.length() > 500) {
                throw invalid("description", "TOO_LONG");
            }
        }
        String criteriaJson = before.criteria();
        boolean criteriaChanged = false;
        if (body.has("criteria")) {
            if (!before.dynamic()) {
                throw invalid("criteria", "STATIC_GROUP");
            }
            GroupCriteria criteria = GroupCriteria.parse(body.get("criteria"), "criteria");
            requireSize(groups.countMatching(org, criteria, null));
            criteriaJson = json.writeValueAsString(body.get("criteria"));
            criteriaChanged = true;
        }
        VersionCheck.requireUpdated(groups.update(org, groupId, base, name, criteriaJson, description, user.userId(), clock.instant()));
        DeviceGroup after = groups.findById(org, groupId).orElseThrow();
        if (criteriaChanged) {
            membership.recompute(after);
        }
        changed(after);
        audits.record(audits.event(org, "DEVICE_GROUP_UPDATED").actor(user).target("DEVICE_GROUP", Long.toString(groupId))
                .detail("before", Map.of("name", before.name())).detail("after", Map.of("name", name)).detail("criteriaChanged", criteriaChanged));
        return toResponse(groups.findById(org, groupId).orElseThrow());
    }

    /** API-DEV-32 삭제. 규칙·플로우·대시보드 참조(GROUP_IN_USE)는 그 기능이 생기는 M3~M5부터 */
    @Transactional
    public void delete(long groupId) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        DeviceGroup group = load(groupId);
        groups.delete(org, groupId);
        publisher.configDeleted(EntityType.GROUP, groupId, group.version(), org);
        configVersions.bump(org, ConfigVersions.GROUPS);
        audits.record(audits.event(org, "DEVICE_GROUP_DELETED").actor(user).target("DEVICE_GROUP", Long.toString(groupId))
                .detail("name", group.name()));
    }

    /** API-DEV-33 동적 조건 미리 보기(표본 ≤ 20, 사용자 공간 범위) */
    @Transactional(readOnly = true)
    public PreviewResponse preview(JsonNode criteriaNode) {
        roleChecker.require(Permission.DEV_READ);
        long org = roleChecker.currentUser().organizationId();
        GroupCriteria criteria = GroupCriteria.parse(criteriaNode, "criteria");
        Set<Long> allowed = roleChecker.spaceScope().unrestricted() ? null : roleChecker.spaceScope().allowedSpaceIds();
        requireSize(groups.countMatching(org, criteria, null));
        long count = groups.countMatching(org, criteria, allowed);
        List<DeviceSample> sample = groups.findDeviceNames(org, groups.findMatchingDeviceIds(org, criteria, null, allowed, SAMPLE)).stream()
                .map(r -> new DeviceSample(Long.toString((Long) r[0]), (String) r[1])).toList();
        return new PreviewResponse(count, sample);
    }

    /** API-DEV-35 추가(정적 그룹만, 합쳐서 1,000대 이하) */
    @Transactional
    public MembersAddedResponse addMembers(long groupId, List<String> rawIds) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        DeviceGroup group = staticGroup(groupId);
        List<Long> ids = existing(org, rawIds);
        Set<Long> union = new LinkedHashSet<>(groups.findMemberIds(org, groupId));
        union.addAll(ids);
        requireSize(union.size());
        List<Long> added = groups.addMembers(org, groupId, ids, DeviceGroup.STATIC, clock.instant());
        membership.membersChanged(group, added, List.of());
        if (!added.isEmpty()) {
            audits.record(audits.event(org, "DEVICE_GROUP_MEMBERS_CHANGED").actor(user).target("DEVICE_GROUP", Long.toString(groupId))
                    .detail("added", added));
        }
        return new MembersAddedResponse(Long.toString(groupId), groups.findById(org, groupId).orElseThrow().memberCount(), added.size());
    }

    /** API-DEV-35 제거(정적 그룹만) */
    @Transactional
    public MembersRemovedResponse removeMembers(long groupId, List<String> rawIds) {
        roleChecker.require(Permission.DEV_ADMIN);
        CurrentUser user = roleChecker.currentUser();
        long org = user.organizationId();
        DeviceGroup group = staticGroup(groupId);
        List<Long> ids = rawIds.stream().map(Long::valueOf).toList();
        List<Long> removed = groups.removeMembers(org, groupId, ids);
        membership.membersChanged(group, List.of(), removed);
        if (!removed.isEmpty()) {
            audits.record(audits.event(org, "DEVICE_GROUP_MEMBERS_CHANGED").actor(user).target("DEVICE_GROUP", Long.toString(groupId))
                    .detail("removed", removed));
        }
        return new MembersRemovedResponse(Long.toString(groupId), groups.findById(org, groupId).orElseThrow().memberCount(),
                removed.size());
    }

    private DeviceGroup staticGroup(long groupId) {
        DeviceGroup group = load(groupId);
        if (group.dynamic()) {
            throw invalid("groupId", "DYNAMIC_GROUP");
        }
        return group;
    }

    private DeviceGroup load(long groupId) {
        return groups.findById(roleChecker.currentUser().organizationId(), groupId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.GROUP_NOT_FOUND));
    }

    private void changed(DeviceGroup group) {
        publisher.configChanged(EntityType.GROUP, group.id(), group.version(), group.organizationId());
        configVersions.bump(group.organizationId(), ConfigVersions.GROUPS);
    }

    /** 조직에 있는(삭제되지 않은) 기기만. 없는 ID가 있으면 400 INVALID_REQUEST */
    private List<Long> existing(long org, List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        rawIds.forEach(id -> ids.add(Long.valueOf(id)));
        if (ids.size() > DeviceGroup.MAX_MEMBERS) {
            throw new BusinessException(DeviceErrorCode.GROUP_SIZE_EXCEEDED);
        }
        List<Long> found = groups.findExistingDeviceIds(org, ids);
        if (found.size() != ids.size()) {
            List<FieldErrorDetail> errors = new ArrayList<>();
            ids.stream().filter(id -> !found.contains(id)).forEach(id -> errors.add(new FieldErrorDetail("deviceIds", "DEVICE_NOT_FOUND",
                    Long.toString(id))));
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        return found;
    }

    private String name(long org, String raw, Long exceptId) {
        String name = raw.strip();
        if (groups.existsName(org, name, exceptId)) {
            throw new BusinessException(DeviceErrorCode.GROUP_NAME_DUPLICATE);
        }
        return name;
    }

    private static void requireSize(long count) {
        if (count > DeviceGroup.MAX_MEMBERS) {
            throw new BusinessException(DeviceErrorCode.GROUP_SIZE_EXCEEDED);
        }
    }

    private GroupResponse toResponse(DeviceGroup g) {
        return new GroupResponse(Long.toString(g.id()), g.name(), g.type(), g.description(),
                g.criteria() == null ? null : json.readTree(g.criteria()), g.memberCount(), NO_USAGE, g.version(), g.updatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }
}
