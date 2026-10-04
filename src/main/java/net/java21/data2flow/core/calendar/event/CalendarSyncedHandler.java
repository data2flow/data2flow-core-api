package net.java21.data2flow.core.calendar.event;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.CalendarSynced;
import net.java21.data2flow.core.calendar.domain.CalendarModels;
import net.java21.data2flow.core.calendar.service.CalendarSyncService;
import net.java21.data2flow.core.external.repository.ContextSourceRepository;
import net.java21.data2flow.core.external.repository.ContextSourceRepository.ContextSource;
import net.java21.data2flow.core.messaging.service.CoreEventHandler;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * EVT-DSC-07 {@code calendar.synced}(ingress의 iCal·공휴일 수집 결과) → 조직 달력 반영(BR-DSC-18). 소스는 이벤트 조직의 HOLIDAY·ICAL 소스여야 하고
 * (아니면 버림), 적용 범위는 소스의 사이트다. core가 직접 동기화한 결과와 같은 규칙({@link CalendarSyncService})을 쓴다.
 */
@Component
public class CalendarSyncedHandler implements CoreEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncedHandler.class);

    private final ContextSourceRepository sources;
    private final CalendarSyncService calendar;
    private final DeploymentOrganization deployment;

    public CalendarSyncedHandler(ContextSourceRepository sources, CalendarSyncService calendar, DeploymentOrganization deployment) {
        this.sources = sources;
        this.calendar = calendar;
        this.deployment = deployment;
    }

    @Override
    public Set<EventType> types() {
        return Set.of(EventType.CALENDAR_SYNCED);
    }

    @Override
    public void handle(DomainEvent<?> event) {
        if (!(event.payload() instanceof CalendarSynced synced)) {
            return;
        }
        ContextSource source = sources.findInternal(synced.sourceId(), deployment.restriction())
                .filter(s -> s.organizationId() == event.organizationId())
                .filter(s -> "HOLIDAY".equals(s.type()) || "ICAL".equals(s.type()))
                .orElse(null);
        if (source == null) {
            log.warn("calendar.synced: 알 수 없는 소스 {}(조직 {}) — 버립니다", synced.sourceId(), event.organizationId());
            return;
        }
        String origin = "HOLIDAY".equals(source.type()) ? CalendarModels.HOLIDAY_API : CalendarModels.ICAL;
        List<Long> scope = source.siteId() == null ? List.of() : List.of(source.siteId());
        calendar.apply(source.organizationId(), source.id(), origin, scope, synced.events(), synced.removedUids());
    }
}
