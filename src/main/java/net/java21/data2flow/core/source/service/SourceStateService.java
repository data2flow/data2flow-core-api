package net.java21.data2flow.core.source.service;

import net.java21.data2flow.contracts.connector.ConnectionErrorKind;
import net.java21.data2flow.contracts.connector.ConnectorState;
import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.SourceConnectionChanged;
import net.java21.data2flow.contracts.message.event.SourceDataActivity;
import net.java21.data2flow.core.common.ConfigVersions;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import net.java21.data2flow.core.source.domain.ConnectionStates;
import net.java21.data2flow.core.source.domain.ConnectionStates.Representative;
import net.java21.data2flow.core.source.domain.SourceModels;
import net.java21.data2flow.core.source.domain.SourceModels.StateRow;
import net.java21.data2flow.core.source.repository.SourceHealthRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 소스 상태 반영(호출하는 쪽 트랜잭션 안에서):
 * <ul>
 *   <li>{@link #recompute}: 인스턴스 보고로 대표 상태를 다시 계산하고, 바뀌면 {@code source_states}에 저장하고 EVT-DSC-04
 *       {@code source.connection.changed}를 아웃박스에 넣는다</li>
 *   <li>{@link #configChanged}: 설정이 바뀌면 SOURCES 버전을 올리고 EVT-DSC-01({@code ConfigChangedMessage} SOURCE)을 보낸다
 *       (ingress는 해당 소스 연결만 다시 맺는다, BR-DSC-05)</li>
 *   <li>{@link #checkNoData}·{@link #dataReceived}: 무수신 시작·재개 EVT-DSC-05</li>
 * </ul>
 */
@Component
public class SourceStateService {

    private final SourceHealthRepository health;
    private final CoreEventPublisher publisher;
    private final ConfigVersions versions;
    private final Clock clock;

    public SourceStateService(SourceHealthRepository health, CoreEventPublisher publisher, ConfigVersions versions, Clock clock) {
        this.health = health;
        this.publisher = publisher;
        this.versions = versions;
        this.clock = clock;
    }

    /** 대표 상태 재계산. 바뀌었으면 새 상태 */
    public Representative recompute(long organizationId, long sourceId, String lifecycle) {
        Instant now = clock.instant();
        StateRow state = health.lockState(organizationId, sourceId);
        Representative rep = ConnectionStates.representative(lifecycle, health.findRuntimes(organizationId, sourceId),
                state.activatedAt(), now);
        boolean changed = !rep.state().equals(state.connectionState()) || !Objects.equals(rep.errorKind(), state.errorKind());
        if (changed) {
            health.updateConnectionState(organizationId, sourceId, rep.state(), rep.errorKind(), now, now);
            publisher.event(EventType.SOURCE_CONNECTION_CHANGED, organizationId, new SourceConnectionChanged(sourceId,
                    ConnectorState.valueOf(state.connectionState()), ConnectorState.valueOf(rep.state()),
                    rep.errorKind() == null ? null : ConnectionErrorKind.valueOf(rep.errorKind()), now));
        }
        return rep;
    }

    /** lifecycle이 ACTIVE가 됐다: 무수신 기준점과 대표 상태(CONNECTING) */
    public void activated(long organizationId, long sourceId) {
        Instant now = clock.instant();
        health.lockState(organizationId, sourceId);
        health.updateActivatedAt(organizationId, sourceId, now, now);
        recompute(organizationId, sourceId, SourceModels.ACTIVE);
    }

    /** 설정 변경 알림(EVT-DSC-01)과 실행 설정 버전 */
    public void configChanged(long organizationId, long sourceId, long version) {
        versions.bump(organizationId, ConfigVersions.SOURCES);
        publisher.configChanged(EntityType.SOURCE, sourceId, version, organizationId);
    }

    public void configDeleted(long organizationId, long sourceId, long version) {
        versions.bump(organizationId, ConfigVersions.SOURCES);
        publisher.configDeleted(EntityType.SOURCE, sourceId, version, organizationId);
    }

    /**
     * 무수신 판정(BR-DSC-08 기준값, EVT-DSC-05). 마지막 수신은 분 단위라 그 분이 끝난 시각(+60초)부터 잰다(일찍 울리지 않게).
     * 한 번도 받지 못했으면 ACTIVE가 된 시각부터 잰다.
     *
     * @return 무수신이 새로 시작됐으면 true
     */
    public boolean checkNoData(long organizationId, long sourceId, int thresholdSec) {
        Instant now = clock.instant();
        StateRow state = health.lockState(organizationId, sourceId);
        if (state.noData()) {
            return false;
        }
        Instant lastActivity = state.lastReceivedAt() == null ? state.activatedAt() : state.lastReceivedAt().plus(Duration.ofMinutes(1));
        if (state.activatedAt() != null && lastActivity != null && state.activatedAt().isAfter(lastActivity)) {
            lastActivity = state.activatedAt();
        }
        if (lastActivity == null || !now.isAfter(lastActivity.plusSeconds(thresholdSec))) {
            return false;
        }
        health.updateNoData(organizationId, sourceId, true, now);
        publisher.event(EventType.SOURCE_NO_DATA, organizationId, new SourceDataActivity(sourceId, state.lastReceivedAt(), thresholdSec));
        return true;
    }

    /** 수신이 있었다(EVT-DSC-03 received &gt; 0). 무수신 중이었으면 재개 이벤트 */
    public void dataReceived(long organizationId, long sourceId, Instant minute, int thresholdSec) {
        Instant now = clock.instant();
        StateRow state = health.lockState(organizationId, sourceId);
        health.updateLastReceived(organizationId, sourceId, minute, now);
        if (state.noData()) {
            health.updateNoData(organizationId, sourceId, false, now);
            Instant last = state.lastReceivedAt() == null || minute.isAfter(state.lastReceivedAt()) ? minute : state.lastReceivedAt();
            publisher.event(EventType.SOURCE_DATA_RESUMED, organizationId, new SourceDataActivity(sourceId, last, thresholdSec));
        }
    }
}
