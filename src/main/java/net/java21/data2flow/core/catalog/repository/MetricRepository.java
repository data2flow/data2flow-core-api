package net.java21.data2flow.core.catalog.repository;

import net.java21.data2flow.core.catalog.domain.CatalogModels.Metric;
import net.java21.data2flow.core.catalog.domain.CatalogModels.MetricAlias;
import net.java21.data2flow.core.catalog.domain.CatalogModels.MetricFields;
import net.java21.data2flow.core.catalog.domain.CatalogModels.RemapJob;
import net.java21.data2flow.core.catalog.domain.CatalogModels.Sample;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;

/**
 * 측정 항목·별칭·재매핑 작업 저장소({@code metrics}, {@code metric_aliases}, {@code metric_remap_jobs}).
 * 최근값 예시와 재매핑 진행률은 pipeline 소유 테이블({@code device_state}, {@code telemetry})을 읽기만 한다(conventions §6).
 */
@Repository
public class MetricRepository {

    private static final String COLUMNS = """
            id, organization_id, key, display_name, unit, value_type, enum_map::text AS enum_map, valid_min, valid_max, precision,
            agg_default, state_type, semantic, status, first_seen_at, first_seen_device_id, builtin, version, updated_at""";

    private final JdbcClient jdbc;

    public MetricRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 목록 조건(API-DEV-50) */
    public record MetricFilter(long organizationId, String status, String q, String key) {
    }

