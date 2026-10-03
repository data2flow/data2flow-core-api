package net.java21.data2flow.core.ingest.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 수집 운영 설정: 수집 알람 기준({@code ingest_alert_thresholds}, API-ING-04), 운영 알람 기준({@code ops_thresholds}, API-OPS-05),
 * 실패 메시지 처리 표시({@code dlq_item_claims}, TC-ING-089).
 */
@Repository
public class IngestSettingsRepository {

    private final JdbcClient jdbc;

    public IngestSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- ingest_alert_thresholds

    public Optional<IngestThresholdRow> findIngestThresholds(long organizationId) {
        return jdbc.sql("""
                        SELECT lag_warn_sec, lag_critical_sec, heartbeat_critical_sec, version, updated_by, updated_at
                          FROM data2flow_core.ingest_alert_thresholds WHERE organization_id = :org""")
                .param("org", organizationId)
                .query((rs, n) -> new IngestThresholdRow(rs.getInt("lag_warn_sec"), rs.getInt("lag_critical_sec"),
                        rs.getInt("heartbeat_critical_sec"), rs.getInt("version"), Pg.longOrNull(rs, "updated_by"), Pg.instant(rs, "updated_at")))
                .optional();
    }

    /** 처음 저장(version 1). 이미 있으면 0행 */
    public int insertIngestThresholds(long organizationId, int lagWarnSec, int lagCriticalSec, int heartbeatCriticalSec, long userId,
                                      Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.ingest_alert_thresholds (organization_id, lag_warn_sec, lag_critical_sec,
                            heartbeat_critical_sec, version, updated_by, created_at, updated_at)
                        VALUES (:org, :warn, :critical, :heartbeat, 1, :by, :now, :now)
                        ON CONFLICT (organization_id) DO NOTHING""")
                .param("org", organizationId).param("warn", lagWarnSec).param("critical", lagCriticalSec).param("heartbeat", heartbeatCriticalSec)
                .param("by", userId).param("now", Pg.ts(now)).update();
    }

    /** baseVersion이 맞을 때만 갱신(BR-OPS-21) */
    public int updateIngestThresholds(long organizationId, int baseVersion, int lagWarnSec, int lagCriticalSec, int heartbeatCriticalSec,
                                      long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.ingest_alert_thresholds
                           SET lag_warn_sec = :warn, lag_critical_sec = :critical, heartbeat_critical_sec = :heartbeat,
                               version = version + 1, updated_by = :by, updated_at = :now
                         WHERE organization_id = :org AND version = :base""")
                .param("warn", lagWarnSec).param("critical", lagCriticalSec).param("heartbeat", heartbeatCriticalSec).param("by", userId)
                .param("now", Pg.ts(now)).param("org", organizationId).param("base", baseVersion).update();
    }

    // ---------------------------------------------------------------- ops_thresholds

    public List<OpsThresholdRow> findOpsThresholds(long organizationId) {
        return jdbc.sql("""
                        SELECT key, value, enabled, severity, channel_ids, version FROM data2flow_core.ops_thresholds
                         WHERE organization_id = :org ORDER BY key""")
                .param("org", organizationId)
                .query((rs, n) -> new OpsThresholdRow(rs.getString("key"), rs.getDouble("value"), rs.getBoolean("enabled"),
                        rs.getString("severity"), Pg.longList(rs, "channel_ids"), rs.getInt("version")))
                .list();
    }

    /** 한 항목 저장(없으면 만든다). version은 조직 전체 판 번호로 모든 행이 같은 값을 가진다 */
    public void upsertOpsThreshold(long organizationId, OpsThresholdRow row, long userId, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.ops_thresholds (organization_id, key, value, enabled, severity, channel_ids, version,
                            updated_by, created_at, updated_at)
                        VALUES (:org, :key, :value, :enabled, :severity, CAST(:channels AS bigint[]), :version, :by, :now, :now)
                        ON CONFLICT (organization_id, key) DO UPDATE SET value = EXCLUDED.value, enabled = EXCLUDED.enabled,
                            severity = EXCLUDED.severity, channel_ids = EXCLUDED.channel_ids, version = EXCLUDED.version,
                            updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at""")
                .param("org", organizationId).param("key", row.key()).param("value", row.value()).param("enabled", row.enabled())
                .param("severity", row.severity()).param("channels", Pg.bigintArray(row.channelIds())).param("version", row.version())
                .param("by", userId).param("now", Pg.ts(now)).update();
    }

    /** 조직 판 번호를 잠그고 읽는다(동시 저장 막기). 행이 없으면 0 */
    public int lockOpsThresholdVersion(long organizationId) {
        return jdbc.sql("""
                        SELECT coalesce(max(version), 0) FROM (SELECT version FROM data2flow_core.ops_thresholds
                         WHERE organization_id = :org FOR UPDATE) x""")
                .param("org", organizationId).query(Integer.class).single();
    }

    // ---------------------------------------------------------------- dlq_item_claims

    /**
     * 실패 항목을 잡는다: 아무도 안 잡았거나 잡은 시간이 지난 항목만 잡히고, 잡힌 ID를 돌려준다. 같은 항목을 동시에 잡으면 한쪽만 성공한다
     * (기본키 충돌 → 먼저 넣은 트랜잭션이 끝날 때까지 기다린 뒤 조건 불일치). ID 순서로 넣어 교착을 피한다.
     */
    public List<Long> claim(long organizationId, Collection<Long> dlqItemIds, long userId, Instant now, Instant until) {
        if (dlqItemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        INSERT INTO data2flow_core.dlq_item_claims (dlq_item_id, organization_id, claimed_by, claimed_until, created_at)
                        SELECT id, :org, :by, :until, :now FROM unnest(CAST(:ids AS bigint[])) AS id ORDER BY id
                        ON CONFLICT (dlq_item_id) DO UPDATE SET organization_id = EXCLUDED.organization_id, claimed_by = EXCLUDED.claimed_by,
                            claimed_until = EXCLUDED.claimed_until, created_at = EXCLUDED.created_at
                         WHERE data2flow_core.dlq_item_claims.claimed_until < :now
                        RETURNING dlq_item_id""")
                .param("org", organizationId).param("by", userId).param("until", Pg.ts(until)).param("now", Pg.ts(now))
                .param("ids", Pg.bigintArray(dlqItemIds.stream().sorted().toList()))
                .query(Long.class).list();
    }

    /** 잡은 항목을 놓는다 */
    public int deleteClaims(long organizationId, Collection<Long> dlqItemIds, long userId) {
        return jdbc.sql("""
                        DELETE FROM data2flow_core.dlq_item_claims
                         WHERE organization_id = :org AND claimed_by = :by AND dlq_item_id = ANY(CAST(:ids AS bigint[]))""")
                .param("org", organizationId).param("by", userId).param("ids", Pg.bigintArray(dlqItemIds)).update();
    }

    /** 지금 다른 사람이 잡고 있는 항목 → 잡은 사용자 */
    public List<Long[]> findActiveClaims(long organizationId, Collection<Long> dlqItemIds, Instant now) {
        return jdbc.sql("""
                        SELECT dlq_item_id, claimed_by FROM data2flow_core.dlq_item_claims
                         WHERE organization_id = :org AND dlq_item_id = ANY(CAST(:ids AS bigint[])) AND claimed_until >= :now""")
                .param("org", organizationId).param("ids", Pg.bigintArray(dlqItemIds)).param("now", Pg.ts(now))
                .query((rs, n) -> new Long[]{rs.getLong("dlq_item_id"), rs.getLong("claimed_by")}).list();
    }

    /** 수집 알람 기준 한 행 */
    public record IngestThresholdRow(int lagWarnSec, int lagCriticalSec, int heartbeatCriticalSec, int version, Long updatedBy,
                                     Instant updatedAt) {
    }

    /** 운영 알람 기준 한 행 */
    public record OpsThresholdRow(String key, double value, boolean enabled, String severity, List<Long> channelIds, int version) {
    }
}
