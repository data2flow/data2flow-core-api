package net.java21.data2flow.core.catalog;

import net.java21.data2flow.core.catalog.service.BuiltinCatalogSeeder;
import net.java21.data2flow.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 기기 모델·측정 항목 통합 테스트 기반. pipeline 대역 주소를 넣는다(이 패키지의 IT가 Spring 컨텍스트 하나를 함께 쓰도록 여기에만 둔다).
 */
abstract class CatalogItSupport extends IntegrationTestSupport {

    static final PipelineStubServer PIPELINE = new PipelineStubServer();

    @DynamicPropertySource
    static void pipeline(DynamicPropertyRegistry registry) {
        registry.add("data2flow.core.pipeline-base-url", PIPELINE::baseUrl);
    }

    @Autowired
    protected BuiltinCatalogSeeder seeder;

    @BeforeEach
    void resetPipeline() {
        PIPELINE.reset();
    }

    /** 아웃박스에 쌓인 설정 변경(data2flow.config) 중 이 종류의 수 */
    protected long configMessages(long orgId, String entityType, String op) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_core.outboxes
                         WHERE organization_id = :org AND kind = 'CONFIG' AND payload::jsonb ->> 'entityType' = :type
                           AND payload::jsonb ->> 'op' = :op""")
                .param("org", orgId).param("type", entityType).param("op", op).query(Long.class).single();
    }

    /** 아웃박스에 쌓인 도메인 이벤트 수(라우팅 키) */
    protected long events(long orgId, String routingKey) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE organization_id = :org AND kind = 'EVENT' AND routing_key = :key")
                .param("org", orgId).param("key", routingKey).query(Long.class).single();
    }

    protected long configVersion(long orgId, String scope) {
        return jdbc.sql("SELECT coalesce(max(version), 0) FROM data2flow_core.config_versions WHERE organization_id = :org AND scope = :scope")
                .param("org", orgId).param("scope", scope).query(Long.class).single();
    }

    /**
     * 데이터 소스 한 행. M2Data.source()는 unknown_device_policy 기본값('AUTO_REGISTER', 13자)이 varchar(12)를 넘어 실패하므로
     * (V202610050900 결함, 보고함) 값을 명시한다.
     */
    protected long source(long orgId, String code) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.data_sources (organization_id, code, name, type, lifecycle, connection, decoder_key,
                                                                unknown_device_policy, created_by, updated_by)
                        VALUES (:org, :code, :code, 'MQTT_SUBSCRIBE', 'ACTIVE', CAST('{}' AS jsonb), 'chirpstack-v4', 'REJECT', 0, 0)
                        RETURNING id""")
                .param("org", orgId).param("code", code).query(Long.class).single();
    }

    protected long modelId(long orgId, String code) {
        return jdbc.sql("SELECT id FROM data2flow_core.device_models WHERE organization_id = :org AND code = :code")
                .param("org", orgId).param("code", code).query(Long.class).single();
    }

    protected long metricId(long orgId, String key) {
        return jdbc.sql("SELECT id FROM data2flow_core.metrics WHERE organization_id = :org AND key = :key")
                .param("org", orgId).param("key", key).query(Long.class).single();
    }
}
