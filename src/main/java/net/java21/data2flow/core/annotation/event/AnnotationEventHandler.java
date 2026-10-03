package net.java21.data2flow.core.annotation.event;

import net.java21.data2flow.contracts.message.DomainEvent;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.DeviceConnectivityChanged;
import net.java21.data2flow.contracts.message.event.DeviceConnectivityChanged.Connectivity;
import net.java21.data2flow.contracts.message.event.IngestAlert;
import net.java21.data2flow.core.annotation.domain.AnnotationModels;
import net.java21.data2flow.core.annotation.repository.AnnotationRepository;
import net.java21.data2flow.core.messaging.service.CoreEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * 시스템 주석 생성기(TSD-01.04, BR-TSD-23): 원천 이벤트로 주석을 만들고, 원천이 해제되면 time_to를 채운다.
 *
 * <ul>
 *   <li>{@code device.connectivity.changed}(EVT-DEV-02) → OFFLINE: 마지막 수신 시각부터 시작하는 기기 주석, ONLINE이 오면 그 시각으로 닫는다</li>
 *   <li>{@code ingest.alert.raised}·{@code cleared}(EVT-ING-05, code=SCRIPT_ERROR_RATE = EVT-SCR-02) → SCRIPT_ERROR: 조직 전체 주석
 *       (ref {@code script:<id>}), cleared로 닫는다. 그 밖의 수집 알람 코드는 무시한다</li>
 * </ul>
 * 같은 원천의 열린 주석이 이미 있으면 새로 만들지 않는다(재전송·중복 판정에도 하나). 알람(ALARM)·이상 탐지(ANOMALY) 등은 같은 방식으로
 * 해당 마일스톤에서 붙인다. 주석 이벤트 EVT-TSD-05는 contracts에 종류가 없어 발행하지 않는다.
 */
@Component
public class AnnotationEventHandler implements CoreEventHandler {

    static final String OFFLINE_TITLE = "기기 오프라인";
    private static final Logger log = LoggerFactory.getLogger(AnnotationEventHandler.class);

    private final AnnotationRepository annotations;
    private final Clock clock;

    public AnnotationEventHandler(AnnotationRepository annotations, Clock clock) {
        this.annotations = annotations;
        this.clock = clock;
    }

    @Override
    public Set<EventType> types() {
        return Set.of(EventType.DEVICE_CONNECTIVITY_CHANGED, EventType.INGEST_ALERT_RAISED, EventType.INGEST_ALERT_CLEARED);
    }

    @Override
    public void handle(DomainEvent<?> event) {
        long orgId = event.organizationId();
        switch (event.payload()) {
            case DeviceConnectivityChanged c -> connectivity(orgId, c, event.occurredAt());
            case IngestAlert a when IngestAlert.SCRIPT_ERROR_RATE.equals(a.code()) && a.scriptId() != null ->
                    scriptAlert(orgId, a, event.eventType() == EventType.INGEST_ALERT_RAISED, event.occurredAt());
            default -> log.debug("주석 대상이 아닌 이벤트: {}", event.type());
        }
    }

    private void connectivity(long orgId, DeviceConnectivityChanged c, Instant occurredAt) {
        if (annotations.findDevice(orgId, c.deviceId()).isEmpty()) {
            return; // 지워진 기기·다른 조직: 주석을 만들지 않는다(FK)
        }
        var open = annotations.findOpen(orgId, "OFFLINE", c.deviceId(), null);
        if (c.to() == Connectivity.OFFLINE) {
            if (open.isEmpty()) {
                Instant from = c.lastSeenAt() != null ? c.lastSeenAt() : occurredAt;
                annotations.insert(orgId, from, null, c.deviceId(), null, null, "OFFLINE", OFFLINE_TITLE,
                        "device:" + c.deviceId(), null, clock.instant());
            }
        } else {
            open.ifPresent(id -> annotations.updateEnd(orgId, id, occurredAt));
        }
    }

    private void scriptAlert(long orgId, IngestAlert a, boolean raised, Instant occurredAt) {
        String ref = "script:" + a.scriptId();
        var open = annotations.findOpen(orgId, "SCRIPT_ERROR", null, ref);
        Instant at = a.at() != null ? a.at() : occurredAt;
        if (!raised) {
            open.ifPresent(id -> annotations.updateEnd(orgId, id, at));
            return;
        }
        if (open.isPresent()) {
            return;
        }
        String name = annotations.findScriptName(orgId, a.scriptId()).orElse("#" + a.scriptId());
        double rate = a.errorRate() != null ? a.errorRate() : a.value();
        String title = String.format(Locale.ROOT, "스크립트 오류율 %.1f%% — %s%s", rate * 100, name,
                Boolean.TRUE.equals(a.autoDisabled()) ? " (자동 비활성)" : "");
        if (title.length() > AnnotationModels.MAX_TITLE) {
            title = title.substring(0, AnnotationModels.MAX_TITLE);
        }
        annotations.insert(orgId, at, null, null, null, null, "SCRIPT_ERROR", title, ref, null, clock.instant());
    }
}
