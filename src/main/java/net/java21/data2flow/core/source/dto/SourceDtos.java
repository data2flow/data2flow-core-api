package net.java21.data2flow.core.source.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 데이터 소스 API DTO(design/api/DSC-api.md §1·§7). ID는 JSON 문자열, 시각은 ISO-8601 UTC, 비율({@code *Rate})은 0~1 소수.
 * 비밀값 원문은 어떤 응답에도 없다(BR-DSC-02): {@link SecretInfo}에는 종류·지문·교체 시각만 있다.
 * 생성·수정 요청은 키가 온 것만 반영해야 해서(PATCH, 비밀값 빈 값 유지) {@link JsonNode}로 받는다.
 */
public final class SourceDtos {

    private SourceDtos() {
    }

    /** API-DSC-01 목록 항목 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceSummaryResponse(String id, String code, String name, String type, String connectorKey, String lifecycle,
                                        String state, StateDetail stateDetail, Instant lastReceivedAt, Double ratePerMin,
                                        Double decodeErrorRate1h, long deviceCount, List<Long> rateSeries, int version) {
    }

    /**
     * 대표 상태 상세. connectedInstances &lt; totalInstances면 화면이 "일부 연결" 경고(AT-DSC-04.3)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StateDetail(int connectedInstances, int totalInstances, String errorKind, String errorMessage) {
    }

    /** API-DSC-02·03·04 소스 상세 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SourceDetailResponse(String id, String code, String name, String type, String connectorKey, String connectorVersion,
                                       String lifecycle, JsonNode connection, JsonNode tls, JsonNode payload, boolean isDev,
                                       List<TopicDto> topics, SecretInfo secret, List<SecretInfo> secrets, String decoderKey,
                                       JsonNode decoderConfig, String decodeScriptId, String unknownDevicePolicy, String defaultModelId,
                                       String defaultSpaceId, int autoregLimitPerHour, int noDataAlarmAfterSec, String siteId,
                                       String webhookUrl, String clientIdBase, List<String> clientIds, List<RuntimeInstance> runtime,
                                       String state, StateDetail stateDetail, Instant lastReceivedAt, Double ratePerMin,
                                       Double decodeErrorRate1h, Instant archivedAt, int version, Instant createdAt, Instant updatedAt,
                                       IssuedSecret issuedSecret) {
    }

    /** 서버가 만든 비밀값(Webhook HMAC_KEY, DSC-01.03). 만든 응답에서 한 번만 보이고 다시 조회할 수 없다 */
    public record IssuedSecret(String kind, String value) {
    }

    public record TopicDto(String topic, int qos) {
    }

    /**
     * 비밀값 정보(BR-DSC-02). fingerprint는 {@code ••••} + 지문 끝 4자리(원문의 일부가 아니라 SHA-256 지문)
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    /** certificateExpiresAt: PEM 인증서(CA_CERT·CLIENT_CERT)의 만료 시각(DSC-09.06, 30일 전부터 화면 경고) */
    public record SecretInfo(String kind, boolean configured, String fingerprint, Instant rotatedAt, boolean rotating,
                             Instant certificateExpiresAt) {
    }

    /** 인스턴스별 연결 상태(API-DSC-03 runtime[], API-DSC-14). stale=90초 넘게 보고 없음(대표 상태에서 빠짐) */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RuntimeInstance(String instanceId, String state, String errorKind, String errorMessage, String clientId,
                                  Instant connectedSince, int reconnects24h, Instant reportedAt, boolean stale) {
    }

    /** API-DSC-14 */
    public record RuntimeResponse(String sourceId, String state, StateDetail stateDetail, List<RuntimeInstance> instances) {
    }

    /** API-DSC-06 상태 변경 응답 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LifecycleResponse(String id, String lifecycle, Instant archivedAt, int version) {
    }

    /** API-DSC-05 비밀값 교체 응답. M2는 무중단 교체(DSC-07.02, M5) 전이라 저장 즉시 DONE */
    public record SecretRotationResponse(String rotationId, String state, List<SecretInfo> secrets) {
    }

    /** API-DSC-58 비밀값 한 종류 교체 응답 */
    public record SecretResponse(String kind, String fingerprint, Instant rotatedAt) {
    }

    /** API-DSC-09 한 구간 */
    public record StatPoint(Instant t, long received, long accepted, long decodeErrors, long scriptErrors, long rejectedUnknown,
                            long invalid, long dup, long bytes, long reconnects) {
    }

    /** API-DSC-11 사용처. flows는 플로우(M3) 전이라 빈 목록 */
    public record UsageResponse(long deviceCount, List<Map<String, Object>> flows, List<Map<String, Object>> volume7d) {
    }

    /** API-DSC-13 무시 목록 항목 */
    public record IgnoreEntryResponse(String externalId, String reason, String createdBy, Instant createdAt) {
    }

