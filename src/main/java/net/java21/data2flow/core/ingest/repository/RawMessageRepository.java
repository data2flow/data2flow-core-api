package net.java21.data2flow.core.ingest.repository;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.ingest.domain.RawCursor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 원본 메시지 조회(API-ING-05·06, pipeline 소유 {@code raw_messages} 읽기 전용). 공간 범위가 제한된 사용자는 기기가 범위 안인 원본만
 * 보고(기기를 모르는 원본은 보지 않는다), 개수에도 넣지 않는다(AT-ING-06.3, IAM-04.06).
 */
@Repository
public class RawMessageRepository {

    private static final String FROM = """
             FROM data2flow_pipeline.raw_messages r
             LEFT JOIN data2flow_core.devices d ON d.id = r.device_id AND d.organization_id = r.organization_id
             LEFT JOIN data2flow_core.data_sources s ON s.id = r.source_id AND s.organization_id = r.organization_id
            """;
    private static final String FILTER = """
             WHERE r.organization_id = :org AND r.received_at >= :from AND r.received_at < :to
               AND (cardinality(CAST(:sources AS bigint[])) = 0 OR r.source_id = ANY(CAST(:sources AS bigint[])))
               AND (cardinality(CAST(:devices AS bigint[])) = 0 OR r.device_id = ANY(CAST(:devices AS bigint[])))
               AND (cardinality(CAST(:statuses AS varchar[])) = 0 OR r.status = ANY(CAST(:statuses AS varchar[])))
               AND (:topic = '' OR position(:topic in coalesce(r.topic, '')) > 0)
               AND (:virtual OR NOT r.is_virtual)
               AND (:unrestricted OR d.space_id = ANY(CAST(:allowed AS bigint[])))
            """;

    private final JdbcClient jdbc;

    public RawMessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 목록 조건 */
    public record RawFilter(long organizationId, Instant from, Instant to, List<Long> sourceIds, List<Long> deviceIds,
                            List<String> statuses, String topicContains, boolean includeVirtual, SpaceScope scope) {
    }

    /** 목록(수신 시각·ID 내림차순, 커서 다음부터) */
    public List<RawSummaryRow> findPage(RawFilter filter, RawCursor cursor, int limit) {
        return bind(jdbc.sql("""
                        SELECT r.id, r.received_at, r.source_id, s.name AS source_name, r.topic, r.device_id, d.name AS device_name,
                               r.external_id, r.status, r.metric_count, octet_length(r.payload) AS size_bytes, r.is_virtual
                        """ + FROM + FILTER + """
                           AND (:cursorId = 0 OR (r.received_at, r.id) < (:cursorAt, :cursorId))
                         ORDER BY r.received_at DESC, r.id DESC LIMIT :limit"""), filter)
                .param("cursorId", cursor == null ? 0L : cursor.id())
                .param("cursorAt", Pg.ts(cursor == null ? filter.to() : cursor.receivedAt()))
                .param("limit", limit)
                .query(RawMessageRepository::summary).list();
    }

    /** 조건에 맞는 전체의 처리 결과별 건수 */
    public Map<String, Long> countByStatus(RawFilter filter) {
        Map<String, Long> counts = new LinkedHashMap<>();
        bind(jdbc.sql("SELECT r.status, count(*) AS n " + FROM + FILTER + " GROUP BY r.status ORDER BY r.status"), filter)
                .query((rs, n) -> Map.entry(rs.getString("status"), rs.getLong("n"))).list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, RawFilter f) {
        SpaceScope scope = f.scope();
        return spec.param("org", f.organizationId()).param("from", Pg.ts(f.from())).param("to", Pg.ts(f.to()))
                .param("sources", Pg.bigintArray(f.sourceIds())).param("devices", Pg.bigintArray(f.deviceIds()))
                .param("statuses", Pg.textArray(f.statuses())).param("topic", f.topicContains() == null ? "" : f.topicContains())
                .param("virtual", f.includeVirtual()).param("unrestricted", scope.unrestricted())
                .param("allowed", Pg.bigintArray(scope.allowedSpaceIds()));
    }

