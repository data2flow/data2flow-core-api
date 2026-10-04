package net.java21.data2flow.core.output.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 출력 연결 저장소: {@code output_connections}, {@code output_secrets}(암호문만), {@code output_delivery_stats}, {@code output_config_versions}
 * (DSC-04.01, ERD core-data-automation.md §3.1~3.3). 모든 조회에 조직 조건을 건다.
 */
@Repository
public class OutputConnectionRepository {

    private static final String SELECT = """
            SELECT o.id, o.organization_id, o.name, o.type, o.target::text AS target, o.filter::text AS filter, o.format, o.template,
                   o.enabled, o.version, o.created_by, o.updated_by, o.created_at, o.updated_at,
                   coalesce((SELECT array_agg(s.kind ORDER BY s.kind) FROM data2flow_core.output_secrets s WHERE s.output_id = o.id),
                            '{}') AS secret_kinds
              FROM data2flow_core.output_connections o
            """;

    private final JdbcClient jdbc;

    public OutputConnectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record OutputRow(long id, long organizationId, String name, String type, String target, String filter, String format,
                            String template, boolean enabled, int version, long createdBy, long updatedBy, Instant createdAt,
                            Instant updatedAt, List<String> secretKinds) {
    }

    public record SecretRow(long outputId, String kind, byte[] ciphertext) {
    }

    public record StatRow(Instant minute, int sent, int failed, int retried, Integer lagMs) {
    }

    public record DeviceContextRow(long deviceId, long organizationId, String deviceName, Long spaceId, String spaceCode, String spacePath,
                                   List<Long> groupIds) {
    }

    public record SampleDeviceRow(long deviceId, long organizationId, long sourceId, String externalId, String name, String status,
                                  Long modelId, Long spaceId, String latest) {
    }

