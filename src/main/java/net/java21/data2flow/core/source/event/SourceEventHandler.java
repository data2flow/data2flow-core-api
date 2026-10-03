package net.java21.data2flow.core.source.event;

import net.java21.data2flow.contracts.connector.ConnectorCatalogEntry;
import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.ConnectorCatalogReported;
import net.java21.data2flow.contracts.message.event.SourceRuntimeReported;
import net.java21.data2flow.contracts.message.event.SourceStatsReported;
import net.java21.data2flow.core.source.domain.SourceModels.DataSource;
import net.java21.data2flow.core.source.domain.SourceModels.RuntimeRow;
import net.java21.data2flow.core.messaging.service.CoreEventHandler;
import net.java21.data2flow.core.source.repository.ConnectorCatalogRepository;
import net.java21.data2flow.core.source.repository.DataSourceRepository;
import net.java21.data2flow.core.source.repository.SourceHealthRepository;
import net.java21.data2flow.core.source.service.SourceStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ingress·pipeline 보고 소비({@code core.events}, 한 메시지 한 트랜잭션 + messageId 중복 제거):
 * <ul>
 *   <li>EVT-DSC-02 {@code source.runtime.reported} → {@code source_runtimes} 저장, 대표 상태가 바뀌면 EVT-DSC-04</li>
 *   <li>EVT-DSC-03 {@code source.stats.1m} → {@code source_stat_1m}에 더하기(ingress 수신 수 + pipeline 오류 수), 마지막 수신 시각,
 *       무수신 중이었으면 EVT-DSC-05 재개</li>
 *   <li>EVT-DSC-09 {@code connector.catalog.reported} → {@code connector_catalogs} 갱신(BR-DSC-23)</li>
 * </ul>
 * 보고의 소스가 그 조직에 없으면(삭제됨·잘못된 조직) 무시한다.
 */
@Component
public class SourceEventHandler implements CoreEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SourceEventHandler.class);
    /** 보관 기간(7일)보다 오래된 분 지표는 받지 않는다 */
    static final Duration STATS_RETENTION = Duration.ofDays(7);

    private final DataSourceRepository sources;
    private final SourceHealthRepository health;
    private final ConnectorCatalogRepository catalog;
    private final SourceStateService states;
    private final JsonMapper json;
    private final Clock clock;

    public SourceEventHandler(DataSourceRepository sources, SourceHealthRepository health, ConnectorCatalogRepository catalog,
                              SourceStateService states, JsonMapper json, Clock clock) {
        this.sources = sources;
        this.health = health;
        this.catalog = catalog;
        this.states = states;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Set<EventType> types() {
        return Set.of(EventType.SOURCE_RUNTIME_REPORTED, EventType.SOURCE_STATS_1M, EventType.CONNECTOR_CATALOG_REPORTED);
    }

    @Override
    public void handle(DomainEvent<?> event) {
        switch (event.payload()) {
            case SourceRuntimeReported r -> runtime(event.organizationId(), event.occurredAt(), r);
            case SourceStatsReported s -> stats(event.organizationId(), s);
            case ConnectorCatalogReported c -> catalog(c);
            default -> log.debug("처리하지 않는 페이로드: {}", event.type());
        }
    }

    void runtime(long orgId, Instant occurredAt, SourceRuntimeReported r) {
        Optional<DataSource> source = sources.findById(orgId, r.sourceId());
        if (source.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        // 보고 시각은 ingress 시계(occurredAt)지만 미래로 앞서면 지금으로 자른다(90초 판정이 어긋나지 않게)
        Instant reportedAt = occurredAt.isAfter(now) ? now : occurredAt;
        health.upsertRuntime(orgId, new RuntimeRow(r.sourceId(), r.instanceId(), r.state().name(),
                r.errorKind() == null ? null : r.errorKind().name(), r.errorMessage(), r.clientId(), r.connectedSince(),
                r.reconnects24h(), reportedAt));
        states.recompute(orgId, r.sourceId(), source.get().lifecycle());
    }

    void stats(long orgId, SourceStatsReported s) {
        Optional<DataSource> source = sources.findById(orgId, s.sourceId());
        if (source.isEmpty()) {
            return;
        }
        Instant minute = s.minute().truncatedTo(ChronoUnit.MINUTES);
        if (minute.isBefore(clock.instant().minus(STATS_RETENTION))) {
            return;
        }
        Map<String, Long> columns = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : s.counters().entrySet()) {
            String column = SourceHealthRepository.COUNTER_COLUMNS.get(e.getKey());
            if (column != null && e.getValue() != null && e.getValue() > 0) {
                columns.merge(column, e.getValue(), Long::sum);
            }
        }
        health.addStats(orgId, s.sourceId(), minute, columns);
        if (columns.getOrDefault("received", 0L) > 0) {
            states.dataReceived(orgId, s.sourceId(), minute, source.get().noDataAlarmAfterSec());
        }
    }

    void catalog(ConnectorCatalogReported c) {
        Instant now = clock.instant();
        for (ConnectorCatalogEntry e : c.connectors()) {
            if (e.key() == null || e.schema() == null || e.category() == null || e.ackMode() == null || e.scaling() == null) {
                continue;
            }
            catalog.upsertReported(e.key(), e.name(), e.version(), e.category().name(), json.writeValueAsString(e.schema()),
                    e.authMethods().stream().map(Enum::name).sorted().toList(),
                    e.payloadFormats().stream().map(Enum::name).sorted().toList(), e.ackMode().name(), e.scaling().name(),
                    e.supportsSend(), c.instanceId(), now);
        }
        log.info("커넥터 카탈로그 보고 반영: {} {}개", c.instanceId(), c.connectors().size());
    }
}
