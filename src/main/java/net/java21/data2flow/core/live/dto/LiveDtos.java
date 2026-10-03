package net.java21.data2flow.core.live.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** 실시간 스트림 이벤트 본문(API-DSH-20·21). 이벤트 이름은 각 레코드 설명에 있다. ID는 문자열, 시각은 UTC */
public final class LiveDtos {

    private LiveDtos() {
    }

    /** {@code ready}: 연결 직후 한 번. accepted는 받은 토픽, rejected는 권한·범위 밖이라 이벤트가 없을 토픽(이유는 밝히지 않는다) */
    public record Ready(List<String> accepted, List<String> rejected, Instant at) {
    }

    /** {@code ping}: 15초마다 */
    public record Ping(Instant at) {
    }

    /** {@code session-revoked}: 보낸 뒤 연결을 닫는다(IAM-07.06). reason: SESSION_REVOKED·USER_INACTIVE */
    public record SessionRevoked(String reason) {
    }

    /** {@code device-update}(space:{id}) — 측정값이 들어오면 metrics, 연결 상태가 바뀌면 connection, 기기 상태가 바뀌면 state */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeviceUpdate(String deviceId, List<MetricUpdate> metrics, String connection, String state) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MetricUpdate(String key, double value, String unit, int quality, Instant at) {
    }

    /**
     * {@code source-state}(sources) — 소스 대표 연결 상태 변경(EVT-DSC-04). state·previousState는 CONNECTED·CONNECTING·DISCONNECTED·
     * ERROR·DISABLED, errorKind는 실패 종류(정상이면 생략)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceState(String sourceId, String state, String previousState, String errorKind, Instant at) {
    }

    /** {@code point}(telemetry:{deviceId}.{metric}) */
    public record Point(String deviceId, String metricKey, Instant t, double v, int quality, boolean virtual) {
    }

    /**
     * {@code message}(ingest-messages). raw는 원본 payload 앞 4KB(INGEST_PAYLOAD_READ — INTEGRATOR·ADMIN만, 없으면 생략).
     * rawEncoding: TEXT(JSON·텍스트) 또는 BASE64(바이너리). canonical은 pipeline 처리 기록에 표준 메시지 요약이 있을 때만
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IngestMessage(String id, Instant receivedAt, String sourceId, String topic, String deviceId, String externalId,
                                String result, String errorCode, String raw, String rawEncoding, Boolean rawTruncated,
                                Object canonical) {
    }
}
