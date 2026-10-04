package net.java21.data2flow.core.notify.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 알림 채널({@code data2flow_core.notification_channels}, OPS-06.01·06.06). 비밀값은 암호문(secret_enc)으로만 */
@Repository
public class ChannelRepository {

    static final String COLUMNS = """
            id, organization_id, name, type, config::text AS config, secret_enc, rate_limit_per_min, digest_window_sec, enabled, status,
            version, updated_at""";

    private final JdbcClient jdbc;

    public ChannelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ChannelRow(long id, long organizationId, String name, String type, String config, byte[] secretEnc, int rateLimitPerMin,
                             int digestWindowSec, boolean enabled, String status, int version, Instant updatedAt) {
    }

    public List<ChannelRow> list(long organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.notification_channels WHERE organization_id = :org ORDER BY name, id")
                .param("org", organizationId).query(ChannelRepository::map).list();
    }

    public Optional<ChannelRow> findById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.notification_channels WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).query(ChannelRepository::map).optional();
    }

    public boolean existsName(long organizationId, String name, Long exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.notification_channels WHERE organization_id = :org AND name = :name
                                         AND (CAST(:except AS bigint) IS NULL OR id <> :except))""")
                .param("org", organizationId).param("name", name).param("except", exceptId).query(Boolean.class).single();
    }

    /** 사용할 수 있는(켜진) 채널 유형 */
    public List<String> listEnabledTypes(long organizationId) {
        return jdbc.sql("SELECT DISTINCT type FROM data2flow_core.notification_channels WHERE organization_id = :org AND enabled")
                .param("org", organizationId).query(String.class).list();
    }

    public long countEnabledOfType(long organizationId, String type, long exceptId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.notification_channels WHERE organization_id = :org AND type = :type AND enabled"
                        + " AND id <> :except")
                .param("org", organizationId).param("type", type).param("except", exceptId).query(Long.class).single();
    }

    public long insert(long organizationId, String name, String type, String configJson, int rateLimitPerMin, int digestWindowSec,
                       boolean enabled, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.notification_channels (organization_id, name, type, config, rate_limit_per_min, digest_window_sec,
                               enabled, status, version, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :name, :type, CAST(:config AS jsonb), :rate, :digest, :enabled, 'OK', 1, :user, :user, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("name", name).param("type", type).param("config", configJson).param("rate", rateLimitPerMin)
                .param("digest", digestWindowSec).param("enabled", enabled).param("user", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    public int update(long organizationId, long id, int baseVersion, String name, String configJson, int rateLimitPerMin,
                      int digestWindowSec, boolean enabled, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.notification_channels SET name = :name, config = CAST(:config AS jsonb), rate_limit_per_min = :rate,
                               digest_window_sec = :digest, enabled = :enabled, version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .param("name", name).param("config", configJson).param("rate", rateLimitPerMin).param("digest", digestWindowSec)
                .param("enabled", enabled).param("user", userId).param("now", Pg.ts(now)).param("org", organizationId).param("id", id)
                .param("base", baseVersion).update();
    }

    public void updateSecret(long organizationId, long id, byte[] secretEnc) {
        jdbc.sql("UPDATE data2flow_core.notification_channels SET secret_enc = :secret WHERE organization_id = :org AND id = :id")
                .param("secret", secretEnc).param("org", organizationId).param("id", id).update();
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.notification_channels WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    static ChannelRow map(ResultSet rs, int n) throws SQLException {
        return new ChannelRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("type"),
                rs.getString("config"), rs.getBytes("secret_enc"), rs.getInt("rate_limit_per_min"), rs.getInt("digest_window_sec"),
                rs.getBoolean("enabled"), rs.getString("status"), rs.getInt("version"), Pg.instant(rs, "updated_at"));
    }
}
