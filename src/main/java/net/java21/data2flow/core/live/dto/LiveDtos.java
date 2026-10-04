package net.java21.data2flow.core.live.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    /**
     * {@code device-update}(space:{id}) — 액추에이터 보고 상태가 바뀐 경우(EVT-ACT-02 {@code device.state.changed}, DSH-api API-DSH-20).
     * 상태만 바뀐 이벤트라 {@code metrics} 없이 {@code state}만 싣는다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActuatorUpdate(String deviceId, String connection, ActuatorState state) {
    }

    /** {@code device-update.state}: {reported, delta, reportedVersion, origin(COMMAND·DEVICE_LOCAL·RECONNECT), at} */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActuatorState(Map<String, Map<String, Object>> reported, Map<String, Map<String, Object>> delta, long reportedVersion,
                                String origin, Instant at) {
    }

    /**
     * {@code command-status}(commands:{deviceId}) — 명령 상태 변경(EVT-ACT-01, DSH-api API-DSH-20): reason은 상태 사유(예: TIMEOUT_ACK),
     * message는 차단 사유 문구. source에 core가 flowName·userName을 붙인다(ADR-043)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CommandStatus(String commandId, String deviceId, String capability, String command, String status, String reason,
                                String message, Map<String, Object> source, Instant at) {
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

    /** {@code alarms} 토픽 {@code alarm}(API-DSH-20). {@code event}는 EVT-RUL-02 라우팅 키(alarm.raised 등) */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record AlarmTopic(String alarmId, String state, String severity, String spaceId, String title, String event) {
    }

    /** {@code notifications} 토픽 {@code notification}(API-DSH-20). 읽음 수(unreadCount)는 알림 센터(DSH-10.01, M7)부터 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Notification(String id, String category, String title, String link, java.time.Instant createdAt, String alarmId) {
    }
}
