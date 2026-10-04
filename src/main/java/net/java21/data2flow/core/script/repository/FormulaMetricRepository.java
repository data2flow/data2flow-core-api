package net.java21.data2flow.core.script.repository;

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
 * 수식 파생 항목({@code formula_metrics}, SCR-01.06, API-SCR-20). 결과 키는 측정 항목({@code metrics})으로도 등록한다(semantic=DERIVED).
 * 대상 이름을 함께 읽는다(MODEL·DEVICE·SPACE는 ID로 저장 — pipeline이 숫자 ID로 맞춘다).
 */
@Repository
public class FormulaMetricRepository {

    private static final String SELECT = """
            SELECT f.id, f.organization_id, f.result_key, f.display_name, f.unit, f.expression, f.compiled_js, f.target_type, f.target_id,
                   f.status, f.version, f.updated_at,
                   CASE f.target_type
                        WHEN 'MODEL' THEN (SELECT m.name FROM data2flow_core.device_models m
                                            WHERE m.organization_id = f.organization_id AND m.id::text = f.target_id)
                        WHEN 'DEVICE' THEN (SELECT d.name FROM data2flow_core.devices d
                                             WHERE d.organization_id = f.organization_id AND d.id::text = f.target_id)
                        ELSE (SELECT s.name FROM data2flow_core.spaces s
                               WHERE s.organization_id = f.organization_id AND s.id::text = f.target_id) END AS target_name
              FROM data2flow_core.formula_metrics f""";

    private final JdbcClient jdbc;

