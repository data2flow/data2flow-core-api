package net.java21.data2flow.core.devicecredential.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 플랫폼 브로커 기기 자격({@code device_credentials}, DSC-03.02·03.05). 비밀번호·서명 키는 해시만 둔다 */
@Repository
public class DeviceCredentialRepository {

    private static final String COLUMNS = """
            id, organization_id, device_id, type, username, status, expires_at, last_used_at, created_at, revoked_at""";

    private final JdbcClient jdbc;

    public DeviceCredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CredentialRow> findByDevice(long organizationId, long deviceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.device_credentials WHERE organization_id = :org AND device_id = :device"
                        + " ORDER BY created_at DESC, id DESC")
                .param("org", organizationId).param("device", deviceId).query(DeviceCredentialRepository::map).list();
    }

    public Optional<CredentialRow> findById(long organizationId, long deviceId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.device_credentials WHERE organization_id = :org AND device_id = :device"
                        + " AND id = :id")
                .param("org", organizationId).param("device", deviceId).param("id", id).query(DeviceCredentialRepository::map).optional();
    }

    /** 다른 기기가 이 사용자 이름을 ACTIVE로 쓰고 있는가(사용자 이름은 브로커 전체에서 고유, uq_device_credentials_username_active) */
    public boolean existsActiveUsername(long organizationId, String username, long exceptDeviceId) {
        // 브로커 계정은 조직을 가리지 않고 고유해야 하므로 조직 조건 없이 본다(이름 충돌 여부만 읽고 내용은 읽지 않는다)
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.device_credentials
                                        WHERE username = :username AND status = 'ACTIVE'
                                          AND NOT (organization_id = :org AND device_id = :device))""")
                .param("username", username).param("org", organizationId).param("device", exceptDeviceId).query(Boolean.class).single();
    }

    public long insert(long organizationId, long deviceId, String username, String passwordHash, String signingKeyHash, Instant expiresAt,
                       long createdBy, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.device_credentials (organization_id, device_id, type, username, password_hash,
                            signing_key_hash, status, expires_at, created_by, created_at, updated_at)
                        VALUES (:org, :device, 'PASSWORD', :username, :password, :signing, 'ACTIVE', :expires, :by, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("device", deviceId).param("username", username).param("password", passwordHash)
                .param("signing", signingKeyHash).param("expires", Pg.ts(expiresAt)).param("by", createdBy).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    /** 기기의 ACTIVE 자격 폐기. 폐기한 ID */
    public List<Long> updateRevokedByDevice(long organizationId, long deviceId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.device_credentials SET status = 'REVOKED', revoked_at = :now, updated_at = :now
                         WHERE organization_id = :org AND device_id = :device AND status = 'ACTIVE' RETURNING id""")
                .param("now", Pg.ts(now)).param("org", organizationId).param("device", deviceId).query(Long.class).list();
    }

    public int updateRevoked(long organizationId, long deviceId, long id, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.device_credentials SET status = 'REVOKED', revoked_at = :now, updated_at = :now
                         WHERE organization_id = :org AND device_id = :device AND id = :id AND status = 'ACTIVE'""")
                .param("now", Pg.ts(now)).param("org", organizationId).param("device", deviceId).param("id", id).update();
    }

    static CredentialRow map(ResultSet rs, int n) throws SQLException {
        return new CredentialRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("device_id"), rs.getString("type"),
                rs.getString("username"), rs.getString("status"), Pg.instant(rs, "expires_at"), Pg.instant(rs, "last_used_at"),
                Pg.instant(rs, "created_at"), Pg.instant(rs, "revoked_at"));
    }

    public record CredentialRow(long id, long organizationId, long deviceId, String type, String username, String status,
                                Instant expiresAt, Instant lastUsedAt, Instant createdAt, Instant revokedAt) {
    }
}
