package net.java21.data2flow.core.dataexchange.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 가져오기 작업({@code import_jobs}, {@code import_errors}) — TSD-04.02, BR-TSD-15·16 */
@Repository
public class ImportJobRepository {

    /** 작업당 오류 보관 한도(TSD domain-model §2.10) */
    public static final int MAX_ERRORS = 1000;

    private static final String SELECT = """
            SELECT id, organization_id, requested_by, source_kind, config, mapping, secret_enc, dry_run, status, total, inserted,
                   skipped_duplicate, failed, origin_label, file_object_key, file_bytes, sample, error, range_from, range_to, started_at,
                   finished_at, created_at
              FROM data2flow_core.import_jobs
            """;

    private final JdbcClient jdbc;

    public ImportJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ImportJobRow(long id, long organizationId, long requestedBy, String sourceKind, String configJson, String mappingJson,
                               byte[] secretEnc, boolean dryRun, String status, Long total, Long inserted, Long skippedDuplicate, Long failed,
                               String originLabel, String fileObjectKey, Long fileBytes, String sampleJson, String error, Instant rangeFrom,
                               Instant rangeTo, Instant startedAt, Instant finishedAt, Instant createdAt) {
    }

    public long insert(long organizationId, long requestedBy, String sourceKind, String configJson, String mappingJson, byte[] secretEnc,
                       boolean dryRun, String originLabel, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.import_jobs (organization_id, requested_by, source_kind, config, mapping, secret_enc, dry_run,
                               status, origin_label, created_at, updated_at)
                        VALUES (:org, :by, :kind, CAST(:config AS jsonb), CAST(:mapping AS jsonb), :secret, :dry, 'QUEUED', :label, :now, :now)
                        RETURNING id""")
                .param("org", organizationId).param("by", requestedBy).param("kind", sourceKind).param("config", configJson)
                .param("mapping", mappingJson).param("secret", secretEnc).param("dry", dryRun).param("label", originLabel)
                .param("now", Pg.ts(now)).query(Long.class).single();
    }

    public void updateFile(long organizationId, long id, String objectKey, long bytes) {
        jdbc.sql("UPDATE data2flow_core.import_jobs SET file_object_key = :key, file_bytes = :bytes WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id).param("key", objectKey).param("bytes", bytes).update();
    }

    public Optional<ImportJobRow> findById(long organizationId, long id) {
        return jdbc.sql(SELECT + " WHERE organization_id = :org AND id = :id").param("org", organizationId).param("id", id)
                .query(ImportJobRepository::map).optional();
    }

    public List<ImportJobRow> list(long organizationId, int limit, long offset) {
        return jdbc.sql(SELECT + " WHERE organization_id = :org ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("org", organizationId).param("limit", limit).param("offset", offset).query(ImportJobRepository::map).list();
    }

    public long count(long organizationId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.import_jobs WHERE organization_id = :org").param("org", organizationId)
                .query(Long.class).single();
    }

    /** 대기 작업 하나를 잡는다: 미리 실행이면 VALIDATING, 실행이면 RUNNING */
    @OrganizationScopeExempt("가져오기 실행기: 모든 조직의 대기 작업")
    public Optional<ImportJobRow> claimNextQueued(Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.import_jobs SET status = CASE WHEN dry_run THEN 'VALIDATING' ELSE 'RUNNING' END,
                               started_at = :now, updated_at = :now
                         WHERE id = (SELECT id FROM data2flow_core.import_jobs WHERE status = 'QUEUED' ORDER BY created_at, id
                                      LIMIT 1 FOR UPDATE SKIP LOCKED)
                        RETURNING id, organization_id, requested_by, source_kind, config, mapping, secret_enc, dry_run, status, total, inserted,
                                  skipped_duplicate, failed, origin_label, file_object_key, file_bytes, sample, error, range_from, range_to,
                                  started_at, finished_at, created_at""")
                .param("now", Pg.ts(now)).query(ImportJobRepository::map).optional();
    }

    /** 미리 실행이 끝난 작업을 실제 실행 대기로(API-TSD-32). 상태가 맞지 않으면 false */
    public boolean updateToRun(long organizationId, long id, Instant now) {
        return jdbc.sql("""
                        UPDATE data2flow_core.import_jobs SET dry_run = false, status = 'QUEUED', total = NULL, inserted = NULL,
                               skipped_duplicate = NULL, failed = NULL, error = NULL, finished_at = NULL, updated_at = :now
                         WHERE organization_id = :org AND id = :id AND status = 'DRY_RUN_DONE'""")
                .param("org", organizationId).param("id", id).param("now", Pg.ts(now)).update() == 1;
    }

    public void updateResult(long organizationId, long id, String status, long total, long inserted, long skippedDuplicate, long failed,
                             String sampleJson, String error, Instant rangeFrom, Instant rangeTo, Instant now) {
        jdbc.sql("""
                        UPDATE data2flow_core.import_jobs SET status = :status, total = :total, inserted = :inserted, skipped_duplicate = :skipped,
                               failed = :failed, sample = CAST(:sample AS jsonb), error = :error, range_from = :rf, range_to = :rt,
                               finished_at = :now, updated_at = :now
                         WHERE organization_id = :org AND id = :id""")
                .param("org", organizationId).param("id", id).param("status", status).param("total", total).param("inserted", inserted)
                .param("skipped", skippedDuplicate).param("failed", failed).param("sample", sampleJson)
                .param("error", ExportJobRepository.truncate(error)).param("rf", Pg.ts(rangeFrom)).param("rt", Pg.ts(rangeTo))
                .param("now", Pg.ts(now)).update();
    }

    public void deleteErrors(long organizationId, long jobId) {
        jdbc.sql("DELETE FROM data2flow_core.import_errors WHERE organization_id = :org AND job_id = :job")
                .param("org", organizationId).param("job", jobId).update();
    }

    public void insertErrors(long organizationId, long jobId, List<String[]> errors, Instant now) {
        for (String[] e : errors) {
            jdbc.sql("""
                            INSERT INTO data2flow_core.import_errors (organization_id, job_id, line_or_point, error_code, message, created_at)
                            VALUES (:org, :job, :line, :code, :message, :now)""")
                    .param("org", organizationId).param("job", jobId).param("line", e[0]).param("code", e[1])
                    .param("message", ExportJobRepository.truncate(e[2])).param("now", Pg.ts(now)).update();
        }
    }

    public List<String[]> findErrors(long organizationId, long jobId, int limit, long offset) {
        return jdbc.sql("""
                        SELECT line_or_point, error_code, message FROM data2flow_core.import_errors
                         WHERE organization_id = :org AND job_id = :job ORDER BY id LIMIT :limit OFFSET :offset""")
                .param("org", organizationId).param("job", jobId).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}).list();
    }

    public long countErrors(long organizationId, long jobId) {
        return jdbc.sql("SELECT count(*) FROM data2flow_core.import_errors WHERE organization_id = :org AND job_id = :job")
                .param("org", organizationId).param("job", jobId).query(Long.class).single();
    }

    /** 끝난 지 7일 지난 CSV 작업(원본 파일 지우기용) */
    @OrganizationScopeExempt("시스템 정리 작업: 모든 조직의 끝난 가져오기 파일")
    public List<Long> listFinishedWithFiles(Instant before) {
        return jdbc.sql("""
                        SELECT id FROM data2flow_core.import_jobs
                         WHERE file_object_key IS NOT NULL AND status IN ('SUCCEEDED', 'PARTIALLY_FAILED', 'FAILED') AND finished_at < :before""")
                .param("before", Pg.ts(before)).query(Long.class).list();
    }

    @OrganizationScopeExempt("시스템 정리 작업")
    public void clearFileKeys(List<Long> ids) {
        if (!ids.isEmpty()) {
            jdbc.sql("UPDATE data2flow_core.import_jobs SET file_object_key = NULL WHERE id = ANY(CAST(:ids AS bigint[]))")
                    .param("ids", Pg.bigintArray(ids)).update();
        }
    }

    static ImportJobRow map(ResultSet rs, int n) throws SQLException {
        return new ImportJobRow(rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("requested_by"), rs.getString("source_kind"),
                rs.getString("config"), rs.getString("mapping"), rs.getBytes("secret_enc"), rs.getBoolean("dry_run"), rs.getString("status"),
                Pg.longOrNull(rs, "total"), Pg.longOrNull(rs, "inserted"), Pg.longOrNull(rs, "skipped_duplicate"), Pg.longOrNull(rs, "failed"),
                rs.getString("origin_label"), rs.getString("file_object_key"), Pg.longOrNull(rs, "file_bytes"), rs.getString("sample"),
                rs.getString("error"), Pg.instant(rs, "range_from"), Pg.instant(rs, "range_to"), Pg.instant(rs, "started_at"),
                Pg.instant(rs, "finished_at"), Pg.instant(rs, "created_at"));
    }
}
