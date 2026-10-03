package net.java21.data2flow.core.source.service;

import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.message.SourceTypes;
import net.java21.data2flow.contracts.secret.Secret;
import net.java21.data2flow.core.source.domain.SourceConfigValidator;
import net.java21.data2flow.core.source.domain.SourceErrorCode;
import net.java21.data2flow.core.source.domain.SourceModels;
import net.java21.data2flow.core.source.domain.SourceModels.DataSource;
import net.java21.data2flow.core.source.domain.SourceModels.SourceTopic;
import net.java21.data2flow.core.source.repository.DataSourceRepository;
import net.java21.data2flow.core.source.repository.SourceReferenceRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 저장 전 연결 테스트(DSC-02.05·09.11, API-DSC-57): 설정을 검증하고 ingress API-DSC-51로 넘겨 단계별 결과(DNS·TCP·TLS·AUTH·SUBSCRIBE)와
 * 미리보기(최대 10건)를 받는다. 결과는 저장하지 않는다. core는 외부 브로커에 직접 붙지 않는다.
 *
 * <ul>
 *   <li>제한 시간: 기본 {@value #DEFAULT_TIMEOUT_SEC}초(BR-DSC-07), 요청 {@code timeoutSec}로 최대 {@value #MAX_TIMEOUT_SEC}초까지
 *       (API-DSC-57의 30초는 상한으로 읽었다. 범위 밖 값은 경계값으로)</li>
 *   <li>동시 테스트는 조직당 {@value #MAX_CONCURRENT}개(API-DSC-51). 넘으면 429 RATE_LIMITED. 파드마다 세고, ingress도 따로 막는다</li>
 *   <li>응답: ingress 결과에 {@code ok}(실패 단계 없음)와 {@code stage}(처음 실패한 단계)를 더한다. 단계 상태는 문서·웹 표기
 *       {@code OK|FAIL|SKIPPED}로 맞춘다(contracts {@code StepStatus.FAILED} → {@code FAIL})</li>
 * </ul>
 */
@Service
public class ConnectionTestService {

    static final int DEFAULT_TIMEOUT_SEC = 15;
    static final int MIN_TIMEOUT_SEC = 5;
    static final int MAX_TIMEOUT_SEC = 30;
    static final int MAX_CONCURRENT = 3;
    /** ingress 응답을 기다리는 여유(테스트 제한 시간 뒤) */
    static final Duration RESPONSE_MARGIN = Duration.ofSeconds(5);

    private final DataSourceRepository sources;
    private final SourceReferenceRepository references;
    private final SourceSecrets secrets;
    private final IngressClient ingress;
    private final RoleChecker roleChecker;
    private final Map<Long, Semaphore> running = new ConcurrentHashMap<>();

    public ConnectionTestService(DataSourceRepository sources, SourceReferenceRepository references, SourceSecrets secrets,
                                 IngressClient ingress, RoleChecker roleChecker) {
        this.sources = sources;
        this.references = references;
        this.secrets = secrets;
        this.ingress = ingress;
        this.roleChecker = roleChecker;
    }

    /** API-DSC-57 새 설정 테스트 */
    public JsonNode test(JsonNode body, Integer timeoutSec) {
        roleChecker.require(Permission.SRC_ADMIN);
        long orgId = roleChecker.currentUser().organizationId();
        JsonNode b = body == null ? JsonNodeFactory.instance.objectNode() : body;
        String type = DataSourceService.text(b, "type");
        if (type == null || !SourceModels.BASIC_TYPES.contains(type.toUpperCase(Locale.ROOT))) {
            throw new BusinessException(SourceErrorCode.SOURCE_CONFIG_INVALID, List.of(new FieldErrorDetail("type", "UNSUPPORTED", null)), "type");
        }
        type = type.toUpperCase(Locale.ROOT);
        String code = DataSourceService.text(b, "code");
        return run(orgId, null, type, code, b.get("connection"), b.get("topics"), SourceSecrets.parse(b.get("secret")), Map.of(),
                b.path("isDev").asBoolean(false), b, timeoutSec);
    }

    /** API-DSC-57 저장된 소스 테스트. 본문에 온 값(connection·topics·secret)이 저장 값보다 우선 */
    public JsonNode testExisting(long sourceId, JsonNode body, Integer timeoutSec) {
        roleChecker.require(Permission.SRC_ADMIN);
        long orgId = roleChecker.currentUser().organizationId();
        DataSource s = sources.findById(orgId, sourceId).orElseThrow(() -> new BusinessException(SourceErrorCode.SOURCE_NOT_FOUND));
        JsonNode b = body == null ? JsonNodeFactory.instance.objectNode() : body;
        JsonNode connection = b.has("connection") ? b.get("connection") : s.connection();
        JsonNode topics = b.has("topics") ? b.get("topics") : topicsNode(sources.findTopics(orgId, sourceId));
        Map<String, Secret> stored = secrets.decrypt(sourceId, sources.findSecrets(orgId, sourceId));
        ObjectNode decoder = JsonNodeFactory.instance.objectNode();
        decoder.put("decoderKey", b.has("decoderKey") ? DataSourceService.text(b, "decoderKey") : s.decoderKey());
        decoder.set("decoderConfig", b.has("decoderConfig") ? b.get("decoderConfig") : s.decoderConfig());
        return run(orgId, sourceId, s.type(), s.code(), connection, topics, SourceSecrets.parse(b.get("secret")), stored,
                b.has("isDev") ? b.get("isDev").asBoolean(false) : s.isDev(), decoder, timeoutSec);
    }

    private JsonNode run(long orgId, Long sourceId, String type, String code, JsonNode rawConnection, JsonNode rawTopics,
                         Map<String, Secret> incoming, Map<String, Secret> stored, boolean isDev, JsonNode decoder, Integer timeoutSec) {
        SourceConfigValidator v = SourceConfigValidator.start();
        ObjectNode connection = v.connection(type, rawConnection, isDev);
        boolean mqtt = SourceTypes.MQTT_SUBSCRIBE.equals(type);
        int defaultQos = connection.path("qos").isIntegralNumber() ? connection.get("qos").asInt() : 1;
        List<SourceTopic> topics = mqtt ? v.topics(rawTopics, defaultQos, references.findLimits(orgId).maxTopicsPerSource(), true) : List.of();
        v.throwIfInvalid();
        String auth = DataSourceService.auth(type, connection);
        SourceSecrets.checkKinds(type, auth, incoming);
        Map<String, Secret> all = new TreeMap<>(stored);
        all.keySet().retainAll(SourceSecrets.allowedKinds(type, auth));
        all.putAll(incoming);
        SourceSecrets.requirePresent(type, auth, all.keySet());
        int timeout = timeoutSec == null ? DEFAULT_TIMEOUT_SEC : Math.max(MIN_TIMEOUT_SEC, Math.min(MAX_TIMEOUT_SEC, timeoutSec));

        ObjectNode config = connection.deepCopy();
        ArrayNode arr = config.putArray("topics");
        topics.forEach(t -> arr.addObject().put("topic", t.topic()).put("qos", t.qos()));
        if (config.has("protocolVersion") && !config.has("version")) {
            config.set("version", config.get("protocolVersion"));
        }
        String base = SourceModels.effectiveClientIdBase(connection, code == null || code.isBlank() ? "test" : code);
        config.put("clientIdBase", base);
        Map<String, String> plain = new LinkedHashMap<>();
        all.forEach((k, val) -> plain.put(k, val.reveal()));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("organizationId", orgId);
        request.put("sourceId", sourceId == null ? null : Long.toString(sourceId));
        request.put("type", type);
        request.put("connectorKey", SourceModels.CONNECTOR_OF_TYPE.get(type));
        request.put("config", config);
        request.put("secrets", plain);
        request.put("clientIdBase", base);
        request.put("decoderKey", DataSourceService.text(decoder, "decoderKey"));
        request.put("decoderConfig", decoder.get("decoderConfig"));
        request.put("timeoutSec", timeout);

        Semaphore slots = running.computeIfAbsent(orgId, k -> new Semaphore(MAX_CONCURRENT));
        if (!slots.tryAcquire()) {
            throw new BusinessException(CommonErrorCode.RATE_LIMITED);
        }
        try {
            return normalize(ingress.test(request, Duration.ofSeconds(timeout).plus(RESPONSE_MARGIN)));
        } finally {
            slots.release();
        }
    }

    /** 단계 상태 표기를 맞추고 ok·stage를 더한다 */
    static JsonNode normalize(JsonNode result) {
        ObjectNode out = result != null && result.isObject() ? ((ObjectNode) result).deepCopy() : JsonNodeFactory.instance.objectNode();
        ArrayNode steps = out.has("steps") && out.get("steps").isArray() ? (ArrayNode) out.get("steps") : out.putArray("steps");
        if (!out.has("preview") || !out.get("preview").isArray()) {
            out.putArray("preview");
        }
        if (!out.has("lossPossible")) {
            out.put("lossPossible", false);
        }
        String failed = null;
        for (JsonNode step : steps) {
            if (step instanceof ObjectNode s && "FAILED".equals(s.path("status").asString(""))) {
                s.put("status", "FAIL");
            }
            if (failed == null && "FAIL".equals(step.path("status").asString(""))) {
                failed = step.path("name").asString(null);
            }
        }
        out.put("ok", failed == null && !steps.isEmpty());
        if (failed != null) {
            out.put("stage", failed);
        } else {
            out.remove("stage");
        }
        return out;
    }

    private static JsonNode topicsNode(List<SourceTopic> topics) {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        topics.forEach(t -> arr.addObject().put("topic", t.topic()).put("qos", t.qos()));
        return arr;
    }

    /** 테스트 도우미: 지금 조직의 남은 자리 */
    int available(long orgId) {
        return running.computeIfAbsent(orgId, k -> new Semaphore(MAX_CONCURRENT)).availablePermits();
    }
}
