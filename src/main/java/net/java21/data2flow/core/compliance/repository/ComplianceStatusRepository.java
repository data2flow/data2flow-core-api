package net.java21.data2flow.core.compliance.repository;

import net.java21.data2flow.core.common.Pg;
import net.java21.data2flow.core.compliance.domain.DailyComplianceEvaluator.DayStats;
import net.java21.data2flow.core.compliance.repository.ComplianceApplicationRepository.SpaceRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 점수·준수 현황 저장소(CMP-01.05·02.01): 일 집계({@code compliance_daily}), 이탈 구간, 연속 달성, 점수({@code ieq_scores}), 조직 가중치.
 * 측정값은 pipeline이 만든 1분 집계({@code data2flow_pipeline.telemetry_1m})를 읽기만 한다. 공간 평균은 그 공간에 놓인 실제 기기(가상 제외,
 * BR-CMP-06)의 정상 품질 값만 쓴 가중 평균(Σ avg·count / Σ count)이다.
 */
@Repository
public class ComplianceStatusRepository {

    private final JdbcClient jdbc;

    public ComplianceStatusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record DailyRow(long spaceId, long profileId, String profileName, long profileVersionId, int versionNo, String metricKey,
                           LocalDate day, int metMinutes, int exceededMinutes, int missingMinutes, BigDecimal dailyRatio, boolean qualityWarning) {
    }

    public record ExcursionRow(long id, long spaceId, long profileVersionId, String metricKey, Instant startedAt, Instant endedAt,
                               BigDecimal peakValue, Long alarmId) {
    }

    public record StreakRow(long spaceId, long profileId, int currentDays, LocalDate startedOn, LocalDate brokenOn, String brokenMetric,
                            LocalDate evaluatedOn) {
    }

    public record ScoreRow(long spaceId, Instant time, Integer score) {
    }

    public record HistoryPoint(Instant time, Double score, String topPenaltyMetric) {
    }

    public record WeightsRow(String weights, int version, long updatedBy, String updatedByName, Instant updatedAt) {
    }

    /** 조직 시간대(일 집계·1d 점수 이력의 날 경계) */
    public String timezone(long orgId) {
        return jdbc.sql("SELECT timezone FROM data2flow_core.organizations WHERE id = :org").param("org", orgId).query(String.class)
                .optional().orElse("UTC");
    }

    /** 점수를 낼 공간: ACTIVE 실제 기기가 놓인 ACTIVE 공간 */
    public List<SpaceRow> scoredSpaces(long orgId) {
        return jdbc.sql("""
                        SELECT s.id, s.name, s.path, s.type, s.usage FROM data2flow_core.spaces s
                         WHERE s.organization_id = :org AND s.status = 'ACTIVE'
                           AND EXISTS (SELECT 1 FROM data2flow_core.devices d
                                        WHERE d.space_id = s.id AND d.status = 'ACTIVE' AND NOT d.is_virtual)
                         ORDER BY s.id""")
                .param("org", orgId).query(ComplianceApplicationRepository::space).list();
    }

    /** 공간과 그 하위 공간(ACTIVE) */
    public List<Long> subtreeIds(long orgId, String path) {
        return jdbc.sql("SELECT id FROM data2flow_core.spaces WHERE organization_id = :org AND status = 'ACTIVE' AND path LIKE :path || '%'")
                .param("org", orgId).param("path", path).query(Long.class).list();
    }

