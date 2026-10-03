package net.java21.data2flow.core.telemetry;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * WP-E 테스트 데이터: pipeline 소유 테이블(집계·통신 품질·공백·원본·실패 메시지·재처리 작업·품질 점수)에 바로 넣는다.
 * core-api는 이 테이블을 읽기만 하므로 테스트가 pipeline 대신 행을 만든다(M2Data의 telemetry·deviceState와 같은 방식).
 */
public class PipelineRows {

    private final JdbcClient jdbc;

    public PipelineRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 집계 한 행. table은 telemetry_1m·telemetry_1h·telemetry_1d. avg·min·max·sum·last·twa는 같은 값 v로 단순화(필요하면 따로) */
    public void agg(String table, long orgId, long deviceId, String metric, Instant bucket, int count, int countAll, Double v, boolean virtual) {
        agg(table, orgId, deviceId, metric, bucket, count, countAll, v, v, v, v == null ? null : v * count, v, v, virtual);
    }

    public void agg(String table, long orgId, long deviceId, String metric, Instant bucket, int count, int countAll, Double avg, Double min,
                    Double max, Double sum, Double last, Double twa, boolean virtual) {
        if (!List.of("telemetry_1m", "telemetry_1h", "telemetry_1d").contains(table)) {
            throw new IllegalArgumentException(table);
        }
        jdbc.sql("INSERT INTO data2flow_pipeline." + table + """
                         (device_id, metric_key, bucket, organization_id, count, count_all, avg, min, max, sum, first, last, twa, is_virtual)
                        VALUES (:device, :metric, :bucket, :org, :count, :countAll, :avg, :min, :max, :sum, :avg, :last, :twa, :virtual)""")
                .param("device", deviceId).param("metric", metric).param("bucket", Pg.ts(bucket)).param("org", orgId)
                .param("count", count).param("countAll", countAll).param("avg", avg).param("min", min).param("max", max)
                .param("sum", sum).param("last", last).param("twa", twa).param("virtual", virtual).update();
    }