    public FormulaMetricRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FormulaRow> list(long organizationId, String targetType, String targetId, int limit, long offset) {
        return jdbc.sql(SELECT + where() + " ORDER BY f.result_key LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("type", targetType).param("target", targetId).param("limit", limit)
                .param("offset", offset).query(FormulaMetricRepository::map).list();
    }

    public long count(long organizationId, String targetType, String targetId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.formula_metrics f" + where())
                .param("org", organizationId).param("type", targetType).param("target", targetId).query(Long.class).single();
    }

    public long countByOrganization(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.formula_metrics WHERE organization_id = :org")
                .param("org", organizationId).query(Long.class).single();
    }

    public Optional<FormulaRow> findById(long organizationId, long id) {
        return jdbc.sql(SELECT + " WHERE f.organization_id = :org AND f.id = :id")
                .param("org", organizationId).param("id", id).query(FormulaMetricRepository::map).optional();
    }

    public boolean existsResultKey(long organizationId, String key, Long excludeId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.formula_metrics
                                        WHERE organization_id = :org AND result_key = :key
                                          AND (CAST(:exclude AS bigint) IS NULL OR id <> :exclude))""")
                .param("org", organizationId).param("key", key).param("exclude", excludeId).query(Boolean.class).single();
    }

    public long insert(long organizationId, FormulaFields f, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.formula_metrics (organization_id, result_key, display_name, unit, expression, compiled_js,
                                                                    target_type, target_id, status, created_by, updated_by, created_at, updated_at)
                        VALUES (:org, :key, :name, :unit, :expression, :js, :type, :target, :status, :user, :user, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("key", f.resultKey()).param("name", f.displayName()).param("unit", f.unit())
                .param("expression", f.expression()).param("js", f.compiledJs()).param("type", f.targetType())
                .param("target", f.targetId()).param("status", f.status()).param("user", userId).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    /** 낙관적 잠금: 기준 버전이 맞을 때만 바꾸고 새 버전을 돌려준다(0행이면 빈 값) */
    public Optional<Integer> update(long organizationId, long id, int baseVersion, FormulaFields f, long userId, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.formula_metrics
                           SET result_key = :key, display_name = :name, unit = :unit, expression = :expression, compiled_js = :js,
                               target_type = :type, target_id = :target, status = :status, version = version + 1, updated_by = :user,
                               updated_at = :now
                         WHERE organization_id = :org AND id = :id AND version = :base
                        RETURNING version""")
                .param("org", organizationId).param("id", id).param("base", baseVersion).param("key", f.resultKey())
                .param("name", f.displayName()).param("unit", f.unit()).param("expression", f.expression()).param("js", f.compiledJs())
                .param("type", f.targetType()).param("target", f.targetId()).param("status", f.status()).param("user", userId)
                .param("now", Pg.ts(now)).query(Integer.class).optional();
    }

    public int delete(long organizationId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.formula_metrics WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).update();
    }

    /** 측정 항목 키가 있는가. 수식이 만든 항목(semantic=DERIVED)이면 derived=true */
    public Optional<Boolean> findMetricDerived(long organizationId, String key) {
        return jdbc.sql("SELECT semantic = 'DERIVED' FROM data2flow_core.metrics WHERE organization_id = :org AND key = :key")
                .param("org", organizationId).param("key", key).query((rs, n) -> rs.getBoolean(1)).optional();
    }

    /** 결과 키를 파생 측정 항목으로 등록(없을 때만). 새로 만든 ID, 이미 있으면 빈 값 */
    public Optional<Long> insertDerivedMetric(long organizationId, String key, String displayName, String unit, long userId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.metrics (organization_id, key, display_name, unit, semantic, status, created_by, updated_by,
                                                            created_at, updated_at)
                        VALUES (:org, :key, :name, :unit, 'DERIVED', 'VERIFIED', :user, :user, :now, :now)
                        ON CONFLICT (organization_id, key) DO NOTHING
                        RETURNING id""")
                .param("org", organizationId).param("key", key).param("name", displayName.length() > 50 ? displayName.substring(0, 50)
                        : displayName).param("unit", unit).param("user", userId).param("now", Pg.ts(now)).query(Long.class).optional();
    }

    /** 대상이 조직에 있는가(MODEL·DEVICE·SPACE, ID) */
    public boolean existsTarget(long organizationId, String targetType, long targetId) {
        String table = switch (targetType) {
            case "MODEL" -> "device_models";
            case "DEVICE" -> "devices";
            default -> "spaces";
        };
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM data2flow_core." + table + " WHERE organization_id = :org AND id = :id)")
                .param("org", organizationId).param("id", targetId).query(Boolean.class).single();
    }

    /** 모델 코드 → ID */
    public Optional<Long> findModelIdByCode(long organizationId, String code) {
        return jdbc.sql("SELECT id FROM data2flow_core.device_models WHERE organization_id = :org AND code = :code")
                .param("org", organizationId).param("code", code).query(Long.class).optional();
    }

    /** 미리 보기용 대표 기기: DEVICE면 그 기기, MODEL·SPACE면 그 모델·공간(하위 포함)의 최근 수신 기기 */
    public Optional<Long> findSampleDevice(long organizationId, String targetType, long targetId) {
        String condition = switch (targetType) {
            case "DEVICE" -> "d.id = :id";
            case "MODEL" -> "d.model_id = :id";
            default -> """
                    d.space_id IN (SELECT s.id FROM data2flow_core.spaces s
                                    WHERE s.organization_id = :org AND (s.id = :id OR s.path LIKE (SELECT p.path || '%'
                                          FROM data2flow_core.spaces p WHERE p.organization_id = :org AND p.id = :id)))""";
        };
        return jdbc.sql("""
                        SELECT d.id FROM data2flow_core.devices d
                          LEFT JOIN data2flow_pipeline.device_state st ON st.device_id = d.id
                         WHERE d.organization_id = :org AND\s""" + condition + " ORDER BY st.last_seen_at DESC NULLS LAST, d.id LIMIT 1")
                .param("org", organizationId).param("id", targetId).query(Long.class).optional();
    }

    /** 실행 묶음(API-SCR-32 formulaMetrics) */
    @OrganizationScopeExempt("조직 전체 실행 묶음(API-SCR-32). 배포 조직(restriction)으로 좁힌다")
    public List<FormulaRow> listForBundle(Long organizationId, Long restriction) {
        return jdbc.sql(SELECT + " " + """
                         WHERE (CAST(:org AS bigint) IS NULL OR f.organization_id = :org)
                           AND (CAST(:restriction AS bigint) IS NULL OR f.organization_id = :restriction)
                         ORDER BY f.organization_id, f.id""")
                .param("org", organizationId).param("restriction", restriction).query(FormulaMetricRepository::map).list();
    }

    private static String where() {
        return " " + """
                 WHERE f.organization_id = :org AND (CAST(:type AS text) IS NULL OR f.target_type = :type)
                   AND (CAST(:target AS text) IS NULL OR f.target_id = :target)""";
    }

    static FormulaRow map(ResultSet rs, int n) throws SQLException {
        return new FormulaRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("result_key"), rs.getString("display_name"),
                rs.getString("unit"), rs.getString("expression"), rs.getString("compiled_js"), rs.getString("target_type"),
                rs.getString("target_id"), rs.getString("target_name"), rs.getString("status"), rs.getInt("version"),
                Pg.instant(rs, "updated_at"));
    }

    public record FormulaFields(String resultKey, String displayName, String unit, String expression, String compiledJs, String targetType,
                                String targetId, String status) {
    }

    public record FormulaRow(long id, long organizationId, String resultKey, String displayName, String unit, String expression,
                             String compiledJs, String targetType, String targetId, String targetName, String status, int version,
                             Instant updatedAt) {
    }
}
