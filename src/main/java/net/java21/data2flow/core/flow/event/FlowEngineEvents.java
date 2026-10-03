package net.java21.data2flow.core.flow.event;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.FlowApplyReported;
import net.java21.data2flow.contracts.message.event.FlowStateChanged;
import net.java21.data2flow.core.flow.repository.FlowRepository;
import net.java21.data2flow.core.flow.repository.FlowRuntimeRepository;
import net.java21.data2flow.core.messaging.service.CoreEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/**
 * 엔진 보고 반영({@code core.events}): EVT-FLW-02 인스턴스 적용 보고 → {@code flow_apply_reports}(API-FLW-02 applyStatus),
 * EVT-FLW-03 엔진 판정 상태 변경(DEGRADED·RUNAWAY·RECOVERED) → {@code flows.status}·{@code status_reason}.
 * 이벤트 조직과 플로우 조직이 다르면 버린다.
 */
@Component
public class FlowEngineEvents implements CoreEventHandler {

    private static final Logger log = LoggerFactory.getLogger(FlowEngineEvents.class);
    private static final Set<String> ENGINE_STATES = Set.of("ACTIVE", "PAUSED", "DEGRADED");

    private final FlowRepository flows;
    private final FlowRuntimeRepository runtime;
    private final Clock clock;

    public FlowEngineEvents(FlowRepository flows, FlowRuntimeRepository runtime, Clock clock) {
        this.flows = flows;
        this.runtime = runtime;
        this.clock = clock;
    }

    @Override
    public Set<EventType> types() {
        return Set.of(EventType.FLOW_APPLY_REPORTED, EventType.FLOW_STATE_CHANGED);
    }

    @Override
    public void handle(DomainEvent<?> event) {
        switch (event.payload()) {
            case FlowApplyReported p -> {
                UUID flowId = id(p.flowId());
                if (flowId == null || !sameOrganization(flowId, event.organizationId())) {
                    return;
                }
                runtime.upsertReport(event.organizationId(), flowId, p.instanceId(), p.appliedVersion(), p.overlayRevision(), p.compileMs(),
                        p.error(), event.occurredAt() == null ? clock.instant() : event.occurredAt());
            }
            case FlowStateChanged p -> {
                UUID flowId = id(p.flowId());
                if (flowId == null || !ENGINE_STATES.contains(p.to())
                        || flows.findById(event.organizationId(), flowId).filter(f -> ENGINE_STATES.contains(f.status())).isEmpty()) {
                    return;
                }
                String reason = p.reason() == null || p.reason() == FlowStateChanged.Reason.RECOVERED ? null : p.reason().name();
                flows.updateStatus(event.organizationId(), flowId, p.to(), reason, null, p.at() == null ? clock.instant() : p.at());
            }
            default -> log.trace("처리하지 않는 이벤트 {}", event.type());
        }
    }

    private boolean sameOrganization(UUID flowId, long organizationId) {
        return flows.findOrganization(flowId).filter(org -> org == organizationId).isPresent();
    }

    private static UUID id(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }
}
