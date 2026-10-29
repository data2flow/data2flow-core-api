package net.java21.data2flow.core.energy.event;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.EnergyBaselineTrained;
import net.java21.data2flow.core.energy.service.EnergyBaselineService;
import net.java21.data2flow.core.messaging.service.CoreEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/** EVT-ENE-02 {@code energy.baseline.trained}({@code core.events}): 기준선 버전 TRAINING → READY·FAILED(ENE-01.04) */
@Component
public class EnergyBaselineEventHandler implements CoreEventHandler {

    private static final Logger log = LoggerFactory.getLogger(EnergyBaselineEventHandler.class);

    private final EnergyBaselineService baselines;

    public EnergyBaselineEventHandler(EnergyBaselineService baselines) {
        this.baselines = baselines;
    }

    @Override
    public Set<EventType> types() {
        return Set.of(EventType.ENERGY_BASELINE_TRAINED);
    }

    @Override
    public void handle(DomainEvent<?> event) {
        if (event.payload() instanceof EnergyBaselineTrained trained && !baselines.onTrained(event.organizationId(), trained)) {
            log.info("energy.baseline.trained 무시(학습 중인 버전이 아님) version={}", trained.versionId());
        }
    }
}
