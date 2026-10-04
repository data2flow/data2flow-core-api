package net.java21.data2flow.core.edge.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 엣지 게이트웨이 저장소: {@code edge_gateways}, {@code edge_registration_tokens}(해시만), {@code edge_config_versions}, {@code edge_updates},
 * {@code edge_commands} (DSC-08.03·08.04, ERD core-data-automation.md §3.4~3.8)
 */
@Repository
public class EdgeRepository {

    private static final String SELECT = """
            SELECT e.*, sp.name AS site_name FROM data2flow_core.edge_gateways e
              LEFT JOIN data2flow_core.spaces sp ON sp.id = e.site_id AND sp.organization_id = e.organization_id
            """;

    private final JdbcClient jdbc;

    public EdgeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record EdgeRow(long id, long organizationId, String name, long siteId, String siteName, Long sourceId, String status,
                          String agentVersion, String arch, String certFingerprint, Integer appliedConfigVersion, Integer desiredConfigVersion,
                          Long bufferUsedBytes, Long bufferItems, long droppedItems, Double throughput, Instant lastSeenAt, Instant revokedAt,
                          int version, Instant createdAt, Instant updatedAt) {
    }

    public record ConfigVersionRow(long edgeId, int versionNo, String targets, String decoders, String result, String error, long createdBy,
                                   Instant createdAt, Instant deployedAt) {
    }

    public record UpdateRow(long id, long edgeId, String fromVersion, String toVersion, String status, long approvedBy, Instant createdAt,
                            Instant startedAt, Instant finishedAt) {
    }

    public record CommandRow(long id, String requestId, String kind, String args, String status, Instant createdAt) {
    }

    public record TokenRow(long id, long organizationId, long edgeId, Instant expiresAt, Instant usedAt) {
    }

    // ---------------------------------------------------------------- 엣지

