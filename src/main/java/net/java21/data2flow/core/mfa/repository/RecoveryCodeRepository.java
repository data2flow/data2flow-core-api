package net.java21.data2flow.core.mfa.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** 2단계 인증 복구 코드(10개·1회용, BR-IAM-26). SHA-256 해시만 둔다 */
@Repository
public class RecoveryCodeRepository {

    private final JdbcClient jdbc;

    public RecoveryCodeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void replaceAll(long organizationId, long userId, List<String> codeHashes, Instant now) {
        deleteAll(organizationId, userId);
        for (String hash : codeHashes) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.mfa_recovery_codes (organization_id, user_id, code_hash, created_at)
                            VALUES (:org, :user, :hash, :now)""")
                    .param("org", organizationId).param("user", userId).param("hash", hash).param("now", Pg.ts(now)).update();
        }
    }

    /** 쓰지 않은 코드면 사용 처리하고 true(1회용) */
    public boolean useCode(long organizationId, long userId, String codeHash, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.mfa_recovery_codes SET used_at = :now
                         WHERE organization_id = :org AND user_id = :user AND code_hash = :hash AND used_at IS NULL""")
                .param("now", Pg.ts(now)).param("org", organizationId).param("user", userId).param("hash", codeHash).update() == 1;
    }

    public long countUnused(long organizationId, long userId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.mfa_recovery_codes WHERE organization_id = :org AND user_id = :user AND used_at IS NULL")
                .param("org", organizationId).param("user", userId).query(Long.class).single();
    }

    public void deleteAll(long organizationId, long userId) {
        jdbc.sql("DELETE FROM data2flow_core.mfa_recovery_codes WHERE organization_id = :org AND user_id = :user")
                .param("org", organizationId).param("user", userId).update();
    }
}
