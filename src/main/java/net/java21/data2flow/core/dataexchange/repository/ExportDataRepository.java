package net.java21.data2flow.core.dataexchange.repository;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.dataexchange.domain.ExportPlan;
import net.java21.data2flow.core.dataexchange.domain.ExportPlan.PlanSeries;
import net.java21.data2flow.core.telemetry.domain.AggFunction;
import net.java21.data2flow.core.telemetry.domain.BucketGrid;
import net.java21.data2flow.core.telemetry.domain.Resolution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 내보내기 데이터 읽기(pipeline 소유 {@code data2flow_pipeline.telemetry*}를 읽기만, conventions §6). 계열마다 하위 쿼리 하나를 만들어
 * UNION ALL로 묶고 커서(fetch 5,000행)로 흘려 읽는다 — 1년치 원본(약 52만 행)도 메모리에 모으지 않는다.
 * 긴 형식은 (계열, 시각), 넓은 형식은 (시각, 계열) 순서로 준다. 호출자는 트랜잭션 안에서 불러야 한다(PostgreSQL 커서 조건).
 */
@Repository
public class ExportDataRepository {

    static final String P = "data2flow_pipeline.";
    static final int FETCH = 5000;

    private final NamedParameterJdbcTemplate jdbc;

    public ExportDataRepository(DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(FETCH);
        this.jdbc = new NamedParameterJdbcTemplate(template);
    }

    /** 한 점(계열 번호, 시각, 값, 원본이면 품질·집계면 표본 수) */
    public interface PointHandler {
        void point(int series, Instant t, Double value, Integer qualityOrCount) throws Exception;
    }

    /** 예상 행 수(BR-TSD-14 동기·비동기 판단). cap에서 센다 */
    public long countRows(long organizationId, ExportPlan plan, long cap) {
        if (plan.series().isEmpty()) {
            return 0;
        }
        MapSqlParameterSource params = params(organizationId, plan);
        params.addValue("cap", cap);
        Long n = jdbc.queryForObject("SELECT count(*) FROM (" + union(plan, params, false) + " LIMIT :cap) x", params, Long.class);
        return n == null ? 0 : n;
    }

