package net.java21.data2flow.core.dataexchange;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;

/** 내보내기·가져오기 시험 데이터(pipeline 소유 표에 generate_series로 많이 넣기) */
final class ExchangeTestData {

    private final JdbcClient jdbc;

    ExchangeTestData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 원본 count개(간격 step, 값 20 + i % 10) */
    void raw(long org, long device, String metric, Instant from, Duration step, int count) {
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry (device_id, metric_key, time, organization_id, value, quality, received_at)
                        SELECT :device, :metric, ts, :org, 20 + (i % 10), 0, ts
                          FROM generate_series(0, :count - 1) i,
                               LATERAL (SELECT CAST(:from AS timestamptz) + i * make_interval(secs => :step) AS ts) x""")
                .param("device", device).param("metric", metric).param("org", org).param("count", count).param("from", Pg.ts(from))
                .param("step", (double) step.toSeconds()).update();
    }

    /** 1시간 집계 count개(avg = 20 + i % 5) */
    void hourly(long org, long device, String metric, Instant from, int count) {
        jdbc.sql("""
                        INSERT INTO data2flow_pipeline.telemetry_1h (device_id, metric_key, bucket, organization_id, count, count_all, avg, min, max,
                               sum, last)
                        SELECT :device, :metric, CAST(:from AS timestamptz) + i * interval '1 hour', :org, 60, 60, 20 + (i % 5), 19, 26, 1200, 21
                          FROM generate_series(0, :count - 1) i""")
                .param("device", device).param("metric", metric).param("org", org).param("count", count).param("from", Pg.ts(from))
                .update();
    }

    long fileChunks(String ownerKind, long id) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.exchange_file_chunks WHERE owner_kind = :k AND owner_id = :id")
                .param("k", ownerKind).param("id", id).query(Long.class).single();
    }

    long outbox(long org, String routingKey) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.outboxes WHERE organization_id = :org AND routing_key = :key")
                .param("org", org).param("key", routingKey).query(Long.class).single();
    }

    String outboxPayload(long org, String routingKey) {
        return jdbc.sql("SELECT payload::text FROM data2flow_core.outboxes WHERE organization_id = :org AND routing_key = :key ORDER BY id DESC LIMIT 1")
                .param("org", org).param("key", routingKey).query(String.class).single();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
