package net.java21.data2flow.core.analytics.service;

import net.java21.data2flow.core.analytics.repository.AnalysisRefRepository;
import net.java21.data2flow.core.messaging.service.RawEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/**
 * analytics 이벤트 반영({@code core.events}, 업무용 — 파드 하나가 받는다).
 * <ul>
 *   <li>EVT-ANA-01 {@code analytics.run.{status}} {@code {runId, analysisId, status, progress, trigger, errorCode?, finishedAt?}} → 색인의 최근 실행·
 *       최근 성공 실행(고정 위젯·사이트 쾌적도가 읽는다, BR-DSH-11 실패 실행 무시)</li>
 *   <li>EVT-ANA-05 {@code analytics.schedule.stopped} {@code {analysisId, ownerUserId, consecutiveFailures}} → 일정 STOPPED_BY_FAILURE
 *       (BR-ANA-11, 다음 일정 시각이 지나도 실행하지 않음). 소유자 알림 센터 알림은 알림 센터(M7) 몫</li>
 * </ul>
 * 화면 갱신(실행 스트림·고정 위젯 SSE)은 파드별 임시 큐로 따로 받는다({@link AnalysisLiveEvents}).
 */
@Component
public class AnalyticsEventHandler implements RawEventHandler {

    static final String RUN_PREFIX = "analytics.run.";
    static final String SCHEDULE_STOPPED = "analytics.schedule.stopped";
    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventHandler.class);

    private final AnalysisRefRepository refs;
    private final Clock clock;

    public AnalyticsEventHandler(AnalysisRefRepository refs, Clock clock) {
        this.refs = refs;
        this.clock = clock;
    }

    @Override
    public Set<String> bindings() {
        return Set.of(RUN_PREFIX + "*", SCHEDULE_STOPPED);
    }

    @Override
    public boolean supports(String type) {
        return type.startsWith(RUN_PREFIX) || SCHEDULE_STOPPED.equals(type);
    }

    @Override
    public void handle(long organizationId, String type, JsonNode payload) {
        Long analysisId = AnalysisService.longOrNull(payload.get("analysisId"));
        if (analysisId == null) {
            log.warn("analytics 이벤트 {}에 analysisId가 없어 무시합니다", type);
            return;
        }
        if (SCHEDULE_STOPPED.equals(type)) {
            refs.updateScheduleState(organizationId, analysisId, "STOPPED_BY_FAILURE", clock.instant());
            return;
        }
        Long runId = AnalysisService.longOrNull(payload.get("runId"));
        if (runId == null) {
            return;
        }
        String status = payload.path("status").asString(type.substring(RUN_PREFIX.length())).toUpperCase(Locale.ROOT);
        refs.recordRun(organizationId, analysisId, runId, status, instant(payload.get("finishedAt")));
    }

    static Instant instant(JsonNode v) {
        if (v == null || v.isNull() || !v.isString()) {
            return null;
        }
        try {
            return Instant.parse(v.asString());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
