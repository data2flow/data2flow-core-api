package net.java21.data2flow.core.catalog.service;

import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.core.catalog.domain.CatalogModels;
import net.java21.data2flow.core.catalog.domain.CatalogModels.Metric;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.ExistingKey;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.InternalMetric;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.InternalMetricsResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.RegisterUnverifiedRequest;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.RegisterUnverifiedResponse;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.RegisteredKey;
import net.java21.data2flow.core.catalog.dto.CatalogDtos.UnverifiedKey;
import net.java21.data2flow.core.catalog.event.CatalogEvents;
import net.java21.data2flow.core.catalog.repository.DeviceModelRepository;
import net.java21.data2flow.core.catalog.repository.MetricRepository;
import net.java21.data2flow.core.common.ConfigVersions;
import net.java21.data2flow.core.organization.service.DeploymentOrganization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * pipeline이 부르는 측정 항목 내부 API(ADR-021: 토큰 없음, 내부망 신뢰).
 * <ul>
 *   <li>API-DEV-123 {@code GET /internal/core/metrics?organizationId=&sinceVersion=}: 조직의 측정 항목·별칭 전체. 설정 버전
 *       (config_versions METRICS)이 sinceVersion과 같으면 본문 없이(204). 조직을 안 주면 이 배포의 조직(ADR-030)</li>
 *   <li>API-DEV-124 {@code POST /internal/core/metrics/register-unverified}: 처음 보는 키를 UNVERIFIED로(ING-04.02). 멱등이고
 *       동시에 같은 키가 와도 한 행만 생긴다. 새로 등록한 키마다 EVT-ING-04 {@code metric.unverified.registered} 1회</li>
 * </ul>
 * 이 배포가 맡지 않는 조직(DeploymentOrganization)이면 404 RESOURCE_NOT_FOUND.
 */
@Service
public class MetricInternalService {

    private final MetricRepository metrics;
    private final DeviceModelRepository models;
    private final ConfigVersions versions;
    private final DeploymentOrganization deployment;
    private final CatalogEvents events;
    private final JsonMapper json;
    private final Clock clock;

    public MetricInternalService(MetricRepository metrics, DeviceModelRepository models, ConfigVersions versions,
                                 DeploymentOrganization deployment, CatalogEvents events, JsonMapper json, Clock clock) {
        this.metrics = metrics;
        this.models = models;
        this.versions = versions;
        this.deployment = deployment;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /** API-DEV-123. 바뀐 것이 없으면 빈 값(204) */
    @Transactional(readOnly = true)
    public Optional<InternalMetricsResponse> snapshot(Long organizationId, Long sinceVersion) {
        long orgId = resolveOrganization(organizationId);
        long version = versions.current(orgId, ConfigVersions.METRICS);
        if (sinceVersion != null && sinceVersion == version) {
            return Optional.empty();
        }
        List<InternalMetric> items = metrics.listAll(orgId).stream().map(m -> new InternalMetric(Long.toString(m.id()), m.key(),
                m.displayName(), m.unit(), m.valueType(), m.enumMapJson() == null ? null : json.readTree(m.enumMapJson()),
                m.validMin(), m.validMax(), m.precision(), m.aggDefault(), m.stateType(), m.status())).toList();
        return Optional.of(new InternalMetricsResponse(Long.toString(orgId), version, items, metrics.listAliasMap(orgId)));
    }

    /** API-DEV-124 */
    @Transactional
    public RegisterUnverifiedResponse registerUnverified(RegisterUnverifiedRequest req) {
        long orgId = req.organizationId();
        if (!deployment.includes(orgId) || !models.existsActiveOrganization(orgId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        Map<String, UnverifiedKey> distinct = new LinkedHashMap<>();
        for (UnverifiedKey item : req.keys()) {
            distinct.putIfAbsent(item.key().strip(), item);
        }
        List<RegisteredKey> registered = new ArrayList<>();
        List<ExistingKey> existing = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        Instant now = clock.instant();
        for (Map.Entry<String, UnverifiedKey> entry : distinct.entrySet()) {
            String key = entry.getKey();
            if (!key.matches(CatalogModels.METRIC_KEY_PATTERN)) {
                invalid.add(key);
                continue;
            }
            Optional<String> aliasOf = metrics.findAliasTarget(orgId, key);
            if (aliasOf.isPresent()) {
                Optional<Metric> target = metrics.findByKey(orgId, aliasOf.get());
                existing.add(new ExistingKey(key, target.map(m -> Long.toString(m.id())).orElse(null),
                        target.map(Metric::status).orElse(null), aliasOf.get()));
                continue;
            }
            Long deviceId = entry.getValue().deviceId();
            Optional<Long> id = metrics.insertUnverified(orgId, key, deviceId, now);
            if (id.isPresent()) {
                registered.add(new RegisteredKey(key, Long.toString(id.get()), CatalogModels.UNVERIFIED));
                events.metricChanged(orgId, id.get(), 0);
                events.unverifiedRegistered(orgId, key, deviceId, now);
            } else {
                Metric metric = metrics.findByKey(orgId, key).orElseThrow();
                existing.add(new ExistingKey(key, Long.toString(metric.id()), metric.status(), null));
            }
        }
        return new RegisterUnverifiedResponse(registered, existing, invalid);
    }

    private long resolveOrganization(Long organizationId) {
        if (organizationId == null) {
            return deployment.current().map(o -> o.id())
                    .orElseThrow(() -> Patch.invalid("organizationId", "NotNull", null));
        }
        if (!deployment.includes(organizationId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return organizationId;
    }
}
