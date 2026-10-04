package net.java21.data2flow.core.storage.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 저장 지표(OPS-01.03, API-OPS-03). DB {@code data2flow} 하나의 크기와 {@code data2flow_*} 스키마 표 크기만 본다(다른 팀 DB·스키마는 보지 않음,
 * CLAUDE.md §2). 파티션 표는 부모 이름으로 하위 파티션을 합친다. 행 수는 통계 추정치({@code reltuples}). 플랫폼 전체 지표라 조직 조건이 없다.
 */
@Repository
@OrganizationScopeExempt("DB·표 크기는 플랫폼 전체 지표다(조직별 데이터가 아님). 조회는 OPS_MANAGE 관리자만")
public class StorageMetricsRepository {

    private final JdbcClient jdbc;

    public StorageMetricsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long findDatabaseSize() {
        return jdbc.sql("SELECT pg_database_size(current_database())").query(Long.class).single();
    }

    /** data2flow_* 스키마의 최상위 표(일반 표 + 파티션 부모). 크기는 색인·TOAST 포함 */
    public List<TableSize> findTableSizes() {
        return jdbc.sql("""
                        SELECT n.nspname AS schema_name, c.relname AS table_name,
                               CASE WHEN c.relkind = 'p'
                                    THEN (SELECT coalesce(sum(pg_total_relation_size(t.relid)), 0) FROM pg_partition_tree(c.oid) t)
                                    ELSE pg_total_relation_size(c.oid) END AS bytes,
                               CASE WHEN c.relkind = 'p'
                                    THEN (SELECT coalesce(sum(greatest(pc.reltuples, 0)), 0) FROM pg_partition_tree(c.oid) t
                                            JOIN pg_class pc ON pc.oid = t.relid WHERE t.isleaf)
                                    ELSE greatest(c.reltuples, 0) END::bigint AS row_estimate
                          FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname LIKE 'data2flow\\_%' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
                         ORDER BY bytes DESC, schema_name, table_name""")
                .query((rs, n) -> new TableSize(rs.getString("schema_name"), rs.getString("table_name"), rs.getLong("bytes"),
                        rs.getLong("row_estimate"))).list();
    }

    /** 오늘 기록(같은 날 다시 부르면 덮어쓴다) */
    public void upsertSnapshot(LocalDate day, TableSize t) {
        jdbc.sql("""
                        INSERT INTO data2flow_core.storage_snapshots (taken_on, schema_name, table_name, bytes, row_estimate)
                        VALUES (:day, :schema, :table, :bytes, :rows)
                        ON CONFLICT (taken_on, schema_name, table_name) DO UPDATE SET bytes = EXCLUDED.bytes, row_estimate = EXCLUDED.row_estimate,
                               created_at = now()""")
                .param("day", day).param("schema", t.schema()).param("table", t.table()).param("bytes", t.bytes()).param("rows", t.rows()).update();
    }

    /** 날짜별 합계(오래된 순) */
    public List<DailyTotal> findDailyTotals(LocalDate from) {
        return jdbc.sql("""
                        SELECT taken_on, sum(bytes)::bigint AS bytes FROM data2flow_core.storage_snapshots
                         WHERE taken_on >= :from GROUP BY taken_on ORDER BY taken_on""")
                .param("from", from).query((rs, n) -> new DailyTotal(rs.getObject("taken_on", LocalDate.class), rs.getLong("bytes"))).list();
    }

    /** 90일 지난 기록 삭제 */
    public int deleteSnapshotsBefore(LocalDate day) {
        return jdbc.sql("DELETE FROM data2flow_core.storage_snapshots WHERE taken_on < :day").param("day", day).update();
    }

    public record TableSize(String schema, String table, long bytes, long rows) {
    }

    public record DailyTotal(LocalDate day, long bytes) {
    }
}
