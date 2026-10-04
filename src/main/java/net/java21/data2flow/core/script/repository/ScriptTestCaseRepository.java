package net.java21.data2flow.core.script.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 스크립트 테스트 케이스({@code script_test_cases}, SCR-03.03, API-SCR-10·11) */
@Repository
public class ScriptTestCaseRepository {

    private static final String COLUMNS = """
            id, script_id, name, input::text AS input, context::text AS context, expected::text AS expected, compare_mode,
            compare_fields, tolerance, last_result::text AS last_result, updated_at""";

    private final JdbcClient jdbc;

    public ScriptTestCaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<TestCaseRow> listByScript(long organizationId, long scriptId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.script_test_cases WHERE organization_id = :org AND script_id = :script"
                        + " ORDER BY id")
                .param("org", organizationId).param("script", scriptId).query(ScriptTestCaseRepository::map).list();
    }

    public Optional<TestCaseRow> findById(long organizationId, long scriptId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.script_test_cases"
                        + " WHERE organization_id = :org AND script_id = :script AND id = :id")
                .param("org", organizationId).param("script", scriptId).param("id", id).query(ScriptTestCaseRepository::map).optional();
    }

    public long countByScript(long organizationId, long scriptId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.script_test_cases WHERE organization_id = :org AND script_id = :script")
                .param("org", organizationId).param("script", scriptId).query(Long.class).single();
    }

    public boolean existsName(long organizationId, long scriptId, String name, Long excludeId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM data2flow_core.script_test_cases
                                        WHERE organization_id = :org AND script_id = :script AND name = :name
                                          AND (CAST(:exclude AS bigint) IS NULL OR id <> :exclude))""")
                .param("org", organizationId).param("script", scriptId).param("name", name).param("exclude", excludeId)
                .query(Boolean.class).single();
    }

    public long insert(long organizationId, long scriptId, TestCaseFields f, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.script_test_cases (organization_id, script_id, name, input, context, expected, compare_mode,
                                                                      compare_fields, tolerance, created_at, updated_at)
                        VALUES (:org, :script, :name, CAST(:input AS jsonb), CAST(:context AS jsonb), CAST(:expected AS jsonb), :mode,
                                CAST(:fields AS text[]), :tolerance, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("script", scriptId).param("name", f.name()).param("input", f.input())
                .param("context", f.context()).param("expected", f.expected()).param("mode", f.compareMode())
                .param("fields", f.compareFields() == null ? null : Pg.textArray(f.compareFields())).param("tolerance", f.tolerance())
                .param("now", Pg.ts(now)).query(Long.class).single();
    }

    /** 내용을 바꾸면 지난 실행 결과는 지운다 */
    public int update(long organizationId, long scriptId, long id, TestCaseFields f, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.script_test_cases
                           SET name = :name, input = CAST(:input AS jsonb), context = CAST(:context AS jsonb), expected = CAST(:expected AS jsonb),
                               compare_mode = :mode, compare_fields = CAST(:fields AS text[]), tolerance = :tolerance, last_result = NULL,
                               updated_at = :now
                         WHERE organization_id = :org AND script_id = :script AND id = :id""")
                .param("org", organizationId).param("script", scriptId).param("id", id).param("name", f.name()).param("input", f.input())
                .param("context", f.context()).param("expected", f.expected()).param("mode", f.compareMode())
                .param("fields", f.compareFields() == null ? null : Pg.textArray(f.compareFields())).param("tolerance", f.tolerance())
                .param("now", Pg.ts(now)).update();
    }

    public int updateLastResult(long organizationId, long id, String lastResultJson) {
        return jdbc.sql("UPDATE data2flow_core.script_test_cases SET last_result = CAST(:r AS jsonb) WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).param("r", lastResultJson).update();
    }

    public int delete(long organizationId, long scriptId, long id) {
        return jdbc.sql("DELETE FROM data2flow_core.script_test_cases WHERE organization_id = :org AND script_id = :script AND id = :id")
                .param("org", organizationId).param("script", scriptId).param("id", id).update();
    }

    static TestCaseRow map(ResultSet rs, int n) throws SQLException {
        double tolerance = rs.getDouble("tolerance");
        Double toleranceOrNull = rs.wasNull() ? null : tolerance;
        return new TestCaseRow(rs.getLong("id"), rs.getLong("script_id"), rs.getString("name"), rs.getString("input"),
                rs.getString("context"), rs.getString("expected"), rs.getString("compare_mode"), Pg.stringList(rs, "compare_fields"),
                toleranceOrNull, rs.getString("last_result"), Pg.instant(rs, "updated_at"));
    }

    /** 저장할 값(JSON은 문자열) */
    public record TestCaseFields(String name, String input, String context, String expected, String compareMode,
                                 List<String> compareFields, Double tolerance) {
    }

    public record TestCaseRow(long id, long scriptId, String name, String input, String context, String expected, String compareMode,
                              List<String> compareFields, Double tolerance, String lastResult, Instant updatedAt) {
    }
}
