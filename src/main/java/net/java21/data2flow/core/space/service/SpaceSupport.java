package net.java21.data2flow.core.space.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.SpaceChanged;
import net.java21.data2flow.core.audit.service.Audits;
import net.java21.data2flow.core.common.ConfigVersions;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.space.domain.Space;
import net.java21.data2flow.core.space.domain.SpaceErrorCode;
import net.java21.data2flow.core.space.dto.SpaceDtos.SpaceResponse;
import net.java21.data2flow.core.space.repository.DeviceRelationRepository;
import net.java21.data2flow.core.space.repository.DeviceRelationRepository.DeviceRef;
import net.java21.data2flow.core.space.repository.SpaceRepository;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 공간 기능 공통: 보이는 공간 읽기(조직·공간 범위 → 404, 권한 → 403), 조상 사슬, 변경 알림(EVT-DEV-05 + 설정 변경 SPACE + 설정 버전).
 */
@Component
public class SpaceSupport {

    /** 사이트 시간대가 없을 때(이론상 없음, SITE는 시간대 필수) */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    /** 감사 행위(UC-DEV-01 사후 조건) */
    public static final String AUDIT_SPACE_CREATED = "SPACE_CREATED";
    public static final String AUDIT_SPACE_UPDATED = "SPACE_UPDATED";
    public static final String AUDIT_SPACE_MOVED = "SPACE_MOVED";
    public static final String AUDIT_SPACE_DELETED = "SPACE_DELETED";

    private final SpaceRepository spaces;
    private final DeviceRelationRepository devices;
    private final RoleChecker roleChecker;
    private final CoreEventPublisher publisher;
    private final ConfigVersions configVersions;
    private final Audits audits;

    public SpaceSupport(SpaceRepository spaces, DeviceRelationRepository devices, RoleChecker roleChecker, CoreEventPublisher publisher,
                        ConfigVersions configVersions, Audits audits) {
        this.spaces = spaces;
        this.devices = devices;
        this.roleChecker = roleChecker;
        this.publisher = publisher;
        this.configVersions = configVersions;
        this.audits = audits;
    }

    public CurrentUser user() {
        return roleChecker.currentUser();
    }

    /** 공간을 읽고 권한을 본다: 없거나 다른 조직·권한 밖 공간이면 404 SPACE_NOT_FOUND, 권한이 없으면 403 */
    public Space visible(long spaceId, Permission permission) {
        Space space = spaces.findById(user().organizationId(), spaceId)
                .orElseThrow(() -> new BusinessException(SpaceErrorCode.SPACE_NOT_FOUND));
        roleChecker.require(permission, space.id(), SpaceErrorCode.SPACE_NOT_FOUND);
        return space;
    }

    /** 잠그고 읽는다(수정·이동·삭제) */
    public Space locked(long spaceId, Permission permission) {
        Space space = spaces.lockById(user().organizationId(), spaceId)
                .orElseThrow(() -> new BusinessException(SpaceErrorCode.SPACE_NOT_FOUND));
        roleChecker.require(permission, space.id(), SpaceErrorCode.SPACE_NOT_FOUND);
        return space;
    }

    /**
     * 기기를 읽고 권한을 본다: 없거나 다른 조직·권한 밖(설치 공간 기준)이면 404 DEVICE_NOT_FOUND, 권한이 없으면 403.
     * 공간이 정해지지 않은 기기는 공간 범위가 있는 사용자에게 보이지 않는다.
     */
    public DeviceRef visibleDevice(long deviceId, Permission permission) {
        DeviceRef device = devices.findDevice(user().organizationId(), deviceId)
                .orElseThrow(() -> new BusinessException(SpaceErrorCode.DEVICE_NOT_FOUND));
        if (device.spaceId() == null && !roleChecker.spaceScope().unrestricted()) {
            throw new BusinessException(SpaceErrorCode.DEVICE_NOT_FOUND);
        }
        roleChecker.require(permission, device.spaceId(), SpaceErrorCode.DEVICE_NOT_FOUND);
        return device;
    }

    /** 루트부터 자기까지의 공간(BR-DEV-04 상속 계산) */
    public List<Space> chain(Space space) {
        return spaces.findByIds(space.organizationId(), space.pathIds());
    }

    /** 사이트(루트) 시간대 */
    public static ZoneId zone(List<Space> chain) {
        if (chain.isEmpty() || chain.getFirst().timezone() == null) {
            return DEFAULT_ZONE;
        }
        return ZoneId.of(chain.getFirst().timezone());
    }

    /** 바뀐 공간 알림: EVT-DEV-05(space.changed), EVT-DEV-04(data2flow.config, SPACE), 설정 버전 SPACES */
    public void changed(long organizationId, long spaceId, String path, String change, long version) {
        publisher.event(EventType.SPACE_CHANGED, organizationId, new SpaceChanged(spaceId, change, Space.eventPath(path)));
        if ("DELETED".equals(change)) {
            publisher.configDeleted(EntityType.SPACE, spaceId, version, organizationId);
        } else {
            publisher.configChanged(EntityType.SPACE, spaceId, version, organizationId);
        }
        configVersions.bump(organizationId, ConfigVersions.SPACES);
    }

    /** 감사 기록 */
    public void audit(String action, long spaceId, Map<String, ?> detail) {
        audit(action, "SPACE", spaceId, detail);
    }

    /** 감사 기록(대상 종류 지정) */
    public void audit(String action, String targetType, long targetId, Map<String, ?> detail) {
        CurrentUser user = user();
        audits.record(audits.event(user.organizationId(), action).actor(user).target(targetType, Long.toString(targetId)).detail(detail));
    }

    public static String id(Long value) {
        return value == null ? null : Long.toString(value);
    }

    /** "123" → 123. 형식이 틀리면 400 INVALID_REQUEST(field) */
    public static Long parseId(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (!value.matches("\\d{1,18}")) {
            throw invalid(field, "Pattern");
        }
        return Long.parseLong(value);
    }

    public static BusinessException invalid(String field, String code) {
        return new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, code, null)));
    }

    public static SpaceResponse toResponse(Space s) {
        return new SpaceResponse(Long.toString(s.id()), id(s.parentId()), s.type().name(), s.name(), s.code(), Space.eventPath(s.path()),
                s.depth(), s.sortOrder(), s.usage(), s.areaM2(), s.capacity(), s.timezone(), s.address(), s.latitude(),
                s.longitude(), s.kmaNx(), s.kmaNy(), s.status(), s.version(), s.updatedAt());
    }
}
