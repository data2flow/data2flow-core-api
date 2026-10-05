package net.java21.data2flow.core.analytics.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * 조직 분석 설정(API-ANA-25). 별도 테이블 없이 보관 정책 {@code retention_policies}의 ORG 범위 {@code data_class='ANALYSIS_RESULT'} 행을
 * 읽고 쓴다(TSD 보관 정책 화면과 같은 값, 기본 365일). 보관 정책은 조직 판(version)을 모든 행이 함께 쓰므로 저장하면 모든 행의 판을 올린다.
 */
@Repository
public class AnalysisSettingsRepository {

    private final JdbcClient jdbc;

    public AnalysisSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Retention(int days, Long updatedBy, String updatedByName, Instant updatedAt) {
    }

    public Optional<Retention> findResultRetention(long organizationId) {
        return jdbc.sql("""
                        SELECT r.retain_days, r.updated_by, u.name AS updated_by_name, r.updated_at
                          FROM data2flow_core.retention_policies r
                          LEFT JOIN data2flow_core.app_users u ON u.organization_id = r.organization_id AND u.id = r.updated_by
                         WHERE r.organization_id = :org AND r.scope = 'ORG' AND r.scope_ref IS NULL AND r.data_class = 'ANALYSIS_RESULT'""")
                .param("org", organizationId).query(AnalysisSettingsRepository::retention).optional();
    }

    /** 결과 보관 일수 저장(조직 정책 잠금 뒤 판 올림) */
    public void saveResultRetention(long organizationId, int days, long userId, Instant now) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('retention:' || :org, 0))").param("org", organizationId)
                .query((rs, n) -> 1).list();
        int version = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM data2flow_core.retention_policies WHERE organization_id = :org")
                .param("org", organizationId).query(Integer.class).single();
        jdbc.sql("""
                        INSERT INTO data2flow_core.retention_policies (organization_id, scope, scope_ref, data_class, retain_days, version,
                               updated_by, created_at, updated_at)
                        VALUES (:org, 'ORG', NULL, 'ANALYSIS_RESULT', :days, :version, :user, :now, :now)
                        ON CONFLICT ON CONSTRAINT uq_retention_policies_scope_data_class
                        DO UPDATE SET retain_days = EXCLUDED.retain_days, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at""")
                .param("org", organizationId).param("days", days).param("version", version).param("user", userId).param("now", Pg.ts(now))
                .update();
        jdbc.sql("UPDATE data2flow_core.retention_policies SET version = :version WHERE organization_id = :org")
                .param("version", version).param("org", organizationId).update();
    }

    static Retention retention(ResultSet rs, int n) throws SQLException {
        return new Retention(rs.getInt("retain_days"), Pg.longOrNull(rs, "updated_by"), rs.getString("updated_by_name"),
                Pg.instant(rs, "updated_at"));
    }
}
