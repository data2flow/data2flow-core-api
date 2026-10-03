package net.java21.data2flow.core.script.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 스크립트 기능이 읽기만 하는 다른 기능의 행: 연결 대상(소스·모델·기기), 테스트 실행 컨텍스트(기기 속성, pipeline device_state 직전 값),
 * 최근 원본(pipeline raw_messages, 읽기 전용 — conventions §6). 쓰기는 하지 않는다.
 */
@Repository
public class ScriptTargetRepository {

    private final JdbcClient jdbc;

    public ScriptTargetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SourceRef> findSource(long organizationId, long sourceId) {
        return jdbc.sql("""
                        SELECT id, code, name, decoder_config::text AS decoder_config FROM data2flow_core.data_sources
                         WHERE organization_id = :org AND id = :id AND archived_at IS NULL""")
                .param("org", organizationId).param("id", sourceId)
                .query((rs, n) -> new SourceRef(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("decoder_config")))
                .optional();
    }

    public Optional<ModelRef> findModelById(long organizationId, long modelId) {
        return jdbc.sql("SELECT id, code, name FROM data2flow_core.device_models WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", modelId)
                .query((rs, n) -> new ModelRef(rs.getLong("id"), rs.getString("code"), rs.getString("name"))).optional();
    }

    public Optional<ModelRef> findModelByCode(long organizationId, String code) {
        return jdbc.sql("SELECT id, code, name FROM data2flow_core.device_models WHERE organization_id = :org AND code = :code")
                .param("org", organizationId).param("code", code)
                .query((rs, n) -> new ModelRef(rs.getLong("id"), rs.getString("code"), rs.getString("name"))).optional();
    }

    public Optional<DeviceRef> findDevice(long organizationId, long deviceId) {
        return jdbc.sql("""
                        SELECT d.id, d.name, d.space_id, d.model_id, m.code AS model_code, m.name AS model_name, d.status
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_core.device_models m ON m.id = d.model_id
                         WHERE d.organization_id = :org AND d.id = :id AND d.status <> 'DELETED'""")
                .param("org", organizationId).param("id", deviceId)
                .query((rs, n) -> new DeviceRef(rs.getLong("id"), rs.getString("name"), Pg.longOrNull(rs, "space_id"),
                        Pg.longOrNull(rs, "model_id"), rs.getString("model_code"), rs.getString("model_name"), rs.getString("status")))
                .optional();
    }

    /** 기기 서버·공유 속성(DEV-07) key → JSON 값 문자열. 같은 키가 둘 다 있으면 SERVER가 이긴다 */
    public Map<String, String> findDeviceAttributes(long organizationId, long deviceId) {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT key, value::text AS value FROM data2flow_core.device_attributes
                         WHERE organization_id = :org AND device_id = :device AND scope IN ('SERVER', 'SHARED') AND value IS NOT NULL
                         ORDER BY CASE scope WHEN 'SHARED' THEN 0 ELSE 1 END, key""")
                .param("org", organizationId).param("device", deviceId)
                .query((rs, n) -> Map.entry(rs.getString("key"), rs.getString("value"))).list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    /** pipeline device_state.latest JSON(없으면 빈 값). 읽기 전용 */
    public Optional<String> findDeviceLatest(long organizationId, long deviceId) {
        return jdbc.sql("""
                        SELECT latest::text FROM data2flow_pipeline.device_state WHERE organization_id = :org AND device_id = :device""")
                .param("org", organizationId).param("device", deviceId).query(String.class).optional();
    }

    /** 최근 원본 하나(pipeline raw_messages, 읽기 전용). 소스 코드·디코더 설정과 함께 */
    public Optional<RawMessageRef> findRawMessage(long organizationId, long rawMessageId) {
        return jdbc.sql("""
                        SELECT r.id, r.source_id, r.device_id, r.topic, r.payload, r.payload_encoding, r.received_at,
                               ds.code AS source_code, ds.decoder_config::text AS decoder_config
                          FROM data2flow_pipeline.raw_messages r
                          LEFT JOIN data2flow_core.data_sources ds ON ds.id = r.source_id AND ds.organization_id = r.organization_id
                         WHERE r.organization_id = :org AND r.id = :id
                         ORDER BY r.received_at DESC LIMIT 1""")
                .param("org", organizationId).param("id", rawMessageId)
                .query((rs, n) -> new RawMessageRef(rs.getLong("id"), rs.getLong("source_id"), Pg.longOrNull(rs, "device_id"),
                        rs.getString("topic"), rs.getBytes("payload"), rs.getString("payload_encoding"), Pg.instant(rs, "received_at"),
                        rs.getString("source_code"), rs.getString("decoder_config")))
                .optional();
    }

    public record SourceRef(long id, String code, String name, String decoderConfig) {
    }

    public record ModelRef(long id, String code, String name) {
    }

    public record DeviceRef(long id, String name, Long spaceId, Long modelId, String modelCode, String modelName, String status) {
    }

    public record RawMessageRef(long id, long sourceId, Long deviceId, String topic, byte[] payload, String payloadEncoding,
                                Instant receivedAt, String sourceCode, String decoderConfig) {
    }
}