    /** 모든 점을 순서대로 흘려 보낸다. 처리기가 예외를 던지면 그대로 멈춘다 */
    public void streamPoints(long organizationId, ExportPlan plan, PointHandler handler) {
        if (plan.series().isEmpty()) {
            return;
        }
        MapSqlParameterSource params = params(organizationId, plan);
        String sql = "SELECT t, s, v, q FROM (" + union(plan, params, true) + ") x ORDER BY "
                + (plan.wide() ? "t, s" : "s, t");
        jdbc.query(sql, params, rs -> {
            try {
                Object v = rs.getObject("v");
                Object q = rs.getObject("q");
                handler.point(rs.getInt("s"), Pg.instant(rs, "t"), v == null ? null : ((Number) v).doubleValue(),
                        q == null ? null : ((Number) q).intValue());
            } catch (SQLException | RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private static MapSqlParameterSource params(long organizationId, ExportPlan plan) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("org", organizationId);
        params.addValue("from", Pg.ts(plan.from()));
        params.addValue("to", Pg.ts(plan.to()));
        params.addValue("virtual", plan.virtual());
        params.addValue("q", "{" + plan.qualities().stream().map(String::valueOf).collect(Collectors.joining(",")) + "}");
        Resolution level = level(plan.resolution());
        params.addValue("lower", Pg.ts(level == Resolution.RAW ? plan.from() : BucketGrid.lowerBound(level, plan.from())));
        params.addValue("slower", Pg.ts(BucketGrid.lowerBound(level(aggregateLevel(plan)), plan.from())));
        return params;
    }

    private static String union(ExportPlan plan, MapSqlParameterSource params, boolean withValues) {
        Resolution level = level(plan.resolution());
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < plan.series().size(); i++) {
            PlanSeries s = plan.series().get(i);
            params.addValue("m" + i, s.metric());
            if (s.space()) {
                if (s.deviceIds().isEmpty()) {
                    continue;
                }
                params.addValue("ids" + i, Pg.bigintArray(s.deviceIds()));
                AggFunction func = AggFunction.parse(s.agg() == null ? "avg" : s.agg());
                AggFunction column = func == AggFunction.MIN || func == AggFunction.MAX ? func : AggFunction.AVG;
                parts.add("""
                        SELECT bucket AS t, %d AS s, %s(%s) AS v, count(DISTINCT device_id) AS q FROM %s
                         WHERE organization_id = :org AND metric_key = :m%d AND device_id = ANY(CAST(:ids%d AS bigint[]))
                           AND bucket > :slower AND bucket < :to AND (:virtual OR NOT is_virtual) AND %s IS NOT NULL
                         GROUP BY bucket""".formatted(i, func.key(), column.column(), P + level(aggregateLevel(plan)).table(), i, i,
                        column.column()));
            } else if (level == Resolution.RAW) {
                parts.add("""
                        SELECT time AS t, %d AS s, value AS v, quality AS q FROM %stelemetry
                         WHERE organization_id = :org AND device_id = %d AND metric_key = :m%d AND time >= :from AND time < :to
                           AND quality = ANY(CAST(:q AS smallint[])) AND (:virtual OR NOT is_virtual)""".formatted(i, P, s.deviceId(), i));
            } else {
                AggFunction agg = AggFunction.parse(s.agg() == null ? "avg" : s.agg());
                String col = agg == AggFunction.COUNT ? "count" : agg.column();
                parts.add("""
                        SELECT bucket AS t, %d AS s, %s AS v, count AS q FROM %s
                         WHERE organization_id = :org AND device_id = %d AND metric_key = :m%d AND bucket > :lower AND bucket < :to
                           AND (:virtual OR NOT is_virtual)""".formatted(i, col, P + level.table(), s.deviceId(), i));
            }
        }
        if (parts.isEmpty()) {
            return "SELECT CAST(NULL AS timestamptz) AS t, 0 AS s, CAST(NULL AS double precision) AS v, 0 AS q WHERE false";
        }
        return String.join("\nUNION ALL\n", parts);
    }

    /** 공간 계열은 원본을 쓸 수 없어 원본 요청이면 1분 집계로 */
    private static String aggregateLevel(ExportPlan plan) {
        return plan.raw() ? "1m" : plan.resolution();
    }

    static Resolution level(String key) {
        Resolution r = Resolution.parse(key);
        return r == null ? Resolution.RAW : r;
    }

    /** 공간 경로 이름(/1/4/9/ → 본관/3층/실습실) */
    public Map<Long, String> spacePathNames(long organizationId, List<Long> spaceIds) {
        Map<Long, String> out = new java.util.HashMap<>();
        if (spaceIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                        SELECT s.id, (SELECT string_agg(p.name, '/' ORDER BY p.depth) FROM data2flow_core.spaces p
                                       WHERE p.organization_id = s.organization_id
                                         AND p.id = ANY(CAST(string_to_array(trim(both '/' from s.path), '/') AS bigint[]))) AS names
                          FROM data2flow_core.spaces s WHERE s.organization_id = :org AND s.id = ANY(CAST(:ids AS bigint[]))""",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("ids", Pg.bigintArray(spaceIds)),
                rs -> {
                    out.put(rs.getLong("id"), rs.getString("names"));
                });
        return out;
    }

    /** 표시 온도 단위(DEV-04.04): 사용자 설정 → 조직 단위 체계(IMPERIAL이면 F) → C */
    public String findTemperatureUnit(long organizationId, long userId) {
        List<String> units = jdbc.query("""
                        SELECT COALESCE((SELECT p.temperature_unit FROM data2flow_core.user_dashboard_prefs p
                                          WHERE p.organization_id = :org AND p.user_id = :user),
                                        (SELECT CASE WHEN s.unit_system = 'IMPERIAL' THEN 'F' ELSE 'C' END FROM data2flow_core.org_settings s
                                          WHERE s.organization_id = :org), 'C') AS unit""",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("user", userId), (rs, n) -> rs.getString("unit"));
        return units.isEmpty() ? "C" : units.getFirst();
    }
}
