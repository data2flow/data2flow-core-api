package net.java21.data2flow.core.live.domain;

import net.java21.data2flow.contracts.authz.AccessGrant;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 연결 하나가 지금 받아도 되는 것(권한·공간 범위 판정 결과, IAM-04.06). 연결을 열 때와 재검사(60초)마다 다시 만든다.
 *
 * @param grant          판정에 쓴 권한
 * @param home           {@code home} 수락
 * @param ingest         {@code ingest} 수락
 * @param spaceTopics    {@code space:{id}} 수락분: 공간 ID → 그 공간과 하위 중 범위 안 공간
 * @param telemetry      {@code telemetry:} 수락분: 기기 ID → 측정 키
 * @param messages       {@code ingest-messages} 수락 필터
 * @param payload        원본 payload를 볼 수 있다(INGEST_PAYLOAD_READ)
 * @param accepted       받은 토픽 이름
 * @param rejected       권한·범위 밖이라 이벤트가 없을 토픽 이름
 * @param sources        {@code sources} 수락(SRC_READ)
 */
public record Subscription(AccessGrant grant, boolean home, boolean ingest, Map<Long, Set<Long>> spaceTopics,
                           Map<Long, Set<String>> telemetry, List<LiveTopic.IngestMessages> messages, boolean payload,
                           List<String> accepted, List<String> rejected, boolean sources) {

    /** 이 공간의 기기 변경을 받을 space 토픽들 */
    public List<Long> spaceTopicsFor(Long deviceSpaceId) {
        if (deviceSpaceId == null || spaceTopics.isEmpty()) {
            return List.of();
        }
        return spaceTopics.entrySet().stream().filter(e -> e.getValue().contains(deviceSpaceId)).map(Map.Entry::getKey).toList();
    }

    public boolean wantsPoint(long deviceId, String metricKey) {
        Set<String> keys = telemetry.get(deviceId);
        return keys != null && keys.contains(metricKey);
    }
}
