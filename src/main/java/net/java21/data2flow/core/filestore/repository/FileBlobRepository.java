package net.java21.data2flow.core.filestore.repository;

import net.java21.data2flow.core.common.Pg;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/** 첨부·사진·내보내기 파일({@code file_blobs}). 오브젝트 저장소 도입 전 임시 보관(평면도와 같은 방식, V202610050910) */
@Repository
public class FileBlobRepository {

    private final JdbcClient jdbc;

    public FileBlobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long organizationId, String purpose, String fileName, String contentType, byte[] data, Long createdBy, Instant now) {
        return jdbc.sql("""
                        INSERT INTO data2flow_core.file_blobs (organization_id, purpose, file_name, content_type, size_bytes, data, created_by, created_at)
                        VALUES (:org, :purpose, :name, :type, :size, :data, :by, :now) RETURNING id""")
                .param("org", organizationId).param("purpose", purpose).param("name", fileName).param("type", contentType)
                .param("size", (long) data.length).param("data", data).param("by", createdBy).param("now", Pg.ts(now))
                .query(Long.class).single();
    }

    public Optional<Blob> find(long organizationId, long id) {
        return jdbc.sql("SELECT id, file_name, content_type, size_bytes, data FROM data2flow_core.file_blobs WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id)
                .query((rs, n) -> new Blob(rs.getLong("id"), rs.getString("file_name"), rs.getString("content_type"),
                        rs.getLong("size_bytes"), rs.getBytes("data")))
                .optional();
    }

    public Optional<Meta> findMeta(long organizationId, long id) {
        return jdbc.sql("SELECT id, file_name, content_type, size_bytes FROM data2flow_core.file_blobs WHERE organization_id = :org AND id = :id")
                .param("org", organizationId).param("id", id)
                .query((rs, n) -> new Meta(rs.getLong("id"), rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size_bytes")))
                .optional();
    }

    public int delete(long organizationId, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("DELETE FROM data2flow_core.file_blobs WHERE organization_id = :org AND id = ANY(CAST(:ids AS bigint[]))")
                .param("org", organizationId).param("ids", Pg.bigintArray(ids)).update();
    }

    public record Blob(long id, String fileName, String contentType, long sizeBytes, byte[] data) {
    }

    public record Meta(long id, String fileName, String contentType, long sizeBytes) {
    }
}
