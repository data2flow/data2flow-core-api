package net.java21.data2flow.core.source.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.message.decoder.DecoderKeys;
import net.java21.data2flow.contracts.secret.Secret;
import net.java21.data2flow.core.common.ConfigVersions;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import net.java21.data2flow.core.source.domain.SourceErrorCode;
import net.java21.data2flow.core.source.domain.SourceModels;
import net.java21.data2flow.core.source.domain.SourceModels.DataSource;
import net.java21.data2flow.core.source.domain.SourceModels.SourceLimits;
import net.java21.data2flow.core.source.domain.SourceModels.SourceTopic;
import net.java21.data2flow.core.source.dto.SourceDtos.Decoder;
import net.java21.data2flow.core.source.dto.SourceDtos.IngestContextResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.MetricContext;
import net.java21.data2flow.core.source.dto.SourceDtos.RateLimit;
import net.java21.data2flow.core.source.dto.SourceDtos.RuntimeConfigResponse;
import net.java21.data2flow.core.source.dto.SourceDtos.RuntimeSource;
import net.java21.data2flow.core.source.dto.SourceDtos.TopicDto;
import net.java21.data2flow.core.source.repository.DataSourceRepository;
import net.java21.data2flow.core.source.repository.SourceReferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 내부 조회(토큰 없음, ADR-021):
 * <ul>
 *   <li>API-DSC-50 {@code GET /internal/core/sources/runtime-config} — ingress가 시작할 때와 EVT-DSC-01을 받을 때 읽는 소스 실행 설정
 *       (복호화한 비밀값 포함, 응답은 로그 금지). 배포 조직으로 좁힌다(ADR-030: staging 파드는 staging 조직 소스만 연결)</li>
 *   <li>API-ING-21 {@code GET /internal/core/ingest-context?sourceId=} — pipeline이 디코딩·자동 등록에 쓰는 소스 맥락</li>
 * </ul>
 */
@Service
public class SourceRuntimeConfigService {

    static final List<String> DEFAULT_LIFECYCLES = List.of(SourceModels.ACTIVE, SourceModels.PAUSED);
    static final double WARN_RATIO = 0.8;

    private final DataSourceRepository sources;
    private final SourceReferenceRepository references;
    private final SourceSecrets secrets;
    private final ConfigVersions versions;
    private final DeploymentOrganization deployment;
    private final net.java21.data2flow.core.source.repository.SourceRotationRepository rotations;

    public SourceRuntimeConfigService(DataSourceRepository sources, SourceReferenceRepository references, SourceSecrets secrets,
                                      ConfigVersions versions, DeploymentOrganization deployment,
                                      net.java21.data2flow.core.source.repository.SourceRotationRepository rotations) {
        this.rotations = rotations;
        this.sources = sources;
        this.references = references;
        this.secrets = secrets;
        this.versions = versions;
        this.deployment = deployment;
    }

    /**
     * API-DSC-50. {@code sinceVersion}이 지금 버전과 같으면 빈 값(컨트롤러가 204). 다르면 전체 스냅샷(ingress가 빠진 소스는 내린다).
     */
    @Transactional(readOnly = true)
    public Optional<RuntimeConfigResponse> runtimeConfig(String lifecycle, Long sinceVersion) {
        OptionalLong restriction = deployment.restriction();
        long version = versions.sum(ConfigVersions.SOURCES, restriction);
        if (sinceVersion != null && sinceVersion == version) {
            return Optional.empty();
        }
        List<String> lifecycles = lifecycles(lifecycle);
        List<DataSource> rows = sources.listForRuntime(lifecycles, restriction);
        List<Long> ids = rows.stream().map(DataSource::id).toList();
        Map<Long, List<SourceTopic>> topics = sources.findTopicsOf(ids);
        Map<Long, List<SourceModels.SecretRow>> secretRows = sources.findSecretsOf(ids);
        Map<Long, String> activeRotations = rotations.findActiveOf(ids);
        Map<Long, List<SourceModels.PendingRow>> pending = activeRotations.isEmpty() ? Map.of() : sources.findPendingOf(activeRotations.keySet());
        Map<Long, SourceLimits> limits = references.limitsOf(rows.stream().map(DataSource::organizationId).distinct().toList());
        List<RuntimeSource> result = new ArrayList<>();
        for (DataSource s : rows) {
            List<SourceTopic> t = topics.getOrDefault(s.id(), List.of());
            Map<String, String> plain = new LinkedHashMap<>();
            for (Map.Entry<String, Secret> e : secrets.decrypt(s.id(), secretRows.getOrDefault(s.id(), List.of())).entrySet()) {
                plain.put(e.getKey(), e.getValue().reveal());
            }
            SourceLimits l = limits.getOrDefault(s.organizationId(), SourceLimits.DEFAULT);
            int qos = s.connection() != null && s.connection().path("qos").isIntegralNumber() ? s.connection().get("qos").asInt() : 1;
            result.add(new RuntimeSource(Long.toString(s.id()), Long.toString(s.organizationId()), s.code(), s.type(),
                    s.connectorKey() == null ? SourceModels.CONNECTOR_OF_TYPE.get(s.type()) : s.connectorKey(), s.connectorVersion(),
                    s.lifecycle(), config(s, t), plain, t.stream().map(x -> new TopicDto(x.topic(), x.qos())).toList(), qos,
                    s.clientIdBase(), s.clientIdBase(), s.unknownDevicePolicy(), new RateLimit(l.maxMessagesPerSec(), l.maxMessageBytes(), WARN_RATIO),
                    s.decoderKey(), s.version(), rotation(s.id(), activeRotations.get(s.id()), pending.get(s.id()))));
        }
        return Optional.of(new RuntimeConfigResponse(version, result));
    }

