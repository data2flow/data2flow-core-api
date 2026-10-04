package net.java21.data2flow.core.retention.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 저장 현황(API-TSD-43, TSD-02.03): pipeline 소유 {@code data2flow_pipeline.partition_registries}를 읽기만 한다(conventions §6).
 * 월 파티션은 조직이 함께 쓰는 공용 표라 조직 조건이 없다. 크기는 기록된 값이 없으면 PostgreSQL 카탈로그에서 잰다.
 */
@Repository
public class StorageStatsRepository {

    private final JdbcClient jdbc;

    public StorageStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @OrganizationScopeExempt("공용 월 파티션 목록(조직 구분 없음). TS_POLICY 관리자 화면 전용")
    public List<PartitionRow> listPartitions() {
        return jdbc.sql("""
                        SELECT r.table_name, r.partition_name, r.range_from, r.range_to, r.state, r.rows_estimate,
                               coalesce(r.bytes, pg_total_relation_size(to_regclass('data2flow_pipeline.' || quote_ident(r.partition_name))))
                                   AS bytes,
                               r.archive_ratio
                          FROM data2flow_pipeline.partition_registries r
                         ORDER BY r.table_name, r.range_from""")
                .query((rs, n) -> {
                    long rows = rs.getLong("rows_estimate");
                    Long rowsOrNull = rs.wasNull() ? null : rows;
                    long bytes = rs.getLong("bytes");
                    Long bytesOrNull = rs.wasNull() ? null : bytes;
                    double ratio = rs.getDouble("archive_ratio");
                    Double ratioOrNull = rs.wasNull() ? null : ratio;
                    return new PartitionRow(rs.getString("table_name"), rs.getString("partition_name"), Pg.instant(rs, "range_from"),
                            Pg.instant(rs, "range_to"), rs.getString("state"), rowsOrNull, bytesOrNull, ratioOrNull);
                }).list();
    }

    public record PartitionRow(String table, String name, Instant rangeFrom, Instant rangeTo, String state, Long rows, Long bytes,
                               Double compressionRatio) {
    }
}
