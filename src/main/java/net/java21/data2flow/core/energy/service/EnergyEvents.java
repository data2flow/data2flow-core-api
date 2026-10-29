package net.java21.data2flow.core.energy.service;

import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.EnergyConfigChanged;
import net.java21.data2flow.contracts.message.event.EnergyConfigChanged.Scope;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.stereotype.Component;

/**
 * EVT-ENE-07 {@code energy.config.changed}. analytics가 조직 설정 묶음(API-ENE-70) 캐시를 지운다. 과거 집계는 다시 계산하지 않는다(BR-ENE-05).
 * 업무 트랜잭션 안에서 아웃박스에 쓴다
 */
@Component
public class EnergyEvents {

    private final CoreEventPublisher publisher;

    public EnergyEvents(CoreEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void configChanged(long orgId, Scope scope, Long spaceId) {
        publisher.event(EventType.ENERGY_CONFIG_CHANGED, orgId, new EnergyConfigChanged(scope, spaceId));
    }
}
