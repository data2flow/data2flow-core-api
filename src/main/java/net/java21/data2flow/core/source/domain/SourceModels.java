package net.java21.data2flow.core.source.domain;

import net.java21.data2flow.contracts.message.SourceTypes;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 데이터 소스 집합체(DSC domain-model §2)의 값과 상수 */
public final class SourceModels {

    public static final String DRAFT = "DRAFT";
    public static final String ACTIVE = "ACTIVE";
    public static final String PAUSED = "PAUSED";
    public static final String ARCHIVED = "ARCHIVED";
    public static final Set<String> LIFECYCLES = Set.of(DRAFT, ACTIVE, PAUSED, ARCHIVED);

    /** M2에서 화면·API로 만들 수 있는 기본 유형(DSC-01.02). 그 밖의 유형은 카탈로그 커넥터(CONNECTOR, DSC-09)로 */
    public static final Set<String> BASIC_TYPES = Set.of(SourceTypes.MQTT_SUBSCRIBE, SourceTypes.PLATFORM_BROKER, SourceTypes.SIMULATION);

    /** 기본 유형 → 카탈로그 커넥터 키(V202610050940 시드, 웹 CONNECTOR_TYPES와 같음) */
    public static final Map<String, String> CONNECTOR_OF_TYPE = Map.of(
            SourceTypes.MQTT_SUBSCRIBE, "mqtt",
            SourceTypes.PLATFORM_BROKER, "platform-broker",
            SourceTypes.SIMULATION, "simulation");

    public static final String DECODER_CHIRPSTACK = "chirpstack-v4";
    public static final String DECODER_GENERIC_JSON = "generic-json";
    public static final String DECODER_SINGLE_VALUE = "single-value";
    public static final String DECODER_SCRIPT = "script";
    /** M2 기본 유형에서 고를 수 있는 디코더(DSC-01.06) */
    public static final Set<String> BASIC_DECODERS = Set.of(DECODER_CHIRPSTACK, DECODER_GENERIC_JSON, DECODER_SINGLE_VALUE, DECODER_SCRIPT);

    public static final String AUTH_NONE = "NONE";
    public static final String AUTH_USERPASS = "USERPASS";
    public static final String AUTH_HEADER = "HEADER";
    public static final String AUTH_MTLS = "MTLS";
    public static final Set<String> AUTH_METHODS = Set.of(AUTH_NONE, AUTH_USERPASS, AUTH_HEADER, AUTH_MTLS);

    public static final String POLICY_AUTO_REGISTER = "AUTO_REGISTER";
    public static final String POLICY_REJECT = "REJECT";

    /** decoder_config 상한(domain-model §2.1, 32KB) */
    public static final int MAX_DECODER_CONFIG_BYTES = 32 * 1024;

    private SourceModels() {
    }

    /**
     * {@code data_sources} 한 행. connection·tls·payload·decoderConfig는 JSON(없으면 null).
     */
    public record DataSource(long id, long organizationId, String code, String name, String type, String connectorKey,
                             String connectorVersion, String lifecycle, JsonNode connection, JsonNode tls, JsonNode payload,
                             boolean isDev, String decoderKey, JsonNode decoderConfig, Long decodeScriptId,
                             String unknownDevicePolicy, Long defaultModelId, Long defaultSpaceId, int autoregLimitPerHour,
                             int noDataAlarmAfterSec, Long siteId, Instant archivedAt, int version, Instant createdAt,
                             Instant updatedAt) {

        /** connection.auth(MQTT). 없으면 NONE */
        public String auth() {
            JsonNode a = connection == null ? null : connection.get("auth");
            return a == null || a.isNull() ? AUTH_NONE : a.asString(AUTH_NONE);
        }

        /** 실제 client-id의 base(BR-DSC-01): connection.clientIdBase, 비면 {@code data2flow-{code}} */
        public String clientIdBase() {
            return effectiveClientIdBase(connection, code);
        }
    }

    public static String effectiveClientIdBase(JsonNode connection, String code) {
        JsonNode base = connection == null ? null : connection.get("clientIdBase");
        String value = base == null || base.isNull() ? "" : base.asString("").strip();
        return value.isEmpty() ? "data2flow-" + code : value;
    }

    /** 구독 토픽(source_topics) */
    public record SourceTopic(String topic, int qos) {
    }

    /** 비밀값 메타(평문·암호문 없음) */
    public record SecretMeta(String kind, String kid, String fingerprint, Instant rotatedAt, Instant updatedAt, boolean rotating) {
    }

    /** 비밀값 한 건(암호문 포함, 내부 전용) */
    public record SecretRow(String kind, byte[] ciphertext, String kid, String fingerprint) {
    }

    /** ingress 인스턴스 하나의 연결 상태(source_runtimes) */
    public record RuntimeRow(long sourceId, String instanceId, String state, String errorKind, String errorMessage,
                             String clientId, Instant connectedSince, int reconnects24h, Instant reportedAt) {
    }

    /** 대표 상태·무수신 판정 상태(source_states) */
    public record StateRow(long sourceId, long organizationId, String connectionState, String errorKind, Instant stateChangedAt,
                           Instant lastReceivedAt, Instant activatedAt, boolean noData) {
    }

    /** 조직 한도(source_limits, 행이 없으면 {@link #DEFAULT}) */
    public record SourceLimits(int maxSources, int maxTopicsPerSource, int maxMessageBytes, int maxMessagesPerSec, int version,
                               Instant updatedAt) {
        public static final SourceLimits DEFAULT = new SourceLimits(50, 20, 256 * 1024, 500, 0, null);
    }

    /** 1분 지표 한 구간 */
    public record StatBucket(Instant t, long received, long accepted, long decodeErrors, long scriptErrors, long rejectedUnknown,
                             long invalid, long dup, long bytes, long reconnects) {
    }

    /** 목록 지표 요약 */
    public record StatSummary(Instant lastReceivedAt, Double ratePerMin, Double decodeErrorRate1h, List<Long> rateSeries) {
        public static final StatSummary EMPTY = new StatSummary(null, null, null, List.of());
    }
}
