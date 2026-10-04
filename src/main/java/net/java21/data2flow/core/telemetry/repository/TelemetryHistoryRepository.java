package net.java21.data2flow.core.telemetry.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 과거 재생 원천(API-FLW-87, ADR-051): pipeline 시계열({@code data2flow_pipeline.telemetry}, 읽기 전용)을 (기기, 측정 시각)마다 한 메시지로 묶어
 * 측정 시각 순서로 준다. 위치는 (측정 시각, 기기 ID) 다음부터.
 */
@Repository
public class TelemetryHistoryRepository {

    private final JdbcClient jdbc;

    public TelemetryHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 한 메시지(측정 항목은 {@code key|value|quality|unit} 줄로) */
    public record Row(long deviceId, Instant time, Instant receivedAt, boolean virtual, Long rawMessageId, long sourceId, String externalId,
                      String deviceStatus, Long modelId, Long spaceId, List<String> metrics) {
    }

    static final String WHERE = """
             WHERE t.organization_id = :org AND t.time >= :from AND t.time < :to
               AND (CAST(:devices AS bigint[]) IS NULL OR t.device_id = ANY(CAST(:devices AS bigint[])))""";

    static final String SELECT = """
                        SELECT t.device_id, t.time, max(t.received_at) AS received_at, bool_or(t.is_virtual) AS is_virtual,
                               max(t.raw_message_id) AS raw_message_id, d.source_id, d.external_id, d.status, d.model_id, d.space_id,
                               array_agg(t.metric_key || '|' || t.value || '|' || t.quality || '|' || coalesce(m.unit, '') ORDER BY t.metric_key)
                                   AS metrics
                          FROM data2flow_pipeline.telemetry t
                          JOIN data2flow_core.devices d ON d.id = t.device_id AND d.organization_id = t.organization_id
                          LEFT JOIN data2flow_core.metrics m ON m.organization_id = t.organization_id AND m.key = t.metric_key
""";

    /** 원본 메시지 하나가 만든 텔레메트리(시험 실행 rawMessageId 변환, API-FLW-12) */
    public List<Row> findByRawMessage(long organizationId, long rawMessageId) {
        return jdbc.sql(SELECT + """
                         WHERE t.organization_id = :org AND t.raw_message_id = :raw
                         GROUP BY t.device_id, t.time, d.source_id, d.external_id, d.status, d.model_id, d.space_id
                         ORDER BY t.time, t.device_id LIMIT 10""")
                .param("org", organizationId).param("raw", rawMessageId).query(TelemetryHistoryRepository::map).list();
    }

    public List<Row> page(long organizationId, Instant from, Instant to, List<Long> deviceIds, Instant afterTime, Long afterDevice, int limit) {
        return jdbc.sql(SELECT + WHERE + """
                           AND (CAST(:afterTime AS timestamptz) IS NULL OR (t.time, t.device_id) > (CAST(:afterTime AS timestamptz), :afterDevice))
                         GROUP BY t.device_id, t.time, d.source_id, d.external_id, d.status, d.model_id, d.space_id
                         ORDER BY t.time, t.device_id
                         LIMIT :limit""")
                .param("org", organizationId).param("from", Pg.ts(from)).param("to", Pg.ts(to)).param("devices", array(deviceIds))
                .param("afterTime", afterTime == null ? null : Pg.ts(afterTime)).param("afterDevice", afterDevice == null ? 0L : afterDevice)
                .param("limit", limit)
                .query(TelemetryHistoryRepository::map).list();
    }

    static Row map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(rs.getLong("device_id"), Pg.instant(rs, "time"), Pg.instant(rs, "received_at"), rs.getBoolean("is_virtual"),
                Pg.longOrNull(rs, "raw_message_id"), rs.getLong("source_id"), rs.getString("external_id"), rs.getString("status"),
                Pg.longOrNull(rs, "model_id"), Pg.longOrNull(rs, "space_id"), List.of((String[]) rs.getArray("metrics").getArray()));
    }

    public long count(long organizationId, Instant from, Instant to, List<Long> deviceIds) {
        return jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM data2flow_pipeline.telemetry t " + WHERE + " GROUP BY t.device_id, t.time) g")
                .param("org", organizationId).param("from", Pg.ts(from)).param("to", Pg.ts(to)).param("devices", array(deviceIds))
                .query(Long.class).single();
    }

    static String array(List<Long> ids) {
        return ids == null || ids.isEmpty() ? null : Pg.bigintArray(ids);
    }
}