    /** 원본 한 건(보관 기간 안). 공간 범위는 서비스가 deviceSpaceId로 확인한다 */
    public Optional<RawDetailRow> findById(long organizationId, long id, Instant since) {
        return jdbc.sql("""
                        SELECT r.id, r.received_at, r.processed_at, r.source_id, s.name AS source_name, r.source_type, r.topic, r.device_id,
                               d.name AS device_name, d.space_id AS device_space_id, r.external_id, r.ingress_instance, r.dedup_key,
                               r.payload, r.payload_encoding, r.status, r.error_code, r.error_detail::text AS error_detail,
                               r.processing_trace::text AS processing_trace, r.is_virtual
                        """ + FROM + """
                         WHERE r.organization_id = :org AND r.id = :id AND r.received_at >= :since
                         LIMIT 1""")
                .param("org", organizationId).param("id", id).param("since", Pg.ts(since))
                .query((rs, n) -> new RawDetailRow(rs.getLong("id"), Pg.instant(rs, "received_at"), Pg.instant(rs, "processed_at"),
                        rs.getLong("source_id"), rs.getString("source_name"), rs.getString("source_type"), rs.getString("topic"),
                        Pg.longOrNull(rs, "device_id"), rs.getString("device_name"), Pg.longOrNull(rs, "device_space_id"),
                        rs.getString("external_id"), rs.getString("ingress_instance"), rs.getString("dedup_key"), rs.getBytes("payload"),
                        rs.getString("payload_encoding"), rs.getString("status"), rs.getString("error_code"), rs.getString("error_detail"),
                        rs.getString("processing_trace"), rs.getBoolean("is_virtual")))
                .optional();
    }

    /** 이 원본에서 저장된 측정값(telemetry.raw_message_id) */
    public List<StoredRow> findStored(long organizationId, long deviceId, long rawMessageId) {
        return jdbc.sql("""
                        SELECT t.metric_key, t.value, m.unit, t.quality, (t.flags & 1) = 1 AS late
                          FROM data2flow_pipeline.telemetry t
                          LEFT JOIN data2flow_core.metrics m ON m.organization_id = t.organization_id AND m.key = t.metric_key
                         WHERE t.organization_id = :org AND t.device_id = :device AND t.raw_message_id = :raw
                         ORDER BY t.metric_key""")
                .param("org", organizationId).param("device", deviceId).param("raw", rawMessageId)
                .query((rs, n) -> new StoredRow(rs.getString("metric_key"), rs.getDouble("value"), rs.getString("unit"), rs.getInt("quality"),
                        rs.getBoolean("late")))
                .list();
    }

    private static RawSummaryRow summary(ResultSet rs, int n) throws SQLException {
        int metricCount = rs.getInt("metric_count");
        Integer metrics = rs.wasNull() ? null : metricCount;
        return new RawSummaryRow(rs.getLong("id"), Pg.instant(rs, "received_at"), rs.getLong("source_id"), rs.getString("source_name"),
                rs.getString("topic"), Pg.longOrNull(rs, "device_id"), rs.getString("device_name"), rs.getString("external_id"),
                rs.getString("status"), metrics, rs.getInt("size_bytes"), rs.getBoolean("is_virtual"));
    }

    /** 목록 한 행 */
    public record RawSummaryRow(long id, Instant receivedAt, long sourceId, String sourceName, String topic, Long deviceId, String deviceName,
                                String externalId, String status, Integer metricCount, int sizeBytes, boolean virtual) {
    }

    /** 상세 한 행(JSON 열은 문자열) */
    public record RawDetailRow(long id, Instant receivedAt, Instant processedAt, long sourceId, String sourceName, String sourceType,
                               String topic, Long deviceId, String deviceName, Long deviceSpaceId, String externalId,
                               String ingressInstance, String dedupKey, byte[] payload, String payloadEncoding, String status,
                               String errorCode, String errorDetail, String processingTrace, boolean virtual) {
    }

    /** 저장된 측정값 */
    public record StoredRow(String metricKey, double value, String unit, int quality, boolean late) {
    }
}
