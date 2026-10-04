package net.java21.data2flow.core.retention.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 콜드 보관 파일({@code archive_files}, TSD-05.02). pipeline이 등록하고(API-TSD-61) 관리자가 목록을 본다(API-TSD-33) */
@Repository
public class ArchiveFileRepository {

    private static final String COLUMNS = """
            id, organization_id, data_class, range_from, range_to, object_key, format, rows_count, bytes, checksum, restored_job_id, created_at""";

    private final JdbcClient jdbc;

    public ArchiveFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 등록. 같은 조직·같은 객체 키가 있으면 그 행 ID(pipeline 재시도 멱등) */
    public long insertOrGet(long organizationId, String dataClass, Instant rangeFrom, Instant rangeTo, String objectKey, String format,
                            long rowsCount, long bytes, String checksum, Instant now) {
        Optional<Long> inserted = jdbc.sql("""
                        INSERT INTO data2flow_core.archive_files (organization_id, data_class, range_from, range_to, object_key, format,
                                                                  rows_count, bytes, checksum, created_at)
                        VALUES (:org, :dc, :from, :to, :key, :format, :rows, :bytes, :checksum, :now)
                        ON CONFLICT (organization_id, object_key) DO NOTHING
                        RETURNING id""")
                .param("org", organizationId).param("dc", dataClass).param("from", Pg.ts(rangeFrom)).param("to", Pg.ts(rangeTo))
                .param("key", objectKey).param("format", format).param("rows", rowsCount).param("bytes", bytes)
                .param("checksum", checksum).param("now", Pg.ts(now)).query(Long.class).optional();
        return inserted.orElseGet(() -> jdbc.sql("""
                        SELECT id FROM data2flow_core.archive_files WHERE organization_id = :org AND object_key = :key""")
                .param("org", organizationId).param("key", objectKey).query(Long.class).single());
    }

    public List<ArchiveRow> list(long organizationId, String dataClass, Instant from, Instant to, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.archive_files" + where()
                        + " ORDER BY range_from DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("dc", dataClass).param("from", from == null ? null : Pg.ts(from))
                .param("to", to == null ? null : Pg.ts(to)).param("limit", limit).param("offset", offset)
                .query(ArchiveFileRepository::map).list();
    }

    public long count(long organizationId, String dataClass, Instant from, Instant to) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.archive_files" + where())
                .param("org", organizationId).param("dc", dataClass).param("from", from == null ? null : Pg.ts(from))
                .param("to", to == null ? null : Pg.ts(to)).query(Long.class).single();
    }

    public Optional<ArchiveRow> findById(long organizationId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data2flow_core.archive_files WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).query(ArchiveFileRepository::map).optional();
    }

    /** 기간이 겹치는 파일(range_to > from AND range_from < to) */
    private static String where() {
        return " " + """
                 WHERE organization_id = :org AND (CAST(:dc AS text) IS NULL OR data_class = :dc)
                   AND (CAST(:from AS timestamptz) IS NULL OR range_to > CAST(:from AS timestamptz))
                   AND (CAST(:to AS timestamptz) IS NULL OR range_from < CAST(:to AS timestamptz))""";
    }

    static ArchiveRow map(ResultSet rs, int n) throws SQLException {
        return new ArchiveRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("data_class"), Pg.instant(rs, "range_from"),
                Pg.instant(rs, "range_to"), rs.getString("object_key"), rs.getString("format"), rs.getLong("rows_count"),
                rs.getLong("bytes"), rs.getString("checksum"), Pg.longOrNull(rs, "restored_job_id"), Pg.instant(rs, "created_at"));
    }

    public record ArchiveRow(long id, long organizationId, String dataClass, Instant rangeFrom, Instant rangeTo, String objectKey,
                             String format, long rowsCount, long bytes, String checksum, Long restoredJobId, Instant createdAt) {
    }
}
