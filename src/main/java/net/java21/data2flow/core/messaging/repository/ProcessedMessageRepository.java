package net.java21.data2flow.core.messaging.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/** 소비한 메시지 기록(ERD README §11.2, {@code data2flow_core.processed_messages}). messageId로 중복을 거른다 */
@Repository
public class ProcessedMessageRepository {

    private final JdbcClient jdbc;

    public ProcessedMessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 처음이면 true(기록함), 이미 처리했으면 false */
    public boolean insertIfAbsent(String consumer, String messageId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.processed_messages (consumer, message_id, processed_at)
                        VALUES (:consumer, :id, :now) ON CONFLICT DO NOTHING""")
                .param("consumer", consumer).param("id", messageId).param("now", Pg.ts(now)).update() == 1;
    }

    /** 7일 지난 기록 삭제(ERD 기본값) */
    @OrganizationScopeExempt("소비 기록은 조직과 무관한 메시지 ID 표다")
    public int deleteBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM data2flow_core.processed_messages WHERE processed_at < :cutoff")
                .param("cutoff", Pg.ts(cutoff)).update();
    }
}
