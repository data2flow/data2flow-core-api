package net.java21.data2flow.core.catalog.event;

import net.java21.data2flow.contracts.message.ConfigChangedMessage.EntityType;
import net.java21.data2flow.contracts.message.EventType;
import net.java21.data2flow.contracts.message.event.MetricUnverifiedRegistered;
import net.java21.data2flow.core.common.ConfigVersions;
import net.java21.data2flow.core.messaging.service.CoreEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 모델·측정 항목 변경 알림(EVT-DEV-04 {@code data2flow.config} MODEL·METRIC·ALIAS, EVT-ING-04). 업무 트랜잭션 안에서 부른다:
 * 설정 버전({@code config_versions} MODELS·METRICS)을 올리고 아웃박스에 기록한다. pipeline은 메시지를 받으면 캐시를 지우고
 * API-DEV-123({@code sinceVersion})으로 다시 읽는다.
 */
@Component
public class CatalogEvents {

    private final CoreEventPublisher publisher;
    private final ConfigVersions versions;

    public CatalogEvents(CoreEventPublisher publisher, ConfigVersions versions) {
        this.publisher = publisher;
        this.versions = versions;
    }

    public void modelChanged(long organizationId, long modelId, long version) {
        versions.bump(organizationId, ConfigVersions.MODELS);
        publisher.configChanged(EntityType.MODEL, modelId, version, organizationId);
    }

    public void modelDeleted(long organizationId, long modelId, long version) {
        versions.bump(organizationId, ConfigVersions.MODELS);
        publisher.configDeleted(EntityType.MODEL, modelId, version, organizationId);
    }

    public void metricChanged(long organizationId, long metricId, long version) {
        versions.bump(organizationId, ConfigVersions.METRICS);
        publisher.configChanged(EntityType.METRIC, metricId, version, organizationId);
    }

    public void metricDeleted(long organizationId, long metricId, long version) {
        versions.bump(organizationId, ConfigVersions.METRICS);
        publisher.configDeleted(EntityType.METRIC, metricId, version, organizationId);
    }

    /** 별칭에는 행 버전이 없어 METRICS 설정 버전을 대상 버전으로 쓴다 */
    public void aliasChanged(long organizationId, long aliasId) {
        long version = versions.bump(organizationId, ConfigVersions.METRICS);
        publisher.configChanged(EntityType.ALIAS, aliasId, version, organizationId);
    }

    public void aliasDeleted(long organizationId, long aliasId) {
        long version = versions.bump(organizationId, ConfigVersions.METRICS);
        publisher.configDeleted(EntityType.ALIAS, aliasId, version, organizationId);
    }

    /** EVT-ING-04: 처음 보는 측정 키가 UNVERIFIED로 등록됐다(ING-04.02). 소비: core 알림 센터 */
    public void unverifiedRegistered(long organizationId, String key, Long deviceId, Instant firstSeenAt) {
        publisher.event(EventType.METRIC_UNVERIFIED_REGISTERED, organizationId,
                new MetricUnverifiedRegistered(key, deviceId == null ? 0 : deviceId, firstSeenAt));
    }
}