    /** 원본 측정값(원본 메시지 ID·flags 지정) */
    public void telemetry(long orgId, long deviceId, String metric, Instant time, double value, int quality, int flags, Long rawMessageId) {
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry (device_id, metric_key, time, organization_id, value, quality, flags, received_at,
                            raw_message_id)
                        VALUES (:device, :metric, :time, :org, :value, :quality, :flags, :time, :raw)""")
                .param("device", deviceId).param("metric", metric).param("time", Pg.ts(time)).param("org", orgId).param("value", value)
                .param("quality", quality).param("flags", flags).param("raw", rawMessageId).update();
    }

    /** 통신 품질 한 행(게이트웨이별) */
    public void link(long orgId, long deviceId, String gatewayEui, Instant time, double rssi, double snr) {
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.link_qualities (device_id, gateway_eui, time, organization_id, rssi, snr, f_cnt)
                        VALUES (:device, :gw, :time, :org, :rssi, :snr, 1)""")
                .param("device", deviceId).param("gw", gatewayEui).param("time", Pg.ts(time)).param("org", orgId).param("rssi", rssi)
                .param("snr", snr).update();
    }

    /** 수신 공백 */
    public void gap(long orgId, long deviceId, Instant start, Instant end, int expected) {
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.data_gaps (organization_id, device_id, gap_start, gap_end, expected_count, detected_at)
                        VALUES (:org, :device, :start, :end, :expected, :end)""")
                .param("org", orgId).param("device", deviceId).param("start", Pg.ts(start)).param("end", Pg.ts(end)).param("expected", expected)
                .update();
    }

    /** 원본 메시지. processedAt이 null이면 처리 전(RECEIVED) */
    public long raw(long orgId, long sourceId, Long deviceId, String status, Instant receivedAt, Instant processedAt, String payload,
                    String topic, boolean virtual) {
        return jdbc.sql("""
                        INSERT INTO data2flow_pipeline.raw_messages (organization_id, source_id, device_id, source_type, topic, payload,
                            payload_encoding, ingress_instance, dedup_key, stream_partition, stream_offset, external_id, status, error_code,
                            processing_trace, metric_count, is_virtual, received_at, processed_at)
                        VALUES (:org, :source, :device, 'MQTT_SUBSCRIBE', :topic, :payload, 'JSON', 'ingress-0', md5(random()::text), 0, 1,
                            'dev-' || coalesce(CAST(:device AS text), 'x'), :status,
                            CASE WHEN :status IN ('OK', 'DUPLICATE', 'RECEIVED') THEN NULL ELSE 'ING_' || :status END,
                            CAST(:trace AS jsonb), 1, :virtual, :received, :processed)
                        RETURNING id""")
                .param("org", orgId).param("source", sourceId).param("device", deviceId).param("topic", topic)
                .param("payload", payload.getBytes(StandardCharsets.UTF_8)).param("status", status)
                .param("trace", "{\"stages\":[{\"stage\":\"DECODE\",\"ok\":true,\"ms\":1,\"info\":\"chirpstack-v4 1\"}],\"canonical\":{\"v\":1}}")
                .param("virtual", virtual).param("received", Pg.ts(receivedAt)).param("processed", Pg.ts(processedAt))
                .query(Long.class).single();
    }

    public Instant rawReceivedAt(long rawId) {
        return jdbc.sql("SELECT received_at FROM data2flow_pipeline.raw_messages WHERE id = :id").param("id", rawId)
                .query((rs, n) -> Pg.instant(rs, "received_at")).single();
    }

    /** 실패 메시지 */
    public long dlq(long orgId, long rawId, String stage, String errorCode, String status, Instant createdAt, Long lockedBy, Instant lockedUntil) {
        return jdbc.sql("""
                        INSERT INTO data2flow_pipeline.dlq_items (organization_id, raw_message_id, raw_received_at, stage, error_code,
                            error_message, attempts, status, locked_by, locked_until, created_at)
                        VALUES (:org, :raw, :rawAt, :stage, :code, :msg, 1, :status, :lockedBy, :lockedUntil, :created)
                        RETURNING id""")
                .param("org", orgId).param("raw", rawId).param("rawAt", Pg.ts(rawReceivedAt(rawId))).param("stage", stage)
                .param("code", errorCode).param("msg", errorCode + " 메시지").param("status", status).param("lockedBy", lockedBy)
                .param("lockedUntil", Pg.ts(lockedUntil)).param("created", Pg.ts(createdAt))
                .query(Long.class).single();
    }

    /** 재처리 작업 */
    public long job(long orgId, long sourceId, String status, long processed, List<Long> deviceIds, Instant from) {
        return jdbc.sql("""
                        INSERT INTO data2flow_pipeline.reprocess_jobs (organization_id, source_id, device_ids, period_from, period_to, status,
                            total, processed, decoder_version, requested_by)
                        VALUES (:org, :source, CAST(:devices AS bigint[]), :from, :to, :status, 100, :processed, '1', 1)
                        RETURNING id""")
                .param("org", orgId).param("source", sourceId).param("devices", deviceIds == null ? null : Pg.bigintArray(deviceIds))
                .param("from", Pg.ts(from)).param("to", Pg.ts(from.plusSeconds(86400))).param("status", status).param("processed", processed)
                .query(Long.class).single();
    }

    /** 일별 품질 점수 */
    public void quality(long orgId, long deviceId, LocalDate day, int score, int expected, int received) {
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.data_quality_daily (device_id, day, organization_id, score, completeness, timeliness,
                            validity, stability, expected_count, received_count, late_count, out_of_range_count, suspect_count)
                        VALUES (:device, :day, :org, :score, :score, 100, 100, 100, :expected, :received, 1, 2, 0)""")
                .param("device", deviceId).param("day", day).param("org", orgId).param("score", score).param("expected", expected)
                .param("received", received).update();
    }
}