    public List<Metric> list(MetricFilter f, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.metrics WHERE " + where()
                        + " ORDER BY builtin DESC, key LIMIT :limit OFFSET :offset")
                .params(filterParams(f)).param("limit", limit).param("offset", offset)
                .query(MetricRepository::map).list();
    }

    public long count(MetricFilter f) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.metrics WHERE " + where()).params(filterParams(f))
                .query(Long.class).single();
    }

    private static String where() {
        return """
                organization_id = :org
                AND (CAST(:status AS text) IS NULL OR status = :status)
                AND (CAST(:key AS text) IS NULL OR key = :key)
                AND (CAST(:q AS text) IS NULL OR key ILIKE :like OR display_name ILIKE :like)""";
    }

    private static Map<String, Object> filterParams(MetricFilter f) {
        Map<String, Object> p = new HashMap<>();
        p.put("org", f.organizationId());
        p.put("status", f.status());
        p.put("key", f.key());
        p.put("q", f.q());
        p.put("like", f.q() == null ? null : "%" + f.q().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        return p;
    }

    /** 조직의 모든 측정 항목(내부 전체 조회 API-DEV-123) */
    public List<Metric> listAll(long organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.metrics WHERE organization_id = :org ORDER BY key")
                .param("org", organizationId).query(MetricRepository::map).list();
    }

    public Optional<Metric> find(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.metrics WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId).query(MetricRepository::map).optional();
    }

    public Optional<Metric> findByKey(long organizationId, String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.metrics WHERE key = :key AND organization_id = :org")
                .param("key", key).param("org", organizationId).query(MetricRepository::map).optional();
    }

    /** 여러 키의 상태(모델 측정 항목 검증용). 키 → 상태 */
    public Map<String, String> findStatuses(long organizationId, List<String> keys) {
        Map<String, String> result = new HashMap<>();
        if (keys.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT key, status FROM data2flow_core.metrics WHERE organization_id = :org AND key = ANY(CAST(:keys AS text[]))")
                .param("org", organizationId).param("keys", Pg.textArray(keys))
                .query((rs, n) -> result.put(rs.getString("key"), rs.getString("status"))).list();
        return result;
    }

    /** 새 측정 항목(사용자 생성, VERIFIED). 같은 키가 있으면 빈 값 */
    public Optional<Long> insert(long organizationId, String key, MetricFields f, String status, boolean builtin, Long createdBy,
                                 Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.metrics (organization_id, key, display_name, unit, value_type, enum_map, valid_min,
                            valid_max, precision, agg_default, state_type, semantic, status, builtin, created_by, updated_by,
                            created_at, updated_at)
                        VALUES (:org, :key, :name, :unit, :type, CAST(:enum AS jsonb), :min, :max, :precision, :agg, :state, :semantic,
                            :status, :builtin, :by, :by, :now, :now)
                        ON CONFLICT (organization_id, key) DO NOTHING RETURNING id""")
                .param("org", organizationId).param("key", key).param("name", f.displayName()).param("unit", f.unit())
                .param("type", f.valueType()).param("enum", f.enumMapJson()).param("min", f.validMin()).param("max", f.validMax())
                .param("precision", f.precision()).param("agg", f.aggDefault()).param("state", f.stateType())
                .param("semantic", f.semantic()).param("status", status).param("builtin", builtin).param("by", createdBy)
                .param("now", Pg.ts(now))
                .query(Long.class).optional();
    }

    /** 처음 보는 키를 UNVERIFIED로(ING-04.02). 동시에 여러 번 와도 한 행만 생긴다. 이미 있으면 빈 값 */
    public Optional<Long> insertUnverified(long organizationId, String key, Long deviceId, Instant seenAt) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.metrics (organization_id, key, display_name, status, first_seen_at, first_seen_device_id,
                            created_at, updated_at)
                        VALUES (:org, :key, :name, 'UNVERIFIED', :seen, :device, :seen, :seen)
                        ON CONFLICT (organization_id, key) DO NOTHING RETURNING id""")
                .param("org", organizationId).param("key", key).param("name", key.length() > 50 ? key.substring(0, 50) : key)
                .param("seen", Pg.ts(seenAt)).param("device", deviceId)
                .query(Long.class).optional();
    }

    public int update(long organizationId, long id, int baseVersion, MetricFields f, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.metrics
                           SET display_name = :name, unit = :unit, value_type = :type, enum_map = CAST(:enum AS jsonb), valid_min = :min,
                               valid_max = :max, precision = :precision, agg_default = :agg, state_type = :state, semantic = :semantic,
                               updated_by = :by, updated_at = :now, version = version + 1
                         WHERE id = :id AND organization_id = :org AND version = :base""")
                .param("name", f.displayName()).param("unit", f.unit()).param("type", f.valueType()).param("enum", f.enumMapJson())
                .param("min", f.validMin()).param("max", f.validMax()).param("precision", f.precision()).param("agg", f.aggDefault())
                .param("state", f.stateType()).param("semantic", f.semantic()).param("by", updatedBy).param("now", Pg.ts(now))
                .param("id", id).param("org", organizationId).param("base", baseVersion)
                .update();
    }

    /** 미검증 항목을 표준으로 등록(UNVERIFIED → VERIFIED, API-DEV-52). 상태가 이미 바뀌었으면 0 */
    public int updateVerified(long organizationId, long id, MetricFields f, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.metrics
                           SET display_name = :name, unit = :unit, value_type = :type, enum_map = CAST(:enum AS jsonb), valid_min = :min,
                               valid_max = :max, precision = :precision, agg_default = :agg, state_type = :state, semantic = :semantic,
                               status = 'VERIFIED', updated_by = :by, updated_at = :now, version = version + 1
                         WHERE id = :id AND organization_id = :org AND status = 'UNVERIFIED'""")
                .param("name", f.displayName()).param("unit", f.unit()).param("type", f.valueType()).param("enum", f.enumMapJson())
                .param("min", f.validMin()).param("max", f.validMax()).param("precision", f.precision()).param("agg", f.aggDefault())
                .param("state", f.stateType()).param("semantic", f.semantic()).param("by", updatedBy).param("now", Pg.ts(now))
                .param("id", id).param("org", organizationId)
                .update();
    }

    /** 상태 전이(from → to). 현재 상태가 from이 아니면 0 */
    public int updateStatus(long organizationId, long id, String from, String to, long updatedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.metrics SET status = :to, updated_by = :by, updated_at = :now, version = version + 1
                         WHERE id = :id AND organization_id = :org AND status = :from""")
                .param("to", to).param("by", updatedBy).param("now", Pg.ts(now)).param("id", id).param("org", organizationId)
                .param("from", from).update();
    }

    /** 미검증 항목 삭제(별칭으로 연결할 때). 상태가 바뀌었으면 0 */
    public int deleteUnverified(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.metrics WHERE id = :id AND organization_id = :org AND status = 'UNVERIFIED'")
                .param("id", id).param("org", organizationId).update();
    }

    // ---- 별칭

    public List<MetricAlias> listAliases(long organizationId, int limit, long offset) {
        return jdbc.sql("""
                        SELECT a.id, a.organization_id, a.alias, a.metric_key, m.id AS metric_id, a.created_by, a.created_at
                          FROM data2flow_core.metric_aliases a
                          LEFT JOIN data2flow_core.metrics m ON m.organization_id = a.organization_id AND m.key = a.metric_key
                         WHERE a.organization_id = :org ORDER BY a.alias LIMIT :limit OFFSET :offset""")
                .param("org", organizationId).param("limit", limit).param("offset", offset)
                .query(MetricRepository::mapAlias).list();
    }

    public long countAliases(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.metric_aliases WHERE organization_id = :org")
                .param("org", organizationId).query(Long.class).single();
    }

    public Optional<MetricAlias> findAlias(long organizationId, long id) {
        return jdbc.sql("""
                        SELECT a.id, a.organization_id, a.alias, a.metric_key, m.id AS metric_id, a.created_by, a.created_at
                          FROM data2flow_core.metric_aliases a
                          LEFT JOIN data2flow_core.metrics m ON m.organization_id = a.organization_id AND m.key = a.metric_key
                         WHERE a.id = :id AND a.organization_id = :org""")
                .param("id", id).param("org", organizationId).query(MetricRepository::mapAlias).optional();
    }

    /** 별칭 → 표준 키 */
    public Optional<String> findAliasTarget(long organizationId, String alias) {
        return jdbc.sql("SELECT metric_key FROM data2flow_core.metric_aliases WHERE organization_id = :org AND alias = :alias")
                .param("org", organizationId).param("alias", alias).query(String.class).optional();
    }

    /** 조직의 별칭 전체(별칭 → 표준 키) */
    public Map<String, String> listAliasMap(long organizationId) {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.sql("SELECT alias, metric_key FROM data2flow_core.metric_aliases WHERE organization_id = :org ORDER BY alias")
                .param("org", organizationId).query((rs, n) -> result.put(rs.getString("alias"), rs.getString("metric_key"))).list();
        return result;
    }

    /** 표준 키 → 별칭 목록 */
    public Map<String, List<String>> listAliasesByKey(long organizationId, List<String> keys) {
        Map<String, List<String>> result = new HashMap<>();
        if (keys.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        SELECT metric_key, alias FROM data2flow_core.metric_aliases
                         WHERE organization_id = :org AND metric_key = ANY(CAST(:keys AS text[])) ORDER BY alias""")
                .param("org", organizationId).param("keys", Pg.textArray(keys))
                .query((rs, n) -> result.computeIfAbsent(rs.getString("metric_key"), k -> new ArrayList<>()).add(rs.getString("alias")))
                .list();
        return result;
    }

    public long insertAlias(long organizationId, String alias, String metricKey, Long createdBy, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.metric_aliases (organization_id, alias, metric_key, created_by, created_at)
                        VALUES (:org, :alias, :key, :by, :now) RETURNING id""")
                .param("org", organizationId).param("alias", alias).param("key", metricKey).param("by", createdBy)
                .param("now", Pg.ts(now)).query(Long.class).single();
    }

    public int deleteAlias(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.metric_aliases WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId).update();
    }

    // ---- pipeline 소유 테이블 읽기

    /** 이 키의 최근값 예시: 이 키를 가장 최근에 보낸 기기들의 마지막 값(device_state.latest, 기기당 한 행이라 가볍다) */
    public List<Sample> listSamples(long organizationId, String key, int limit) {
        return jdbc.sql("""
                        SELECT device_id, (latest -> CAST(:key AS text) ->> 'v') AS v, (latest -> CAST(:key AS text) ->> 't') AS t, last_seen_at
                          FROM data2flow_pipeline.device_state
                         WHERE organization_id = :org AND (latest -> CAST(:key AS text)) IS NOT NULL
                         ORDER BY last_seen_at DESC NULLS LAST LIMIT :limit""")
                .param("org", organizationId).param("key", key).param("limit", limit)
                .query((rs, n) -> sample(rs)).list().stream().filter(s -> s != null).toList();
    }

    private static Sample sample(ResultSet rs) throws SQLException {
        String v = rs.getString("v");
        if (v == null) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(v);
        } catch (NumberFormatException ex) {
            return null;
        }
        Instant at = Pg.instant(rs, "last_seen_at");
        String t = rs.getString("t");
        if (t != null) {
            try {
                at = Instant.parse(t);
            } catch (RuntimeException ignored) {
                // 형식이 다르면 마지막 수신 시각을 쓴다
            }
        }
        return new Sample(rs.getLong("device_id"), value, at);
    }

    /** 이 키로 남아 있는 시계열 행 수(재매핑 진행률). from 이후만 센다 */
    public long countTelemetry(long organizationId, String key, Instant from) {
        return jdbc.sql("""
                        SELECT count(*) FROM data2flow_pipeline.telemetry
                         WHERE organization_id = :org AND metric_key = :key AND (CAST(:from AS timestamptz) IS NULL OR time >= :from)""")
                .param("org", organizationId).param("key", key).param("from", Pg.ts(from)).query(Long.class).single();
    }

    // ---- 재매핑 작업

    public long insertRemapJob(long organizationId, String alias, String targetKey, Instant from, long total, Long requestedBy,
                               Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.metric_remap_jobs (organization_id, alias, target_key, from_ts, status, total,
                            requested_by, created_at, updated_at)
                        VALUES (:org, :alias, :target, :from, :status, :total, :by, :now, :now) RETURNING id""")
                .param("org", organizationId).param("alias", alias).param("target", targetKey).param("from", Pg.ts(from))
                .param("status", total == 0 ? "SUCCEEDED" : "PENDING").param("total", total).param("by", requestedBy)
                .param("now", Pg.ts(now)).query(Long.class).single();
    }

    public Optional<RemapJob> findRemapJob(long organizationId, long id) {
        return jdbc.sql("""
                        SELECT id, organization_id, alias, target_key, from_ts, status, total, processed, pipeline_job_id, error,
                               created_at, updated_at
                          FROM data2flow_core.metric_remap_jobs WHERE id = :id AND organization_id = :org""")
                .param("id", id).param("org", organizationId)
                .query((rs, n) -> new RemapJob(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("alias"),
                        rs.getString("target_key"), rs.getString("status"), rs.getLong("total"), rs.getLong("processed"),
                        rs.getString("pipeline_job_id"), rs.getString("error"), Pg.instant(rs, "created_at"),
                        Pg.instant(rs, "updated_at")))
                .optional();
    }

    /** 진행률 계산의 시작 시각 */
    public Optional<Instant> findRemapJobFrom(long organizationId, long id) {
        return jdbc.sql("SELECT from_ts FROM data2flow_core.metric_remap_jobs WHERE id = :id AND organization_id = :org")
                .param("id", id).param("org", organizationId).query((rs, n) -> Pg.instant(rs, "from_ts")).optional();
    }

    public int updateRemapJob(long organizationId, long id, String status, long processed, String pipelineJobId, String error,
                              Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.metric_remap_jobs
                           SET status = :status, processed = :processed, pipeline_job_id = coalesce(:pipelineJob, pipeline_job_id),
                               error = :error, updated_at = :now
                         WHERE id = :id AND organization_id = :org""")
                .param("status", status).param("processed", processed).param("pipelineJob", pipelineJobId).param("error", error)
                .param("now", Pg.ts(now)).param("id", id).param("org", organizationId).update();
    }

    private static Metric map(ResultSet rs, int n) throws SQLException {
        return new Metric(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("key"), rs.getString("display_name"),
                rs.getString("unit"), rs.getString("value_type"), rs.getString("enum_map"), doubleOrNull(rs, "valid_min"),
                doubleOrNull(rs, "valid_max"), rs.getInt("precision"), rs.getString("agg_default"), rs.getBoolean("state_type"),
                rs.getString("semantic"), rs.getString("status"), Pg.instant(rs, "first_seen_at"),
                Pg.longOrNull(rs, "first_seen_device_id"), rs.getBoolean("builtin"), rs.getInt("version"),
                Pg.instant(rs, "updated_at"));
    }

    private static MetricAlias mapAlias(ResultSet rs, int n) throws SQLException {
        return new MetricAlias(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("alias"), rs.getString("metric_key"),
                Pg.longOrNull(rs, "metric_id"), Pg.longOrNull(rs, "created_by"), Pg.instant(rs, "created_at"));
    }

    private static Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }
}
