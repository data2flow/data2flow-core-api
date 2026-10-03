package net.java21.data2flow.core.outbox.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 아웃박스(ERD README §11.1, {@code data2flow_core.outboxes}). 업무 트랜잭션 안에서 기록하고 릴레이가 confirm 뒤 sent_at을 채운다.
 * 릴레이는 조직과 무관하게 전체를 처리한다.
 */
@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 같은 멱등 키가 있으면 무시한다(재시도·중복 기록 방지) */
    public void insert(long organizationId, String idempotencyKey, String kind, String exchange, String routingKey,
                       String payloadJson, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.outboxes (organization_id, idempotency_key, kind, exchange, routing_key, payload, created_at)
                        VALUES (:org, :key, :kind, :exchange, :routingKey, CAST(:payload AS jsonb), :createdAt)
                        ON CONFLICT (idempotency_key) DO NOTHING""")
                .param("org", organizationId).param("key", idempotencyKey).param("kind", kind)
                .param("exchange", exchange).param("routingKey", routingKey).param("payload", payloadJson)
                .param("createdAt", Pg.ts(createdAt))
                .update();
    }

    /** 보낼 행을 잠그고 가져온다. 다른 파드가 잡은 행은 건너뛴다(FOR UPDATE SKIP LOCKED) */
    @OrganizationScopeExempt("릴레이는 모든 조직의 아웃박스를 처리한다")
    public List<OutboxMessage> lockUnsent(int limit) {
        return jdbc.sql("""
                        SELECT id, organization_id, kind, exchange, routing_key, payload::text AS payload, attempts
                          FROM data2flow_core.outboxes
                         WHERE sent_at IS NULL
                         ORDER BY created_at, id
                         LIMIT :limit
                         FOR UPDATE SKIP LOCKED""")
                .param("limit", limit)
                .query((rs, n) -> new OutboxMessage(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("kind"),
                        rs.getString("exchange"), rs.getString("routing_key"), rs.getString("payload"), rs.getInt("attempts")))
                .list();
    }

    @OrganizationScopeExempt("릴레이가 잠근 행을 ID로 갱신")
    public void markSent(long id, Instant sentAt) {
        jdbc.sql("UPDATE data2flow_core.outboxes SET sent_at = :sentAt, attempts = attempts + 1, last_error = NULL WHERE id = :id")
                .param("sentAt", Pg.ts(sentAt)).param("id", id).update();
    }

    @OrganizationScopeExempt("릴레이가 잠근 행을 ID로 갱신")
    public void markFailed(long id, String error) {
        String message = error == null ? "unknown" : (error.length() > 500 ? error.substring(0, 500) : error);
        jdbc.sql("UPDATE data2flow_core.outboxes SET attempts = LEAST(attempts + 1, 32767), last_error = :error WHERE id = :id")
                .param("error", message).param("id", id).update();
    }

    /** 보낸 지 보관 기간이 지난 행 삭제(ERD README §14, 7일) */
    @OrganizationScopeExempt("모든 조직의 보관 정리")
    public int deleteSentBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM data2flow_core.outboxes WHERE sent_at IS NOT NULL AND sent_at < :cutoff")
                .param("cutoff", Pg.ts(cutoff)).update();
    }

    /** 릴레이가 보낼 아웃박스 한 행 */
    public record OutboxMessage(long id, long organizationId, String kind, String exchange, String routingKey,
                                String payload, int attempts) {
    }
}
