package net.java21.data2flow.core.support;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;

/**
 * M2(수집 경로) 테스트 데이터를 DB에 바로 만든다: 공간 트리, 데이터 소스, 측정 항목, 모델, 기기, 사용자 공간 범위,
 * 그리고 pipeline 소유 테이블(telemetry·device_state·raw_messages)의 읽기용 행. 기능 API를 거치지 않으므로
 * 다른 기능의 테스트가 서로의 구현에 기대지 않는다.
 */
public class M2Data {

    private final JdbcClient jdbc;

    public M2Data(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** SITE(최상위) */
    public long site(long orgId, String name) {
        return space(orgId, null, "SITE", name);
    }

    /** 공간 하나. path는 조상 ID를 / 로 이은 경로(예: /1/4/9/), depth는 1부터 */
    public long space(long orgId, Long parentId, String type, String name) {
        String parentPath = parentId == null ? "/" : jdbc.sql("SELECT path FROM data2flow_core.spaces WHERE id = :id")
                .param("id", parentId).query(String.class).single();
        int depth = parentId == null ? 1 : jdbc.sql("SELECT depth + 1 FROM data2flow_core.spaces WHERE id = :id")
                .param("id", parentId).query(Integer.class).single();
        long id = jdbc.sql("""
                        INSERT INTO data2flow_core.spaces (organization_id, parent_id, type, name, path, depth, timezone)
                        VALUES (:org, :parent, :type, :name, '/', :depth, :tz) RETURNING id""")
                .param("org", orgId).param("parent", parentId).param("type", type).param("name", name).param("depth", depth)
                .param("tz", "SITE".equals(type) ? "Asia/Seoul" : null)
                .query(Long.class).single();
        jdbc.sql("UPDATE data2flow_core.spaces SET path = :path WHERE id = :id").param("path", parentPath + id + "/").param("id", id).update();
        return id;
    }

    /** MQTT 구독 소스(ACTIVE, chirpstack-v4 디코더) */
    public long source(long orgId, String code) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.data_sources (organization_id, code, name, type, lifecycle, connection, decoder_key,
                                                                created_by, updated_by)
                        VALUES (:org, :code, :name, 'MQTT_SUBSCRIBE', 'ACTIVE', CAST(:conn AS jsonb), 'chirpstack-v4', 0, 0)
                        RETURNING id""")
                .param("org", orgId).param("code", code).param("name", "소스 " + code)
                .param("conn", "{\"url\":\"wss://broker.test/mqtt\",\"protocol\":\"wss\"}")
                .query(Long.class).single();
    }

    /** 측정 항목(VERIFIED) */
    public long metric(long orgId, String key, String unit) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.metrics (organization_id, key, display_name, unit)
                        VALUES (:org, :key, :key, :unit) RETURNING id""")
                .param("org", orgId).param("key", key).param("unit", unit).query(Long.class).single();
    }

    /** 기기 모델과 측정 항목 연결(측정 항목은 미리 만들어 둔다) */
    public long model(long orgId, String code, List<String> metricKeys) {
        long id = jdbc.sql("""
                        INSERT INTO data2flow_core.device_models (organization_id, code, vendor, name, protocol, kind)
                        VALUES (:org, :code, 'Milesight', :code, 'LORAWAN', 'SENSOR') RETURNING id""")
                .param("org", orgId).param("code", code).query(Long.class).single();
        for (String key : metricKeys) {
            jdbc.sql("INSERT INTO data2flow_core.model_metrics (organization_id, model_id, metric_key) VALUES (:org, :model, :key)")
                    .param("org", orgId).param("model", id).param("key", key).update();
        }
        return id;
    }

    /** 기기(상태·공간·모델 지정) */
    public long device(long orgId, long sourceId, String externalId, String status, Long spaceId, Long modelId) {
        return device(orgId, sourceId, externalId, status, spaceId, modelId, false);
    }

    public long device(long orgId, long sourceId, String externalId, String status, Long spaceId, Long modelId, boolean virtual) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.devices (organization_id, source_id, external_id, name, status, space_id, model_id, is_virtual)
                        VALUES (:org, :source, :ext, :name, :status, :space, :model, :virtual) RETURNING id""")
                .param("org", orgId).param("source", sourceId).param("ext", externalId).param("name", "기기 " + externalId)
                .param("status", status).param("space", spaceId).param("model", modelId).param("virtual", virtual)
                .query(Long.class).single();
    }

    /** 사용자 역할의 공간 범위(IAM-04.02). 빈 목록이면 전체 */
    public void spaceScope(long orgId, long userId, List<Long> spaceIds) {
        jdbc.sql("UPDATE data2flow_core.user_roles SET space_scope = CAST(:scope AS bigint[]) WHERE organization_id = :org AND user_id = :user")
                .param("scope", Pg.bigintArray(spaceIds)).param("org", orgId).param("user", userId).update();
    }

    /** pipeline 소유 telemetry 한 행(테스트용 쓰기) */
    public void telemetry(long orgId, long deviceId, String metricKey, Instant time, double value, int quality, boolean virtual) {
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry (device_id, metric_key, time, organization_id, value, quality, is_virtual, received_at)
                        VALUES (:device, :key, :time, :org, :value, :quality, :virtual, :time)""")
                .param("device", deviceId).param("key", metricKey).param("time", Pg.ts(time)).param("org", orgId)
                .param("value", value).param("quality", quality).param("virtual", virtual).update();
    }

    /** pipeline 소유 device_state 한 행(테스트용 쓰기). latestJson 예: {"temperature":{"v":23.4,"t":"…","q":0}} */
    public void deviceState(long orgId, long deviceId, String connectivity, Instant lastSeenAt, String latestJson) {
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.device_state (device_id, organization_id, last_seen_at, last_measured_at, connectivity, latest)
                        VALUES (:device, :org, :seen, :seen, :conn, CAST(:latest AS jsonb))
                        ON CONFLICT (device_id) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at, connectivity = EXCLUDED.connectivity,
                               latest = EXCLUDED.latest""")
                .param("device", deviceId).param("org", orgId).param("seen", Pg.ts(lastSeenAt)).param("conn", connectivity)
                .param("latest", latestJson == null ? "{}" : latestJson).update();
    }
}
