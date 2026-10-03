package net.java21.data2flow.core.device.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.core.device.domain.Device;
import net.java21.data2flow.core.device.domain.DeviceErrorCode;
import net.java21.data2flow.core.device.domain.References.ModelRef;
import net.java21.data2flow.core.device.domain.References.SourceRef;
import net.java21.data2flow.core.device.domain.References.SpaceRef;
import net.java21.data2flow.core.device.repository.DeviceReferenceRepository;
import net.java21.data2flow.core.device.repository.DeviceRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 기기·참조 자원 접근 검사(IAM-04.05·04.06, BR-DEV-25). 다른 조직·없는·삭제한 기기, 권한 밖 공간의 기기는 404
 * {@code DEVICE_NOT_FOUND}, 보이는데 권한이 없으면 403. 공간이 정해지지 않은 기기(승인 대기)는 공간 범위가 제한된 사용자에게
 * 보이지 않는다(목록과 같게).
 */
@Component
public class DeviceAccess {

    /** 공간 범위가 제한된 사용자에게 "공간 없음"을 범위 밖으로 보이게 하는 값 */
    private static final long NO_SPACE = -1L;

    private final RoleChecker roleChecker;
    private final DeviceRepository devices;
    private final DeviceReferenceRepository refs;

    public DeviceAccess(RoleChecker roleChecker, DeviceRepository devices, DeviceReferenceRepository refs) {
        this.roleChecker = roleChecker;
        this.devices = devices;
        this.refs = refs;
    }

    public long organizationId() {
        return roleChecker.currentUser().organizationId();
    }

    /** 보이는 기기를 읽고 권한을 확인한다 */
    public Device device(long deviceId, Permission permission) {
        Device device = find(deviceId).orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
        roleChecker.require(permission, scopeKey(device.spaceId()), DeviceErrorCode.DEVICE_NOT_FOUND);
        return device;
    }

    /** 보이는 기기(삭제 제외, 공간 범위 안)면 값. 권한은 보지 않는다(일괄 작업의 항목별 결과용) */
    public Optional<Device> find(long deviceId) {
        return devices.findById(organizationId(), deviceId)
                .filter(d -> !d.deleted())
                .filter(d -> visible(d.spaceId()));
    }

    public boolean visible(Long spaceId) {
        SpaceScope scope = roleChecker.spaceScope();
        return scope.unrestricted() || (spaceId != null && scope.allowedSpaceIds().contains(spaceId));
    }

    /** 공간 범위 목록 조건(null = 제한 없음) */
    public Set<Long> allowedSpaces() {
        SpaceScope scope = roleChecker.spaceScope();
        return scope.unrestricted() ? null : scope.allowedSpaceIds();
    }

    Long scopeKey(Long spaceId) {
        if (spaceId == null && !roleChecker.spaceScope().unrestricted()) {
            return NO_SPACE;
        }
        return spaceId;
    }

    /** 조직의 공간이고 사용자 범위 안이어야 한다. 아니면 404 SPACE_NOT_FOUND */
    public SpaceRef space(long spaceId) {
        SpaceRef space = refs.findSpace(organizationId(), spaceId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.SPACE_NOT_FOUND));
        roleChecker.requireSpace(space.id(), DeviceErrorCode.SPACE_NOT_FOUND);
        return space;
    }

    /** 조직의 모델. 사용 중지(DEPRECATED) 모델은 새로 지정할 수 없다(domain-model §2.7) */
    public ModelRef assignableModel(long modelId, String field) {
        ModelRef model = refs.findModel(organizationId(), modelId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.MODEL_NOT_FOUND));
        if (!"ACTIVE".equals(model.status())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "MODEL_DEPRECATED", null)));
        }
        return model;
    }

    public SourceRef source(long sourceId) {
        return refs.findSource(organizationId(), sourceId).orElseThrow(() -> new BusinessException(DeviceErrorCode.SOURCE_NOT_FOUND));
    }

    public static long id(String raw, String field) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException | NullPointerException ex) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail(field, "INVALID", null)));
        }
    }
}
