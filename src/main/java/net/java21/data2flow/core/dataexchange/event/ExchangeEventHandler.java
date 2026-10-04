package net.java21.data2flow.core.dataexchange.event;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.AggregatesRecomputed;
import net.java21.data2flow.core.dataexchange.repository.ExportScheduleRepository;
import net.java21.data2flow.core.messaging.service.CoreEventHandler;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * EVT-TSD-03 {@code aggregates.recomputed}(늦은 데이터·가져오기로 지난 구간 재계산)을 받으면 그 기간을 이미 내보낸 정기 내보내기 실행을
 * stale로 표시한다. 다음 실행 때 같은 기간을 새 판(_v2)으로 다시 만든다(AT-TSD-17.2, BR-TSD-28).
 */
@Component
public class ExchangeEventHandler implements CoreEventHandler {

    static final Duration LOOKBACK = Duration.ofDays(31);

    private final ExportScheduleRepository schedules;
    private final Clock clock;

    public ExchangeEventHandler(ExportScheduleRepository schedules, Clock clock) {
        this.schedules = schedules;
        this.clock = clock;
    }

    @Override
    public Set<EventType> types() {
        return Set.of(EventType.AGGREGATES_RECOMPUTED);
    }

    @Override
    public void handle(DomainEvent<?> event) {
        if (event.payload() instanceof AggregatesRecomputed r) {
            Instant now = clock.instant();
            for (AggregatesRecomputed.Item item : r.items()) {
                schedules.updateStaleOverlapping(event.organizationId(), item.from(), item.to(), now.minus(LOOKBACK), now);
            }
        }
    }
}