    public long insert(long orgId, String name, long siteId, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.edge_gateways (organization_id, name, site_id, status, created_by, created_at, updated_at)
                        VALUES (:org, :name, :site, 'REGISTERING', :user, :now, :now) RETURNING id""")
                .param("org", orgId).param("name", name).param("site", siteId).param("user", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    public Optional<EdgeRow> findById(long orgId, long id) {
        return jdbc.sql(SELECT + " WHERE e.organization_id = :org AND e.id = :id").param("org", orgId).param("id", id)
                .query(EdgeRepository::map).optional();
    }

    public Optional<EdgeRow> lockById(long orgId, long id) {
        jdbc.sql("SELECT id FROM data2flow_core.edge_gateways WHERE organization_id = :org AND id = :id FOR UPDATE")
                .param("org", orgId).param("id", id).query(Long.class).optional();
        return findById(orgId, id);
    }

    public List<EdgeRow> list(long orgId, Long siteId, int limit, long offset) {
        return jdbc.sql(SELECT + " WHERE e.organization_id = :org AND (CAST(:site AS bigint) IS NULL OR e.site_id = :site)"
                        + " ORDER BY e.name, e.id LIMIT :limit OFFSET :offset")
                .param("org", orgId).param("site", siteId).param("limit", limit).param("offset", offset).query(EdgeRepository::map).list();
    }

    public long count(long orgId, Long siteId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.edge_gateways WHERE organization_id = :org AND (CAST(:site AS bigint) IS NULL OR site_id = :site)")
                .param("org", orgId).param("site", siteId).query(Long.class).single();
    }

    public boolean existsName(long orgId, String name, Long exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.edge_gateways
                                        WHERE organization_id = :org AND name = :name AND (CAST(:except AS bigint) IS NULL OR id <> :except))""")
                .param("org", orgId).param("name", name).param("except", exceptId).query(Boolean.class).single();
    }

    public int updateName(long orgId, long id, int baseVersion, String name, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.edge_gateways SET name = :name, version = version + 1, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .param("name", name).param("now", Pg.ts(now)).param("org", orgId).param("id", id).param("base", baseVersion).update();
    }

    public int updateStatus(long orgId, long id, String status, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.edge_gateways SET status = :status, updated_at = :now,
                               revoked_at = CASE WHEN :status = 'REVOKED' THEN :now ELSE revoked_at END
                         WHERE organization_id = :org AND id = :id""")
                .param("status", status).param("now", Pg.ts(now)).param("org", orgId).param("id", id).update();
    }

    public int updateRegistered(long orgId, long id, String agentVersion, String arch, String certFingerprint, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.edge_gateways
                           SET status = 'ONLINE', agent_version = :ver, arch = :arch, cert_fingerprint = :fp, last_seen_at = :now, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("ver", agentVersion).param("arch", arch).param("fp", certFingerprint).param("now", Pg.ts(now))
                .param("org", orgId).param("id", id).update();
    }

    /** 하트비트 보고값 */
    public int updateHeartbeat(long orgId, long id, String status, String agentVersion, Long bufferUsedBytes, Long bufferItems, long droppedItems,
                               Double throughput, Integer appliedConfigVersion, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.edge_gateways
                           SET status = :status, agent_version = coalesce(:ver, agent_version), buffer_used_bytes = :bytes, buffer_items = :items,
                               dropped_items = dropped_items + :dropped, throughput = :tp,
                               applied_config_version = coalesce(:applied, applied_config_version), last_seen_at = :now, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("status", status).param("ver", agentVersion).param("bytes", bufferUsedBytes).param("items", bufferItems)
                .param("dropped", droppedItems).param("tp", throughput).param("applied", appliedConfigVersion).param("now", Pg.ts(now))
                .param("org", orgId).param("id", id).update();
    }

    public int updateDesiredConfig(long orgId, long id, int versionNo, Instant now) {
        return jdbc.sql("UPDATE data2flow_core.edge_gateways SET desired_config_version = :v, updated_at = :now WHERE organization_id = :org AND id = :id")
                .param("v", versionNo).param("now", Pg.ts(now)).param("org", orgId).param("id", id).update();
    }

    public int addDropped(long orgId, long id, long droppedItems, Instant now) {
        return jdbc.sql("UPDATE data2flow_core.edge_gateways SET dropped_items = dropped_items + :d, updated_at = :now WHERE organization_id = :org AND id = :id")
                .param("d", droppedItems).param("now", Pg.ts(now)).param("org", orgId).param("id", id).update();
    }

    public int delete(long orgId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.edge_gateways WHERE organization_id = :org AND id = :id")
                .param("org", orgId).param("id", id).update();
    }

    /** 공간 유형(사이트 확인) */
    public Optional<String> findSpaceType(long orgId, long spaceId) {
        return jdbc.sql("SELECT type FROM data2flow_core.spaces WHERE organization_id = :org AND id = :id")
                .param("org", orgId).param("id", spaceId).query(String.class).optional();
    }

    // ---------------------------------------------------------------- 등록 토큰

    public void insertToken(long orgId, long edgeId, String tokenHash, Instant expiresAt, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.edge_registration_tokens (organization_id, edge_id, token_hash, expires_at, created_at)
                        VALUES (:org, :edge, :hash, :exp, :now)""")
                .param("org", orgId).param("edge", edgeId).param("hash", tokenHash).param("exp", Pg.ts(expiresAt)).param("now", Pg.ts(now)).update();
    }

    /** 아직 쓰지 않은 토큰을 모두 만료시킨다(재발급) */
    public int updateTokensExpired(long orgId, long edgeId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.edge_registration_tokens SET expires_at = :now
                         WHERE organization_id = :org AND edge_id = :edge AND used_at IS NULL AND expires_at > :now""")
                .param("now", Pg.ts(now)).param("org", orgId).param("edge", edgeId).update();
    }

    /** 해시로 토큰 찾기(엣지 에이전트 등록, 조직은 토큰이 정한다) — 잠금 */
    @OrganizationScopeExempt("엣지 등록(API-DSC-78): 에이전트는 토큰만 가지고 있고, 조직은 토큰 행이 정한다. 배포 조직 확인은 서비스가 한다")
    public Optional<TokenRow> lockTokenByHash(String tokenHash) {
        return jdbc.sql("""
                        SELECT id, organization_id, edge_id, expires_at, used_at FROM data2flow_core.edge_registration_tokens
                         WHERE token_hash = :hash FOR UPDATE""")
                .param("hash", tokenHash)
                .query((rs, n) -> new TokenRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("edge_id"),
                        Pg.instant(rs, "expires_at"), Pg.instant(rs, "used_at"))).optional();
    }

    public int updateTokenUsed(long orgId, long tokenId, Instant now) {
        return jdbc.sql("UPDATE data2flow_core.edge_registration_tokens SET used_at = :now WHERE organization_id = :org AND id = :id AND used_at IS NULL")
                .param("now", Pg.ts(now)).param("org", orgId).param("id", tokenId).update();
    }

    // ---------------------------------------------------------------- 설정 판

    public int nextConfigVersion(long orgId, long edgeId) {
        return jdbc.sql("SELECT coalesce(max(version_no), 0) + 1 FROM data2flow_core.edge_config_versions WHERE organization_id = :org AND edge_id = :edge")
                .param("org", orgId).param("edge", edgeId).query(Integer.class).single();
    }

    public void insertConfigVersion(long orgId, long edgeId, int versionNo, String targets, String decoders, long userId, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.edge_config_versions (edge_id, version_no, organization_id, targets, decoders, created_by, created_at)
                        VALUES (:edge, :v, :org, CAST(:targets AS jsonb), CAST(:decoders AS jsonb), :user, :now)""")
                .param("edge", edgeId).param("v", versionNo).param("org", orgId).param("targets", targets).param("decoders", decoders)
                .param("user", userId).param("now", Pg.ts(now)).update();
    }

    public List<ConfigVersionRow> listConfigVersions(long orgId, long edgeId) {
        return jdbc.sql("SELECT * FROM data2flow_core.edge_config_versions WHERE organization_id = :org AND edge_id = :edge ORDER BY version_no DESC")
                .param("org", orgId).param("edge", edgeId).query(EdgeRepository::mapConfig).list();
    }

    public Optional<ConfigVersionRow> findConfigVersion(long orgId, long edgeId, int versionNo) {
        return jdbc.sql("SELECT * FROM data2flow_core.edge_config_versions WHERE organization_id = :org AND edge_id = :edge AND version_no = :v")
                .param("org", orgId).param("edge", edgeId).param("v", versionNo).query(EdgeRepository::mapConfig).optional();
    }

    public int updateConfigDeployed(long orgId, long edgeId, int versionNo, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.edge_config_versions SET deployed_at = :now, result = 'PENDING', error = NULL
                         WHERE organization_id = :org AND edge_id = :edge AND version_no = :v""")
                .param("now", Pg.ts(now)).param("org", orgId).param("edge", edgeId).param("v", versionNo).update();
    }

    public int updateConfigResult(long orgId, long edgeId, int versionNo, String result, String error) {
        return jdbc.sql("""
                        UPDATE data2flow_core.edge_config_versions SET result = :result, error = :error
                         WHERE organization_id = :org AND edge_id = :edge AND version_no = :v""")
                .param("result", result).param("error", error == null ? null : error.length() > 500 ? error.substring(0, 500) : error)
                .param("org", orgId).param("edge", edgeId).param("v", versionNo).update();
    }

    // ---------------------------------------------------------------- 업데이트

    public long insertUpdate(long orgId, long edgeId, String fromVersion, String toVersion, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.edge_updates (organization_id, edge_id, from_version, to_version, status, approved_by, created_at)
                        VALUES (:org, :edge, :from, :to, 'APPROVED', :user, :now) RETURNING id""")
                .param("org", orgId).param("edge", edgeId).param("from", fromVersion).param("to", toVersion).param("user", userId)
                .param("now", Pg.ts(now)).query(Long.class).single();
    }

    public List<UpdateRow> listUpdates(long orgId, long edgeId) {
        return jdbc.sql("SELECT * FROM data2flow_core.edge_updates WHERE organization_id = :org AND edge_id = :edge ORDER BY id DESC")
                .param("org", orgId).param("edge", edgeId).query(EdgeRepository::mapUpdate).list();
    }

    /** 끝나지 않은 업데이트(APPROVED·IN_PROGRESS) */
    public Optional<UpdateRow> findOpenUpdate(long orgId, long edgeId) {
        return jdbc.sql("""
                        SELECT * FROM data2flow_core.edge_updates
                         WHERE organization_id = :org AND edge_id = :edge AND status IN ('APPROVED', 'IN_PROGRESS') ORDER BY id DESC LIMIT 1""")
                .param("org", orgId).param("edge", edgeId).query(EdgeRepository::mapUpdate).optional();
    }

    public int updateUpdateStarted(long orgId, long updateId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.edge_updates SET status = 'IN_PROGRESS', started_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'APPROVED'""")
                .param("now", Pg.ts(now)).param("org", orgId).param("id", updateId).update();
    }

    public int updateUpdateFinished(long orgId, long updateId, String status, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.edge_updates SET status = :status, finished_at = :now
                         WHERE organization_id = :org AND id = :id AND status IN ('APPROVED', 'IN_PROGRESS')""")
                .param("status", status).param("now", Pg.ts(now)).param("org", orgId).param("id", updateId).update();
    }

    // ---------------------------------------------------------------- 원격 명령

    public void insertCommand(long orgId, long edgeId, String requestId, String kind, String args, long userId, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.edge_commands (organization_id, edge_id, request_id, kind, args, requested_by, created_at)
                        VALUES (:org, :edge, :rid, :kind, CAST(:args AS jsonb), :user, :now)""")
                .param("org", orgId).param("edge", edgeId).param("rid", requestId).param("kind", kind).param("args", args)
                .param("user", userId).param("now", Pg.ts(now)).update();
    }

    public List<CommandRow> listPendingCommands(long orgId, long edgeId) {
        return jdbc.sql("""
                        SELECT id, request_id, kind, args::text AS args, status, created_at FROM data2flow_core.edge_commands
                         WHERE organization_id = :org AND edge_id = :edge AND status = 'PENDING' ORDER BY id FOR UPDATE""")
                .param("org", orgId).param("edge", edgeId)
                .query((rs, n) -> new CommandRow(rs.getLong("id"), rs.getString("request_id"), rs.getString("kind"), rs.getString("args"),
                        rs.getString("status"), Pg.instant(rs, "created_at"))).list();
    }

    public int updateCommandsSent(long orgId, long edgeId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.edge_commands SET status = 'SENT', sent_at = :now
                         WHERE organization_id = :org AND edge_id = :edge AND status = 'PENDING'""")
                .param("now", Pg.ts(now)).param("org", orgId).param("edge", edgeId).update();
    }

    static EdgeRow map(ResultSet rs, int n) throws SQLException {
        return new EdgeRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getLong("site_id"), rs.getString("site_name"),
                Pg.longOrNull(rs, "source_id"), rs.getString("status"), rs.getString("agent_version"), rs.getString("arch"),
                rs.getString("cert_fingerprint"), (Integer) rs.getObject("applied_config_version"), (Integer) rs.getObject("desired_config_version"),
                Pg.longOrNull(rs, "buffer_used_bytes"), Pg.longOrNull(rs, "buffer_items"), rs.getLong("dropped_items"),
                (Double) rs.getObject("throughput"), Pg.instant(rs, "last_seen_at"), Pg.instant(rs, "revoked_at"), rs.getInt("version"),
                Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"));
    }

    static ConfigVersionRow mapConfig(ResultSet rs, int n) throws SQLException {
        return new ConfigVersionRow(rs.getLong("edge_id"), rs.getInt("version_no"), rs.getString("targets"), rs.getString("decoders"),
                rs.getString("result"), rs.getString("error"), rs.getLong("created_by"), Pg.instant(rs, "created_at"), Pg.instant(rs, "deployed_at"));
    }

    static UpdateRow mapUpdate(ResultSet rs, int n) throws SQLException {
        return new UpdateRow(rs.getLong("id"), rs.getLong("edge_id"), rs.getString("from_version"), rs.getString("to_version"),
                rs.getString("status"), rs.getLong("approved_by"), Pg.instant(rs, "created_at"), Pg.instant(rs, "started_at"),
                Pg.instant(rs, "finished_at"));
    }
}
