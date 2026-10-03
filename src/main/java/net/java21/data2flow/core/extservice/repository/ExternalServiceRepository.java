package net.java21.data2flow.core.extservice.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 외부 서비스 설정({@code data2flow_core.external_service_config}, OPS-07.02). 비밀값은 암호문(secret_enc)만 둔다 */
@Repository
public class ExternalServiceRepository {

    private static final String COLUMNS = """
            id, organization_id, kind, provider, settings::text AS settings, secret_enc, enabled, last_test_at,
            last_test_result, version""";

    private final JdbcClient jdbc;

    public ExternalServiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ExternalServiceRow> findAll(long organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.external_service_config WHERE organization_id = :org ORDER BY kind")
                .param("org", organizationId).query(ExternalServiceRepository::map).list();
    }

    public Optional<ExternalServiceRow> findByKind(long organizationId, String kind) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.external_service_config WHERE organization_id = :org AND kind = :kind")
                .param("org", organizationId).param("kind", kind).query(ExternalServiceRepository::map).optional();
    }

    public void insert(long organizationId, String kind, String provider, String settingsJson, byte[] secretEnc, boolean enabled,
                       long createdBy, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.external_service_config (organization_id, kind, provider, settings, secret_enc, enabled,
                            created_by, created_at, updated_at)
                        VALUES (:org, :kind, :provider, CAST(:settings AS jsonb), :secret, :enabled, :by, :now, :now)""")
                .param("org", organizationId).param("kind", kind).param("provider", provider).param("settings", settingsJson)
                .param("secret", secretEnc).param("enabled", enabled).param("by", createdBy).param("now", Pg.ts(now))
                .update();
    }

    public int update(long organizationId, String kind, int baseVersion, String provider, String settingsJson, byte[] secretEnc,
                      boolean enabled, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.external_service_config
                           SET provider = :provider, settings = CAST(:settings AS jsonb), secret_enc = :secret, enabled = :enabled,
                               updated_by = :by, version = version + 1, updated_at = :now
                         WHERE organization_id = :org AND kind = :kind AND version = :base""")
                .param("provider", provider).param("settings", settingsJson).param("secret", secretEnc).param("enabled", enabled)
                .param("by", updatedBy).param("now", Pg.ts(now)).param("org", organizationId).param("kind", kind)
                .param("base", baseVersion).update();
    }

    public void updateTestResult(long organizationId, String kind, String result, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.external_service_config SET last_test_at = :now, last_test_result = :result
                         WHERE organization_id = :org AND kind = :kind""")
                .param("now", Pg.ts(now)).param("result", result.length() > 500 ? result.substring(0, 500) : result)
                .param("org", organizationId).param("kind", kind).update();
    }

    private static ExternalServiceRow map(ResultSet rs, int n) throws SQLException {
        return new ExternalServiceRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("kind"), rs.getString("provider"),
                rs.getString("settings"), rs.getBytes("secret_enc"), rs.getBoolean("enabled"), Pg.instant(rs, "last_test_at"),
                rs.getString("last_test_result"), rs.getInt("version"));
    }

    /** 설정 한 행. settings는 JSON 문자열 */
    public record ExternalServiceRow(long id, long organizationId, String kind, String provider, String settings, byte[] secretEnc,
                                     boolean enabled, Instant lastTestAt, String lastTestResult, int version) {
    }
}
