package net.java21.data2flow.core.device.repository;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.device.domain.DeviceFilter;
import net.java21.data2flow.core.device.domain.DeviceListRow;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 기기 조회(API-DEV-11 목록·API-DEV-20 내보내기·API-DEV-23 상세). 연결 상태·최근값은 pipeline 소유
 * {@code data2flow_pipeline.device_state}를 읽기만 한다(conventions §6, domain-model §2.6).
 */
@Repository
public class DeviceQueryRepository {

    private static final String FROM = """
             FROM data2flow_core.devices d
             LEFT JOIN data2flow_pipeline.device_state st ON st.device_id = d.id AND st.organization_id = d.organization_id
             LEFT JOIN data2flow_core.device_models m ON m.id = d.model_id AND m.organization_id = d.organization_id
             LEFT JOIN data2flow_core.data_sources s ON s.id = d.source_id AND s.organization_id = d.organization_id
            """;
    private static final String SELECT = "SELECT " + DeviceRepository.COLUMNS + """
            , m.code AS model_code, m.name AS model_name, m.kind AS model_kind, s.name AS source_name,
              coalesce(st.connectivity, 'UNKNOWN') AS connectivity, st.last_seen_at, st.battery, st.rssi,
              ARRAY(SELECT k FROM (SELECT jsonb_object_keys(coalesce(st.latest, '{}'::jsonb)) AS k
                                   UNION SELECT jsonb_array_elements_text(CASE WHEN jsonb_typeof(d.source_meta -> 'metrics') = 'array'
                                                                               THEN d.source_meta -> 'metrics' ELSE '[]'::jsonb END)) x
                    ORDER BY k) AS metric_keys,
              ARRAY(SELECT t.tag FROM data2flow_core.device_tags t WHERE t.device_id = d.id ORDER BY t.created_at, t.tag) AS tags
            """;
    /** 정렬 필드 → 열(SortParams#toOrderBy) */
    public static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "d.name", "lastSeenAt", "st.last_seen_at", "createdAt", "d.created_at", "updatedAt", "d.updated_at");

    private final JdbcClient jdbc;

    public DeviceQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<DeviceListRow> list(DeviceFilter filter, String orderBy, int limit, long offset) {
        Where where = where(filter);
        String order = (orderBy == null || orderBy.isBlank() ? "d.name ASC" : orderBy)
                .replace(" ASC", " ASC NULLS LAST").replace(" DESC", " DESC NULLS LAST");
        return jdbc.sql(SELECT + FROM + where.sql + " ORDER BY " + order + ", d.id ASC LIMIT :limit OFFSET :offset")
                .params(where.params).param("limit", limit).param("offset", offset)
                .query(DeviceQueryRepository::mapRow).list();
    }

    public long count(DeviceFilter filter) {
        Where where = where(filter);
        return jdbc.sql("SELECT count(*)" + FROM + where.sql).params(where.params).query(Long.class).single();
    }

    /** 이 트랜잭션의 문장 시간 제한(검색식 BR-DEV-35: 5초). 트랜잭션이 끝나면 풀린다 */
    public void setStatementTimeout(int millis) {
        jdbc.sql("SELECT set_config('statement_timeout', :ms, true)").param("ms", Integer.toString(millis)).query(String.class).single();
    }

    /** 조건에 맞는 기기 ID(그룹 미리 보기·동적 그룹 계산) */
    public List<Long> listIds(DeviceFilter filter, int limit) {
        Where where = where(filter);
        return jdbc.sql("SELECT d.id" + FROM + where.sql + " ORDER BY d.id LIMIT :limit")
                .params(where.params).param("limit", limit).query(Long.class).list();
    }

    public Optional<StateRow> findState(long organizationId, long deviceId) {
        return jdbc.sql("""
                        SELECT connectivity, last_seen_at, last_measured_at, battery, rssi, snr, best_gateway_eui, msg_count_24h,
                               latest::text AS latest
                          FROM data2flow_pipeline.device_state WHERE organization_id = :org AND device_id = :id""")
                .param("org", organizationId).param("id", deviceId)
                .query((rs, n) -> new StateRow(rs.getString("connectivity"), Pg.instant(rs, "last_seen_at"),
                        Pg.instant(rs, "last_measured_at"), rs.getBigDecimal("battery"), rs.getBigDecimal("rssi"),
                        rs.getBigDecimal("snr"), rs.getString("best_gateway_eui"), rs.getInt("msg_count_24h"), rs.getString("latest")))
                .optional();
    }

    /** 측정 항목 표시 이름·단위(최근값 표시) */
    public Map<String, String[]> findMetricLabels(long organizationId, Collection<String> keys) {
        Map<String, String[]> result = new HashMap<>();
        if (keys.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT key, display_name, unit FROM data2flow_core.metrics WHERE organization_id = :org AND key = ANY(CAST(:keys AS text[]))")
                .param("org", organizationId).param("keys", Pg.textArray(keys))
                .query((RowCallbackHandler) rs -> result.put(rs.getString("key"),
                        new String[] {rs.getString("display_name"), rs.getString("unit")}));
        return result;
    }

    /** 기기가 속한 그룹 [id, name] */
    public List<Object[]> findGroups(long organizationId, long deviceId) {
        return jdbc.sql("""
                        SELECT g.id, g.name FROM data2flow_core.device_group_members gm
                          JOIN data2flow_core.device_groups g ON g.id = gm.group_id
                         WHERE gm.organization_id = :org AND gm.device_id = :id ORDER BY g.name""")
                .param("org", organizationId).param("id", deviceId)
                .query((rs, n) -> new Object[] {rs.getLong("id"), rs.getString("name")}).list();
    }

    /** 직접 추가한 공간 관계 [spaceId, relation] (DEV-01.05) */
    public List<Object[]> findRelations(long organizationId, long deviceId) {
        return jdbc.sql("""
                        SELECT space_id, relation FROM data2flow_core.device_space_relations
                         WHERE organization_id = :org AND device_id = :id ORDER BY space_id, relation""")
                .param("org", organizationId).param("id", deviceId)
                .query((rs, n) -> new Object[] {rs.getLong("space_id"), rs.getString("relation")}).list();
    }

    /** 공간과 그 하위 공간 ID(공간 범위 필터) */
    public List<Long> findSpaceWithDescendants(long organizationId, long spaceId) {
        return jdbc.sql("""
                        SELECT c.id FROM data2flow_core.spaces c JOIN data2flow_core.spaces p ON c.path LIKE p.path || '%'
                         WHERE p.organization_id = :org AND c.organization_id = :org AND p.id = :id""")
                .param("org", organizationId).param("id", spaceId).query(Long.class).list();
    }

    private static Where where(DeviceFilter f) {
        StringBuilder sql = new StringBuilder(" WHERE d.organization_id = :org AND d.status <> 'DELETED'");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("org", f.organizationId());
        if (f.q() != null && !f.q().isBlank()) {
            sql.append(" AND (d.name ILIKE :q ESCAPE '\\' OR d.external_id ILIKE :q ESCAPE '\\')");
            p.put("q", "%" + likeEscape(f.q().strip()) + "%");
        }
        if (!f.statuses().isEmpty()) {
            sql.append(" AND d.status = ANY(CAST(:statuses AS text[]))");
            p.put("statuses", Pg.textArray(f.statuses()));
        }
        if (!f.connectivities().isEmpty()) {
            sql.append(" AND coalesce(st.connectivity, 'UNKNOWN') = ANY(CAST(:conn AS text[]))");
            p.put("conn", Pg.textArray(f.connectivities()));
        }
        if (!f.kinds().isEmpty()) {
            sql.append(" AND d.kind = ANY(CAST(:kinds AS text[]))");
            p.put("kinds", Pg.textArray(f.kinds()));
        }
        if (f.modelId() != null) {
            sql.append(" AND d.model_id = :modelId");
            p.put("modelId", f.modelId());
        }
        if (f.spaceId() != null) {
            if (f.includeDescendants()) {
                sql.append("""
                         AND d.space_id IN (SELECT c.id FROM data2flow_core.spaces c JOIN data2flow_core.spaces sp ON c.path LIKE sp.path || '%'
                                             WHERE sp.id = :spaceId AND sp.organization_id = :org AND c.organization_id = :org)""");
            } else {
                sql.append(" AND d.space_id = :spaceId");
            }
            p.put("spaceId", f.spaceId());
        }
        if (f.sourceId() != null) {
            sql.append(" AND d.source_id = :sourceId");
            p.put("sourceId", f.sourceId());
        }
        if (!f.tags().isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM data2flow_core.device_tags t WHERE t.device_id = d.id AND lower(t.tag) = ANY(CAST(:tags AS text[])))");
            p.put("tags", Pg.textArray(f.tags().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList()));
        }
        if (f.groupId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM data2flow_core.device_group_members gm WHERE gm.device_id = d.id AND gm.group_id = :groupId"
                    + " AND gm.organization_id = :org)");
            p.put("groupId", f.groupId());
        }
        if (f.virtual() != null) {
            sql.append(" AND d.is_virtual = :virtual");
            p.put("virtual", f.virtual());
        }
        if (f.onboarding() != null) {
            String complete = "((st.last_seen_at IS NOT NULL OR d.first_seen_at IS NOT NULL) AND d.model_id IS NOT NULL"
                    + " AND d.space_id IS NOT NULL AND d.kind <> 'SENSOR')";
            sql.append("COMPLETE".equals(f.onboarding()) ? " AND " + complete : " AND NOT " + complete);
        }
        if (f.allowedSpaceIds() != null) {
            sql.append(" AND d.space_id = ANY(CAST(:allowed AS bigint[]))");
            p.put("allowed", Pg.bigintArray(f.allowedSpaceIds()));
        }
        if (f.expression() != null) {
            sql.append(" AND (").append(f.expression().sql()).append(')');
            p.putAll(f.expression().params());
        }
        if (f.updatedAfter() != null) {
            sql.append(" AND d.updated_at > :updatedAfter");
            p.put("updatedAfter", Pg.ts(f.updatedAfter()));
        }
        return new Where(sql.toString(), p);
    }

    static String likeEscape(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static DeviceListRow mapRow(ResultSet rs, int n) throws SQLException {
        return new DeviceListRow(DeviceRepository.map(rs, n), rs.getString("model_code"), rs.getString("model_name"),
                rs.getString("model_kind"), rs.getString("source_name"), rs.getString("connectivity"), Pg.instant(rs, "last_seen_at"),
                rs.getBigDecimal("battery"), rs.getBigDecimal("rssi"), Pg.stringList(rs, "metric_keys"),
                new ArrayList<>(Pg.stringList(rs, "tags")));
    }

    private record Where(String sql, Map<String, Object> params) {
    }

    /** pipeline {@code device_state} 한 행. latest는 {@code {metricKey:{v,t,q}}} JSON 문자열 */
    public record StateRow(String connectivity, Instant lastSeenAt, Instant lastMeasuredAt, BigDecimal battery, BigDecimal rssi,
                           BigDecimal snr, String bestGatewayEui, int msgCount24h, String latest) {
    }
}
