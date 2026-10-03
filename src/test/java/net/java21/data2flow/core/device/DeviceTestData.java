package net.java21.data2flow.core.device;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;

/** WP-B2 테스트 데이터(공용 M2Data에 없는 것): 유형·정책·기본값을 고른 소스, 종류를 고른 모델, 아웃박스 확인 */
public class DeviceTestData {

    private final JdbcClient jdbc;

    public DeviceTestData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 소스(유형·미등록 정책·기본 모델·기본 공간·시간당 한도) */
    public long source(long orgId, String code, String type, String policy, Long defaultModelId, Long defaultSpaceId, int limitPerHour) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.data_sources (organization_id, code, name, type, lifecycle, connection, decoder_key,
                            unknown_device_policy, default_model_id, default_space_id, autoreg_limit_per_hour, created_by, updated_by)
                        VALUES (:org, :code, :name, :type, 'ACTIVE', CAST('{}' AS jsonb), :decoder, :policy, :model, :space, :limit, 0, 0)
                        RETURNING id""")
                .param("org", orgId).param("code", code).param("name", "소스 " + code).param("type", type)
                .param("decoder", "PLATFORM_BROKER".equals(type) ? "generic-json" : "chirpstack-v4").param("policy", policy)
                .param("model", defaultModelId).param("space", defaultSpaceId).param("limit", limitPerHour)
                .query(Long.class).single();
    }

    /** 모델 종류·기본 주기·상태 바꾸기 */
    public void model(long modelId, String kind, Integer intervalSec, String status) {
        jdbc.sql("UPDATE data2flow_core.device_models SET kind = :kind, default_interval_sec = coalesce(:interval, default_interval_sec),"
                        + " status = :status WHERE id = :id")
                .param("kind", kind).param("interval", intervalSec).param("status", status).param("id", modelId).update();
    }

    public void attributeSchema(long modelId, String schemaJson) {
        jdbc.sql("UPDATE data2flow_core.device_models SET attribute_schema = CAST(:s AS jsonb) WHERE id = :id")
                .param("s", schemaJson).param("id", modelId).update();
    }

    public void tag(long orgId, long deviceId, String tag) {
        jdbc.sql("INSERT INTO data2flow_core.device_tags (organization_id, device_id, tag) VALUES (:org, :d, :t)")
                .param("org", orgId).param("d", deviceId).param("t", tag).update();
    }

    public int version(long deviceId) {
        return jdbc.sql("SELECT version FROM data2flow_core.devices WHERE id = :id").param("id", deviceId).query(Integer.class).single();
    }

    public String status(long deviceId) {
        return jdbc.sql("SELECT status FROM data2flow_core.devices WHERE id = :id").param("id", deviceId).query(String.class).single();
    }

    /** 아웃박스에 쌓인 메시지 수(라우팅 키, CONFIG는 "") */
    public long outbox(long orgId, String kind, String routingKey) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE organization_id = :org AND kind = :kind AND routing_key = :key")
                .param("org", orgId).param("kind", kind).param("key", routingKey).query(Long.class).single();
    }

    public List<String> outboxPayloads(long orgId, String routingKey) {
        return jdbc.sql("SELECT payload::text FROM data2flow_core.outboxes WHERE organization_id = :org AND routing_key = :key ORDER BY id")
                .param("org", orgId).param("key", routingKey).query(String.class).list();
    }

    public long count(String sql, Map<String, ?> params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }
}
