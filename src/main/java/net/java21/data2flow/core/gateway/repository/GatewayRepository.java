package net.java21.data2flow.core.gateway.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** LoRa 게이트웨이({@code gateways}, DEV-05.01·BR-DEV-19). 수신 분포는 pipeline 소유 표를 읽기만 한다 */
@Repository
public class GatewayRepository {

    private static final String SELECT = """
            SELECT g.id, g.organization_id, g.source_id, s.name AS source_name, g.gateway_eui, g.name, g.space_id, sp.name AS space_name,
                   g.last_seen_at, g.offline_after_sec, g.updated_at,
                   CASE WHEN g.last_seen_at IS NULL THEN 'UNKNOWN'
                        WHEN g.last_seen_at < CAST(:now AS timestamptz) - make_interval(secs => g.offline_after_sec) THEN 'OFFLINE'
                        ELSE 'ONLINE' END AS effective_status,
                   (SELECT count(*) FROM data2flow_pipeline.device_state st
                     WHERE st.organization_id = g.organization_id AND st.best_gateway_eui = g.gateway_eui
                       AND st.last_seen_at > CAST(:now AS timestamptz) - interval '24 hours') AS device_count,
                   (SELECT count(*) FROM data2flow_pipeline.link_qualities lq
                     WHERE lq.organization_id = g.organization_id AND lq.gateway_eui = g.gateway_eui
                       AND lq.time > CAST(:now AS timestamptz) - interval '24 hours') AS uplinks
              FROM data2flow_core.gateways g
              LEFT JOIN data2flow_core.data_sources s ON s.id = g.source_id AND s.organization_id = g.organization_id
              LEFT JOIN data2flow_core.spaces sp ON sp.id = g.space_id AND sp.organization_id = g.organization_id
            """;

    private final JdbcClient jdbc;

    public GatewayRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 목록. allowedSpaceIds가 있으면 그 공간에 놓인 게이트웨이만(BR-DEV-25) */
    public List<GatewayRow> list(long organizationId, Long sourceId, String status, Long spaceId, Set<Long> allowedSpaceIds, Instant now,
                                 int limit, long offset) {
        return jdbc.sql("SELECT * FROM (" + SELECT + where() + ") x WHERE (CAST(:status AS text) IS NULL OR x.effective_status = :status)"
                        + " ORDER BY x.gateway_eui, x.id LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("source", sourceId).param("space", spaceId)
                .param("allowed", allowedSpaceIds == null ? null : Pg.bigintArray(allowedSpaceIds)).param("status", status)
                .param("now", Pg.ts(now)).param("limit", limit).param("offset", offset).query(GatewayRepository::map).list();
    }

    public long count(long organizationId, Long sourceId, String status, Long spaceId, Set<Long> allowedSpaceIds, Instant now) {
        return jdbc.sql("SELECT count(*) FROM (" + SELECT + where() + ") x WHERE (CAST(:status AS text) IS NULL OR x.effective_status = :status)")
                .param("org", organizationId).param("source", sourceId).param("space", spaceId)
                .param("allowed", allowedSpaceIds == null ? null : Pg.bigintArray(allowedSpaceIds)).param("status", status)
                .param("now", Pg.ts(now)).query(Long.class).single();
    }

    public Optional<GatewayRow> findById(long organizationId, long id, Instant now) {
        return jdbc.sql(SELECT + " WHERE g.organization_id = :org AND g.id = :id")
                .param("org", organizationId).param("id", id).param("now", Pg.ts(now)).query(GatewayRepository::map).optional();
    }

    public int update(long organizationId, long id, String name, Long spaceId, int offlineAfterSec, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.gateways SET name = :name, space_id = :space, offline_after_sec = :offline, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("name", name).param("space", spaceId).param("offline", offlineAfterSec).param("now", Pg.ts(now))
                .param("org", organizationId).param("id", id).update();
    }

    /**
     * 업링크 게이트웨이 기록(BR-DEV-19): 처음 보면 이름=EUI로 만들고, 있으면 마지막 수신 시각을 더 늦은 값으로 갱신한다.
     * 지웠던 게이트웨이도 다시 수신되면 다시 생긴다. 새로 만들었으면 true
     */
    public boolean upsertSeen(long organizationId, long sourceId, String gatewayEui, Instant seenAt, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.gateways (organization_id, source_id, gateway_eui, name, last_seen_at, status, created_at, updated_at)
                        VALUES (:org, :source, :eui, :eui, :seen, 'ONLINE', :now, :now)
                        ON CONFLICT (source_id, gateway_eui) DO UPDATE
                           SET last_seen_at = GREATEST(data2flow_core.gateways.last_seen_at, EXCLUDED.last_seen_at),
                               status = 'ONLINE', updated_at = EXCLUDED.updated_at
                        RETURNING (xmax = 0) AS inserted""")
                .param("org", organizationId).param("source", sourceId).param("eui", gatewayEui).param("seen", Pg.ts(seenAt))
                .param("now", Pg.ts(now)).query(Boolean.class).single();
    }

    /** API-DEV-61 시간별 업링크 수(이 게이트웨이가 받은 신호 기록) */
    public List<HourCount> findUplinksByHour(long organizationId, String gatewayEui, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT date_trunc('hour', time) AS t, count(*) AS n FROM data2flow_pipeline.link_qualities
                         WHERE organization_id = :org AND gateway_eui = :eui AND time >= :from AND time < :to GROUP BY 1 ORDER BY 1""")
                .param("org", organizationId).param("eui", gatewayEui).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query((rs, n) -> new HourCount(Pg.instant(rs, "t"), rs.getLong("n"))).list();
    }