    private net.java21.data2flow.core.source.dto.SourceDtos.RuntimeRotation rotation(long sourceId, String rotationId,
                                                                                     List<SourceModels.PendingRow> rows) {
        if (rotationId == null || rows == null || rows.isEmpty()) {
            return null;
        }
        Map<String, String> plain = new LinkedHashMap<>();
        secrets.decryptPending(sourceId, rows).forEach((k, v) -> plain.put(k, v.reveal()));
        return new net.java21.data2flow.core.source.dto.SourceDtos.RuntimeRotation(rotationId, plain);
    }

    /** ingress 커넥터 설정 = connection + topics[] + clientIdBase(+ version: protocolVersion 별칭, ingress MqttSourceSettings가 읽는 이름) */
    static JsonNode config(DataSource s, List<SourceTopic> topics) {
        ObjectNode c = s.connection() != null && s.connection().isObject() ? ((ObjectNode) s.connection()).deepCopy()
                : JsonNodeFactory.instance.objectNode();
        if (!topics.isEmpty()) {
            ArrayNode arr = c.putArray("topics");
            topics.forEach(t -> arr.addObject().put("topic", t.topic()).put("qos", t.qos()));
        }
        if (c.has("protocolVersion") && !c.has("version")) {
            c.set("version", c.get("protocolVersion"));
        }
        c.put("clientIdBase", s.clientIdBase());
        // DSC-09.07·09.08(ADR-056): 형식 변환·토픽 템플릿은 ingress가 기록 직전에 한다
        if (s.payload() != null && s.payload().isObject()) {
            c.set("payload", s.payload());
        }
        if (s.topicTemplate() != null) {
            c.put("topicTemplate", s.topicTemplate());
        }
        return c;
    }

    private static List<String> lifecycles(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIFECYCLES;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String l = part.strip().toUpperCase(Locale.ROOT);
            if (!SourceModels.LIFECYCLES.contains(l)) {
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("lifecycle", "INVALID", null)));
            }
            out.add(l);
        }
        return List.copyOf(out);
    }

    /**
     * API-ING-21. contextVersion = SOURCES + METRICS + SCRIPTS 설정 버전 합(조직)이라 소스·측정 항목·별칭·스크립트 중 하나라도
     * 바뀌면 커진다. 배포 조직 밖 소스는 404.
     */
    @Transactional(readOnly = true)
    public IngestContextResponse ingestContext(long sourceId) {
        DataSource s = sources.findInternal(sourceId, deployment.restriction())
                .orElseThrow(() -> new BusinessException(SourceErrorCode.SOURCE_NOT_FOUND));
        long orgId = s.organizationId();
        String key = null;
        if (SourceModels.DECODER_SCRIPT.equals(s.decoderKey()) && s.decodeScriptId() != null) {
            key = references.findActiveScriptVersion(orgId, s.decodeScriptId())
                    .map(v -> DecoderKeys.script(s.decodeScriptId(), v)).orElse(null);
        } else {
            key = s.decoderKey();
        }
        Set<String> scriptIds = new LinkedHashSet<>();
        if (s.decodeScriptId() != null && SourceModels.DECODER_SCRIPT.equals(s.decoderKey())) {
            scriptIds.add(Long.toString(s.decodeScriptId()));
        }
        references.findSourceScriptIds(orgId, sourceId).forEach(id -> scriptIds.add(Long.toString(id)));
        List<MetricContext> metrics = references.findMetrics(orgId).stream()
                .map(m -> new MetricContext(Long.toString(m.id()), m.key(), m.unit(), m.valueType(), m.status(), List.copyOf(m.aliases())))
                .toList();
        long contextVersion = versions.current(orgId, ConfigVersions.SOURCES) + versions.current(orgId, ConfigVersions.METRICS)
                + versions.current(orgId, ConfigVersions.SCRIPTS);
        return new IngestContextResponse(Long.toString(sourceId), Long.toString(orgId), s.type(), s.lifecycle(),
                new Decoder(s.decoderKey(), key, s.decodeScriptId() == null ? null : Long.toString(s.decodeScriptId()), s.decoderConfig()),
                s.unknownDevicePolicy(), s.autoregLimitPerHour(),
                s.defaultModelId() == null ? null : Long.toString(s.defaultModelId()),
                s.defaultSpaceId() == null ? null : Long.toString(s.defaultSpaceId()), metrics, List.copyOf(scriptIds), contextVersion);
    }
}
