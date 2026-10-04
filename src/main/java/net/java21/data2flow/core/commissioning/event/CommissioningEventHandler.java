package net.java21.data2flow.core.commissioning.event;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.DeviceConnectivityChanged;
import net.java21.data2flow.core.commissioning.service.CommissioningService;
import net.java21.data2flow.core.messaging.service.CoreEventHandler;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 기기가 온라인이 되면(EVT-DEV-02) 설치 확인을 바로 한다(AT-DEV-28.2: 1분 작업을 기다리지 않고 3초 안에 현황판 갱신) */
@Component
public class CommissioningEventHandler implements CoreEventHandler {

    private final CommissioningService service;

    public CommissioningEventHandler(CommissioningService service) {
        this.service = service;
    }

    @Override
    public Set<EventType> types() {
        return Set.of(EventType.DEVICE_CONNECTIVITY_CHANGED);
    }

    @Override
    public void handle(DomainEvent<?> event) {
        if (event.payload() instanceof DeviceConnectivityChanged c && c.to() == DeviceConnectivityChanged.Connectivity.ONLINE) {
            service.check(event.organizationId(), c.deviceId());
        }
    }
}
