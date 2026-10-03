package net.java21.data2flow.core.account.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** 최근 비밀번호 해시(사용자당 3개, IAM-02.02, BR-IAM-03) */
@Repository
public class PasswordHistoryRepository {

    static final int KEEP = 3;

    private final JdbcClient jdbc;

    public PasswordHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> findRecentHashes(long organizationId, long userId) {
        return jdbc.sql("""
                        SELECT password_hash FROM data2flow_core.password_history
                         WHERE organization_id = :org AND user_id = :user ORDER BY created_at DESC, id DESC LIMIT :keep""")
                .param("org", organizationId).param("user", userId).param("keep", KEEP).query(String.class).list();
    }

    /** 새 해시를 넣고 최근 3개만 남긴다 */
    public void push(long organizationId, long userId, String passwordHash, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.password_history (organization_id, user_id, password_hash, created_at)
                        VALUES (:org, :user, :hash, :now)""")
                .param("org", organizationId).param("user", userId).param("hash", passwordHash).param("now", Pg.ts(now)).update();
        jdbc.sql("""
                        DELETE FROM data2flow_core.password_history
                         WHERE organization_id = :org AND user_id = :user AND id NOT IN (
                            SELECT id FROM data2flow_core.password_history WHERE organization_id = :org AND user_id = :user
                             ORDER BY created_at DESC, id DESC LIMIT :keep)""")
                .param("org", organizationId).param("user", userId).param("keep", KEEP).update();
    }

    public void deleteAll(long organizationId, long userId) {
        jdbc.sql("DELETE FROM data2flow_core.password_history WHERE organization_id = :org AND user_id = :user")
                .param("org", organizationId).param("user", userId).update();
    }
}