    public Map<Long, SpaceRow> spaces(long orgId, Collection<Long> ids) {
        Map<Long, SpaceRow> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT id, name, path, type, usage FROM data2flow_core.spaces WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))")
                .param("org", orgId).param("ids", Pg.bigintArray(ids))
                .query(ComplianceApplicationRepository::space).list().forEach(sp -> out.put(sp.id(), sp));
        return out;
    }

    /** 프로필 현재 버전의 연속 달성 요구 일수(없으면 키가 없다) */
    public Map<Long, Integer> requiredDays(Collection<Long> profileIds) {
        Map<Long, Integer> out = new HashMap<>();
        if (profileIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT p.id, v.streak_days FROM data2flow_core.compliance_profiles p
                          JOIN data2flow_core.compliance_profile_versions v ON v.profile_id = p.id AND v.version_no = p.current_version
                         WHERE p.id = ANY(CAST(:ids AS bigint[])) AND v.streak_days IS NOT NULL""")
                .param("ids", Pg.bigintArray(profileIds)).query((rs, n) -> out.put(rs.getLong("id"), rs.getInt("streak_days"))).list();
        return out;
    }

    /** 항목별 분 단위 공간 평균 [from, to) */
    public Map<String, NavigableMap<Instant, Double>> minuteSeries(long orgId, long spaceId, Collection<String> metricKeys, Instant from,
                                                                  Instant to) {
        Map<String, NavigableMap<Instant, Double>> out = new HashMap<>();
        if (metricKeys.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT t.metric_key, t.bucket, sum(t.avg * t.count) / sum(t.count) AS v
                          FROM data2flow_pipeline.telemetry_1m t
                          JOIN data2flow_core.devices d ON d.id = t.device_id
                         WHERE t.organization_id = :org AND d.organization_id = :org AND d.space_id = :space AND NOT d.is_virtual
                           AND NOT t.is_virtual AND t.count > 0 AND t.avg IS NOT NULL
                           AND t.metric_key = ANY(CAST(:keys AS text[])) AND t.bucket >= :from AND t.bucket < :to
                         GROUP BY t.metric_key, t.bucket""")
                .param("org", orgId).param("space", spaceId).param("keys", Pg.textArray(metricKeys)).param("from", Pg.ts(from))
                .param("to", Pg.ts(to))
                .query((rs, n) -> out.computeIfAbsent(rs.getString("metric_key"), k -> new TreeMap<>())
                        .put(Pg.instant(rs, "bucket"), rs.getDouble("v"))).list();
        return out;
    }

    /**
     * 점수 입력(BR-CMP-08): 항목별 직전 5분 평균. 5분 안에 값이 없고 10분 안에만 있으면 10분 평균을 쓰고, 10분 안에도 없는 항목은 빠진다
     */
    public Map<String, Double> recentAverages(long orgId, long spaceId, Collection<String> metricKeys, Instant time) {
        Map<String, Double> out = new HashMap<>();
        if (metricKeys.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT t.metric_key,
                               sum(t.avg * t.count) FILTER (WHERE t.bucket >= :five)
                                   / nullif(sum(t.count) FILTER (WHERE t.bucket >= :five), 0) AS avg5,
                               sum(t.avg * t.count) / nullif(sum(t.count), 0) AS avg10
                          FROM data2flow_pipeline.telemetry_1m t
                          JOIN data2flow_core.devices d ON d.id = t.device_id
                         WHERE t.organization_id = :org AND d.organization_id = :org AND d.space_id = :space AND NOT d.is_virtual
                           AND NOT t.is_virtual AND t.count > 0 AND t.avg IS NOT NULL
                           AND t.metric_key = ANY(CAST(:keys AS text[])) AND t.bucket >= :ten AND t.bucket < :time
                         GROUP BY t.metric_key""")
                .param("org", orgId).param("space", spaceId).param("keys", Pg.textArray(metricKeys))
                .param("five", Pg.ts(time.minusSeconds(300))).param("ten", Pg.ts(time.minusSeconds(600))).param("time", Pg.ts(time))
                .query((rs, n) -> {
                    Double avg5 = rs.getObject("avg5", Double.class);
                    Double avg10 = rs.getObject("avg10", Double.class);
                    Double v = avg5 != null ? avg5 : avg10;
                    if (v != null) {
                        out.put(rs.getString("metric_key"), v);
                    }
                    return v;
                }).list();
        return out;
    }

    public void upsertDaily(long orgId, long spaceId, long versionId, String metricKey, DayStats d, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.compliance_daily (space_id, profile_version_id, metric_key, day, organization_id, met_minutes,
                               exceeded_minutes, missing_minutes, daily_ratio, max_value, avg_value, quality_warning, computed_at)
                        VALUES (:space, :version, :metric, :day, :org, :met, :exceeded, :missing, :ratio, :max, :avg, :warning, :now)
                        ON CONFLICT (space_id, profile_version_id, metric_key, day) DO UPDATE
                           SET met_minutes = EXCLUDED.met_minutes, exceeded_minutes = EXCLUDED.exceeded_minutes,
                               missing_minutes = EXCLUDED.missing_minutes, daily_ratio = EXCLUDED.daily_ratio, max_value = EXCLUDED.max_value,
                               avg_value = EXCLUDED.avg_value, quality_warning = EXCLUDED.quality_warning, computed_at = EXCLUDED.computed_at""")
                .param("space", spaceId).param("version", versionId).param("metric", metricKey).param("day", d.day()).param("org", orgId)
                .param("met", d.metMinutes()).param("exceeded", d.exceededMinutes()).param("missing", d.missingMinutes())
                .param("ratio", d.dailyRatio()).param("max", d.maxValue()).param("avg", d.avgValue()).param("warning", d.qualityWarning())
                .param("now", Pg.ts(now)).update();
    }

    /** 기간 [from, to]의 일 집계(프로필·버전 이름 포함). spaceIds가 null이면 조직 전체 */
    public List<DailyRow> daily(long orgId, LocalDate from, LocalDate to, Collection<Long> spaceIds, Long profileId) {
        return jdbc.sql("""
                        SELECT c.space_id, v.profile_id, p.name AS profile_name, c.profile_version_id, v.version_no, c.metric_key, c.day,
                               c.met_minutes, c.exceeded_minutes, c.missing_minutes, c.daily_ratio, c.quality_warning
                          FROM data2flow_core.compliance_daily c
                          JOIN data2flow_core.compliance_profile_versions v ON v.id = c.profile_version_id
                          JOIN data2flow_core.compliance_profiles p ON p.id = v.profile_id
                         WHERE c.organization_id = :org AND c.day BETWEEN :from AND :to
                           AND (CAST(:spaces AS bigint[]) IS NULL OR c.space_id = ANY(CAST(:spaces AS bigint[])))
                           AND (CAST(:profile AS bigint) IS NULL OR v.profile_id = :profile)
                         ORDER BY c.space_id, v.profile_id, c.day, c.metric_key""")
                .param("org", orgId).param("from", from).param("to", to).param("spaces", spaceIds == null ? null : Pg.bigintArray(spaceIds))
                .param("profile", profileId)
                .query((rs, n) -> new DailyRow(rs.getLong("space_id"), rs.getLong("profile_id"), rs.getString("profile_name"),
                        rs.getLong("profile_version_id"), rs.getInt("version_no"), rs.getString("metric_key"),
                        rs.getObject("day", LocalDate.class), rs.getInt("met_minutes"), rs.getInt("exceeded_minutes"),
                        rs.getInt("missing_minutes"), rs.getBigDecimal("daily_ratio"), rs.getBoolean("quality_warning"))).list();
    }

    public Optional<ExcursionRow> openExcursion(long spaceId, long versionId, String metricKey) {
        return jdbc.sql(EXCURSION + " WHERE space_id = :space AND profile_version_id = :version AND metric_key = :metric AND ended_at IS NULL")
                .param("space", spaceId).param("version", versionId).param("metric", metricKey).query(ComplianceStatusRepository::excursion)
                .optional();
    }

    public Optional<ExcursionRow> excursionStartedAt(long spaceId, long versionId, String metricKey, Instant startedAt) {
        return jdbc.sql(EXCURSION + " WHERE space_id = :space AND profile_version_id = :version AND metric_key = :metric AND started_at = :start")
                .param("space", spaceId).param("version", versionId).param("metric", metricKey).param("start", Pg.ts(startedAt))
                .query(ComplianceStatusRepository::excursion).optional();
    }

    public long insertExcursion(long orgId, long spaceId, long versionId, String metricKey, Instant startedAt, Instant endedAt, BigDecimal peak,
                                Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.compliance_excursions (organization_id, space_id, profile_version_id, metric_key, started_at,
                               ended_at, peak_value, created_at, updated_at)
                        VALUES (:org, :space, :version, :metric, :start, :end, :peak, :now, :now) RETURNING id""")
                .param("org", orgId).param("space", spaceId).param("version", versionId).param("metric", metricKey)
                .param("start", Pg.ts(startedAt)).param("end", endedAt == null ? null : Pg.ts(endedAt)).param("peak", peak)
                .param("now", Pg.ts(now)).query(Long.class).single();
    }

    public void updateExcursion(long id, Instant endedAt, BigDecimal peak, Instant now) {
        jdbc.sql("UPDATE data2flow_core.compliance_excursions SET ended_at = :end, peak_value = :peak, updated_at = :now WHERE id = :id")
                .param("id", id).param("end", endedAt == null ? null : Pg.ts(endedAt)).param("peak", peak).param("now", Pg.ts(now)).update();
    }

    /** 공간의 이탈 구간 중 [from, to)와 겹치는 것(최근부터) */
    public List<ExcursionRow> excursions(long orgId, long spaceId, Long profileId, Instant from, Instant to) {
        return jdbc.sql(EXCURSION + """
                         WHERE organization_id = :org AND space_id = :space AND started_at < :to AND (ended_at IS NULL OR ended_at > :from)
                           AND (CAST(:profile AS bigint) IS NULL OR profile_version_id IN
                                (SELECT id FROM data2flow_core.compliance_profile_versions WHERE profile_id = :profile))
                         ORDER BY started_at DESC, id DESC LIMIT 500""")
                .param("org", orgId).param("space", spaceId).param("profile", profileId).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                .query(ComplianceStatusRepository::excursion).list();
    }

    public Optional<StreakRow> streak(long spaceId, long profileId) {
        return jdbc.sql(STREAK + " WHERE space_id = :space AND profile_id = :profile").param("space", spaceId).param("profile", profileId)
                .query(ComplianceStatusRepository::streak).optional();
    }

    public List<StreakRow> streaks(long orgId) {
        return jdbc.sql(STREAK + " WHERE organization_id = :org").param("org", orgId).query(ComplianceStatusRepository::streak).list();
    }

    public void saveStreak(long orgId, StreakRow s, Instant now) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.compliance_streaks (space_id, profile_id, organization_id, current_days, started_on, broken_on,
                               broken_metric, evaluated_on, updated_at)
                        VALUES (:space, :profile, :org, :days, :started, :broken, :metric, :evaluated, :now)
                        ON CONFLICT (space_id, profile_id) DO UPDATE
                           SET current_days = EXCLUDED.current_days, started_on = EXCLUDED.started_on, broken_on = EXCLUDED.broken_on,
                               broken_metric = EXCLUDED.broken_metric, evaluated_on = EXCLUDED.evaluated_on, updated_at = EXCLUDED.updated_at""")
                .param("space", s.spaceId()).param("profile", s.profileId()).param("org", orgId).param("days", s.currentDays())
                .param("started", s.startedOn()).param("broken", s.brokenOn()).param("metric", s.brokenMetric())
                .param("evaluated", s.evaluatedOn()).param("now", Pg.ts(now)).update();
    }

    /** 점수 한 행. 같은 (공간, 시각)이 이미 있으면 넣지 않고 false(EVT-CMP-02를 한 번만 내게) */
    public boolean insertScore(long orgId, long spaceId, Instant time, Integer score, String indicesJson, String topPenaltyMetric, String basis,
                               Collection<String> excluded, Collection<Long> versionIds, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.ieq_scores (space_id, time, organization_id, score, indices, top_penalty_metric, basis,
                               excluded_metrics, profile_version_ids, computed_at)
                        VALUES (:space, :time, :org, :score, CAST(:indices AS jsonb), :top, :basis, CAST(:excluded AS varchar[]),
                               CAST(:versions AS bigint[]), :now)
                        ON CONFLICT (space_id, time) DO NOTHING""")
                .param("space", spaceId).param("time", Pg.ts(time)).param("org", orgId).param("score", score).param("indices", indicesJson)
                .param("top", topPenaltyMetric).param("basis", basis).param("excluded", Pg.textArray(excluded))
                .param("versions", Pg.bigintArray(versionIds)).param("now", Pg.ts(now)).update() > 0;
    }

    /** 공간별 가장 최근 점수(since 이후) */
    public Map<Long, ScoreRow> latestScores(long orgId, Instant since) {
        Map<Long, ScoreRow> out = new HashMap<>();
        jdbc.sql("""
                        SELECT DISTINCT ON (space_id) space_id, time, score FROM data2flow_core.ieq_scores
                         WHERE organization_id = :org AND time >= :since ORDER BY space_id, time DESC""")
                .param("org", orgId).param("since", Pg.ts(since))
                .query((rs, n) -> out.put(rs.getLong("space_id"), new ScoreRow(rs.getLong("space_id"), Pg.instant(rs, "time"),
                        (Integer) rs.getObject("score", Integer.class)))).list();
        return out;
    }

    /** 점수 이력. step 5m은 저장한 그대로, 1h·1d는 평균(1d는 조직 시간대 자정) */
    public List<HistoryPoint> history(long orgId, long spaceId, Instant from, Instant to, String step, String zone) {
        if ("5m".equals(step)) {
            return jdbc.sql("""
                            SELECT time, CAST(score AS float8) AS score, top_penalty_metric FROM data2flow_core.ieq_scores
                             WHERE organization_id = :org AND space_id = :space AND time >= :from AND time < :to ORDER BY time""")
                    .param("org", orgId).param("space", spaceId).param("from", Pg.ts(from)).param("to", Pg.ts(to))
                    .query((rs, n) -> new HistoryPoint(Pg.instant(rs, "time"), rs.getObject("score", Double.class),
                            rs.getString("top_penalty_metric"))).list();
        }
        String unit = "1d".equals(step) ? "day" : "hour";
        return jdbc.sql("""
                        SELECT date_trunc(:unit, time, :zone) AS bucket, CAST(avg(score) AS float8) AS score,
                               mode() WITHIN GROUP (ORDER BY top_penalty_metric) AS top
                          FROM data2flow_core.ieq_scores
                         WHERE organization_id = :org AND space_id = :space AND time >= :from AND time < :to
                         GROUP BY 1 ORDER BY 1""")
                .param("unit", unit).param("zone", zone).param("org", orgId).param("space", spaceId).param("from", Pg.ts(from))
                .param("to", Pg.ts(to))
                .query((rs, n) -> new HistoryPoint(Pg.instant(rs, "bucket"), rs.getObject("score", Double.class), rs.getString("top"))).list();
    }

    public Optional<WeightsRow> weights(long orgId) {
        return jdbc.sql("""
                        SELECT w.weights::text AS weights, w.version, w.updated_by, u.name AS updated_by_name, w.updated_at
                          FROM data2flow_core.compliance_score_weights w LEFT JOIN data2flow_core.app_users u ON u.id = w.updated_by
                         WHERE w.organization_id = :org""")
                .param("org", orgId).query((rs, n) -> new WeightsRow(rs.getString("weights"), rs.getInt("version"), rs.getLong("updated_by"),
                        rs.getString("updated_by_name"), Pg.instant(rs, "updated_at"))).optional();
    }

    /** 가중치 저장. baseVersion이 지금 버전과 다르면 0(충돌). 행이 없을 때는 baseVersion 0으로 만든다 */
    public int saveWeights(long orgId, String weightsJson, int baseVersion, long userId, Instant now) {
        String sql = baseVersion == 0 ? """
                INSERT INTO data2flow_core.compliance_score_weights (organization_id, weights, version, updated_by, updated_at)
                VALUES (:org, CAST(:weights AS jsonb), 1, :user, :now) ON CONFLICT (organization_id) DO NOTHING""" : """
                UPDATE data2flow_core.compliance_score_weights
                   SET weights = CAST(:weights AS jsonb), version = version + 1, updated_by = :user, updated_at = :now
                 WHERE organization_id = :org AND version = :base""";
        var spec = jdbc.sql(sql).param("org", orgId).param("weights", weightsJson).param("user", userId).param("now", Pg.ts(now));
        return (baseVersion == 0 ? spec : spec.param("base", baseVersion)).update();
    }

    private static final String EXCURSION = """
            SELECT id, space_id, profile_version_id, metric_key, started_at, ended_at, peak_value, alarm_id
              FROM data2flow_core.compliance_excursions
            """;

    private static final String STREAK = """
            SELECT space_id, profile_id, current_days, started_on, broken_on, broken_metric, evaluated_on FROM data2flow_core.compliance_streaks
            """;

    static ExcursionRow excursion(ResultSet rs, int n) throws SQLException {
        return new ExcursionRow(rs.getLong("id"), rs.getLong("space_id"), rs.getLong("profile_version_id"), rs.getString("metric_key"),
                Pg.instant(rs, "started_at"), Pg.instant(rs, "ended_at"), rs.getBigDecimal("peak_value"), Pg.longOrNull(rs, "alarm_id"));
    }

    static StreakRow streak(ResultSet rs, int n) throws SQLException {
        return new StreakRow(rs.getLong("space_id"), rs.getLong("profile_id"), rs.getInt("current_days"), rs.getObject("started_on", LocalDate.class),
                rs.getObject("broken_on", LocalDate.class), rs.getString("broken_metric"), rs.getObject("evaluated_on", LocalDate.class));
    }
}
