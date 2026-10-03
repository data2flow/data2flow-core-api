package net.java21.data2flow.core.live.service;

import net.java21.data2flow.contracts.authz.AccessGrant;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.core.live.domain.LiveTopic;
import net.java21.data2flow.core.live.domain.Subscription;
import net.java21.data2flow.core.live.repository.LiveRepository;
import net.java21.data2flow.core.live.repository.LiveRepository.DeviceRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 토픽별 권한 판정(API-DSH-20 "토픽별로 다시 검사", IAM-04.06). 서버가 강제하고 기본은 거부한다.
 *
 * <ul>
 *   <li>{@code home}: DASHBOARD_READ(VIEWER 이상). 수치는 홈 요약이 공간 범위로 센다</li>
 *   <li>{@code space:{id}}: DASHBOARD_READ + 그 공간(또는 하위 일부)이 범위 안. 범위 안 하위 공간의 기기만 보낸다</li>
 *   <li>{@code telemetry:{기기}.{항목}}: TS_READ + 기기의 공간이 범위 안(TC-DSH-051: 범위 밖 기기 토픽은 이벤트 0건)</li>
 *   <li>{@code ingest}·{@code ingest-messages}: INGEST_READ(OPERATOR 이상). 원본 payload는 INGEST_PAYLOAD_READ만</li>
 * </ul>
 * 거부한 토픽은 이유를 밝히지 않고 {@code rejected}에 둔다(없는 것과 범위 밖을 구분하지 않음, BR-IAM-16).
 */
@Component
public class LiveSubscriptions {

    private final LiveRepository repository;

    public LiveSubscriptions(LiveRepository repository) {
        this.repository = repository;
    }

    public Subscription resolve(long organizationId, AccessGrant grant, List<LiveTopic> topics) {
        SpaceScope scope = grant.spaceScope();
        boolean home = false;
        boolean ingest = false;
        Map<Long, Set<Long>> spaces = new HashMap<>();
        Map<Long, Set<String>> telemetry = new HashMap<>();
        List<LiveTopic.IngestMessages> messages = new ArrayList<>();
        List<String> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        Map<Long, Optional<DeviceRef>> devices = new HashMap<>();
        for (LiveTopic topic : topics) {
            boolean ok = switch (topic) {
                case LiveTopic.Home h -> home = grant.has(Permission.DASHBOARD_READ);
                case LiveTopic.Ingest i -> ingest = grant.has(Permission.INGEST_READ);
                case LiveTopic.Future f -> grant.has(Permission.DASHBOARD_READ);
                case LiveTopic.Space s -> {
                    if (!grant.has(Permission.DASHBOARD_READ)) {
                        yield false;
                    }
                    Set<Long> visible = new LinkedHashSet<>();
                    for (Long id : repository.findSubtreeIds(organizationId, s.spaceId())) {
                        if (scope.unrestricted() || scope.allowedSpaceIds().contains(id)) {
                            visible.add(id);
                        }
                    }
                    if (visible.isEmpty()) {
                        yield false;
                    }
                    spaces.put(s.spaceId(), visible);
                    yield true;
                }
                case LiveTopic.Telemetry t -> {
                    if (!grant.has(Permission.TS_READ)) {
                        yield false;
                    }
                    Optional<DeviceRef> device = devices.computeIfAbsent(t.deviceId(), id -> repository.findDevice(organizationId, id));
                    if (device.isEmpty() || !scope.includes(device.get().spaceId())) {
                        yield false;
                    }
                    telemetry.computeIfAbsent(t.deviceId(), k -> new HashSet<>()).add(t.metricKey());
                    yield true;
                }
                case LiveTopic.IngestMessages m -> {
                    if (!grant.has(Permission.INGEST_READ) || !messagesTargetVisible(organizationId, scope, m, devices)) {
                        yield false;
                    }
                    messages.add(m);
                    yield true;
                }
            };
            (ok ? accepted : rejected).add(topic.raw());
        }
        return new Subscription(grant, home, ingest, spaces, telemetry, List.copyOf(messages),
                grant.has(Permission.INGEST_PAYLOAD_READ), List.copyOf(accepted), List.copyOf(rejected));
    }

    /** 수집 메시지 필터의 소스·기기가 이 조직에 있고(기기는 범위 안) 볼 수 있는가 */
    boolean messagesTargetVisible(long organizationId, SpaceScope scope, LiveTopic.IngestMessages m,
                                  Map<Long, Optional<DeviceRef>> devices) {
        if (m.sourceId() != null && !repository.existsSource(organizationId, m.sourceId())) {
            return false;
        }
        if (m.deviceId() != null) {
            Optional<DeviceRef> device = devices.computeIfAbsent(m.deviceId(), id -> repository.findDevice(organizationId, id));
            return device.isPresent() && scope.includes(device.get().spaceId());
        }
        return true;
    }

    public boolean sourceExists(long organizationId, long sourceId) {
        return repository.existsSource(organizationId, sourceId);
    }

    /** 연결을 열 때만: 수집 메시지 필터의 소스·기기가 없거나 범위 밖이면 404로 거부한다(TC-DSH-022 "범위 밖 404") */
    public Optional<LiveTopic.IngestMessages> firstMissingMessageTarget(long organizationId, AccessGrant grant, List<LiveTopic> topics) {
        Map<Long, Optional<DeviceRef>> devices = new HashMap<>();
        for (LiveTopic topic : topics) {
            if (topic instanceof LiveTopic.IngestMessages m && grant.has(Permission.INGEST_READ)
                    && !messagesTargetVisible(organizationId, grant.spaceScope(), m, devices)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }
}
