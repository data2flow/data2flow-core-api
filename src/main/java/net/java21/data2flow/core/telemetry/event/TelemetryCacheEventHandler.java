package net.java21.data2flow.core.telemetry.event;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.AggregatesRecomputed;
import net.java21.data2flow.contracts.message.event.ImportCompleted;
import net.java21.data2flow.contracts.message.event.RetentionPurged;
import net.java21.data2flow.core.messaging.service.CoreEventHandler;
import net.java21.data2flow.core.telemetry.service.TelemetryCache;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.TreeSet;

/**
 * 집계 조회 캐시 무효화(TSD-06.04, BR-TSD-20, AT-TSD-13.3): EVT-TSD-03 {@code aggregates.recomputed}면 그 기기·공간 집계 캐시,
 * EVT-TSD-04 {@code retention.purged}면 조직 전체, EVT-TSD-02 {@code import.completed}면 가져온 기기 캐시를 지운다.
 */
@Component
public class TelemetryCacheEventHandler implements CoreEventHandler {

    private final TelemetryCache cache;

    public TelemetryCacheEventHandler(TelemetryCache cache) {
        this.cache = cache;
    }

    @Override
    public Set<EventType> types() {
        return Set.of(EventType.AGGREGATES_RECOMPUTED, EventType.RETENTION_PURGED, EventType.IMPORT_COMPLETED);
    }

    @Override
    public void handle(DomainEvent<?> event) {
        if (!cache.enabled()) {
            return;
        }
        switch (event.payload()) {
            case AggregatesRecomputed r -> {
                Set<Long> devices = new TreeSet<>();
                r.items().forEach(i -> devices.add(i.deviceId()));
                cache.invalidateDevices(event.organizationId(), devices);
            }
            case RetentionPurged p -> cache.invalidateOrganization(event.organizationId());
            case ImportCompleted i -> cache.invalidateDevices(event.organizationId(), i.deviceIds());
            default -> {
            }
        }
    }
}
