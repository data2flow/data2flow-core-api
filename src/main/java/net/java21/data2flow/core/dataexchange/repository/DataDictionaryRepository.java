package net.java21.data2flow.core.dataexchange.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 데이터 사전 원천(측정 항목·별칭·공간)과 판({@code data_dictionary_versions}, TSD-07.04·BR-TSD-27) */
@Repository
public class DataDictionaryRepository {

    private final JdbcClient jdbc;

    public DataDictionaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record MetricRow(String key, String displayName, String unit, String valueType, String aggDefault, Double validMin,
                            Double validMax, boolean stateType, List<String> aliases) {
    }

    public record SpaceRow(long id, Long parentId, String type, String name, String code, String path) {
    }

    public record VersionRow(int version, String contentHash, String content, Instant createdAt) {
    }

    /** 검증된 측정 항목(무시·미검증 제외)과 별칭, 키 순서 */
    public List<MetricRow> findMetrics(long organizationId) {
        return jdbc.sql("""
                        SELECT m.key, m.display_name, m.unit, m.value_type, m.agg_default, m.valid_min, m.valid_max, m.state_type,
                               COALESCE((SELECT array_agg(a.alias ORDER BY a.alias) FROM data2flow_core.metric_aliases a
                                          WHERE a.organization_id = m.organization_id AND a.metric_key = m.key), '{}') AS aliases
                          FROM data2flow_core.metrics m
                         WHERE m.organization_id = :org AND m.status = 'VERIFIED'
                         ORDER BY m.key""")
                .param("org", organizationId)
                .query((rs, n) -> new MetricRow(rs.getString("key"), rs.getString("display_name"), rs.getString("unit"),
                        rs.getString("value_type"), rs.getString("agg_default"), (Double) rs.getObject("valid_min"),
                        (Double) rs.getObject("valid_max"), rs.getBoolean("state_type"), Pg.stringList(rs, "aliases")))
                .list();
    }

    /** 활성 공간 트리(경로 순) */
    public List<SpaceRow> findSpaces(long organizationId) {
        return jdbc.sql("""
                        SELECT id, parent_id, type, name, code, path FROM data2flow_core.spaces
                         WHERE organization_id = :org AND status = 'ACTIVE' ORDER BY path""")
                .param("org", organizationId)
                .query((rs, n) -> new SpaceRow(rs.getLong("id"), Pg.longOrNull(rs, "parent_id"), rs.getString("type"), rs.getString("name"),
                        rs.getString("code"), rs.getString("path")))
                .list();
    }

    public Optional<VersionRow> findLatest(long organizationId) {
        return jdbc.sql("""
                        SELECT version_no, content_hash, content, created_at FROM data2flow_core.data_dictionary_versions
                         WHERE organization_id = :org ORDER BY version_no DESC LIMIT 1""")
                .param("org", organizationId).query(DataDictionaryRepository::map).optional();
    }

    public Optional<VersionRow> findVersion(long organizationId, int version) {
        return jdbc.sql("""
                        SELECT version_no, content_hash, content, created_at FROM data2flow_core.data_dictionary_versions
                         WHERE organization_id = :org AND version_no = :v""")
                .param("org", organizationId).param("v", version).query(DataDictionaryRepository::map).optional();
    }

    /** 새 판(동시에 둘이 올리면 하나만 성공, 다른 쪽은 다시 읽는다) */
    public boolean insert(long organizationId, int version, String hash, String content, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.data_dictionary_versions (organization_id, version_no, content_hash, content, created_at)
                        VALUES (:org, :v, :hash, CAST(:content AS jsonb), :now) ON CONFLICT DO NOTHING""")
                .param("org", organizationId).param("v", version).param("hash", hash).param("content", content).param("now", Pg.ts(now))
                .update() == 1;
    }

    private static VersionRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new VersionRow(rs.getInt("version_no"), rs.getString("content_hash"), rs.getString("content"), Pg.instant(rs, "created_at"));
    }
}
