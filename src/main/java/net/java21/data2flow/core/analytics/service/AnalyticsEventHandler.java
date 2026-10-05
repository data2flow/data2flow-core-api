package net.java21.data2flow.core.analytics.service;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.AnalyticsRunStatusChanged;
import net.java21.data2flow.contracts.message.event.AnalyticsScheduleStopped;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository;
import net.java21.data2flow.core.messaging.service.CoreEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * analytics 이벤트 반영({@code core.events}, 업무용 — 파드 하나가 받는다). 페이로드는 계약 모듈 타입(ANA-06.02)이다.
 * <ul>
 *   <li>EVT-ANA-01 {@code analytics.run.{status}} {@link AnalyticsRunStatusChanged} → 색인의 최근 실행·최근 성공 실행(고정 위젯·
 *       사이트 쾌적도가 읽는다, BR-DSH-11 실패 실행 무시)</li>
 *   <li>EVT-ANA-05 {@code analytics.schedule.stopped} {@link AnalyticsScheduleStopped} → 일정 STOPPED_BY_FAILURE
 *       (BR-ANA-11, 다음 일정 시각이 지나도 실행하지 않음). 소유자 알림 센터 알림은 알림 센터(M7) 몫</li>
 * </ul>
 * 화면 갱신(실행 스트림·고정 위젯 SSE)은 파드별 임시 큐로 따로 받는다({@code LiveEventsConfig}).
 */
@Component
public class AnalyticsEventHandler implements CoreEventHandler {

    /** EVT-ANA-01 상태별 종류 전부 */
    public static final Set<EventType> RUN_TYPES = Collections.unmodifiableSet(Arrays.stream(EventType.values())
            .filter(t -> t.payloadType() == AnalyticsRunStatusChanged.class)
            .collect(() -> EnumSet.noneOf(EventType.class), Set::add, Set::addAll));
    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventHandler.class);

    private final AnalysisRefRepository refs;
    private final Clock clock;

    public AnalyticsEventHandler(AnalysisRefRepository refs, Clock clock) {
        this.refs = refs;
        this.clock = clock;
    }

    @Override
    public Set<EventType> types() {
        Set<EventType> types = EnumSet.copyOf(RUN_TYPES);
        types.add(EventType.ANALYTICS_SCHEDULE_STOPPED);
        return types;
    }

    @Override
    public void handle(DomainEvent<?> event) {
        long organizationId = event.organizationId();
        if (event.payload() instanceof AnalyticsScheduleStopped stopped) {
            Long analysisId = stopped.analysisIdAsLong();
            if (analysisId == null) {
                log.warn("analytics.schedule.stopped의 analysisId가 숫자가 아니라 무시합니다: {}", stopped.analysisId());
                return;
            }
            refs.updateScheduleState(organizationId, analysisId, "STOPPED_BY_FAILURE", clock.instant());
            return;
        }
        if (event.payload() instanceof AnalyticsRunStatusChanged run) {
            Long analysisId = run.analysisIdAsLong();
            Long runId = run.runIdAsLong();
            if (analysisId == null || runId == null) {
                log.warn("{}의 analysisId·runId가 숫자가 아니라 무시합니다", event.type());
                return;
            }
            String status = run.status() == AnalyticsRunStatusChanged.Status.UNKNOWN
                    ? event.type().substring(EventType.ANALYTICS_RUN_PREFIX.length()).toUpperCase(java.util.Locale.ROOT)
                    : run.status().name();
            refs.recordRun(organizationId, analysisId, runId, status, run.finishedAt());
        }
    }
}
