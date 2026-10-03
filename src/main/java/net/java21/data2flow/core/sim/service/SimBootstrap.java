package net.java21.data2flow.core.sim.service;

import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.contracts.message.event.DeviceChanged;
import net.java21.data2flow.core.common.ConfigVersions;
import net.java21.data2flow.core.control.domain.ControlModels;
import net.java21.data2flow.core.control.repository.DriverRepository;
import net.java21.data2flow.core.device.repository.DeviceRepository;
import net.java21.data2flow.core.device.service.DeviceEvents;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.sim.repository.SimRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 조직의 가상 환경 기본값(멱등): SIM 유형 데이터 소스(simulator가 API-DSC-50으로 찾는 소스), 하트비트 카나리 기기
 * {@code __heartbeat__}(ING-07.05: simulator가 10초마다 이 기기로 원본을 넣는다), 가상 장비 드라이버(VIRTUAL, ACT-03.02).
 * 시작할 때(설정 {@code data2flow.core.loop.seed-on-startup})와 가상 환경 기기를 만들 때 부른다.
 */
@Component
public class SimBootstrap {

    /** 시스템이 만든 행의 created_by */
    static final long SYSTEM_USER = 0L;
    static final String VIRTUAL_DRIVER_NAME = "가상 장비(virtual)";
    private static final Logger log = LoggerFactory.getLogger(SimBootstrap.class);

    private final SimRepository sim;
    private final DriverRepository drivers;
    private final DeviceRepository devices;
    private final DeviceEvents deviceEvents;
    private final CoreEventPublisher publisher;
    private final ConfigVersions configVersions;
    private final Clock clock;

    public SimBootstrap(SimRepository sim, DriverRepository drivers, DeviceRepository devices, DeviceEvents deviceEvents,
                        CoreEventPublisher publisher, ConfigVersions configVersions, Clock clock) {
        this.sim = sim;
        this.drivers = drivers;
        this.devices = devices;
        this.deviceEvents = deviceEvents;
        this.publisher = publisher;
        this.configVersions = configVersions;
        this.clock = clock;
    }

    /** 결과: SIM 소스·하트비트 기기·가상 드라이버 ID */
    public record Defaults(long simSourceId, long heartbeatDeviceId, long virtualDriverId) {
    }

    @Transactional
    public Defaults ensure(long organizationId) {
        Instant now = clock.instant();
        long sourceId = sim.findSimSource(organizationId).orElseGet(() -> {
            String code = sim.existsSourceCode(organizationId, "simulation") ? "simulation-" + organizationId : "simulation";
            long id = sim.insertSimSource(organizationId, code, SYSTEM_USER, now);
            publisher.configChanged(EntityType.SOURCE, id, 1, organizationId);
            configVersions.bump(organizationId, ConfigVersions.SOURCES);
            log.info("가상 환경 SIM 소스를 만들었습니다 org={} source={}", organizationId, id);
            return id;
        });
        long heartbeat = sim.findHeartbeatDevice(organizationId, sourceId).orElseGet(() -> {
            long id = sim.insertVirtualDevice(organizationId, sourceId, SimRepository.HEARTBEAT_EXTERNAL_ID, "하트비트 카나리", "SENSOR",
                    null, null, SYSTEM_USER, now);
            devices.findById(organizationId, id).ifPresent(d -> deviceEvents.changed(d, DeviceChanged.Change.CREATED, List.of()));
            log.info("하트비트 카나리 기기를 만들었습니다 org={} device={}", organizationId, id);
            return id;
        });
        long driver = drivers.findFirstByType(organizationId, "VIRTUAL").map(DriverRepository.DriverRow::id)
                .orElseGet(() -> drivers.insert(organizationId, VIRTUAL_DRIVER_NAME, "VIRTUAL", "{}", null, 0, 10, 60,
                        "{\"maxAttempts\":3,\"initialMs\":1000,\"multiplier\":2,\"maxMs\":10000}", "{\"failureRate\":0.5,\"windowSec\":60,\"openSec\":30}",
                        ControlModels.defaultCapabilities("VIRTUAL"), SYSTEM_USER, now));
        return new Defaults(sourceId, heartbeat, driver);
    }
}