    /** 조직 소스 한도(DSC-07.03) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceLimitsResponse(int maxSources, int maxTopicsPerSource, int maxMessageBytes, int maxMessagesPerSec,
                                       long activeSources, int version, Instant updatedAt) {
    }

    /** API-DSC-23 플랫폼 브로커 정보(ADR-029: 기존 iot-data.java21.net WSS, 8883 없음) */
    public record PlatformBrokerResponse(String wssUrl, String auth, String signing, List<String> topicRules) {
    }

    // ---- 커넥터 카탈로그(API-DSC-55·56) ----

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConnectorResponse(String connectorKey, String version, String name, String category, String standard,
                                    List<String> transports, List<String> authMethods, List<String> payloadFormats, String ackMode,
                                    String scaling, boolean supportsSend, boolean lossPossible, boolean enabled, String disabledReason,
                                    String sourceType) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TemplateSummary(String key, String name, String connectorKey, String description) {
    }

    public record CatalogResponse(List<ConnectorResponse> connectors, List<TemplateSummary> templates) {
    }

    /** authSecretKinds: 인증 방식 → {required[], optional[]} 비밀값 종류(DSC-09.05 인증 방식 매트릭스) */
    public record ConnectorSchemaResponse(String key, String version, JsonNode jsonSchema, JsonNode uiHints,
                                          Map<String, Map<String, List<String>>> authSecretKinds) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TemplateResponse(String key, String connectorKey, String name, String description, String docsUrl, boolean builtin,
                                   JsonNode preset) {
    }

    // ---- 내부(API-DSC-50, API-ING-21) ----

    /**
     * API-DSC-50 응답. version은 배포 조직의 소스 설정 버전 합(sinceVersion과 같으면 204). 전체 스냅샷이다(빠진 소스는 내린다).
     */
    public record RuntimeConfigResponse(long version, List<RuntimeSource> sources) {
    }

    /**
     * ingress 실행 설정 한 건. {@code clientId}는 client-id base(API-DSC-50, ingress가 읽는 이름. {@code clientIdBase}는 같은 값의 별칭).
     * config = connection + {@code topics[]} + {@code clientIdBase}(ingress가 {@code {base}-{env}-{n}}을 붙인다,
     * contracts ClientIds). secrets는 종류(PASSWORD·HEADER_VALUE·CLIENT_CERT·CLIENT_KEY·CA_CERT …) → <b>복호화한 값</b>(내부 전용, 로그 금지).
     */
    public record RuntimeSource(String id, String organizationId, String code, String type, String connectorKey,
                                String connectorVersion, String lifecycle, JsonNode config, Map<String, String> secrets,
                                List<TopicDto> topics, int qos, String clientId, String clientIdBase, String unknownDevicePolicy,
                                RateLimit rateLimit,
                                String decoderKey, int version, RuntimeRotation rotation) {
        @Override
        public String toString() {
            return "RuntimeSource[id=" + id + ", secrets=" + secrets.keySet() + "]";
        }
    }

    /**
     * 진행 중인 무중단 자격증명 교체(DSC-07.02, BR-DSC-09). ingress는 인스턴스를 하나씩 {@code secrets}(새 값, 복호화)로 다시 연결해 보고
     * 하고(EVT-DSC-08), 확정되면 다음 실행 설정의 {@code secrets}가 새 값이 되고 이 칸은 사라진다. 없으면 null
     */
    public record RuntimeRotation(String rotationId, Map<String, String> secrets) {
        @Override
        public String toString() {
            return "RuntimeRotation[rotationId=" + rotationId + ", secrets=" + secrets.keySet() + "]";
        }
    }

    /** 소스 한도(BR-DSC-06). warnRatio를 넘으면 경고(80%) */
    public record RateLimit(int maxMessagesPerSec, int maxMessageBytes, double warnRatio) {
    }

    /** API-ING-21 수집 맥락 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record IngestContextResponse(String sourceId, String organizationId, String sourceType, String lifecycle, Decoder decoder,
                                        String unknownDevicePolicy, int autoRegisterHourlyLimit, String defaultModelId,
                                        String defaultSpaceId, List<MetricContext> metrics, List<String> scriptIds, long contextVersion) {
    }

    /**
     * @param type     디코더 키(chirpstack-v4·generic-json·single-value·script)
     * @param key      실제 디코더 키. script면 {@code script:{id}@v{n}}(contracts DecoderKeys), 활성 버전이 없으면 null
     * @param scriptId script일 때 스크립트 ID
     * @param config   decoderConfig(generic-json 매핑 등)
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Decoder(String type, String key, String scriptId, JsonNode config) {
    }

    public record MetricContext(String id, String key, String unit, String valueType, String status, List<String> aliases) {
    }
}
