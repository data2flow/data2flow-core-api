package net.java21.data2flow.core.analytics.service;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.test.message.MessageFixtures;
import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** ANA-06.02 TC-ANA-140: analytics(Python)가 보낸 바이트(공유 픽스처)를 계약 타입으로 읽어 색인에 반영한다 */
class AnalyticsEventHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private final AnalysisRefRepository refs = mock(AnalysisRefRepository.class);
    private final AnalyticsEventHandler handler = new AnalyticsEventHandler(refs, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("[ANA-06.02][TC-ANA-140] EVT-ANA-01 7종과 EVT-ANA-05를 받는다(계약 타입 바인딩)")
    void types() {
        assertThat(handler.types()).contains(EventType.ANALYTICS_RUN_QUEUED, EventType.ANALYTICS_RUN_SUCCEEDED, EventType.ANALYTICS_RUN_CANCELLED,
                EventType.ANALYTICS_SCHEDULE_STOPPED).hasSize(8);
    }

    @Test
    @DisplayName("[ANA-06.02][BR-DSH-11] analytics.run.succeeded 픽스처 → 최근 실행(SUCCEEDED, finishedAt), 실패 픽스처 → FAILED")
    void runStatus() {
        handler.handle(MessageFixtures.domainEvent("analytics-run-succeeded"));
        verify(refs).recordRun(1, 12, 41, "SUCCEEDED", Instant.parse("2026-10-05T00:00:42Z"));
        handler.handle(MessageFixtures.domainEvent("analytics-run-failed"));
        verify(refs).recordRun(1, 12, 42, "FAILED", Instant.parse("2026-10-05T00:00:42Z"));
        handler.handle(MessageFixtures.domainEvent("analytics-run-pending"));
        verify(refs).recordRun(1, 12, 41, "PENDING", null);
    }

    @Test
    @DisplayName("[ANA-06.02][BR-ANA-11] analytics.schedule.stopped 픽스처 → 일정 STOPPED_BY_FAILURE")
    void scheduleStopped() {
        handler.handle(MessageFixtures.domainEvent("analytics-schedule-stopped"));
        verify(refs).updateScheduleState(1, 12, "STOPPED_BY_FAILURE", NOW);
    }

    @Test
    @DisplayName("[ANA-06.02] 숫자가 아닌 ID·다른 페이로드는 무시한다")
    void ignoresNonNumericIds() {
        handler.handle(DomainEvent.of(EventType.ANALYTICS_RUN_RUNNING, 1,
                new net.java21.data2flow.contracts.message.event.AnalyticsRunStatusChanged("r-1", "12",
                        net.java21.data2flow.contracts.message.event.AnalyticsRunStatusChanged.Status.RUNNING, 5,
                        net.java21.data2flow.contracts.message.event.AnalyticsRunStatusChanged.Trigger.API, "LOAD", null, null),
                null, Clock.fixed(NOW, ZoneOffset.UTC)));
        handler.handle(DomainEvent.of(EventType.ANALYTICS_SCHEDULE_STOPPED, 1,
                new net.java21.data2flow.contracts.message.event.AnalyticsScheduleStopped("x", "7", 3), null, Clock.fixed(NOW, ZoneOffset.UTC)));
        handler.handle(MessageFixtures.domainEvent("analytics-model-drift"));
        verify(refs, never()).recordRun(anyLong(), anyLong(), anyLong(), anyString(), any());
        verify(refs, never()).updateScheduleState(anyLong(), anyLong(), anyString(), any());
    }
}
