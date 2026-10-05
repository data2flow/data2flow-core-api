package net.java21.data2flow.core.analytics.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 입력 판정에 쓰는 core 기준 정보 읽기: 바인딩의 기기·공간(BR-ANA-02·03), 측정 항목 의미 태그, 역할 후보(API-ANA-19), 사이트 계층(DEV-10.02).
 * 기기 마지막 수신 시각은 pipeline 소유 {@code data2flow_pipeline.device_state}를 읽기만 한다(conventions §6 읽기 전용 예외).
 */
@Repository
public class AnalyticsDataRepository {

    private final JdbcClient jdbc;

    public AnalyticsDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record DeviceRef(long id, String name, Long spaceId, String status, boolean virtual) {
    }

    public record SpaceRef(long id, String name, String path) {
    }

    /** 기기(삭제 제외) */
    public Map<Long, DeviceRef> findDevices(long organizationId, Collection<Long> ids) {
        Map<Long, DeviceRef> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT id, name, space_id, status, is_virtual FROM data2flow_core.devices
                         WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[])) AND status <> 'DELETED'""")
                .param("org", organizationId).param("ids", Pg.bigintArray(ids))
                .query((rs, n) -> new DeviceRef(rs.getLong("id"), rs.getString("name"), Pg.longOrNull(rs, "space_id"), rs.getString("status"),
                        rs.getBoolean("is_virtual")))
                .list().forEach(d -> out.put(d.id(), d));
        return out;
    }

    /** 공간(ACTIVE) */
    public Map<Long, SpaceRef> findSpaces(long organizationId, Collection<Long> ids) {
        Map<Long, SpaceRef> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT id, name, path FROM data2flow_core.spaces
                         WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[])) AND status = 'ACTIVE'""")
                .param("org", organizationId).param("ids", Pg.bigintArray(ids))
                .query((rs, n) -> new SpaceRef(rs.getLong("id"), rs.getString("name"), rs.getString("path")))
                .list().forEach(s -> out.put(s.id(), s));
        return out;
    }

    /** 측정 항목 키 → 의미 태그(없으면 값 null). 모르는 키는 결과에 없다 */
    public Map<String, String> metricSemantics(long organizationId, Collection<String> keys) {
        Map<String, String> out = new HashMap<>();
        if (keys.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT key, semantic FROM data2flow_core.metrics WHERE organization_id = :org AND key = ANY(CAST(:keys AS varchar[]))")
                .param("org", organizationId).param("keys", Pg.textArray(keys))
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).list()
                .forEach(r -> out.put(r[0], r[1]));
        return out;
    }

    /** 그 공간과 하위 공간 ID(공간 경로 기준) */
    public List<Long> subtree(long organizationId, long spaceId) {
        return jdbc.sql("""
                        SELECT c.id FROM data2flow_core.spaces p JOIN data2flow_core.spaces c
                               ON c.organization_id = p.organization_id AND c.path LIKE p.path || '%'
                         WHERE p.organization_id = :org AND p.id = :id AND c.status = 'ACTIVE'""")
                .param("org", organizationId).param("id", spaceId).query(Long.class).list();
    }

    /** 역할 후보 한 줄(API-ANA-19) */
    public record Candidate(String kind, Long deviceId, Long spaceId, String metricKey, String label, String semantic, String unit,
                            Instant lastSeenAt) {
    }

    /** 후보 조건. spaceIds·allowedSpaceIds가 null이면 거르지 않는다 */
    public record CandidateFilter(long organizationId, String kind, String semantic, List<Long> spaceIds, List<Long> allowedSpaceIds,
                                  String keyword, boolean includeVirtual) {
    }

    public List<Candidate> candidates(CandidateFilter f, int limit, long offset) {
        return bind(jdbc.sql("SELECT * FROM (" + candidateSql(f) + ") c ORDER BY c.label, c.metric_key LIMIT :limit OFFSET :offset"), f)
                .param("limit", limit).param("offset", offset).query(AnalyticsDataRepository::candidate).list();
    }

    public long countCandidates(CandidateFilter f) {
        return bind(jdbc.sql("SELECT count(*) FROM (" + candidateSql(f) + ") c"), f).query(Long.class).single();
    }

    private static String candidateSql(CandidateFilter f) {
        String deviceFilter = (f.spaceIds() == null ? "" : " AND d.space_id = ANY(CAST(:spaces AS bigint[]))")
                + (f.allowedSpaceIds() == null ? "" : " AND d.space_id = ANY(CAST(:allowed AS bigint[]))")
                + (f.includeVirtual() ? "" : " AND NOT d.is_virtual");
        String semantic = f.semantic() == null ? "" : " AND m.semantic = :semantic";
        String keyword = f.keyword() == null ? "" : " AND (label ILIKE :keyword OR metric_key ILIKE :keyword)";
        return switch (f.kind()) {
            case "SPACE_AGGREGATE" -> """
                    SELECT * FROM (
                      SELECT 'SPACE_AGGREGATE' AS kind, NULL::bigint AS device_id, s.id AS space_id, m.key AS metric_key,
                             s.name || ' · ' || m.display_name AS label, m.semantic, m.unit, max(ds.last_seen_at) AS last_seen_at
                        FROM data2flow_core.devices d
                        JOIN data2flow_core.model_metrics mm ON mm.organization_id = d.organization_id AND mm.model_id = d.model_id
                        JOIN data2flow_core.metrics m ON m.organization_id = d.organization_id AND m.key = mm.metric_key
                        JOIN data2flow_core.spaces s ON s.organization_id = d.organization_id AND s.id = d.space_id
                        LEFT JOIN data2flow_pipeline.device_state ds ON ds.organization_id = d.organization_id AND ds.device_id = d.id
                       WHERE d.organization_id = :org AND d.status = 'ACTIVE' AND m.value_type = 'NUMBER'""" + deviceFilter + semantic + " " + """
                       GROUP BY s.id, s.name, m.key, m.display_name, m.semantic, m.unit) x WHERE true""" + keyword;
            case "DERIVED_METRIC" -> """
                    SELECT * FROM (
                      SELECT 'DERIVED_METRIC' AS kind, CASE WHEN f.target_type = 'DEVICE' THEN d.id END AS device_id,
                             COALESCE(d.space_id, s.id) AS space_id, f.result_key AS metric_key,
                             COALESCE(d.name, s.name) || ' · ' || f.display_name AS label, m.semantic, f.unit, ds.last_seen_at
                        FROM data2flow_core.formula_metrics f
                        LEFT JOIN data2flow_core.devices d ON f.target_type = 'DEVICE' AND d.organization_id = f.organization_id
                                  AND d.id::text = f.target_id AND d.status = 'ACTIVE'
                        LEFT JOIN data2flow_core.spaces s ON f.target_type = 'SPACE' AND s.organization_id = f.organization_id
                                  AND s.id::text = f.target_id AND s.status = 'ACTIVE'
                        LEFT JOIN data2flow_core.metrics m ON m.organization_id = f.organization_id AND m.key = f.result_key
                        LEFT JOIN data2flow_pipeline.device_state ds ON ds.organization_id = f.organization_id AND ds.device_id = d.id
                       WHERE f.organization_id = :org AND f.status = 'ACTIVE' AND (d.id IS NOT NULL OR s.id IS NOT NULL)""" + semantic + " " + """
                      ) x WHERE true""" + (f.spaceIds() == null ? "" : " AND x.space_id = ANY(CAST(:spaces AS bigint[]))")
                    + (f.allowedSpaceIds() == null ? "" : " AND x.space_id = ANY(CAST(:allowed AS bigint[]))") + keyword;
            default -> """
                    SELECT * FROM (
                      SELECT 'DEVICE_METRIC' AS kind, d.id AS device_id, d.space_id, m.key AS metric_key,
                             d.name || ' · ' || m.display_name AS label, m.semantic, m.unit, ds.last_seen_at
                        FROM data2flow_core.devices d
                        JOIN data2flow_core.model_metrics mm ON mm.organization_id = d.organization_id AND mm.model_id = d.model_id
                        JOIN data2flow_core.metrics m ON m.organization_id = d.organization_id AND m.key = mm.metric_key
                        LEFT JOIN data2flow_pipeline.device_state ds ON ds.organization_id = d.organization_id AND ds.device_id = d.id
                       WHERE d.organization_id = :org AND d.status = 'ACTIVE' AND d.space_id IS NOT NULL""" + deviceFilter + semantic + " " + """
                      ) x WHERE true""" + keyword;
        };
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, CandidateFilter f) {
        spec = spec.param("org", f.organizationId());
        if (f.spaceIds() != null) {
            spec = spec.param("spaces", Pg.bigintArray(f.spaceIds()));
        }
        if (f.allowedSpaceIds() != null) {
            spec = spec.param("allowed", Pg.bigintArray(f.allowedSpaceIds()));
        }
        if (f.semantic() != null) {
            spec = spec.param("semantic", f.semantic());
        }
        if (f.keyword() != null) {
            spec = spec.param("keyword", "%" + f.keyword().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return spec;
    }

    static Candidate candidate(ResultSet rs, int n) throws SQLException {
        return new Candidate(rs.getString("kind"), Pg.longOrNull(rs, "device_id"), Pg.longOrNull(rs, "space_id"), rs.getString("metric_key"),
                rs.getString("label"), rs.getString("semantic"), rs.getString("unit"), Pg.instant(rs, "last_seen_at"));
    }

    /** 조직 시간대(일정 프리셋 해석, 기본 Asia/Seoul) */
    public String organizationTimezone(long organizationId) {
        return jdbc.sql("SELECT timezone FROM data2flow_core.organizations WHERE id = :org")
                .param("org", organizationId).query(String.class).optional().orElse("Asia/Seoul");
    }

    /** 기기 ID → 공간 ID(삭제 포함, 실시간 이벤트 거르기) */
    public Map<Long, Long> deviceSpaces(long organizationId, Collection<Long> ids) {
        Map<Long, Long> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT id, space_id FROM data2flow_core.devices WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))")
                .param("org", organizationId).param("ids", Pg.bigintArray(ids))
                .query((rs, n) -> new Long[]{rs.getLong(1), Pg.longOrNull(rs, "space_id")}).list()
                .forEach(r -> out.put(r[0], r[1]));
        return out;
    }
}
