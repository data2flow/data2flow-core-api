package net.java21.data2flow.core.device.service;

import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.DeviceChanged;
import net.java21.data2flow.contracts.message.event.DevicePendingCreated;
import net.java21.data2flow.core.common.ConfigVersions;
import net.java21.data2flow.core.device.domain.Device;
import net.java21.data2flow.core.devicegroup.service.DynamicGroupMembership;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 기기가 바뀌면 같은 트랜잭션에서 할 일(아웃박스): EVT-DEV-01 {@code device.changed}, EVT-DEV-04 설정 변경(DEVICE),
 * 기기 식별 설정 버전(ConfigVersions DEVICES, API-DEV-130 캐시 예열), 동적 그룹 소속 다시 계산(BR-DEV-13, 1분 이내 → 즉시).
 */
@Component
public class DeviceEvents {

    private final CoreEventPublisher publisher;
    private final ConfigVersions configVersions;
    private final DynamicGroupMembership groups;

    public DeviceEvents(CoreEventPublisher publisher, ConfigVersions configVersions, DynamicGroupMembership groups) {
        this.publisher = publisher;
        this.configVersions = configVersions;
        this.groups = groups;
    }

    /** 기기 변경. {@code device}는 바뀐 뒤의 행 */
    public void changed(Device device, DeviceChanged.Change change, List<String> fields) {
        long org = device.organizationId();
        publisher.event(EventType.DEVICE_CHANGED, org, new DeviceChanged(device.id(), change, fields,
                device.status(), device.modelId() == null ? null : Long.toString(device.modelId()),
                device.spaceId(), device.version()));
        if (device.deleted()) {
            publisher.configDeleted(EntityType.DEVICE, device.id(), device.version(), org);
        } else {
            publisher.configChanged(EntityType.DEVICE, device.id(), device.version(), org);
        }
        configVersions.bump(org, ConfigVersions.DEVICES);
        groups.refreshDevice(org, device.id());
    }

    /** EVT-DEV-03: 수신 데이터로 발견해 승인 대기로 등록(ADR-031) */
    public void pendingCreated(Device device) {
        long org = device.organizationId();
        publisher.event(EventType.DEVICE_PENDING_CREATED, org, new DevicePendingCreated(device.id(), device.sourceId(),
                device.externalId(), device.name(), device.firstSeenAt()));
        publisher.configChanged(EntityType.DEVICE, device.id(), device.version(), org);
        configVersions.bump(org, ConfigVersions.DEVICES);
        groups.refreshDevice(org, device.id());
    }
}
