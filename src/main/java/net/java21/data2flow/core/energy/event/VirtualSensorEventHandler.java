package net.java21.data2flow.core.energy.event;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.EnergyVirtualSensorUpdated;
import net.java21.data2flow.core.energy.service.VirtualSensorService;
import net.java21.data2flow.core.messaging.service.CoreEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/** EVT-ENE-03 {@code energy.virtual-sensor.updated}({@code core.events}): 가상 센서 마지막 계산 시각·상태(ENE-02) */
@Component
public class VirtualSensorEventHandler implements CoreEventHandler {

    private static final Logger log = LoggerFactory.getLogger(VirtualSensorEventHandler.class);

    private final VirtualSensorService sensors;

    public VirtualSensorEventHandler(VirtualSensorService sensors) {
        this.sensors = sensors;
    }

    @Override
    public Set<EventType> types() {
        return Set.of(EventType.ENERGY_VIRTUAL_SENSOR_UPDATED);
    }

    @Override
    public void handle(DomainEvent<?> event) {
        if (event.payload() instanceof EnergyVirtualSensorUpdated updated && !sensors.onUpdated(event.organizationId(), updated, event.occurredAt())) {
            log.info("energy.virtual-sensor.updated 무시(설정 없음 또는 오래된 결과) space={} kind={}", updated.spaceId(), updated.kind());
        }
    }
}
