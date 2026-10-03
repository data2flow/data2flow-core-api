package net.java21.data2flow.core.ingest.repository;

import net.java21.data2flow.contracts.authz.SpaceScope;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 데이터 품질 점수({@code data_quality_daily}, API-ING-13)와 수신 공백({@code data_gaps}, API-ING-15). pipeline 소유라 읽기만 한다.
 * 공간 범위가 제한된 사용자는 범위 안 기기의 행만 본다(IAM-04.06).
 */
@Repository
public class DataQualityRepository {

    private final JdbcClient jdbc;

    public DataQualityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 묶음 기준(정해진 값만 SQL에 넣는다) */
    public enum GroupBy {
        DEVICE("d.id", "d.name"), SPACE("d.space_id", "sp.name"), MODEL("d.model_id", "m.name");

        private final String idExpr;
        private final String nameExpr;

        GroupBy(String idExpr, String nameExpr) {
            this.idExpr = idExpr;
            this.nameExpr = nameExpr;
        }
    }

    /** 품질 조회 조건. spacePath가 있으면 그 공간과 하위 공간의 기기만 */
    public record QualityFilter(long organizationId, LocalDate day, Instant dayStart, Instant dayEnd, GroupBy groupBy, String spacePath,
                                SpaceScope scope) {
    }

    private static final String FROM = """
             FROM data2flow_pipeline.data_quality_daily q
             JOIN data2flow_core.devices d ON d.id = q.device_id AND d.organization_id = q.organization_id
             LEFT JOIN data2flow_core.spaces sp ON sp.id = d.space_id
             LEFT JOIN data2flow_core.device_models m ON m.id = d.model_id
             LEFT JOIN data2flow_pipeline.device_state ds ON ds.device_id = q.device_id
             LEFT JOIN LATERAL (SELECT count(*) AS n FROM data2flow_pipeline.data_gaps g
                                 WHERE g.organization_id = q.organization_id AND g.device_id = q.device_id
                                   AND g.gap_start < :dayEnd AND g.gap_end > :dayStart) gp ON true
             WHERE q.organization_id = :org AND q.day = :day
               AND (:path = '' OR sp.path LIKE :path || '%')
               AND (:unrestricted OR d.space_id = ANY(CAST(:allowed AS bigint[])))
            """;

    public List<QualityRow> findPage(QualityFilter filter, long offset, int limit) {
        GroupBy g = filter.groupBy();
        return bind(jdbc.sql("""
                        SELECT %s AS target_id, %s AS target_name,
                               round(avg(q.score)) AS score, round(avg(q.completeness)) AS completeness, round(avg(q.timeliness)) AS timeliness,
                               round(avg(q.validity)) AS validity, round(avg(q.stability)) AS stability,
                               sum(gp.n) AS gaps, bool_or(abs(coalesce(ds.clock_skew_avg_sec, 0)) > 300) AS skew,
                               sum(q.expected_count) AS expected, sum(q.received_count) AS received, sum(q.late_count) AS late,
                               sum(q.out_of_range_count) AS out_of_range, sum(q.suspect_count) AS suspect
                        """.formatted(g.idExpr, g.nameExpr) + FROM + """
                         GROUP BY 1, 2 ORDER BY score, target_id OFFSET :offset LIMIT :limit"""), filter)
                .param("offset", offset).param("limit", limit)
                .query((rs, n) -> new QualityRow(Pg.longOrNull(rs, "target_id"), rs.getString("target_name"), rs.getInt("score"),
                        rs.getInt("completeness"), rs.getInt("timeliness"), rs.getInt("validity"), rs.getInt("stability"), rs.getLong("gaps"),
                        rs.getBoolean("skew"), rs.getLong("expected"), rs.getLong("received"), rs.getLong("late"), rs.getLong("out_of_range"),
                        rs.getLong("suspect")))
                .list();
    }

    public long count(QualityFilter filter) {
        GroupBy g = filter.groupBy();
        return bind(jdbc.sql("SELECT count(*) FROM (SELECT %s, %s ".formatted(g.idExpr, g.nameExpr) + FROM + " GROUP BY 1, 2) x"), filter)
                .query(Long.class).single();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, QualityFilter f) {
        return spec.param("org", f.organizationId()).param("day", f.day()).param("dayStart", Pg.ts(f.dayStart()))
                .param("dayEnd", Pg.ts(f.dayEnd())).param("path", f.spacePath() == null ? "" : f.spacePath())
                .param("unrestricted", f.scope().unrestricted()).param("allowed", Pg.bigintArray(f.scope().allowedSpaceIds()));
    }

    /** 수신 공백 조건(deviceId 0이면 전체) */
    public record GapFilter(long organizationId, long deviceId, Instant from, Instant to, SpaceScope scope) {
    }

    private static final String GAP_FROM = """
             FROM data2flow_pipeline.data_gaps g
             JOIN data2flow_core.devices d ON d.id = g.device_id AND d.organization_id = g.organization_id
             WHERE g.organization_id = :org AND g.gap_start < :to AND g.gap_end > :from AND (:device = 0 OR g.device_id = :device)
               AND (:unrestricted OR d.space_id = ANY(CAST(:allowed AS bigint[])))
            """;

    public List<GapRow> findGaps(GapFilter filter, long offset, int limit) {
        return bindGap(jdbc.sql("SELECT g.device_id, d.name, g.gap_start, g.gap_end, g.expected_count" + GAP_FROM
                        + " ORDER BY g.gap_start DESC, g.id DESC OFFSET :offset LIMIT :limit"), filter)
                .param("offset", offset).param("limit", limit)
                .query((rs, n) -> new GapRow(rs.getLong("device_id"), rs.getString("name"), Pg.instant(rs, "gap_start"),
                        Pg.instant(rs, "gap_end"), rs.getLong("expected_count")))
                .list();
    }

    public long countGaps(GapFilter filter) {
        return bindGap(jdbc.sql("SELECT count(*)" + GAP_FROM), filter).query(Long.class).single();
    }

    private static JdbcClient.StatementSpec bindGap(JdbcClient.StatementSpec spec, GapFilter f) {
        return spec.param("org", f.organizationId()).param("from", Pg.ts(f.from())).param("to", Pg.ts(f.to())).param("device", f.deviceId())
                .param("unrestricted", f.scope().unrestricted()).param("allowed", Pg.bigintArray(f.scope().allowedSpaceIds()));
    }

    /** 품질 한 묶음 */
    public record QualityRow(Long targetId, String targetName, int score, int completeness, int timeliness, int validity, int stability,
                             long gaps, boolean clockSkewSuspect, long expected, long received, long late, long outOfRange, long suspect) {
    }

    /** 공백 한 건 */
    public record GapRow(long deviceId, String deviceName, Instant from, Instant to, long expectedCount) {
    }
}
