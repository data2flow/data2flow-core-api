package net.java21.data2flow.core.dataexchange.repository;

import net.java21.data2flow.contracts.tenancy.OrganizationScopeExempt;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 내보내기 결과·가져오기 원본 파일({@code exchange_file_chunks}, 1MiB 조각). 오브젝트 저장소가 생기기 전 임시 보관이다
 * (floorplan_images와 같은 방식). 읽기·쓰기 모두 조각 단위라 파일 크기와 상관없이 메모리가 일정하다.
 */
@Repository
public class ExchangeFileRepository {

    public static final int CHUNK = 1024 * 1024;

    private final JdbcClient jdbc;

    public ExchangeFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** object_key 표기(db:exchange_file_chunks/{종류}/{ID}) */
    public static String objectKey(String ownerKind, long ownerId) {
        return "db:exchange_file_chunks/" + ownerKind + "/" + ownerId;
    }

    /** 스트림 내용을 조각으로 저장(기존 조각은 지운다). 저장한 바이트 수 */
    public long store(long organizationId, String ownerKind, long ownerId, InputStream in) throws IOException {
        deleteFile(organizationId, ownerKind, ownerId);
        byte[] buffer = new byte[CHUNK];
        int no = 0;
        long total = 0;
        while (true) {
            int filled = in.readNBytes(buffer, 0, CHUNK);
            if (filled <= 0) {
                break;
            }
            byte[] data = filled == CHUNK ? buffer.clone() : java.util.Arrays.copyOf(buffer, filled);
            jdbc.sql("""
                            INSERT INTO data2flow_core.exchange_file_chunks (owner_kind, owner_id, chunk_no, organization_id, data)
                            VALUES (:kind, :id, :no, :org, :data)""")
                    .param("kind", ownerKind).param("id", ownerId).param("no", no++).param("org", organizationId).param("data", data)
                    .update();
            total += filled;
            if (filled < CHUNK) {
                break;
            }
        }
        return total;
    }

    /** 조각 하나(없으면 null) */
    public byte[] readChunk(long organizationId, String ownerKind, long ownerId, int chunkNo) {
        return jdbc.sql("""
                        SELECT data FROM data2flow_core.exchange_file_chunks
                         WHERE organization_id = :org AND owner_kind = :kind AND owner_id = :id AND chunk_no = :no""")
                .param("org", organizationId).param("kind", ownerKind).param("id", ownerId).param("no", chunkNo)
                .query(byte[].class).optional().orElse(null);
    }

    /** 조각을 차례로 읽는 스트림(한 번에 조각 하나만 메모리에) */
    public InputStream open(long organizationId, String ownerKind, long ownerId) {
        return new InputStream() {
            private byte[] current = new byte[0];
            private int pos;
            private int next;
            private boolean done;

            private boolean fill() {
                while (!done && pos >= current.length) {
                    byte[] chunk = readChunk(organizationId, ownerKind, ownerId, next++);
                    if (chunk == null) {
                        done = true;
                        return false;
                    }
                    current = chunk;
                    pos = 0;
                }
                return pos < current.length;
            }

            @Override
            public int read() {
                return fill() ? current[pos++] & 0xff : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (len == 0) {
                    return 0;
                }
                if (!fill()) {
                    return -1;
                }
                int n = Math.min(len, current.length - pos);
                System.arraycopy(current, pos, b, off, n);
                pos += n;
                return n;
            }
        };
    }

    public void deleteFile(long organizationId, String ownerKind, long ownerId) {
        jdbc.sql("DELETE FROM data2flow_core.exchange_file_chunks WHERE organization_id = :org AND owner_kind = :kind AND owner_id = :id")
                .param("org", organizationId).param("kind", ownerKind).param("id", ownerId).update();
    }

    /** 정리 작업: 주인이 없거나 만료된 파일 조각 지우기(내보내기 7일, 가져오기 끝난 지 7일) */
    @OrganizationScopeExempt("시스템 정리 작업: 모든 조직의 만료 파일")
    public int deleteOrphans(List<Long> expiredExportIds, List<Long> finishedImportIds) {
        int n = 0;
        if (!expiredExportIds.isEmpty()) {
            n += jdbc.sql("DELETE FROM data2flow_core.exchange_file_chunks WHERE owner_kind = 'EXPORT' AND owner_id = ANY(CAST(:ids AS bigint[]))")
                    .param("ids", net.java21.data2flow.core.common.Pg.bigintArray(expiredExportIds)).update();
        }
        if (!finishedImportIds.isEmpty()) {
            n += jdbc.sql("DELETE FROM data2flow_core.exchange_file_chunks WHERE owner_kind = 'IMPORT' AND owner_id = ANY(CAST(:ids AS bigint[]))")
                    .param("ids", net.java21.data2flow.core.common.Pg.bigintArray(finishedImportIds)).update();
        }
        return n;
    }
}
