package net.java21.data2flow.core.live;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** WP-F 테스트 데이터: 목표 환경, 소스 분 통계·런타임, 실패 보관함, 원본 메시지, 세션 계보 */
public class LiveTestData {

    private final JdbcClient jdbc;

    public LiveTestData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * MQTT 구독 소스(ACTIVE). M2Data.source와 같지만 unknown_device_policy를 직접 넣는다: 기반 마이그레이션의 기본값 'AUTO_REGISTER'(13자)가
     * 열 길이 varchar(12)를 넘어 기본값으로는 넣을 수 없다(기반 결함, 보고함).
     */
    public long source(long orgId, String code) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.data_sources (organization_id, code, name, type, lifecycle, connection, decoder_key,
                                                                unknown_device_policy, created_by, updated_by)
                        VALUES (:org, :code, :name, 'MQTT_SUBSCRIBE', 'ACTIVE', CAST(:conn AS jsonb), 'chirpstack-v4', 'REJECT', 0, 0)
                        RETURNING id""")
                .param("org", orgId).param("code", code).param("name", "소스 " + code)
                .param("conn", "{\"url\":\"wss://broker.test/mqtt\",\"protocol\":\"wss\"}")
                .query(Long.class).single();
    }

    public void target(long orgId, long spaceId, String metricKey, Double min, Double max) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.space_targets (organization_id, space_id, metric_key, min_value, max_value)
                        VALUES (:org, :space, :key, :min, :max)""")
                .param("org", orgId).param("space", spaceId).param("key", metricKey).param("min", min).param("max", max).update();
    }

    public void stat(long orgId, long sourceId, Instant minute, int received, int scriptErrors) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.source_stat_1m (source_id, minute, organization_id, received, accepted, script_errors)
                        VALUES (:source, :minute, :org, :received, :accepted, :script)""")
                .param("source", sourceId).param("minute", Pg.ts(minute)).param("org", orgId).param("received", received)
                .param("accepted", received - scriptErrors).param("script", scriptErrors).update();
    }

    public void runtime(long orgId, long sourceId, String instance, String state, Instant at) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.source_runtimes (source_id, instance_id, organization_id, state, reported_at)
                        VALUES (:source, :instance, :org, :state, :at)""")
                .param("source", sourceId).param("instance", instance).param("org", orgId).param("state", state).param("at", Pg.ts(at))
                .update();
    }

    public void siteOf(long orgId, long sourceId, long siteId) {
        jdbc.sql("UPDATE data2flow_core.data_sources SET site_id = :site WHERE id = :id AND organization_id = :org")
                .param("site", siteId).param("id", sourceId).param("org", orgId).update();
    }

    public void dlq(long orgId, String stage, Instant at) {
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.dlq_items (organization_id, raw_message_id, raw_received_at, stage, error_code,
                                                                  error_message, created_at)
                        VALUES (:org, 1, :at, :stage, 'STORE_ERROR', 'x', :at)""")
                .param("org", orgId).param("at", Pg.ts(at)).param("stage", stage).update();
    }

    /** 원본 메시지(JSON payload). 돌려주는 값은 raw_messages.id */
    public long raw(long orgId, long sourceId, Long deviceId, String topic, String payload, String status, Instant receivedAt,
                    String trace) {
        return raw(orgId, sourceId, deviceId, topic, payload.getBytes(StandardCharsets.UTF_8), "JSON", status, receivedAt, trace);
    }

    public long raw(long orgId, long sourceId, Long deviceId, String topic, byte[] payload, String encoding, String status,
                    Instant receivedAt, String trace) {
        return jdbc.sql("""
                        INSERT INTO data2flow_pipeline.raw_messages (organization_id, source_id, device_id, source_type, topic, payload,
                               payload_encoding, ingress_instance, dedup_key, stream_partition, stream_offset, status, received_at,
                               processing_trace)
                        VALUES (:org, :source, :device, 'MQTT_SUBSCRIBE', :topic, :payload, :enc, 'ingress-0', :dedup, 0, 1, :status, :at,
                                CAST(:trace AS jsonb))
                        RETURNING id""")
                .param("org", orgId).param("source", sourceId).param("device", deviceId).param("topic", topic)
                .param("payload", payload).param("enc", encoding).param("dedup", UUID.randomUUID().toString())
                .param("status", status).param("at", Pg.ts(receivedAt)).param("trace", trace)
                .query(Long.class).single();
    }

    /** 세션 계보 한 행(웹 로그인 세션) */
    public UUID session(long orgId, long userId, Instant now) {
        UUID sid = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO data2flow_core.refresh_tokens (jti, session_id, organization_id, user_id, token_hash, issued_at,
                               last_used_at, expires_at, absolute_expires_at)
                        VALUES (:jti, :sid, :org, :user, :hash, :now, :now, :exp, :exp)""")
                .param("jti", UUID.randomUUID()).param("sid", sid).param("org", orgId).param("user", userId)
                .param("hash", HexFormat.of().formatHex(UUID.randomUUID().toString().repeat(2).getBytes(StandardCharsets.UTF_8), 0, 32))
                .param("now", Pg.ts(now)).param("exp", Pg.ts(now.plus(Duration.ofHours(6))))
                .update();
        return sid;
    }

    public void revokeSession(UUID sid, Instant now) {
        jdbc.sql("UPDATE data2flow_core.refresh_tokens SET revoked_at = :now, revoke_reason = 'LOGOUT' WHERE session_id = :sid")
                .param("now", Pg.ts(now)).param("sid", sid).update();
    }
}