    public long insert(long orgId, String name, String type, String target, String filter, String format, String template, boolean enabled,
                       long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.output_connections (organization_id, name, type, target, filter, format, template, enabled,
                                                                      version, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :name, :type, CAST(:target AS jsonb), CAST(:filter AS jsonb), :format, :template, :enabled, 1, :user,
                                :user, :now, :now)
                        RETURNING id""")
                .param("org", orgId).param("name", name).param("type", type).param("target", target).param("filter", filter)
                .param("format", format).param("template", template).param("enabled", enabled).param("user", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    /** 낙관적 잠금 수정. 바뀐 행 수(0이면 버전 충돌) */
    public int update(long orgId, long id, int baseVersion, String name, String target, String filter, String format, String template,
                      boolean enabled, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.output_connections
                           SET name = :name, target = CAST(:target AS jsonb), filter = CAST(:filter AS jsonb), format = :format,
                               template = :template, enabled = :enabled, version = version + 1, updated_by = :user, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base""")
                .param("name", name).param("target", target).param("filter", filter).param("format", format).param("template", template)
                .param("enabled", enabled).param("user", userId).param("now", Pg.ts(now)).param("org", orgId).param("id", id)
                .param("base", baseVersion).update();
    }

    public int delete(long orgId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.output_connections WHERE organization_id = :org AND id = :id")
                .param("org", orgId).param("id", id).update();
    }

    public Optional<OutputRow> findById(long orgId, long id) {
        return jdbc.sql(SELECT + " WHERE o.organization_id = :org AND o.id = :id").param("org", orgId).param("id", id)
                .query(OutputConnectionRepository::map).optional();
    }

    public Optional<OutputRow> lockById(long orgId, long id) {
        jdbc.sql("SELECT id FROM data2flow_core.output_connections WHERE organization_id = :org AND id = :id FOR UPDATE")
                .param("org", orgId).param("id", id).query(Long.class).optional();
        return findById(orgId, id);
    }

    public List<OutputRow> list(long orgId, String type, int limit, long offset) {
        return jdbc.sql(SELECT + " WHERE o.organization_id = :org AND (CAST(:type AS text) IS NULL OR o.type = :type)"
                        + " ORDER BY o.name, o.id LIMIT :limit OFFSET :offset")
                .param("org", orgId).param("type", type).param("limit", limit).param("offset", offset)
                .query(OutputConnectionRepository::map).list();
    }

    public long count(long orgId, String type) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.output_connections WHERE organization_id = :org"
                        + " AND (CAST(:type AS text) IS NULL OR type = :type)")
                .param("org", orgId).param("type", type).query(Long.class).single();
    }

    public boolean existsName(long orgId, String name, Long exceptId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.output_connections
                                        WHERE organization_id = :org AND name = :name AND (CAST(:except AS bigint) IS NULL OR id <> :except))""")
                .param("org", orgId).param("name", name).param("except", exceptId).query(Boolean.class).single();
    }

    /** 실행 설정(API-DSC-73): 여러 조직의 연결 전체 */
    @OrganizationScopeExempt("action 내부 API: 이 배포가 맡는 조직 목록(ADR-030)으로 좁힌다")
    public List<OutputRow> listForRuntime(Collection<Long> organizations) {
        return jdbc.sql(SELECT + " WHERE o.organization_id = ANY(CAST(:orgs AS bigint[])) ORDER BY o.id")
                .param("orgs", Pg.bigintArray(organizations)).query(OutputConnectionRepository::map).list();
    }

    // ---------------------------------------------------------------- 비밀값

    public void upsertSecret(long orgId, long outputId, String kind, byte[] ciphertext, String kid, String fingerprint, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.output_secrets (output_id, organization_id, kind, ciphertext, kid, fingerprint, rotated_at,
                                                                  created_at)
                        VALUES (:id, :org, :kind, :enc, :kid, :fp, :now, :now)
                        ON CONFLICT (output_id, kind) DO UPDATE
                           SET ciphertext = EXCLUDED.ciphertext, kid = EXCLUDED.kid, fingerprint = EXCLUDED.fingerprint,
                               rotated_at = EXCLUDED.rotated_at""")
                .param("id", outputId).param("org", orgId).param("kind", kind).param("enc", ciphertext).param("kid", kid).param("fp", fingerprint)
                .param("now", Pg.ts(now)).update();
    }

    public int deleteSecret(long orgId, long outputId, String kind) {
        return jdbc.sql("DELETE FROM data2flow_core.output_secrets WHERE organization_id = :org AND output_id = :id AND kind = :kind")
                .param("org", orgId).param("id", outputId).param("kind", kind).update();
    }

    /** 종류가 바뀌어 쓸 수 없게 된 비밀값을 지운다 */
    public int deleteSecretsExcept(long orgId, long outputId, Collection<String> kinds) {
        return jdbc.sql("""
                        DELETE FROM data2flow_core.output_secrets
                         WHERE organization_id = :org AND output_id = :id AND NOT (kind = ANY(CAST(:kinds AS text[])))""")
                .param("org", orgId).param("id", outputId).param("kinds", Pg.textArray(kinds)).update();
    }

    public List<SecretRow> secrets(Collection<Long> outputIds) {
        if (outputIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT output_id, kind, ciphertext FROM data2flow_core.output_secrets WHERE output_id = ANY(CAST(:ids AS bigint[]))")
                .param("ids", Pg.bigintArray(outputIds))
                .query((rs, n) -> new SecretRow(rs.getLong("output_id"), rs.getString("kind"), rs.getBytes("ciphertext"))).list();
    }

    // ---------------------------------------------------------------- 실행 설정 버전

    public long bumpVersion(long orgId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.output_config_versions (organization_id, version, updated_at) VALUES (:org, 1, :now)
                        ON CONFLICT (organization_id) DO UPDATE
                           SET version = data2flow_core.output_config_versions.version + 1, updated_at = EXCLUDED.updated_at
                        RETURNING version""")
                .param("org", orgId).param("now", Pg.ts(now)).query(Long.class).single();
    }

    public long versionSum(Collection<Long> organizations) {
        return jdbc.sql("SELECT coalesce(sum(version), 0) FROM data2flow_core.output_config_versions WHERE organization_id = ANY(CAST(:orgs AS bigint[]))")
                .param("orgs", Pg.bigintArray(organizations)).query(Long.class).single();
    }

    // ---------------------------------------------------------------- 발송 지표

    /** 1분 지표 더하기(API-DSC-75). 그 조직의 연결이 없으면 0 */
    public int addStats(long orgId, long outputId, Instant minute, int sent, int failed, int retried, Integer lagMs) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.output_delivery_stats (output_id, minute, organization_id, sent, failed, retried, lag_ms)
                        SELECT o.id, :minute, o.organization_id, :sent, :failed, :retried, :lag
                          FROM data2flow_core.output_connections o WHERE o.organization_id = :org AND o.id = :id
                        ON CONFLICT (output_id, minute) DO UPDATE
                           SET sent = data2flow_core.output_delivery_stats.sent + EXCLUDED.sent,
                               failed = data2flow_core.output_delivery_stats.failed + EXCLUDED.failed,
                               retried = data2flow_core.output_delivery_stats.retried + EXCLUDED.retried,
                               lag_ms = GREATEST(data2flow_core.output_delivery_stats.lag_ms, EXCLUDED.lag_ms)""")
                .param("minute", Pg.ts(minute)).param("sent", sent).param("failed", failed).param("retried", retried).param("lag", lagMs)
                .param("org", orgId).param("id", outputId).update();
    }

    public List<StatRow> stats(long orgId, long outputId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT minute, sent, failed, retried, lag_ms FROM data2flow_core.output_delivery_stats
                         WHERE organization_id = :org AND output_id = :id AND minute >= :from AND minute < :to ORDER BY minute""")
                .param("org", orgId).param("id", outputId).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query((rs, n) -> new StatRow(Pg.instant(rs, "minute"), rs.getInt("sent"), rs.getInt("failed"), rs.getInt("retried"),
                        (Integer) rs.getObject("lag_ms"))).list();
    }

    public int purgeStats(Instant before) {
        return jdbc.sql("DELETE FROM data2flow_core.output_delivery_stats WHERE minute < :before").param("before", Pg.ts(before)).update();
    }

    // ---------------------------------------------------------------- 기기 맥락·샘플

    /** API-DSC-74 기기 맥락(배포 조직 안) */
    public List<DeviceContextRow> deviceContexts(Collection<Long> organizations, Collection<Long> deviceIds) {
        return jdbc.sql("""
                        SELECT d.id, d.organization_id, d.name, d.space_id, sp.code AS space_code, sp.path AS space_path,
                               coalesce((SELECT array_agg(m.group_id ORDER BY m.group_id) FROM data2flow_core.device_group_members m
                                          WHERE m.device_id = d.id AND m.organization_id = d.organization_id), '{}') AS group_ids
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_core.spaces sp ON sp.id = d.space_id AND sp.organization_id = d.organization_id
                         WHERE d.organization_id = ANY(CAST(:orgs AS bigint[])) AND d.id = ANY(CAST(:ids AS bigint[]))
                         ORDER BY d.id""")
                .param("orgs", Pg.bigintArray(organizations)).param("ids", Pg.bigintArray(deviceIds))
                .query((rs, n) -> new DeviceContextRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"),
                        Pg.longOrNull(rs, "space_id"), rs.getString("space_code"), rs.getString("space_path"), Pg.longList(rs, "group_ids")))
                .list();
    }

    /** 테스트 발송 샘플용 기기와 현재값({@code data2flow_pipeline.device_state.latest}, 읽기만) */
    public Optional<SampleDeviceRow> sampleDevice(long orgId, long deviceId) {
        return jdbc.sql("""
                        SELECT d.id, d.organization_id, d.source_id, d.external_id, d.name, d.status, d.model_id, d.space_id,
                               ds.latest::text AS latest
                          FROM data2flow_core.devices d
                          LEFT JOIN data2flow_pipeline.device_state ds ON ds.device_id = d.id AND ds.organization_id = d.organization_id
                         WHERE d.organization_id = :org AND d.id = :id""")
                .param("org", orgId).param("id", deviceId)
                .query((rs, n) -> new SampleDeviceRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("source_id"),
                        rs.getString("external_id"), rs.getString("name"), rs.getString("status"), Pg.longOrNull(rs, "model_id"),
                        Pg.longOrNull(rs, "space_id"), rs.getString("latest"))).optional();
    }

    /** 필터가 가리키는 ID 중 이 조직에 없는 것 */
    public List<Long> missing(long orgId, String table, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String t = switch (table) {
            case "devices", "device_groups", "spaces" -> table;
            default -> throw new IllegalArgumentException(table);
        };
        return jdbc.sql("SELECT x FROM unnest(CAST(:ids AS bigint[])) x WHERE NOT EXISTS (SELECT 1 FROM data2flow_core." + t
                        + " t WHERE t.organization_id = :org AND t.id = x)")
                .param("ids", Pg.bigintArray(ids)).param("org", orgId).query(Long.class).list();
    }

    static OutputRow map(ResultSet rs, int n) throws SQLException {
        return new OutputRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("name"), rs.getString("type"), rs.getString("target"),
                rs.getString("filter"), rs.getString("format"), rs.getString("template"), rs.getBoolean("enabled"), rs.getInt("version"),
                rs.getLong("created_by"), rs.getLong("updated_by"), Pg.instant(rs, "created_at"), Pg.instant(rs, "updated_at"),
                Pg.stringList(rs, "secret_kinds"));
    }
}
