package net.java21.data2flow.core.retention.repository;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.retention.domain.DataClass;
import net.java21.data2flow.core.retention.domain.RetentionRules.Policy;
import net.java21.data2flow.core.retention.domain.RetentionRules.Scope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 보관 정책({@code retention_policies}, TSD-02.01·05.01·05.03). 범위 재정의의 모델·측정 항목 확인도 여기서 한다 */
@Repository
public class RetentionPolicyRepository {

    private final JdbcClient jdbc;

    public RetentionPolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<StoredPolicy> list(long organizationId) {
        return jdbc.sql("""
                        SELECT scope, scope_ref, data_class, retain_days, compress_after_days, archive_before_delete, store_mode, version
                          FROM data2flow_core.retention_policies WHERE organization_id = :org ORDER BY id""")
                .param("org", organizationId).query(RetentionPolicyRepository::map).list();
    }

    /** 조직 정책 판(행 version의 최댓값, 없으면 0). 저장할 때마다 모든 행이 새 판을 받는다 */
    public long version(long organizationId) {
        return jdbc.sql("SELECT coalesce(max(version), 0) FROM data2flow_core.retention_policies WHERE organization_id = :org")
                .param("org", organizationId).query(Long.class).single();
    }

    /** 조직 정책을 새 집합으로 바꾼다(같은 트랜잭션, 조직 행 잠금 뒤). 모든 행이 새 판 version을 받는다 */
    public void replace(long organizationId, List<Policy> set, int version, long userId, Instant now) {
        jdbc.sql("DELETE FROM data2flow_core.retention_policies WHERE organization_id = :org").param("org", organizationId).update();
        for (Policy p : set) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.retention_policies (organization_id, scope, scope_ref, data_class, retain_days,
                                   compress_after_days, archive_before_delete, store_mode, version, updated_by, created_at, updated_at)
                            VALUES (:org, :scope, :ref, :dc, :days, :compress, :archive, :mode, :version, :user, :now, :now)""")
                    .param("org", organizationId).param("scope", p.scope().name()).param("ref", p.scopeRef())
                    .param("dc", p.dataClass().name()).param("days", p.retainDays()).param("compress", p.compressAfterDays())
                    .param("archive", p.archiveBeforeDelete()).param("mode", p.storeMode()).param("version", version)
                    .param("user", userId).param("now", Pg.ts(now)).update();
        }
    }

    /** 같은 조직의 정책 저장을 한 번에 하나씩(조직 행 잠금) */
    public void lockOrganization(long organizationId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('retention:' || :org, 0))").param("org", organizationId)
                .query((rs, n) -> 1).list();
    }

    /** 모델 ID 또는 코드 → 모델 ID */
    public Optional<Long> findModel(long organizationId, String idOrCode) {
        if (idOrCode.matches("\\d{1,18}")) {
            Optional<Long> byId = jdbc.sql("SELECT id FROM data2flow_core.device_models WHERE organization_id = :org AND id = :id")
                    .param("org", organizationId).param("id", Long.parseLong(idOrCode)).query(Long.class).optional();
            if (byId.isPresent()) {
                return byId;
            }
        }
        return jdbc.sql("SELECT id FROM data2flow_core.device_models WHERE organization_id = :org AND code = :code")
                .param("org", organizationId).param("code", idOrCode).query(Long.class).optional();
    }

    /** 측정 항목이 있고 상태형(state_type 또는 BOOLEAN·ENUM)인가. 없으면 빈 값 */
    public Optional<Boolean> metricStateful(long organizationId, String key) {
        return jdbc.sql("""
                        SELECT state_type OR value_type IN ('BOOLEAN', 'ENUM') FROM data2flow_core.metrics
                         WHERE organization_id = :org AND key = :key""")
                .param("org", organizationId).param("key", key).query(Boolean.class).optional();
    }

    static StoredPolicy map(ResultSet rs, int n) throws SQLException {
        int compress = rs.getInt("compress_after_days");
        Integer compressOrNull = rs.wasNull() ? null : compress;
        return new StoredPolicy(new Policy(Scope.valueOf(rs.getString("scope")), rs.getString("scope_ref"),
                DataClass.valueOf(rs.getString("data_class")), rs.getInt("retain_days"), compressOrNull,
                rs.getBoolean("archive_before_delete"), rs.getString("store_mode")), rs.getInt("version"));
    }

    public record StoredPolicy(Policy policy, int version) {
    }
}
