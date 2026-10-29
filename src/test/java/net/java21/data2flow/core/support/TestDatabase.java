package net.java21.data2flow.core.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

/**
 * 통합 테스트 DB 도우미.
 * <ul>
 *   <li>{@link #createPipelineSchema}: data2flow-pipeline이 소유하는 {@code data2flow_pipeline} 스키마와 data2flow-action의
 *       {@code data2flow_action} 명령·기기 상태 표를 문서 DDL로 한 번 만든다(core-api는 읽기만 한다, conventions §6)</li>
 *   <li>{@link #truncateAll}: 테스트마다 업무 테이블을 비운다. 마이그레이션이 넣은 시스템 공용 행(커넥터 카탈로그 등)과
 *       INSERT 전용 감사 로그, Flyway 이력은 남긴다. ID는 이어서 매긴다(감사 로그가 이전 테스트의 조직 ID와 섞이지 않게)</li>
 * </ul>
 */
public final class TestDatabase {

    /**
     * 비우지 않는 테이블: Flyway 이력, 감사 로그(INSERT 전용), 마이그레이션 시드(시스템 공용 카탈로그·템플릿·기준 프로필·기본 배출계수).
     * 기준 프로필·배출계수 표는 시스템 행(organization_id 0)만 남기고 지운다({@link #truncateAll})
     */
    static final Set<String> KEEP = Set.of("flyway_schema_history", "audit_logs", "audit_logs_default",
            "connector_catalogs", "connector_templates", "rule_templates", "notification_templates",
            "compliance_profiles", "compliance_profile_versions", "profile_metric_rules", "profile_grade_bands", "profile_coverage_rules",
            "energy_emission_factors");

    private static volatile String truncateSql;

    private TestDatabase() {
    }

    public static void createPipelineSchema(PostgreSQLContainer postgres) {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement()) {
            boolean exists;
            try (var rs = st.executeQuery("SELECT to_regclass('data2flow_pipeline.telemetry') IS NOT NULL")) {
                rs.next();
                exists = rs.getBoolean(1);
            }
            if (!exists) {
                st.execute(new ClassPathResource("db/pipeline-schema.sql").getContentAsString(StandardCharsets.UTF_8));
            }
            boolean actionExists;
            try (var rs = st.executeQuery("SELECT to_regclass('data2flow_action.commands') IS NOT NULL")) {
                rs.next();
                actionExists = rs.getBoolean(1);
            }
            if (!actionExists) {
                st.execute(new ClassPathResource("db/action-schema.sql").getContentAsString(StandardCharsets.UTF_8));
            }
        } catch (SQLException | IOException ex) {
            throw new IllegalStateException("data2flow_pipeline 테스트 스키마를 만들지 못했습니다", ex);
        }
    }

    public static void truncateAll(JdbcClient jdbc) {
        if (truncateSql == null) {
            List<String> tables = jdbc.sql("""
                            SELECT n.nspname || '.' || c.relname
                              FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                             WHERE n.nspname IN ('data2flow_core', 'data2flow_pipeline', 'data2flow_action')
                               AND c.relkind IN ('r', 'p') AND NOT c.relispartition
                             ORDER BY 1""")
                    .query(String.class).list().stream()
                    .filter(t -> !KEEP.contains(t.substring(t.indexOf('.') + 1)) && !t.contains(".audit_logs_y"))
                    .toList();
            truncateSql = "TRUNCATE " + String.join(", ", tables) + " CASCADE";
        }
        jdbc.sql(truncateSql).update();
        // 조직 프로필은 버전·규칙·등급·적정성까지 FK로 함께 지워지고, 적용에 잠긴 시스템 버전은 시드 상태로 되돌린다
        jdbc.sql("DELETE FROM data2flow_core.compliance_profiles WHERE organization_id <> 0").update();
        jdbc.sql("UPDATE data2flow_core.compliance_profile_versions SET locked = false WHERE organization_id = 0 AND locked").update();
        jdbc.sql("DELETE FROM data2flow_core.energy_emission_factors WHERE organization_id <> 0").update();
    }
}
