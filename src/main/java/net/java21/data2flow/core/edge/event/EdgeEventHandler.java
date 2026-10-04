package net.java21.data2flow.core.edge.event;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.EdgeEvent;
import net.java21.data2flow.core.edge.service.EdgeInternalService;
import net.java21.data2flow.core.messaging.service.CoreEventHandler;
import org.springframework.stereotype.Component;

import java.util.Set;

/** EVT-DSC-10 {@code edge.status.changed}·{@code edge.config.applied}·{@code edge.buffer.dropped}(생산 ingress) 소비 → 엣지 상태 반영(DSC-08.02·08.03) */
@Component
public class EdgeEventHandler implements CoreEventHandler {

    private final EdgeInternalService edges;

    public EdgeEventHandler(EdgeInternalService edges) {
        this.edges = edges;
    }

    @Override
    public Set<EventType> types() {
        return Set.of(EventType.EDGE_STATUS_CHANGED, EventType.EDGE_CONFIG_APPLIED, EventType.EDGE_BUFFER_DROPPED);
    }

    @Override
    public void handle(DomainEvent<?> event) {
        if (event.payload() instanceof EdgeEvent e) {
            edges.onEvent(event.organizationId(), event.type(), e);
        }
    }
}
