package net.java21.data2flow.core.space.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.domain.SpaceErrorCode;
import net.java21.data2flow.core.space.dto.SpaceDtos.InternalSpaceDeviceResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.RelationDto;
import net.java21.data2flow.core.space.dto.SpaceDtos.RelationItem;
import net.java21.data2flow.core.space.dto.SpaceDtos.RelationsRequest;
import net.java21.data2flow.core.space.dto.SpaceDtos.RelationsResponse;
import net.java21.data2flow.core.space.dto.SpaceDtos.SpaceDeviceResponse;
import net.java21.data2flow.core.space.repository.DeviceRelationRepository;
import net.java21.data2flow.core.space.repository.DeviceRelationRepository.DeviceRef;
import net.java21.data2flow.core.space.repository.DeviceRelationRepository.SpaceDeviceRow;
import net.java21.data2flow.core.space.repository.DeviceRelationRepository.StoredRelation;
import net.java21.data2flow.core.space.repository.SpaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 공간–기기 관계(DEV-01.05): 기기의 추가 관계 저장(API-DEV-14), 공간 기준 관계 질의(API-DEV-18, 내부 API-DEV-128).
 * 설치 공간의 자동 관계는 기기 종류로 만든다(SENSOR→MEASURES, ACTUATOR→CONTROLS, HYBRID→둘 다). 플로우는 "이 공간을 제어하는
 * Thermostat"처럼 관계·기능으로 대상을 찾는다(AT-DEV-12.2, 참고: Azure Digital Twins).
 * 기기 상세(API-DEV-23)는 {@link #effectiveRelations}로 같은 결과를 쓸 수 있다.
 */
@Service
public class DeviceRelationService {

    static final Set<String> RELATIONS = Set.of("MEASURES", "CONTROLS");
    static final int MAX_RELATIONS = 50;

    private final DeviceRelationRepository devices;
    private final SpaceRepository spaces;
    private final SpaceSupport support;
    private final SpaceSettingsService settings;
    private final RoleChecker roleChecker;
    private final Clock clock;

    public DeviceRelationService(DeviceRelationRepository devices, SpaceRepository spaces, SpaceSupport support,
                                 SpaceSettingsService settings, RoleChecker roleChecker, Clock clock) {
        this.devices = devices;
        this.spaces = spaces;
        this.support = support;
        this.settings = settings;
        this.roleChecker = roleChecker;
        this.clock = clock;
    }

    /** 기기의 유효 관계 조회 */
    @Transactional(readOnly = true)
    public RelationsResponse relations(long deviceId) {
        DeviceRef device = support.visibleDevice(deviceId, Permission.DEV_READ);
        return new RelationsResponse(Long.toString(device.id()), effectiveRelations(support.user().organizationId(), device));
    }

    /** API-DEV-14 추가 관계 전체 교체. 공간은 조직에 있고 사용자 범위 안이어야 한다(아니면 404 SPACE_NOT_FOUND) */
    @Transactional
    public RelationsResponse replaceRelations(long deviceId, RelationsRequest req) {
        DeviceRef device = support.visibleDevice(deviceId, Permission.DEV_ADMIN);
        long org = support.user().organizationId();
        List<RelationItem> items = req == null || req.items() == null ? List.of() : req.items();
        if (items.size() > MAX_RELATIONS) {
            throw SpaceSupport.invalid("items", "Size");
        }
        List<FieldErrorDetail> errors = new ArrayList<>();
        Set<StoredRelation> rows = new LinkedHashSet<>();
        for (int i = 0; i < items.size(); i++) {
            RelationItem item = items.get(i);
            Long spaceId = item == null ? null : SpaceSupport.parseId(item.spaceId(), "items[" + i + "].spaceId");
            String relation = item == null || item.relation() == null ? "" : item.relation().strip().toUpperCase(Locale.ROOT);
            if (spaceId == null) {
                errors.add(new FieldErrorDetail("items[" + i + "].spaceId", "NotNull", null));
            } else if (!RELATIONS.contains(relation)) {
                errors.add(new FieldErrorDetail("items[" + i + "].relation", "INVALID", "MEASURES|CONTROLS"));
            } else {
                rows.add(new StoredRelation(spaceId, relation));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, errors);
        }
        Set<Long> spaceIds = new LinkedHashSet<>();
        rows.forEach(r -> spaceIds.add(r.spaceId()));
        Set<Long> found = new LinkedHashSet<>(spaces.findByIds(org, spaceIds).stream().map(Space::id).toList());
        SpaceScope scope = roleChecker.spaceScope();
        for (Long spaceId : spaceIds) {
            if (!found.contains(spaceId) || !scope.includes(spaceId)) {
                throw new BusinessException(SpaceErrorCode.SPACE_NOT_FOUND);
            }
        }
        devices.replaceRelations(org, device.id(), List.copyOf(rows), clock.instant());
        support.audit("DEVICE_RELATIONS_CHANGED", "DEVICE", device.id(), Map.of(
                "relations", rows.stream().map(r -> r.relation() + ":" + r.spaceId()).toList()));
        return new RelationsResponse(Long.toString(device.id()), effectiveRelations(org, device));
    }

    /** 유효 관계: 설치 공간 자동 관계(auto=true) + 저장된 추가 관계. 같은 공간·관계는 한 번만 */
    public List<RelationDto> effectiveRelations(long organizationId, DeviceRef device) {
        Map<String, RelationDto> result = new LinkedHashMap<>();
        Set<Long> spaceIds = new LinkedHashSet<>();
        List<StoredRelation> stored = devices.findRelations(organizationId, device.id());
        if (device.spaceId() != null) {
            spaceIds.add(device.spaceId());
        }
        stored.forEach(r -> spaceIds.add(r.spaceId()));
        Map<Long, String> names = new HashMap<>();
        spaces.findByIds(organizationId, spaceIds).forEach(s -> names.put(s.id(), s.name()));
        if (device.spaceId() != null && names.containsKey(device.spaceId())) {
            for (String relation : autoRelations(device.kind())) {
                result.put(device.spaceId() + relation, new RelationDto(Long.toString(device.spaceId()), names.get(device.spaceId()),
                        relation, true));
            }
        }
        for (StoredRelation r : stored) {
            if (names.containsKey(r.spaceId())) {
                result.putIfAbsent(r.spaceId() + r.relation(), new RelationDto(Long.toString(r.spaceId()), names.get(r.spaceId()),
                        r.relation(), false));
            }
        }
        return List.copyOf(result.values());
    }

    static List<String> autoRelations(String kind) {
        return switch (kind == null ? "" : kind) {
            case "SENSOR" -> List.of("MEASURES");
            case "ACTUATOR" -> List.of("CONTROLS");
            case "HYBRID" -> List.of("MEASURES", "CONTROLS");
            default -> List.of();
        };
    }

    /** API-DEV-18 공간 기준 관계 질의(사용자 범위: 기기 설치 공간 기준, IAM-04.06) */
    @Transactional(readOnly = true)
    public ListApiResponse<SpaceDeviceResponse> spaceDevices(long spaceId, String relation, String capability,
                                                            Boolean includeDescendants, Integer page, Integer size) {
        Space space = support.visible(spaceId, Permission.DEV_READ);
        PageParams params = PageParams.of(page, size);
        List<SpaceDeviceRow> rows = devices.findSpaceDevices(space.organizationId(), targetSpaces(space, includeDescendants),
                relation(relation), blankToNull(capability), roleChecker.spaceScope());
        List<SpaceDeviceResponse> pageRows = rows.stream().skip(params.offset()).limit(params.size())
                .map(r -> new SpaceDeviceResponse(Long.toString(r.deviceId()), r.name(), r.kind(), r.status(),
                        r.connectivity() == null ? "UNKNOWN" : r.connectivity(), r.relation(), SpaceSupport.id(r.modelId()),
                        r.modelName(), r.capabilities(), Long.toString(r.spaceId()), r.lastSeenAt()))
                .toList();
        return ListApiResponse.of(params, pageRows, rows.size());
    }

    /** API-DEV-128 내부: 사용자 범위 대신 조직 범위(flow-engine·action) */
    @Transactional(readOnly = true)
    public List<InternalSpaceDeviceResponse> internalSpaceDevices(long spaceId, String relation, String capability,
                                                                  Boolean includeDescendants) {
        Space space = settings.internalSpace(spaceId);
        return devices.findSpaceDevices(space.organizationId(), targetSpaces(space, includeDescendants), relation(relation),
                        blankToNull(capability), null).stream()
                .map(r -> new InternalSpaceDeviceResponse(Long.toString(r.deviceId()), r.name(), r.relation(),
                        SpaceSupport.id(r.modelId()), r.capabilities(), r.status(),
                        r.connectivity() == null ? "UNKNOWN" : r.connectivity(), Long.toString(r.spaceId())))
                .toList();
    }

    private List<Long> targetSpaces(Space space, Boolean includeDescendants) {
        return Boolean.TRUE.equals(includeDescendants) ? spaces.findIdsUnder(space.organizationId(), space.path()) : List.of(space.id());
    }

    private static String relation(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        value = value.toUpperCase(Locale.ROOT);
        if (!RELATIONS.contains(value)) {
            throw SpaceSupport.invalid("relation", "INVALID");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