    /**
     * API-DEV-61 기기별 평균 rssi·snr과 최적 게이트웨이 비율(DEV-05.02, AT-DEV-11.3). 비율 = 기기가 보낸 업링크(시각) 중 이 게이트웨이가
     * 가장 센 신호(rssi 최대)로 받은 비율. allowedSpaceIds가 있으면 그 공간의 기기만
     */
    public List<DeviceLink> findDeviceLinks(long organizationId, String gatewayEui, Instant from, Instant to, Set<Long> allowedSpaceIds) {
        return jdbc.sql("""
                        WITH lq AS (
                            SELECT l.device_id, l.gateway_eui, l.time, l.rssi, l.snr FROM data2flow_pipeline.link_qualities l
                             WHERE l.organization_id = :org AND l.time >= :from AND l.time < :to
                               AND l.device_id IN (SELECT x.device_id FROM data2flow_pipeline.link_qualities x
                                                    WHERE x.organization_id = :org AND x.gateway_eui = :eui AND x.time >= :from AND x.time < :to)),
                        best AS (
                            SELECT DISTINCT ON (device_id, time) device_id, time, gateway_eui FROM lq
                             ORDER BY device_id, time, rssi DESC NULLS LAST, gateway_eui)
                        SELECT d.id, d.name, avg(lq.rssi) FILTER (WHERE lq.gateway_eui = :eui) AS avg_rssi,
                               avg(lq.snr) FILTER (WHERE lq.gateway_eui = :eui) AS avg_snr,
                               count(*) FILTER (WHERE lq.gateway_eui = :eui) AS uplinks,
                               (SELECT count(*) FROM best b WHERE b.device_id = d.id AND b.gateway_eui = :eui) AS best_count,
                               (SELECT count(*) FROM best b WHERE b.device_id = d.id) AS total_count
                          FROM lq JOIN data2flow_core.devices d ON d.id = lq.device_id AND d.organization_id = :org
                         WHERE (CAST(:allowed AS bigint[]) IS NULL OR d.space_id = ANY(CAST(:allowed AS bigint[])))
                         GROUP BY d.id, d.name ORDER BY d.name, d.id""")
                .param("org", organizationId).param("eui", gatewayEui).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .param("allowed", allowedSpaceIds == null ? null : Pg.bigintArray(allowedSpaceIds))
                .query((rs, n) -> new DeviceLink(rs.getLong("id"), rs.getString("name"), rs.getBigDecimal("avg_rssi"),
                        rs.getBigDecimal("avg_snr"), rs.getLong("uplinks"), rs.getLong("best_count"), rs.getLong("total_count")))
                .list();
    }

    /** API-DEV-61 rssi 분포(10 dBm 칸, 칸 아래 경계) */
    public List<HistogramBucket> findRssiHistogram(long organizationId, String gatewayEui, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT CAST(floor(rssi / 10) * 10 AS integer) AS bucket, count(*) AS n FROM data2flow_pipeline.link_qualities
                         WHERE organization_id = :org AND gateway_eui = :eui AND time >= :from AND time < :to AND rssi IS NOT NULL
                         GROUP BY 1 ORDER BY 1""")
                .param("org", organizationId).param("eui", gatewayEui).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query((rs, n) -> new HistogramBucket(rs.getInt("bucket"), rs.getLong("n"))).list();
    }

    public record HourCount(Instant t, long count) {
    }

    public record DeviceLink(long deviceId, String name, java.math.BigDecimal avgRssi, java.math.BigDecimal avgSnr, long uplinks,
                             long bestCount, long totalCount) {
    }

    public record HistogramBucket(int fromDbm, long count) {
    }

    private static String where() {
        return """
                 WHERE g.organization_id = :org AND (CAST(:source AS bigint) IS NULL OR g.source_id = :source)
                   AND (CAST(:space AS bigint) IS NULL OR g.space_id = :space)
                   AND (CAST(:allowed AS bigint[]) IS NULL OR g.space_id = ANY(CAST(:allowed AS bigint[])))""";
    }

    static GatewayRow map(ResultSet rs, int n) throws SQLException {
        return new GatewayRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("source_id"), rs.getString("source_name"),
                rs.getString("gateway_eui"), rs.getString("name"), Pg.longOrNull(rs, "space_id"), rs.getString("space_name"),
                Pg.instant(rs, "last_seen_at"), rs.getInt("offline_after_sec"), rs.getString("effective_status"), rs.getLong("device_count"),
                rs.getLong("uplinks"), Pg.instant(rs, "updated_at"));
    }

    public record GatewayRow(long id, long organizationId, long sourceId, String sourceName, String gatewayEui, String name, Long spaceId,
                             String spaceName, Instant lastSeenAt, int offlineAfterSec, String status, long deviceCount24h,
                             long uplinks24h, Instant updatedAt) {
    }
}
